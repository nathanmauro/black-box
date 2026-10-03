import hashlib
import http.server
import json
import os
from pathlib import Path
import select
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock
import urllib.parse
import uuid

import compact_backend as c
import compact_server as cs
import history_search as h
import test_compact_backend as tb
import test_history_search as lit

BENCH = Path(__file__).resolve().parent
CLI = BENCH / "compact_history_search.py"
REAL_PLATFORM = cs.Platform()
HAVE_PLATFORM = bool(REAL_PLATFORM.lsof and REAL_PLATFORM.ps)

SCHEMA = """
CREATE TABLE agent_sessions (id TEXT PRIMARY KEY, source TEXT NOT NULL, client_session_id TEXT NOT NULL,
  cwd TEXT, UNIQUE (source, client_session_id));
CREATE TABLE agent_events (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, source TEXT NOT NULL,
  client_session_id TEXT NOT NULL, event_type TEXT NOT NULL, role TEXT, text TEXT, tool_name TEXT,
  metadata_json TEXT, observed_at TEXT NOT NULL);
CREATE TABLE event_capture_receipts (source TEXT NOT NULL, client_session_id TEXT NOT NULL,
  capture_id TEXT NOT NULL, request_hash TEXT NOT NULL, event_id TEXT UNIQUE,
  PRIMARY KEY (source, client_session_id, capture_id));
"""


def fake_java_main(argv, config_path):
    """Entry point of the fake `java` executable: a tiny stand-in server with optional faults."""
    config = json.loads(Path(config_path).read_text())
    mode = config.get("mode")
    if argv == ["-version"]:
        sys.stderr.write('openjdk version "%s" 2025-01-01\n' % config.get("version", "21.0.5"))
        return 0
    if config.get("dump"):
        Path(config["dump"]).write_text(json.dumps({"env": dict(os.environ), "argv": argv}))
    options = dict(arg[2:].split("=", 1) for arg in argv if arg.startswith("--"))
    port, db = int(options["server.port"]), options["spring.datasource.url"][len("jdbc:sqlite:"):]
    if mode == "exit_early":
        return 1
    if mode == "hang_on_term":
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    if mode == "db_elsewhere":
        Path(db).touch()
        db += "-elsewhere"
    connection = sqlite3.connect(db, check_same_thread=False, isolation_level=None)
    connection.executescript(SCHEMA)
    if mode == "child_ignores_term" and os.fork() == 0:
        signal.signal(signal.SIGTERM, signal.SIG_IGN)  # Outlives the leader unless the group is killed.
        Path(config["child_pid_file"]).write_text(str(os.getpid()))
        while True:
            time.sleep(1)
    if mode == "orphan_listener":
        ready_read, ready_write = os.pipe()
        if os.fork() != 0:
            # The leader exits only after its child ignores TERM and has recorded its PID (bounded
            # wait), so cleanup can never win the race against that setup.
            os.close(ready_write)
            select.select([ready_read], [], [], 5.0)
            os.close(ready_read)
            os._exit(0)  # The TERM-ignoring child keeps the port.
        os.close(ready_read)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        Path(config["child_pid_file"]).write_text(str(os.getpid()))
        os.write(ready_write, b"1")
        os.close(ready_write)
    if mode == "fork_listener" and os.fork() != 0:
        while True:  # The group leader never listens; its child claims the port.
            time.sleep(1)
    captures = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, status, value):
            body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path, _, query = self.path.partition("?")
            if path == "/api/status":
                return self.reply(200, {"storage": {"sessions": 0}})
            if path != "/api/search/compact":
                return self.reply(404, {})
            if mode == "fail_search":
                return self.reply(400, tb.FakeCanonicalApi.error("cursor_unavailable"))
            params = urllib.parse.parse_qsl(query, keep_blank_values=True, strict_parsing=True)
            rows = [dict(zip(("id", "session_id", "client_session_id", "text", "tool_name", "metadata_json",
                              "observed_at", "cwd"), row)) for row in connection.execute(
                "SELECT e.id, e.session_id, e.client_session_id, e.text, e.tool_name, e.metadata_json,"
                " e.observed_at, s.cwd FROM agent_events e JOIN agent_sessions s ON s.id = e.session_id")]
            status, body = tb.FakeCanonicalApi(rows)(params)
            if mode == "tamper_marker_on_search":
                Path(os.getcwd(), ".blackbox-compact-owner").write_text("tampered")
            if mode == "dribble_search":  # Never idle for a full timeout, yet slower than the deadline.
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                quarter = -(-len(body) // 4)
                for start in range(0, len(body), quarter):
                    self.wfile.write(body[start:start + quarter])
                    self.wfile.flush()
                    time.sleep(0.05)
                return
            self.reply(status, body)

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            event, capture = request["event"], str(uuid.UUID(request["captureId"]))
            captures.append(capture)
            source, client = event["source"].strip().lower(), event["clientSessionId"].strip()
            session = connection.execute("SELECT id FROM agent_sessions WHERE source=? AND client_session_id=?",
                                         (source, client)).fetchone()
            session_id = session[0] if session else str(uuid.uuid4())
            if not session:
                connection.execute("INSERT INTO agent_sessions VALUES (?,?,?,?)",
                                   (session_id, source, client, event["cwd"]))
            text = None if event["text"] is None or c.java_blank(event["text"]) else event["text"]
            if mode == "keep_blank":
                text = event["text"]
            metadata = event["metadata"]
            observed = c.parse_instant(event["observedAt"])
            second = len(captures) == 2
            if second and mode == "mutate_text":
                text = (text or "") + "!"
            if second and mode == "metadata_extra":
                metadata = dict(metadata, extra=True)
            if second and mode == "shift_time":
                observed += 1
            event_id = str(uuid.uuid4())
            connection.execute("INSERT INTO agent_events VALUES (?,?,?,?,?,?,?,?,?,?)", (
                event_id, session_id, source, client, event["eventType"], None, text,
                "tool" if second and mode == "set_tool" else event["toolName"],
                json.dumps(metadata, ensure_ascii=False, separators=(",", ":")),
                c.utc_text(observed) if mode == "nine_digit_time" else c.java_instant_text(observed)))
            if not (second and mode == "drop_receipt"):
                connection.execute("INSERT INTO event_capture_receipts VALUES (?,?,?,?,?)",
                                   (source, client, capture, "hash", event_id))
            self.reply(200, {"captureId": capture, "eventId": event_id, "sessionId": session_id,
                             "replayed": second and mode == "replayed"})

    server = http.server.HTTPServer(("127.0.0.1", port), Handler)
    server.serve_forever()
    return 0


def corpus_items():
    secretish = "password=hunter2 Bearer abcdefghijklmnopqrstuvwxyz sk-" + "a" * 24 + " AKIAABCDEFGHIJKLMNOP"
    return [
        lit.item("h1", "Resume: Export writer still drops notes.", kind="handoff",
                 observed="2026-09-29T10:00:00.123456789+05:30"),
        lit.item("big", "😀" * 16384, observed="2026-09-01T00:00:00.000000001Z"),
        lit.item("blank", "", observed="2026-09-02T00:00:00Z"),
        lit.item("spaces", " \t  ", observed="2026-09-02T00:00:00Z", session="s2"),
        lit.item("nul", "before\x00after Export", observed="2026-09-03T00:00:00.5Z"),
        lit.item("secret", secretish, observed="2026-09-04T00:00:00Z", ref='src/"Q"\\ü' + "r" * 900),
        lit.item("edge", "cutoff boundary Export", observed=lit.CUTOFF),
        lit.item("old", "pre epoch Export", observed="1969-12-31T23:59:59.999999999Z"),
        lit.item("year0", "year zero Export", observed="0001-01-01T00:00:00+01:00", session="s2"),
    ]


def group_members(pgid):
    """Live (non-zombie) processes whose process group is pgid."""
    output = subprocess.run(["/bin/ps", "-axo", "pid=,pgid=,stat="], capture_output=True, text=True).stdout
    rows = (line.split() for line in output.splitlines())
    return sorted(int(row[0]) for row in rows if len(row) >= 3 and int(row[1]) == pgid and not row[2].startswith("Z"))


@unittest.skipUnless(HAVE_PLATFORM, "lsof and ps are required for real-process identity proof")
class ServerFixture(lit.CorpusFixture):
    def setUp(self):
        super().setUp()
        self.parent = self.root / "owned"
        self.parent.mkdir()
        self.jar = self.root / "app.jar"
        self.jar.write_bytes(b"trusted fake jar bytes")
        self.jar_sha = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.config = self.root / "fake.json"
        self.child_pid_file = self.root / "child.pid"
        self.java = self.root / "java"
        self.java.write_text("#!%s\nimport sys\nsys.path.insert(0, %r)\nimport test_compact_server as t\n"
                             "sys.exit(t.fake_java_main(sys.argv[1:], %r))\n"
                             % (sys.executable, str(BENCH), str(self.config)))
        self.java.chmod(0o700)
        self.configure()

    def configure(self, **values):
        self.config.write_text(json.dumps(dict(values, child_pid_file=str(self.child_pid_file))))

    def tearDown(self):
        # Never leak a fake descendant even when a regression fails: kill only the recorded fake.
        if self.child_pid_file.exists():
            pid = int(self.child_pid_file.read_text())
            command = subprocess.run(["/bin/ps", "-p", str(pid), "-o", "command="], capture_output=True,
                                     text=True).stdout
            if str(self.java) in command:
                os.kill(pid, signal.SIGKILL)
        super().tearDown()

    def wait_gone(self, pid, seconds=5.0):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return True
            time.sleep(0.05)
        return False

    def server(self, items=None, **kwargs):
        corpus = self.corpus(corpus_items() if items is None else items)
        kwargs.setdefault("temp_parent", str(self.parent))
        return corpus, cs.PrivateCompactServer(str(self.jar), self.jar_sha, str(self.java), corpus, **kwargs)

    def assert_clean(self, server):
        self.assertEqual(list(self.parent.iterdir()), [])
        if server.proc is not None:
            self.assertIsNotNone(server.proc.returncode)
            self.assertEqual(REAL_PLATFORM.listeners(server.port), [])

    def refuses(self, code, **kwargs):
        corpus, server = self.server(**kwargs)
        with self.assertRaises(cs.ControllerError) as caught:
            with server:
                self.fail("context must not be entered")
        self.assertEqual(caught.exception.code, code)
        self.assert_clean(server)
        return server


class PrivateServerTests(ServerFixture):
    def test_launch_capture_fidelity_search_and_cleanup(self):
        dump = self.root / "dump.json"
        self.configure(dump=str(dump))
        leaks = {"SBA_PORT": "8766", "SPRING_APPLICATION_JSON": "{}", "HTTPS_PROXY": "http://proxy",
                 "JAVA_TOOL_OPTIONS": "-Dx=y", "OPENAI_API_KEY": "sk-leak", "SBA_DATASOURCE_URL": "jdbc:sqlite:live"}
        with mock.patch.dict(os.environ, leaks):
            corpus, server = self.server()
            with server:
                root = server.root
                self.assertEqual(oct(os.stat(root).st_mode & 0o777), "0o700")
                self.assertNotIn(server.port, cs.EXCLUDED_PORTS)
                self.assertEqual(server.report["sessions"], 2)
                self.assertEqual(len(server.index.entries), len(corpus.items))
                stored = {e.item.id: e for e in server.index.entries.values()}
                self.assertEqual(json.loads(stored["secret"].metadata_json),
                                 {"sourceRef": 'src/"Q"\\ü' + "r" * 900})
                self.assertIn('\\"Q\\"\\\\ü', stored["secret"].metadata_json)
                session = h.Session(corpus, server.backend())
                first = lit.decode(session.start())
                self.assertEqual((first["backend"], first["results"][0]["id"]), (c.BACKEND, "h1"))
                body = lit.decode(session.search("Export"))
                self.assertEqual([r["id"] for r in body["results"]], ["edge", "h1", "nul", "old", "year0"])
                self.assertEqual(body["results"][4]["observed_at"], "0001-01-01T00:00:00+01:00")
                secret = lit.decode(session.search("password=hunter2 sk-" + "a" * 24))
                self.assertEqual([r["excerpt"]["text"][:16] for r in secret["results"]], ["password=hunter2"])
                self.assertEqual(lit.decode(session.search('\\"Q\\"'))["results"][0]["match"]["fields"], ["metadata"])
                self.assertNotIn("infrastructure_error", session.accounting())
        self.assert_clean(server)
        self.assertEqual(server.report["events"], len(corpus.items))
        self.assertGreater(server.report["events"], 0)
        self.assertFalse(root.exists())
        self.assertEqual(server.report["process"]["forced_kill"], False)
        launched = json.loads(dump.read_text())
        home = str(root / "home")
        self.assertEqual(set(launched["env"]) - {"__CF_USER_TEXT_ENCODING", "__PYVENV_LAUNCHER__"},
                         {"PATH", "LANG", "LC_ALL", "TZ", "HOME", "TMPDIR", "XDG_CONFIG_HOME", "XDG_CACHE_HOME",
                          "XDG_DATA_HOME", "JAVA_HOME"})
        self.assertEqual(launched["env"]["HOME"], home)
        argv = launched["argv"]
        for flag in ("--spring.config.location=classpath:/application.yml", "--server.address=127.0.0.1",
                     "--sba.ingestion.redact-enabled=false", "--sba.ingestion.max-text-length=65536",
                     "--sba.summary.backend=local", "--sba.local-ai.enabled=false",
                     "--sba.memory.embedding.enabled=false", "--sba.editor.enabled=false",
                     "--spring.datasource.url=jdbc:sqlite:" + str(root / cs.DB_NAME)):
            self.assertIn(flag, argv)
        self.assertIn(str(root / cs.JAR_NAME), argv)

    def test_complete_pagination_through_the_private_server(self):
        tie = "2026-09-20T00:00:00.000000001Z"
        items = [lit.item("t%03d" % n, "needle %d" % n, observed=tie) for n in range(205)]
        corpus, server = self.server(items)
        with server:
            session = h.Session(corpus, server.backend())
            session.start()
            body = lit.decode(session.search("needle"))
            self.assertEqual(body["total_matches"], 205)
            shown = [r["id"] for r in body["results"]]
            self.assertEqual(shown, ["t%03d" % n for n in range(len(shown))])
            self.assertEqual(session.backend.host_log[-1]["pages"], 5)
        self.assert_clean(server)

    def test_fidelity_mismatches_fail_closed_before_start(self):
        for mode, code in (("mutate_text", "fidelity_text"), ("set_tool", "fidelity_tool"),
                           ("shift_time", "fidelity_time"), ("metadata_extra", "fidelity_metadata"),
                           ("drop_receipt", "fidelity_count"), ("replayed", "capture_rejected"),
                           ("keep_blank", "fidelity_text"), ("nine_digit_time", "fidelity_time")):
            with self.subTest(mode=mode):
                self.configure(mode=mode)
                self.refuses(code, items=[lit.item("a", "alpha"), lit.item("b", " \t ")])

    def test_launch_refusals_clean_up(self):
        corpus = self.corpus([lit.item("a", "x")])
        with self.assertRaises(cs.ControllerError) as caught:
            with cs.PrivateCompactServer(str(self.jar), "0" * 64, str(self.java), corpus,
                                         temp_parent=str(self.parent)):
                pass
        self.assertEqual(caught.exception.code, "jar_hash_mismatch")
        self.assertEqual(list(self.parent.iterdir()), [])
        with self.assertRaises(cs.ControllerError):
            cs.PrivateCompactServer(str(self.jar), "A" * 64, str(self.java), corpus)
        with self.assertRaises(cs.ControllerError):
            cs.PrivateCompactServer(str(self.jar), self.jar_sha, str(self.java), object())
        self.configure(version="17.0.2")
        self.refuses("java_version")
        self.configure(mode="exit_early")
        self.refuses("server_exited")
        self.configure(mode="fork_listener")
        self.refuses("listener_identity")
        self.configure(mode="db_elsewhere")
        self.refuses("database_identity")
        self.configure()
        self.refuses("platform_proof_unavailable", platform=cs.Platform(lsof="", ps=""))
        self.configure(mode="hang_on_term")
        self.refuses("ready_timeout", ready_timeout=0.0, stop_timeout=0.5)

    def test_descendant_ignoring_term_is_killed_before_storage_removal(self):
        self.configure(mode="child_ignores_term")
        corpus, server = self.server([lit.item("a", "alpha")], stop_timeout=0.5)
        with server:
            pgid = server.proc.pid
            child = int(self.child_pid_file.read_text())
            self.assertIn(child, group_members(pgid))
        self.assertTrue(self.wait_gone(child), "TERM-ignoring descendant survived cleanup")
        self.assertEqual(group_members(pgid), [])
        self.assert_clean(server)

    def test_group_is_reaped_when_the_leader_already_exited(self):
        self.configure(mode="orphan_listener")
        corpus, server = self.server([lit.item("a", "alpha")], stop_timeout=0.5)
        with self.assertRaises(cs.ControllerError) as caught:
            with server:
                self.fail("context must not be entered")
        self.assertIn(caught.exception.code, ("server_exited", "listener_identity"))
        deadline = time.monotonic() + 5
        while not self.child_pid_file.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        child = int(self.child_pid_file.read_text())
        self.assertTrue(self.wait_gone(child), "orphaned group member survived cleanup")
        self.assertEqual(group_members(server.proc.pid), [])
        self.assert_clean(server)

    def test_dribbled_reply_hits_the_absolute_request_deadline(self):
        self.configure(mode="dribble_search")
        corpus, server = self.server([lit.item("a", "alpha"), lit.item("h", "handoff", kind="handoff")])
        with server:
            session = h.Session(corpus, server.backend())
            session.start()
            before = session.delivered
            with mock.patch.object(cs, "REQUEST_TIMEOUT", 0.10):
                started = time.monotonic()
                self.assertIsNone(session.search("alpha"))
                elapsed = time.monotonic() - started
            self.assertEqual(session.delivered, before)
            self.assertEqual(session.accounting()["infrastructure_error"]["code"], "transport_error")
            self.assertLess(elapsed, 1.0)
        self.assert_clean(server)

    def test_stubborn_process_is_killed_and_reaped(self):
        self.configure(mode="hang_on_term")
        corpus, server = self.server([lit.item("a", "alpha")], stop_timeout=0.5)
        with server:
            pid = server.proc.pid
        self.assertEqual(server.report["process"]["forced_kill"], True)
        self.assert_clean(server)
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_mid_session_identity_loss_is_a_backend_failure(self):
        corpus, server = self.server([lit.item("a", "alpha"), lit.item("h", "handoff", kind="handoff")])
        with server:
            session = h.Session(corpus, server.backend())
            session.start()
            before = session.delivered
            with mock.patch.object(server.platform, "listeners", return_value=[1]):
                self.assertIsNone(session.search("alpha"))
            self.assertEqual(session.delivered, before)
            self.assertEqual(session.accounting()["infrastructure_error"]["code"], "server_identity")
        self.assert_clean(server)

    def test_tampered_marker_refuses_recursive_cleanup(self):
        corpus, server = self.server([lit.item("a", "alpha")])
        with self.assertRaises(cs.ControllerError) as caught:
            with server:
                root = server.root
                (root / cs.MARKER).write_text("someone else", encoding="ascii")
        self.assertEqual(caught.exception.code, "cleanup_refused")
        self.assertTrue(root.exists())
        self.assertIsNotNone(server.proc.returncode)

    def test_signal_unwinds_through_owned_cleanup(self):
        path, digest = lit.write_corpus(self.root, [lit.item("a", "alpha")])
        script = self.root / "signal_helper.py"
        script.write_text(
            "import json, os, signal, sys, time\nsys.path.insert(0, %r)\n"
            "import compact_server as cs, history_search as h\n"
            "corpus = h.load_corpus(%r, %r)\n"
            "with cs.PrivateCompactServer(%r, %r, %r, corpus, temp_parent=%r) as server:\n"
            "    print(json.dumps({'root': str(server.root), 'pid': server.proc.pid}), flush=True)\n"
            "    os.kill(os.getpid(), signal.SIGTERM)\n"
            "    time.sleep(30)\n"
            % (str(BENCH), str(path), digest, str(self.jar), self.jar_sha, str(self.java), str(self.parent)))
        done = subprocess.run([sys.executable, "-B", str(script)], capture_output=True, timeout=120)
        self.assertEqual(done.returncode, 128 + signal.SIGTERM, done.stderr[-500:])
        owned = json.loads(done.stdout)
        self.assertFalse(Path(owned["root"]).exists())
        with self.assertRaises(ProcessLookupError):
            os.kill(owned["pid"], 0)
        self.assertEqual(list(self.parent.iterdir()), [])


class CompactCliTests(ServerFixture):
    def run_cli(self, path, digest, lines=(), jar_sha=None):
        stdin = b"".join(line + b"\n" for line in lines)
        env = dict(os.environ, TMPDIR=str(self.parent))
        done = subprocess.run([sys.executable, "-B", str(CLI), "serve", "--manifest", str(path),
                               "--manifest-sha256", digest, "--jar", str(self.jar),
                               "--jar-sha256", jar_sha or self.jar_sha, "--java", str(self.java)],
                              input=stdin, capture_output=True, timeout=180, env=env)
        records = [json.loads(line) for line in done.stderr.decode().splitlines()]
        return done.returncode, done.stdout, records

    def test_cli_journey_shares_the_literal_stream_and_budgets(self):
        path, digest = lit.write_corpus(self.root, corpus_items())
        lines = [b'{"query": "Export"}', b'{"query": "export"}', b"{bad", b'{"query": "Export"}',
                 b'{"query": "x"}', b'{"query": "y"}', b'{"query": "after limit"}']
        code, stdout, records = self.run_cli(path, digest, lines)
        self.assertEqual(code, 0, records)
        bodies = [json.loads(line) for line in stdout.decode().splitlines()]
        accounting = records[0]["host_accounting"]
        self.assertEqual(accounting["delivered_bytes"], len(stdout))
        self.assertEqual([b["status"] for b in bodies],
                         ["ok", "ok", "empty", "error", "ok", "ok", "ok", "exhausted"])
        self.assertEqual(bodies[1]["total_matches"], 5)
        self.assertEqual(bodies[-1]["error"]["code"], "attempt_limit")
        self.assertTrue(all(b["backend"] == c.BACKEND for b in bodies))
        self.assertEqual(records[1]["compact_backend"][0]["matches"], 5)
        self.assertTrue(records[2]["compact_server"]["storage_removed"])
        self.assertEqual(list(self.parent.iterdir()), [])

    def test_cli_controller_errors_emit_nothing(self):
        path, digest = lit.write_corpus(self.root, [lit.item("a", "alpha")])
        code, stdout, records = self.run_cli(path, "f" * 64, [b'{"query": "alpha"}'])
        self.assertEqual((code, stdout, records[0]["controller_error"]["code"]), (2, b"", "manifest_hash_mismatch"))
        code, stdout, records = self.run_cli(path, digest, [b'{"query": "alpha"}'], jar_sha="0" * 64)
        self.assertEqual((code, stdout), (2, b""))
        self.assertEqual(records[0]["controller_error"]["code"], "jar_hash_mismatch")
        self.assertEqual(records[0]["controller_error"]["phase"], "before_start")
        self.configure(mode="mutate_text")
        path, digest = lit.write_corpus(self.root, [lit.item("a", "alpha"), lit.item("b", "beta")])
        code, stdout, records = self.run_cli(path, digest, [b'{"query": "alpha"}'])
        self.assertEqual((code, stdout, records[0]["controller_error"]["code"]), (2, b"", "fidelity_text"))
        self.assertTrue(records[1]["compact_server"]["storage_removed"])
        self.assertEqual(list(self.parent.iterdir()), [])

    def test_cli_cleanup_failure_after_delivery_is_mid_session(self):
        self.configure(mode="tamper_marker_on_search")
        path, digest = lit.write_corpus(self.root, [lit.item("h", "handoff alpha", kind="handoff")])
        code, stdout, records = self.run_cli(path, digest, [b'{"query": "alpha"}'])
        lines = stdout.decode().splitlines()
        self.assertEqual([json.loads(line)["type"] for line in lines], ["handoff", "search"])
        self.assertEqual(code, 3, records)
        accounting = records[0]["host_accounting"]
        self.assertEqual(accounting["delivered_bytes"], len(stdout))
        error = [r["controller_error"] for r in records if "controller_error" in r][0]
        self.assertEqual((error["code"], error["phase"], error["pair_invalid"]), ("cleanup_refused", "cleanup", True))
        for leftover in list(self.parent.iterdir()):  # Refused storage stays for inspection; remove it here.
            shutil.rmtree(str(leftover))

    def test_cli_mid_session_failure_stops_delivery_and_invalidates(self):
        self.configure(mode="fail_search")
        path, digest = lit.write_corpus(self.root, [lit.item("h", "handoff", kind="handoff")])
        code, stdout, records = self.run_cli(path, digest, [b'{"query": "alpha"}', b'{"query": "beta"}'])
        self.assertEqual(code, 3)
        self.assertEqual([json.loads(line)["type"] for line in stdout.decode().splitlines()], ["handoff"])
        accounting = records[0]["host_accounting"]
        self.assertEqual((accounting["close_reason"], accounting["infrastructure_error"]["code"],
                          accounting["delivered_bytes"]), ("infrastructure_error", "api_cursor_unavailable",
                                                            len(stdout)))
        self.assertEqual(list(self.parent.iterdir()), [])


class PlatformTests(unittest.TestCase):
    def test_missing_tools_fail_closed(self):
        with self.assertRaises(cs.ControllerError) as caught:
            cs.Platform(lsof="", ps="/bin/ps").require_available()
        self.assertEqual(caught.exception.code, "platform_proof_unavailable")

    def test_capture_identity_is_deterministic_and_never_a_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            path, digest = lit.write_corpus(tmp, [lit.item("a", "x", session="s1"), lit.item("b", "y", session="s2")])
            corpus = h.load_corpus(path, digest)
        first, second = corpus.items
        self.assertEqual(cs.capture_id(corpus, first), cs.capture_id(corpus, first))
        self.assertNotEqual(cs.capture_id(corpus, first), cs.capture_id(corpus, second))
        self.assertRegex(cs.client_session(first), r"\Acorpus-[0-9a-f]{32}\Z")
        self.assertNotEqual(cs.client_session(first), cs.client_session(second))
        payload = cs.capture_payload(corpus, first)
        self.assertEqual(payload["event"]["metadata"], {"sourceRef": first.source_ref})
        self.assertEqual((payload["event"]["toolName"], payload["event"]["text"]), (None, "x"))
        self.assertEqual(payload["event"]["observedAt"], "2026-09-01T00:00:00.000000000Z")


if __name__ == "__main__":
    unittest.main()
