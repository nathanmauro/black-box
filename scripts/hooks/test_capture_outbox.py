#!/usr/bin/env python3
"""Disposable filesystem/process/HTTP verification; never uses the real queue or service."""
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import socket
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = Path(__file__).resolve().parent
PROGRAM = HERE / "capture_outbox.py"
HOOK = HERE / "sba-agent-hook.sh"
spec = importlib.util.spec_from_file_location("capture_outbox", PROGRAM)
outbox = importlib.util.module_from_spec(spec)
spec.loader.exec_module(outbox)


def event(client="fixture", text="Recover this result"):
    return {"source": "codex", "clientSessionId": client, "eventType": "Observation", "text": text}


class Recorder(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, port=0):
        super().__init__(("127.0.0.1", port), Handler)
        self.origin = "http://127.0.0.1:" + str(self.server_port)
        self.lock = threading.Lock()
        self.received = []
        self.committed = {}
        self.mode = "ok"
        self.code = 200
        self.ack_mutator = None
        self.entered = threading.Event()
        self.partial = threading.Event()
        self.ack_sent = threading.Event()
        self.release = threading.Event()
        self.thread = threading.Thread(target=self.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.thread.start()

    def close(self):
        self.release.set()
        self.shutdown()
        self.server_close()
        self.thread.join(2)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, unused_format, *unused_args):
        pass

    def do_POST(self):
        try:
            raw = self.rfile.read(int(self.headers["Content-Length"]))
            body = json.loads(raw)
            with self.server.lock:
                self.server.received.append((self.path, raw))
                capture = body["captureId"]
                replayed = capture in self.server.committed
                if not replayed:
                    self.server.committed[capture] = (raw, str(uuid.uuid4()), str(uuid.uuid4()))
                original, event_id, session_id = self.server.committed[capture]
            self.server.entered.set()
            if original != raw:
                self.send_response(409)
                self.end_headers()
                return
            mode = self.server.mode
            if mode == "drop":
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                return
            if mode == "hold":
                self.server.release.wait(8)
            ack = {"captureId": capture, "eventId": event_id, "sessionId": session_id, "replayed": replayed}
            if self.server.ack_mutator:
                ack = self.server.ack_mutator(ack)
            data = ack if isinstance(ack, bytes) else json.dumps(ack).encode()
            self.send_response(self.server.code)
            if mode == "redirect":
                self.send_header("Location", self.server.redirect_to)
            self.send_header("Content-Length", str(len(data) if mode != "oversize" else 5000))
            self.end_headers()
            if mode == "partial":
                self.wfile.write(data[:len(data) // 2])
                self.wfile.flush()
                self.server.partial.set()
                self.server.release.wait(8)
                self.wfile.write(data[len(data) // 2:])
            elif mode == "drip":
                for byte in data:
                    self.wfile.write(bytes((byte,)))
                    self.wfile.flush()
                    time.sleep(0.05)
            else:
                self.wfile.write(data)
            self.wfile.flush()
            self.server.ack_sent.set()
        except (OSError, ValueError):
            pass


class OutboxTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.directory = self.root / "outbox"
        self.servers = []

    def tearDown(self):
        for server in self.servers:
            server.close()
        self.temporary.cleanup()

    def server(self, port=0):
        recorder = Recorder(port)
        self.servers.append(recorder)
        return recorder

    def env(self, origin="http://127.0.0.1:1", directory=None):
        env = dict(os.environ)
        env.pop("SBA_AGENT_SOURCE", None)
        env.update(SBA_CAPTURE_DURABLE="1", SBA_CAPTURE_OUTBOX_DIR=str(directory or self.directory), SBA_AGENTIC_URL=origin)
        return env

    def cli(self, command, payload=None, origin="http://127.0.0.1:1", extra=(), directory=None, env_extra=None):
        env = self.env(origin, directory)
        if env_extra:
            env.update(env_extra)
        data = json.dumps(payload).encode() if payload is not None else None
        return subprocess.run([sys.executable, str(PROGRAM), command, *extra], input=data,
                              capture_output=True, env=env, timeout=7)

    def hook(self, payload, origin="http://127.0.0.1:1", source="codex", extra_env=None):
        env = self.env(origin)
        if extra_env:
            env.update(extra_env)
        data = json.dumps(payload) if not isinstance(payload, str) else payload
        return subprocess.run([str(HOOK), source], input=data, text=True, capture_output=True, env=env, timeout=7)

    def rows(self):
        with sqlite3.connect(str(self.directory / outbox.DB_NAME)) as database:
            database.row_factory = sqlite3.Row
            return [dict(row) for row in database.execute("SELECT * FROM captures ORDER BY id")]

    def seed(self, origin, payload=None):
        queue = outbox.Queue(self.directory)
        try:
            return queue.enqueue(origin, outbox.sanitize_event(payload or event()))
        finally:
            queue.close()

    def start_drain(self, origin):
        return subprocess.Popen([sys.executable, str(PROGRAM), "drain"], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, env=self.env(origin), start_new_session=True)

    def kill(self, process):
        os.killpg(process.pid, signal.SIGKILL)
        process.communicate(timeout=3)

    def test_outage_is_queued_by_actual_opted_in_hook(self):
        result = self.hook({"session_id": "fixture", "hook_event_name": "Stop", "last_assistant_message": "Recover this result"})
        self.assertEqual(result.returncode, 0)
        rows = self.rows()
        self.assertEqual(len(rows), 1)
        stored = json.loads(rows[0]["event_bytes"])
        self.assertEqual(stored["role"], "assistant")
        self.assertEqual(stored["text"], "Recover this result")
        self.assertEqual(stored["metadata"], {})
        self.assertTrue(outbox.canonical_uuid(rows[0]["capture_id"]))
        self.assertNotIn("sanitizerVersion", stored)
        self.assertEqual(rows[0]["sanitizer_version"], 1)

    def test_raw_hook_jq_input_never_uses_a_regular_temporary_file(self):
        real_jq = shutil.which("jq")
        wrapper = self.root / "bin"
        wrapper.mkdir()
        audit = self.root / "stdin-types"
        executable = wrapper / "jq"
        executable.write_text("#!" + sys.executable + "\n"
                              "import os, stat\n"
                              "with open(os.environ['JQ_STDIN_AUDIT'], 'a') as log:\n"
                              "    log.write(str(stat.S_ISREG(os.fstat(0).st_mode)) + '\\n')\n"
                              "os.execv(" + repr(real_jq) + ", [" + repr(real_jq) + "] + __import__('sys').argv[1:])\n")
        executable.chmod(0o700)
        result = self.hook({"session_id": "pipe-proof", "prompt": "password=syntheticsecret " + "x" * 78000},
                           extra_env={"PATH": str(wrapper) + os.pathsep + os.environ["PATH"],
                                      "JQ_STDIN_AUDIT": str(audit)})
        self.assertEqual(result.returncode, 0)
        kinds = audit.read_text().splitlines()
        self.assertGreater(len(kinds), 5)
        self.assertNotIn("True", kinds)
        self.assertEqual(len(self.rows()), 1)

    def test_secret_json_member_names_are_sanitized_in_every_branch(self):
        secret = "sk-proj-" + "syntheticcredential" * 3
        payload = event()
        payload["toolInput"] = {"password " + secret: "another-secret", secret: "ordinary"}
        result = self.cli("enqueue", payload)
        self.assertEqual(result.returncode, 0)
        self.assertFalse(secret.encode() in (self.directory / outbox.DB_NAME).read_bytes(), "Secret in database bytes")
        self.assertNotIn(secret.encode(), self.rows()[0]["event_bytes"])

    def test_entire_hook_is_bounded_when_input_never_finishes(self):
        started = time.monotonic()
        process = subprocess.Popen([str(HOOK), "codex"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, env=self.env(), start_new_session=True)
        try:
            process.stdin.write(b'{"session_id":"unfinished"')
            process.stdin.flush()
            process.wait(timeout=4.5)
            self.assertEqual(process.returncode, 0)
            self.assertLess(time.monotonic() - started, 4.5)
            self.assertFalse(self.directory.exists())
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=3)

    def test_large_actual_hook_normalization_and_delivery_share_one_deadline(self):
        server = self.server()
        server.mode = "hold"
        started = time.monotonic()
        result = self.hook({"session_id": "large-deadline", "prompt": "password=syntheticsecret " + "x" * 900000},
                           server.origin)
        elapsed = time.monotonic() - started
        self.assertEqual(result.returncode, 0)
        self.assertGreater(elapsed, 2.5)
        self.assertLess(elapsed, 4.5)
        self.assertTrue(server.entered.is_set())
        self.assertEqual(len(self.rows()), 1)
        self.assertFalse(b"syntheticsecret" in self.rows()[0]["event_bytes"])
        server.release.set()
        server.mode = "ok"
        self.cli("drain", origin=server.origin)
        self.assertEqual(self.rows(), [])
        self.assertEqual(len(server.committed), 1)

    def test_hook_supervisor_signals_fail_soft_and_preserve_accepted_rows(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(signal=signum):
                server = self.server()
                server.mode = "hold"
                process = subprocess.Popen([str(HOOK), "codex"], stdin=subprocess.PIPE,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                           env=self.env(server.origin), start_new_session=True)
                process.stdin.write(json.dumps({"session_id": "signal", "prompt": "Keep this"}).encode())
                process.stdin.close()
                process.stdin = None
                try:
                    self.assertTrue(server.entered.wait(2))
                    process.send_signal(signum)
                    process.communicate(timeout=2)
                    self.assertEqual(process.returncode, 0)
                    self.assertEqual(len(self.rows()), 1)
                finally:
                    if process.poll() is None:
                        self.kill(process)
                server.release.set()
                server.mode = "ok"
                self.cli("drain", origin=server.origin)
                self.assertEqual(self.rows(), [])
                self.assertEqual(len(server.committed), 1)

    def test_actual_hook_outage_recovery_reuses_identity_bytes_and_time(self):
        with socket.socket() as bound:
            bound.bind(("127.0.0.1", 0))
            port = bound.getsockname()[1]
        origin = "http://127.0.0.1:" + str(port)
        self.hook({"session_id": "parent", "hook_event_name": "SubagentStop", "agent_id": "child", "agent_type": "Explore",
                   "last_assistant_message": "Done\n\n"}, origin)
        before = self.rows()[0]
        stored = json.loads(before["event_bytes"])
        self.assertEqual(stored["clientSessionId"], "parent:child")
        self.assertEqual(stored["metadata"], {"agentId": "child", "agentType": "Explore", "parentClientSessionId": "parent"})
        self.assertEqual(stored["text"], "Done")
        server = self.server(port)
        self.cli("drain", origin=origin)
        self.assertEqual(self.rows(), [])
        path, raw = server.received[0]
        self.assertEqual(path, "/api/events/idempotent")
        delivered = json.loads(raw)
        self.assertEqual(delivered["captureId"], before["capture_id"])
        self.assertEqual(delivered["event"], stored)
        self.assertIn(before["event_bytes"], raw)

    def test_commit_then_response_close_retries_without_duplicate_event(self):
        server = self.server()
        server.mode = "drop"
        self.hook({"session_id": "response-loss", "hook_event_name": "UserPromptSubmit", "prompt": "retain me"}, server.origin)
        before = self.rows()[0]
        self.assertEqual(len(server.committed), 1)
        server.mode = "ok"
        self.cli("drain", origin=server.origin)
        self.assertEqual(self.rows(), [])
        self.assertEqual(len(server.committed), 1)
        self.assertEqual(server.received[0][1], server.received[1][1])
        self.assertEqual(json.loads(server.received[1][1])["captureId"], before["capture_id"])

    def test_golden_java_and_stronger_outbox_redaction_cases(self):
        fixtures = json.loads((HERE / "fixtures" / "capture-redaction-v1.json").read_text())
        for fixture in fixtures["javaCases"] + fixtures["outboxCases"]:
            with self.subTest(input=fixture["input"]):
                self.assertEqual(outbox.redact_text(fixture["input"]), fixture["expected"])

    def test_nested_secret_keys_raw_hook_and_oversized_strings_are_sanitized_before_disk(self):
        secrets = ("sensitive-short", "longsyntheticsecretvalue", "private material never stored", "raw-hook-only-secret")
        payload = event(text="password=" + secrets[1] + " " + "x" * 70000)
        payload["toolInput"] = {"nested": [{"api_key": secrets[0]}, {"Authorization": "Bearer " + secrets[1]}]}
        payload["toolOutput"] = {"stdout": "-----BEGIN PRIVATE KEY-----\n" + secrets[2]}
        payload["metadata"] = {"rawHook": {"prompt": secrets[3]}, "safe": "context"}
        result = self.cli("enqueue", payload)
        self.assertEqual(result.returncode, 0)
        row = self.rows()[0]
        saved = json.loads(row["event_bytes"])
        self.assertEqual(saved["metadata"], {"safe": "context"})
        self.assertEqual(saved["toolInput"]["nested"][0]["api_key"], "[REDACTED]")
        self.assertEqual(saved["toolOutput"]["stdout"], "[REDACTED]")
        self.assertTrue(saved["text"].endswith("…[truncated]"))
        # Force a live rollback journal so privacy inspection covers old page images too.
        with sqlite3.connect(str(self.directory / outbox.DB_NAME), isolation_level=None) as database:
            database.execute("BEGIN IMMEDIATE")
            database.execute("UPDATE captures SET attempts=attempts+1")
            disk = b"".join(path.read_bytes() for path in self.directory.iterdir() if path.is_file())
            database.execute("ROLLBACK")
        for secret in secrets:
            self.assertNotIn(secret.encode(), disk + result.stdout + result.stderr)
        self.assertLess(len(saved["text"]), 51000)

    def test_hostile_keyword_dense_input_is_bounded(self):
        start = time.monotonic()
        output = outbox.redact_text("token " * 17000)
        self.assertLess(time.monotonic() - start, 1)
        self.assertLess(len(output), 50100)
        self.assertIn("truncated", output)

    def test_truncation_drops_short_provider_fragment_at_scan_boundary(self):
        prefix = "x " * 24995
        output = outbox.redact_text(prefix + "sk-proj-abcdefghijklmnopqrstuvwxyz")
        self.assertNotIn("sk-proj", output)
        self.assertTrue(output.endswith("[REDACTED] …[truncated]"))

    def test_suspicious_identity_or_path_is_rejected_before_queue_creation(self):
        for field in ("source", "clientSessionId", "cwd", "turnId"):
            payload = event()
            payload[field] = "sk-proj-abc123def456ghi789jkl"
            result = self.cli("enqueue", payload)
            self.assertEqual(result.returncode, 0)
            self.assertIn(b"unsafe_identity", result.stderr)
            self.assertFalse(self.directory.exists())
        payload = event()
        payload["toolInput"] = {"path": "/secret/ghp_abcdefghijklmnopqrstuvwxyz0123456789AB"}
        self.cli("enqueue", payload)
        self.assertFalse(self.directory.exists())

    def test_invalid_origins_are_rejected_without_queue_creation(self):
        bad = ("http://localhost:8766", "https://127.0.0.1:8766", "http://127.0.0.1", "http://127.0.0.1:8766/",
               "http://127.0.0.1:8766?", "http://127.0.0.1:8766#", "http://user:pass@127.0.0.1:8766",
               "http://192.0.2.1:8766", "http://2130706433:8766", "http://[::1%lo0]:8766", "")
        for origin in bad:
            with self.subTest(origin=origin):
                result = self.cli("enqueue", event(), origin=origin)
                self.assertIn(b"invalid_origin", result.stderr)
                self.assertFalse(self.directory.exists())
        self.assertEqual(outbox.normalized_origin("HTTP://[0:0:0:0:0:0:0:1]:08766")[0], "http://[::1]:8766")

    def test_origins_never_retarget_and_proxy_environment_is_ignored(self):
        first = self.server()
        second = self.server()
        first.mode = "drop"
        self.cli("enqueue", event("first"), first.origin)
        first.mode = "ok"
        self.cli("drain", origin=second.origin)
        self.assertEqual(len(self.rows()), 1)
        self.assertEqual(len(first.received), 1)
        self.assertEqual(second.received, [])
        self.cli("drain", origin=first.origin, env_extra={"HTTP_PROXY": "http://127.0.0.1:1", "http_proxy": "http://127.0.0.1:1", "NO_PROXY": ""})
        self.assertEqual(self.rows(), [])

    def test_redirect_is_not_followed(self):
        first = self.server()
        second = self.server()
        first.mode = "redirect"
        first.code = 302
        first.redirect_to = second.origin + "/api/events/idempotent"
        self.cli("enqueue", event(), first.origin)
        self.assertEqual(second.received, [])
        self.assertEqual(len(self.rows()), 1)

    def test_wrong_malformed_missing_and_oversize_acknowledgements_retain_row(self):
        cases = [lambda ack: dict(ack, captureId=str(uuid.uuid4())), lambda ack: dict(ack, eventId="not-a-uuid"),
                 lambda ack: dict(ack, sessionId="1-1-1-1-1"), lambda ack: dict(ack, replayed=1),
                 lambda ack: {"captureId": ack["captureId"]}, lambda ack: [], lambda ack: b"not json",
                 lambda ack: b"x" * 4097,
                 lambda ack: b'{"captureId":"wrong",' + json.dumps(ack).encode()[1:]]
        server = self.server()
        self.seed(server.origin)
        for mutate in cases:
            server.ack_mutator = mutate
            self.cli("drain", origin=server.origin)
            self.assertEqual(len(self.rows()), 1)
        server.ack_mutator = None
        server.mode = "oversize"
        self.cli("drain", origin=server.origin)
        self.assertEqual(len(self.rows()), 1)
        server.mode = "ok"
        self.cli("drain", origin=server.origin)
        self.assertEqual(self.rows(), [])

    def test_auth_or_unsupported_endpoint_pauses_until_explicit_drain(self):
        server = self.server()
        for code in (401, 403, 404, 405):
            with self.subTest(code=code):
                server.code = code
                self.cli("enqueue", event(str(code)), server.origin)
                self.assertEqual(self.rows()[0]["category"], "paused")
                count = len(server.received)
                server.code = 200
                self.cli("drain", origin=server.origin)
                self.assertEqual(len(server.received), count)
                self.cli("drain", origin=server.origin, extra=("--retry-paused",))
                self.assertEqual(self.rows(), [])
        self.assertTrue(all(path == "/api/events/idempotent" for path, raw in server.received))

    def test_permanent_errors_are_retained_as_gaps_while_later_events_can_send(self):
        server = self.server()
        for code in (400, 409, 413, 422):
            server.code = code
            self.cli("enqueue", event(str(code)), server.origin)
        self.assertEqual([row["category"] for row in self.rows()], ["rejected"] * 4)
        server.code = 200
        self.cli("enqueue", event("after gaps"), server.origin)
        self.assertEqual(len(self.rows()), 4)
        self.assertEqual(len(server.received), 5)
        status = json.loads(self.cli("status", origin=server.origin).stdout)
        self.assertEqual(status["categories"], {"rejected": 4})
        self.assertNotIn("after gaps", json.dumps(status))

    def test_retryable_responses_keep_oldest_first(self):
        server = self.server()
        for code in (408, 429, 500, 503):
            server.code = code
            self.cli("enqueue", event(str(code)), server.origin)
        rows = self.rows()
        self.assertEqual(len(rows), 4)
        attempts = [json.loads(raw)["captureId"] for path, raw in server.received]
        self.assertEqual(set(attempts), {rows[0]["capture_id"]})
        server.code = 200
        self.cli("drain", origin=server.origin, extra=("--max-events", "2"))
        self.assertEqual(len(self.rows()), 2)

    def test_slow_and_dripping_responses_are_bounded_and_fail_soft(self):
        server = self.server()
        for mode in ("hold", "drip"):
            server.mode = mode
            start = time.monotonic()
            result = self.hook({"session_id": mode, "hook_event_name": "Stop", "message": "bounded"}, server.origin)
            elapsed = time.monotonic() - start
            self.assertEqual(result.returncode, 0)
            self.assertLess(elapsed, 4.5)
            self.assertGreater(elapsed, 2.5)
        self.assertEqual(len(self.rows()), 2)

    def test_private_permissions_and_filesystem_rejections(self):
        self.seed("http://127.0.0.1:1")
        self.cli("drain")
        self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)
        for path in self.directory.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        for name in (outbox.DB_NAME, outbox.LOCK_NAME, outbox.DB_NAME + "-journal"):
            with self.subTest(name=name):
                separate = self.root / ("unsafe-" + name)
                separate.mkdir(mode=0o700)
                target = self.root / ("target-" + name)
                target.write_text("do not change")
                (separate / name).symlink_to(target)
                result = self.cli("enqueue", event(), directory=separate)
                self.assertEqual(result.returncode, 0)
                self.assertEqual(target.read_text(), "do not change")
        self.directory.chmod(0o755)
        self.assertIn(b"unsafe_directory", self.cli("enqueue", event()).stderr)
        self.directory.chmod(0o700)
        (self.directory / outbox.DB_NAME).chmod(0o644)
        self.assertIn(b"unsafe_file", self.cli("enqueue", event()).stderr)

    def test_symlink_ancestor_hardlink_and_fifo_are_refused(self):
        actual = self.root / "actual"
        actual.mkdir(mode=0o700)
        alias = self.root / "alias"
        alias.symlink_to(actual, target_is_directory=True)
        self.cli("enqueue", event(), directory=alias / "outbox")
        self.assertFalse((actual / "outbox").exists())
        self.directory.mkdir(mode=0o700)
        target = self.root / "linked-file"
        target.touch(mode=0o600)
        os.link(target, self.directory / outbox.DB_NAME)
        self.assertIn(b"unsafe_file", self.cli("enqueue", event()).stderr)

        (self.directory / outbox.DB_NAME).unlink()
        os.mkfifo(self.directory / outbox.DB_NAME, 0o600)
        self.assertIn(b"unsafe_file", self.cli("enqueue", event()).stderr)

    def test_owner_mismatch_is_refused_without_permission_repair(self):
        self.directory.mkdir(mode=0o700)
        original_mode = self.directory.stat().st_mode
        with patch.object(outbox.os, "getuid", return_value=os.getuid() + 1):
            with self.assertRaises(outbox.OutboxError):
                outbox.Queue(self.directory)
        self.assertEqual(self.directory.stat().st_mode, original_mode)
        self.assertFalse((self.directory / outbox.DB_NAME).exists())

    def test_sqlite_full_transaction_preserves_previously_queued_row(self):
        queue = outbox.Queue(self.directory)
        try:
            first = queue.enqueue("http://127.0.0.1:1", outbox.sanitize_event(event("existing")))
            pages = queue.db.execute("PRAGMA page_count").fetchone()[0]
            queue.db.execute("PRAGMA max_page_count=" + str(pages))
            with self.assertRaises(sqlite3.DatabaseError):
                queue.enqueue("http://127.0.0.1:1", outbox.sanitize_event(event("full", "x" * 50000)))
            self.assertEqual(queue.db.execute("SELECT capture_id FROM captures").fetchall(), [(first,)])
        finally:
            queue.close()

    def test_corrupt_database_and_invalid_large_input_are_fail_soft(self):
        self.directory.mkdir(mode=0o700)
        database = self.directory / outbox.DB_NAME
        database.write_bytes(b"corrupt sqlite fixture")
        database.chmod(0o600)
        result = self.hook({"session_id": "corrupt", "hook_event_name": "Stop"})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(database.read_bytes(), b"corrupt sqlite fixture")
        database.unlink()
        self.directory.rmdir()
        result = self.hook("x" * (outbox.MAX_PAYLOAD + 1))
        self.assertEqual(result.returncode, 0)
        self.assertFalse(self.directory.exists())
        self.assertNotIn("x" * 100, result.stderr)

    def test_concurrent_enqueue_can_progress_while_one_sender_holds_flock(self):
        server = self.server()
        server.mode = "hold"
        self.seed(server.origin)
        sender = self.start_drain(server.origin)
        try:
            self.assertTrue(server.entered.wait(2))
            with ThreadPoolExecutor(max_workers=6) as workers:
                results = list(workers.map(lambda index: self.cli("enqueue", event("worker-" + str(index)), server.origin), range(6)))
            self.assertTrue(all(result.returncode == 0 for result in results))
            self.assertEqual(len(self.rows()), 7)
            self.assertEqual(len(server.received), 1)
        finally:
            server.release.set()
            sender.communicate(timeout=5)
        self.cli("drain", origin=server.origin)
        self.assertEqual(self.rows(), [])
        self.assertEqual(len(server.committed), 7)

    def test_quota_is_atomic_never_evicts_and_checks_logical_bytes(self):
        queue = outbox.Queue(self.directory)
        try:
            data = outbox.sanitize_event(event())
            queue.db.execute("BEGIN IMMEDIATE")
            queue.db.executemany("""INSERT INTO captures(origin,capture_id,event_bytes,sanitizer_version,created_at,logical_bytes)
                VALUES(?,?,?,?,?,?)""", (("http://127.0.0.1:1", str(uuid.uuid4()), data, 1, time.time(), len(data) + 64)
                                        for unused in range(outbox.MAX_QUEUE_ROWS - 1)))
            queue.db.execute("COMMIT")
        finally:
            queue.close()
        with ThreadPoolExecutor(max_workers=5) as workers:
            results = list(workers.map(lambda index: self.cli("enqueue", event("quota-" + str(index))), range(5)))
        self.assertEqual(len(self.rows()), outbox.MAX_QUEUE_ROWS)
        # Contenders may hit the bounded SQLite busy timeout before observing the full queue.
        # Verify the quota response after contention, independently of process scheduling.
        self.assertTrue(all(result.returncode == 0 for result in results))
        self.assertIn(b"queue_full", self.cli("enqueue", event("after contention")).stderr)
        self.assertEqual(len(self.rows()), outbox.MAX_QUEUE_ROWS)
        with sqlite3.connect(str(self.directory / outbox.DB_NAME)) as database:
            database.execute("DELETE FROM captures WHERE id != (SELECT min(id) FROM captures)")
            database.execute("UPDATE captures SET logical_bytes=?", (outbox.MAX_QUEUE_BYTES - 1,))
        result = self.cli("enqueue", event("byte quota"))
        self.assertIn(b"queue_full", result.stderr)
        self.assertEqual(len(self.rows()), 1)

    def test_kill_before_and_during_ack_retains_row_then_deduplicates(self):
        for mode in ("hold", "partial"):
            with self.subTest(mode=mode):
                server = self.server()
                server.mode = mode
                self.seed(server.origin, event(mode))
                sender = self.start_drain(server.origin)
                signal_event = server.entered if mode == "hold" else server.partial
                self.assertTrue(signal_event.wait(2))
                self.kill(sender)
                self.assertEqual(len(self.rows()), 1)
                server.release.set()
                server.mode = "ok"
                self.cli("drain", origin=server.origin)
                self.assertEqual(self.rows(), [])
                self.assertEqual(len(server.committed), 1)
                self.assertEqual(server.received[0][1], server.received[1][1])

    def test_kill_after_ack_before_local_delete_retains_recoverable_row(self):
        server = self.server()
        server.mode = "hold"
        self.seed(server.origin)
        sender = self.start_drain(server.origin)
        self.assertTrue(server.entered.wait(2))
        # A real competing SQLite writer blocks only the acknowledgement-delete transaction.
        with sqlite3.connect(str(self.directory / outbox.DB_NAME), isolation_level=None) as database:
            database.execute("BEGIN IMMEDIATE")
            server.release.set()
            self.assertTrue(server.ack_sent.wait(2))
            self.kill(sender)
            database.execute("ROLLBACK")
        self.assertEqual(len(self.rows()), 1)
        server.mode = "ok"
        self.cli("drain", origin=server.origin)
        self.assertEqual(self.rows(), [])
        self.assertEqual(len(server.committed), 1)

    def test_actual_hook_normalizes_non_json_and_large_text_without_raw_payload(self):
        result = self.hook("password=supersecret123 " + "x" * 70000)
        self.assertEqual(result.returncode, 0)
        stored = json.loads(self.rows()[0]["event_bytes"])
        self.assertEqual(stored["eventType"], "RawText")
        self.assertTrue(stored["text"].startswith("password=[REDACTED]"))
        self.assertTrue(stored["text"].endswith("…[truncated]"))
        self.assertEqual(stored["metadata"], {})

    def test_actual_durable_hook_keeps_legacy_role_and_tool_precedence(self):
        server = self.server()
        cases = (("UserPromptSubmit", "user"), ("user_prompt_submit", "user"),
                 ("beforeSubmitPrompt", "user"), ("Stop", "assistant"),
                 ("AssistantMessage", "assistant"), ("pre-tool-use", "tool"),
                 ("POST_TOOL_USE", "tool"), ("Notification", "agent"))
        for event_type, role in cases:
            payload = {"session_id": "role-fixture", "hook_event_name": event_type, "message": "text",
                       "tool_input": {"command": "echo fixture"}, "tool_response": {"stdout": "preferred"},
                       "tool_output": {"stdout": "lower priority"}}
            self.hook(payload, server.origin)
            normalized = json.loads(server.received[-1][1])["event"]
            self.assertEqual(normalized["role"], role)
            self.assertEqual(normalized["eventType"], event_type)
            self.assertEqual(normalized["toolInput"], {"command": "echo fixture"})
            self.assertEqual(normalized["toolOutput"], {"stdout": "preferred"})
        self.assertEqual(self.rows(), [])


if __name__ == "__main__":
    unittest.main()
