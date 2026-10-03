#!/usr/bin/env python3
"""Private, throwaway Black Box server holding one frozen corpus for the compact backend (NAT-319).

PrivateCompactServer launches an explicitly supplied, hash-pinned JAR with an explicit Java 21 inside a
fresh 0700 temporary root, captures every corpus item through the idempotent capture route, proves
from the private SQLite file that the stored rows match the corpus, and exposes only a fetch
callable for GET /api/search/compact. There is no server URL parameter, no reuse or discovery of
databases, no download and no inherited environment. This is isolation of trusted code, not a JVM
sandbox, and supports no efficacy claim.
"""

import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import stat
import subprocess
import tempfile
import threading
import time
import urllib.parse
import uuid

import compact_backend as c
import history_search as h

SOURCE = "blackbox-compact-corpus"
EVENT_TYPE = "CorpusItem"
PROJECT = "/blackbox-compact-corpus"
CAPTURE_NAMESPACE = uuid.UUID("6f1d7c52-0b7e-5c38-9d63-2c1f7f0e9a41")
CAPTURE_ROUTE = "/api/events/idempotent"
STATUS_ROUTE = "/api/status"
SEARCH_ROUTE = "/api/search/compact"
EXCLUDED_PORTS = frozenset((8766, 8799, 18879))
MARKER = ".blackbox-compact-owner"
JAR_NAME = "trusted.jar"
DB_NAME = "compact.sqlite"
REQUEST_TIMEOUT = 10.0
READY_TIMEOUT = 120.0
CAPTURE_DEADLINE = 1800.0
STOP_TIMEOUT = 15.0
KILL_TIMEOUT = 10.0
TOOL_TIMEOUT = 10.0
MAX_SMALL_BODY = 64 * 1024
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")
BASE_ENV = {"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "TZ": "UTC"}
LSOF_CANDIDATES = ("/usr/sbin/lsof", "/usr/bin/lsof", "/sbin/lsof", "/bin/lsof")
PS_CANDIDATES = ("/bin/ps", "/usr/bin/ps")


class ControllerError(Exception):
    """Host-only failure before the session starts; the model receives nothing."""

    def __init__(self, code, message):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


def require(condition, code, message):
    if not condition:
        raise ControllerError(code, message)


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def capture_id(corpus, item):
    """Deterministic receipt UUID: fixed namespace, manifest hash and item ID."""
    return str(uuid.uuid5(CAPTURE_NAMESPACE, corpus.manifest_sha256 + "\n" + item.id))


def client_session(item):
    """Synthetic client session per original (project, session) pair; never derived into a path."""
    pair = json.dumps([item.project, item.session], ensure_ascii=False).encode("utf-8")
    return "corpus-" + hashlib.sha256(pair).hexdigest()[:32]


def capture_payload(corpus, item):
    return {"captureId": capture_id(corpus, item),
            "event": {"source": SOURCE, "clientSessionId": client_session(item), "eventType": EVENT_TYPE,
                      "cwd": PROJECT, "text": item.text, "toolName": None,
                      "metadata": {"sourceRef": item.source_ref}, "observedAt": c.utc_text(item.observed_ns)}}


def _first(candidates):
    for candidate in candidates:
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None


class Platform:
    """Process/listener/open-file proof through fixed system tools. Missing tools fail closed."""

    def __init__(self, lsof=None, ps=None):
        self.lsof = lsof if lsof is not None else _first(LSOF_CANDIDATES)
        self.ps = ps if ps is not None else _first(PS_CANDIDATES)

    def require_available(self):
        require(self.lsof and self.ps, "platform_proof_unavailable",
                "lsof and ps are required to prove listener and database identity")

    def _run(self, args, allow_absent=False):
        result = subprocess.run(args, env=dict(BASE_ENV), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=TOOL_TIMEOUT)
        if allow_absent and result.returncode == 1 and not result.stdout.strip():
            return ""
        require(result.returncode == 0, "platform_proof_failed", "identity inspection command failed")
        return result.stdout.decode("utf-8", "surrogateescape")

    def listeners(self, port):
        output = self._run([self.lsof, "-nP", "-a", "-iTCP:%d" % port, "-sTCP:LISTEN", "-Fp"], allow_absent=True)
        return sorted({int(line[1:]) for line in output.splitlines() if line.startswith("p")})

    def open_files(self, pid):
        output = self._run([self.lsof, "-nP", "-a", "-p", str(pid), "-Fn"])
        return {line[1:] for line in output.splitlines() if line.startswith("n")}

    def group_members(self, pgid):
        """Live (non-zombie) process IDs whose process group is pgid."""
        output = self._run([self.ps, "-axo", "pid=,pgid=,stat="])
        members = []
        for line in output.splitlines():
            fields = line.split()
            if len(fields) >= 3 and fields[1] == str(pgid) and not fields[2].startswith("Z"):
                members.append(int(fields[0]))
        return sorted(members)

    def running(self, pid):
        """True while pid exists and is not a zombie; never reaps it."""
        output = self._run([self.ps, "-o", "stat=", "-p", str(pid)], allow_absent=True).strip()
        return bool(output) and not output.startswith("Z")

    def command(self, pid):
        return self._run([self.ps, "-ww", "-p", str(pid), "-o", "command="]).strip()


def _free_port():
    for _ in range(50):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        if port not in EXCLUDED_PORTS:
            return port
    raise ControllerError("port_unavailable", "no fresh loopback port")


class _Duplicate(Exception):
    pass


def _strict_object(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise _Duplicate()
            result[key] = value
        return result
    try:
        value = json.loads(raw, object_pairs_hook=pairs)
    except (ValueError, RecursionError, _Duplicate, TypeError):
        return None
    return value if isinstance(value, dict) else None


class PrivateCompactServer:
    """Context manager owning one private server process and its storage for one corpus."""

    def __init__(self, jar, jar_sha256, java, corpus, platform=None, temp_parent=None,
                 ready_timeout=READY_TIMEOUT, stop_timeout=STOP_TIMEOUT):
        require(isinstance(corpus, h.Corpus), "invalid_corpus", "a hash-validated Corpus is required")
        require(isinstance(jar_sha256, str) and SHA_RE.match(jar_sha256) is not None, "invalid_jar_hash",
                "jar SHA-256 must be 64 lowercase hex digits")
        self.jar = Path(jar)
        self.java = Path(java)
        self.jar_sha256 = jar_sha256
        self.corpus = corpus
        self.platform = platform or Platform()
        self.temp_parent = temp_parent
        self.ready_timeout = ready_timeout
        self.stop_timeout = stop_timeout
        self.root = None
        self.root_identity = None
        self.marker = None
        self.db = None
        self.port = None
        self.proc = None
        self.index = None
        self.report = {"jar_sha256": jar_sha256, "items": len(corpus.items)}
        self._group_stopped = False
        self._signals = {}

    # Lifecycle -----------------------------------------------------------------------------------

    def __enter__(self):
        self._install_signals()
        try:
            self._start()
        except BaseException as error:
            self._close(error)
            if isinstance(error, (ControllerError, KeyboardInterrupt, SystemExit)):
                raise
            raise ControllerError("launch_failed", type(error).__name__) from None
        return self

    def __exit__(self, kind, error, _traceback):
        self._close(error)
        return False

    def _install_signals(self):
        if threading.current_thread() is not threading.main_thread():
            return

        def interrupted(signum, _frame):
            raise SystemExit(128 + signum)

        for signum in (signal.SIGTERM, signal.SIGHUP):
            self._signals[signum] = signal.signal(signum, interrupted)

    def _close(self, error):
        for signum in self._signals:  # A repeated signal must not abort cleanup half way.
            signal.signal(signum, signal.SIG_IGN)
        failure = None
        try:
            self.report["process"] = self._stop()
        except BaseException as caught:
            failure = failure or caught
            self.report["process_error"] = type(caught).__name__
        try:
            self.report["storage_removed"] = self._remove_storage()
        except BaseException as caught:
            failure = failure or caught
            self.report["storage_error"] = getattr(caught, "code", type(caught).__name__)
        for signum, previous in self._signals.items():
            signal.signal(signum, previous)
        self._signals = {}
        if failure is not None and error is None:
            if isinstance(failure, ControllerError):
                raise failure
            raise ControllerError("cleanup_failed", type(failure).__name__)

    def _stop(self):
        """Stop the whole owned process group, then reap the leader.

        start_new_session makes the child a session leader, so its process group ID is its PID and
        cannot change. The leader is never reaped before this point (liveness checks use ps, not
        wait), and a zombie still holds its PID, so the group ID cannot be reused by an unrelated
        process while it is signalled here. Descendants that ignore TERM or outlive the leader are
        killed before the leader is reaped and before storage may be removed.
        """
        proc = self.proc
        if proc is None:
            return {"launched": False}
        require(proc.returncode is None, "process_reaped_early",
                "leader was reaped before its group was stopped; refusing to signal its group ID")
        pgid, forced = proc.pid, False
        self._signal_group(pgid, signal.SIGTERM)
        if not self._group_drained(pgid, self.stop_timeout):
            forced = True
            self._signal_group(pgid, signal.SIGKILL)
            require(self._group_drained(pgid, KILL_TIMEOUT), "process_group_survived",
                    "owned process group did not exit after SIGKILL")
        proc.wait(timeout=KILL_TIMEOUT)
        self._group_stopped = True
        return {"launched": True, "exit_code": proc.returncode, "forced_kill": forced, "reaped": True}

    def _signal_group(self, pgid, signum):
        try:
            os.killpg(pgid, signum)
        except ProcessLookupError:  # Only the unreaped zombie leader remains (Linux).
            pass
        except PermissionError:
            # macOS reports EPERM for a group holding only the zombie leader. Accept that only when
            # no live member remains; otherwise fail closed and keep the storage.
            require(not self.platform.group_members(pgid), "process_group_signal",
                    "could not signal a live owned process group member")

    def _group_drained(self, pgid, seconds):
        deadline = time.monotonic() + seconds
        while True:
            if not self.platform.group_members(pgid):
                return True
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.05)

    def _remove_storage(self):
        root = self.root
        if root is None:
            return False
        require(self.proc is None or self._group_stopped, "cleanup_refused",
                "owned process group was not proven stopped")
        info = os.lstat(root)
        marker = root / MARKER
        require(stat.S_ISDIR(info.st_mode) and (info.st_dev, info.st_ino, info.st_uid) == self.root_identity
                and info.st_uid == os.getuid() and not marker.is_symlink() and marker.is_file()
                and marker.read_text(encoding="ascii") == self.marker,
                "cleanup_refused", "private storage ownership changed; refusing recursive removal")
        shutil.rmtree(str(root))
        self.root = None
        return True

    # Launch --------------------------------------------------------------------------------------

    def _start(self):
        self.platform.require_available()
        require(self.java.is_absolute() and self.jar.is_absolute(), "invalid_path", "jar and java must be absolute")
        java = self.java.resolve(strict=True)
        jar = self.jar.resolve(strict=True)
        require(stat.S_ISREG(java.stat().st_mode) and os.access(str(java), os.X_OK), "invalid_java",
                "java is not an executable regular file")
        require(stat.S_ISREG(jar.stat().st_mode), "invalid_jar", "jar is not a regular file")
        require(file_sha256(jar) == self.jar_sha256, "jar_hash_mismatch", "jar bytes differ from the pinned SHA-256")

        root = Path(tempfile.mkdtemp(prefix="blackbox-compact-", dir=self.temp_parent)).resolve()
        self.root = root
        os.chmod(str(root), 0o700)
        info = os.lstat(root)
        self.root_identity = (info.st_dev, info.st_ino, info.st_uid)
        self.marker = secrets.token_hex(16)
        fd = os.open(str(root / MARKER), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w", encoding="ascii") as stream:
            stream.write(self.marker)
        for name in ("home", "tmp", "xdg-config", "xdg-cache", "xdg-data"):
            (root / name).mkdir(mode=0o700)
        copied = root / JAR_NAME
        shutil.copyfile(str(jar), str(copied))
        os.chmod(str(copied), 0o400)
        require(file_sha256(copied) == self.jar_sha256, "jar_hash_mismatch", "jar changed while being copied")
        env = dict(BASE_ENV, HOME=str(root / "home"), TMPDIR=str(root / "tmp"),
                   XDG_CONFIG_HOME=str(root / "xdg-config"), XDG_CACHE_HOME=str(root / "xdg-cache"),
                   XDG_DATA_HOME=str(root / "xdg-data"), JAVA_HOME=str(java.parent.parent))
        self.env = env
        version = subprocess.run([str(java), "-version"], env=env, cwd=str(root), stdin=subprocess.DEVNULL,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        text = (version.stdout + version.stderr).decode("utf-8", "replace")
        require(version.returncode == 0 and re.search(r'version "21(?:[."]|$)', text) is not None,
                "java_version", "java must report version 21")

        self.port = _free_port()
        require(self.platform.listeners(self.port) == [], "port_unavailable", "selected port already listens")
        self.db = root / DB_NAME
        require(not os.path.lexists(str(self.db)), "database_exists", "private database path already exists")
        home = root / "home"
        argv = [str(java), "-Duser.home=" + str(home), "-Djava.io.tmpdir=" + str(root / "tmp"),
                "-Dorg.sqlite.tmpdir=" + str(root / "tmp"), "-Dfile.encoding=UTF-8", "-Djava.net.useSystemProxies=false",
                "-jar", str(copied),
                "--spring.config.location=classpath:/application.yml", "--spring.profiles.active=default",
                "--server.address=127.0.0.1", "--server.port=%d" % self.port, "--sba.storage.backend=sqlite",
                "--spring.datasource.url=jdbc:sqlite:" + str(self.db),
                "--sba.ingestion.redact-enabled=false", "--sba.ingestion.max-text-length=65536",
                "--sba.summary.backend=local", "--sba.local-ai.enabled=false",
                "--sba.local-ai.base-url=http://127.0.0.1:9", "--sba.elasticsearch.enabled=false",
                "--sba.memory.embedding.enabled=false", "--sba.memory.embedding.base-url=http://127.0.0.1:9",
                "--sba.ask.embedding-enabled=false", "--sba.ask.embedding-base-url=http://127.0.0.1:9",
                "--sba.judge.enabled=false", "--sba.editor.enabled=false", "--sba.storage.retire-workflow=false",
                "--sba.transcript.codex-roots=" + str(home / "no-codex-transcripts"),
                "--sba.transcript.claude-roots=" + str(home / "no-claude-transcripts"),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"]
        log_fd = os.open(str(root / "server.log"), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(log_fd, "wb") as log:
            self.proc = subprocess.Popen(argv, env=env, cwd=str(root), stdin=subprocess.DEVNULL, stdout=log,
                                         stderr=subprocess.STDOUT, start_new_session=True, close_fds=True)
        self._await_ready()
        self._prove_database()
        require(self._count("agent_events") == 0 and self._count("event_capture_receipts") == 0,
                "database_not_empty", "private database was not empty before capture")
        acks = self._capture()
        self.index = self._prove_fidelity(acks)
        self._prove_database()
        self.report.update(port=self.port, events=len(self.index.entries))

    def _await_ready(self):
        deadline = time.monotonic() + self.ready_timeout
        while True:
            require(self.platform.running(self.proc.pid), "server_exited", "server exited before readiness")
            require(time.monotonic() < deadline, "ready_timeout", "server did not become ready in time")
            current = self.platform.listeners(self.port)
            require(not current or current == [self.proc.pid], "listener_identity",
                    "another process claimed the selected port")
            if current and self.db.is_file():
                try:
                    status, body = self._http("GET", STATUS_ROUTE, None, MAX_SMALL_BODY)
                    if status == 200 and _strict_object(body) is not None:
                        return
                except (OSError, http.client.HTTPException):
                    pass
            time.sleep(0.2)

    def _prove_listener(self):
        # A listening socket held by the leader PID proves it is alive; nothing here reaps it.
        require(self.proc is not None, "server_exited", "owned server is not running")
        require(self.platform.listeners(self.port) == [self.proc.pid], "listener_identity",
                "selected port is not exclusively owned by the launched process")

    def _prove_database(self):
        self._prove_listener()
        require(os.getpgid(self.proc.pid) == self.proc.pid, "process_group_changed", "owned process group changed")
        require(str(self.db) in self.platform.open_files(self.proc.pid), "database_identity",
                "owned process does not hold the private database")
        command = self.platform.command(self.proc.pid)
        require(str(self.root / JAR_NAME) in command and "jdbc:sqlite:" + str(self.db) in command,
                "process_identity", "process command does not name the private jar and database")
        info = os.lstat(self.db)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_dev == self.root_identity[0],
                "database_identity", "private database is not an owned regular file")

    # HTTP ----------------------------------------------------------------------------------------

    def _http(self, method, path, payload, limit):
        """One bounded loopback request with an absolute wall-clock deadline.

        The socket timeout only bounds each idle wait, so a reply dribbled just faster than it could
        last forever. A timer shuts the socket down at the deadline; any reply finishing after it is
        discarded as a TimeoutError.
        """
        require(path.startswith("/api/") and "//" not in path, "invalid_route", "unexpected route")
        timeout = REQUEST_TIMEOUT
        deadline = time.monotonic() + timeout
        expired = threading.Event()
        timer = None
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        try:
            connection.connect()
            sock = connection.sock

            def expire():
                expired.set()
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

            timer = threading.Timer(max(0.0, deadline - time.monotonic()), expire)
            timer.daemon = True
            timer.start()
            body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers = {"Accept": "application/json"}
            if body is not None:
                headers["Content-Type"] = "application/json"
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read(limit + 1)
            status = response.status
        except (OSError, http.client.HTTPException):
            if expired.is_set():
                raise TimeoutError("request deadline exceeded") from None
            raise
        finally:
            if timer is not None:
                timer.cancel()
            connection.close()
        if expired.is_set() or time.monotonic() >= deadline:
            raise TimeoutError("request deadline exceeded")
        return status, data

    def _request(self, method, path, payload=None, limit=MAX_SMALL_BODY):
        self._prove_listener()  # Every request is preceded by an ownership check.
        return self._http(method, path, payload, limit)

    def fetch(self, params):
        """Controller-owned canonical search fetch for the backend: fixed route, owned listener only."""
        try:
            self._prove_listener()
        except ControllerError as error:
            raise h.BackendFailure("server_identity", error.code) from None
        query = urllib.parse.urlencode(list(params), quote_via=urllib.parse.quote, safe="")
        return self._http("GET", SEARCH_ROUTE + "?" + query, None, c.PAGE_MAX_BYTES)

    def backend(self):
        require(self.index is not None, "not_started", "server is not ready")
        return c.CompactCanonicalBackend(self.fetch, self.index)

    # Capture and fidelity ------------------------------------------------------------------------

    def _capture(self):
        deadline = time.monotonic() + CAPTURE_DEADLINE
        acks = {}
        for item in self.corpus.items:
            require(time.monotonic() < deadline, "capture_deadline", "capture exceeded its deadline")
            payload = capture_payload(self.corpus, item)
            status, body = self._request("POST", CAPTURE_ROUTE, payload)
            ack = _strict_object(body) if len(body) <= MAX_SMALL_BODY else None
            require(status == 200 and ack is not None and set(ack) == {"captureId", "eventId", "sessionId", "replayed"}
                    and ack["captureId"] == payload["captureId"] and ack["replayed"] is False
                    and isinstance(ack["eventId"], str) and ack["eventId"]
                    and isinstance(ack["sessionId"], str) and ack["sessionId"],
                    "capture_rejected", "capture was not acknowledged as a first write")
            acks[payload["captureId"]] = (item, ack["eventId"], ack["sessionId"])
        return acks

    def _connect(self):
        require(self.db.is_file() and not self.db.is_symlink(), "database_identity", "private database missing")
        connection = sqlite3.connect(self.db.as_uri() + "?mode=ro", uri=True, timeout=5)
        connection.execute("PRAGMA query_only=ON")
        listed = connection.execute("PRAGMA database_list").fetchall()
        if len(listed) != 1 or Path(listed[0][2]).resolve() != self.db:
            connection.close()
            raise ControllerError("database_identity", "read-only connection is not the private database")
        return connection

    def _count(self, table):
        connection = self._connect()
        try:
            return connection.execute("SELECT count(*) FROM " + table).fetchone()[0]
        finally:
            connection.close()

    def _prove_fidelity(self, acks):
        self._prove_database()
        connection = self._connect()
        try:
            counts = [connection.execute("SELECT count(*) FROM " + t).fetchone()[0]
                      for t in ("agent_events", "event_capture_receipts")]
            rows = connection.execute(
                "SELECT r.capture_id, r.source, r.client_session_id, e.id, e.session_id, e.source,"
                " e.client_session_id, e.text, e.tool_name, e.metadata_json, e.observed_at,"
                " s.source, s.client_session_id, s.cwd"
                " FROM event_capture_receipts r JOIN agent_events e ON e.id = r.event_id"
                " JOIN agent_sessions s ON s.id = e.session_id").fetchall()
        except sqlite3.Error:
            raise ControllerError("fidelity_unreadable", "stored rows could not be read") from None
        finally:
            connection.close()
        n = len(self.corpus.items)
        require(counts == [n, n] and len(rows) == n, "fidelity_count", "stored event or receipt count differs")
        entries, pair_sessions, session_pairs = {}, {}, {}
        for (receipt, r_source, r_client, event_id, session_id, e_source, e_client, text, tool, metadata,
             observed, s_source, s_client, cwd) in rows:
            require(receipt in acks, "fidelity_receipt", "stored receipt does not belong to this corpus")
            item, ack_event, ack_session = acks.pop(receipt)
            client = client_session(item)
            require(event_id == ack_event and session_id == ack_session, "fidelity_identity",
                    "stored identity differs from the acknowledgement")
            require(r_source == e_source == s_source == SOURCE and r_client == e_client == s_client == client
                    and cwd == PROJECT, "fidelity_session", "stored session mapping differs")
            pair = (item.project, item.session)
            require(pair_sessions.setdefault(pair, session_id) == session_id
                    and session_pairs.setdefault(session_id, pair) == pair, "fidelity_session",
                    "synthetic sessions do not map one-to-one to source sessions")
            require(text is None if c.java_blank(item.text) else text == item.text, "fidelity_text",
                    "stored text differs from the corpus item")
            require(tool is None, "fidelity_tool", "stored tool name is not null")
            require(isinstance(metadata, str) and _strict_object(metadata) == {"sourceRef": item.source_ref},
                    "fidelity_metadata", "stored metadata differs from the source reference")
            require(c.canonical_instant(observed) == item.observed_ns, "fidelity_time",
                    "stored observed time differs from the corpus item")
            entries[event_id] = c.IndexEntry(item, session_id, client, metadata)
        require(not acks and len(entries) == n, "fidelity_count", "receipts do not cover every corpus item")
        self.report["sessions"] = len(pair_sessions)
        return c.CompactIndex(self.corpus, PROJECT, entries)
