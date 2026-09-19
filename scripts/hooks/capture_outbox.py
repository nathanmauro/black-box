#!/usr/bin/env python3
"""Opt-in, local-only, sanitized event outbox. Python 3.9 standard library only."""
import argparse
import datetime
import fcntl
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import signal
import sqlite3
import stat
import subprocess
import sys
import time
import uuid

SANITIZER_VERSION = 1
MAX_PAYLOAD = 1024 * 1024
MAX_QUEUE_BYTES = 64 * 1024 * 1024
MAX_QUEUE_ROWS = 10000
MAX_SCAN_CHARS = 50000
MAX_NODES = 20000
MAX_DEPTH = 32
MAX_ACK_BYTES = 4096
DB_NAME = "outbox.sqlite3"
LOCK_NAME = "sender.lock"
REDACTED = "[REDACTED]"
CLIPPED = " …[truncated]"
SECRET_WORDS = ("apikey", "secret", "token", "passwd", "password", "authorization", "credential", "privatekey")
IDENTITY_KEYS = frozenset(("source", "clientsessionid", "sessionid", "parentclientsessionid", "agentid",
                           "agenttype", "turnid", "eventtype", "role", "cwd", "toolname", "path", "filepath",
                           "filename", "repo", "repository", "worktree"))
PRIVATE_BEGIN = re.compile(r"-----BEGIN [^\r\n-]{0,80}PRIVATE KEY-----", re.I)
PRIVATE_END = re.compile(r"-----END [^\r\n-]{0,80}PRIVATE KEY-----", re.I)
ASSIGNMENT = re.compile(r'''(?<![A-Za-z0-9_-])(["']?)([A-Za-z0-9_-]+)\1(\s*[=:]\s*)''')
BEARER = re.compile(r"\bbearer\s+[A-Za-z0-9._~+/=-]+", re.I)
PROVIDERS = (
    re.compile(r"\b(?:AKIA|ASIA|A3T[A-Z0-9])[A-Z0-9]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),
)


class OutboxError(Exception):
    """Contains a fixed diagnostic code only, never an event or filesystem value."""


def key_kind(value):
    return re.sub(r"[^a-z0-9]", "", value.lower())


def secret_key(value):
    normalized = key_kind(value)
    return any(word in normalized for word in SECRET_WORDS)


def redact_text(value):
    # Drop the unscanned tail. Never truncate first and then persist that tail elsewhere.
    text = value[:MAX_SCAN_CHARS]
    clipped = len(value) > MAX_SCAN_CHARS
    pieces = []
    cursor = 0
    while True:
        begin = PRIVATE_BEGIN.search(text, cursor)
        if begin is None:
            pieces.append(text[cursor:])
            break
        pieces.append(text[cursor:begin.start()])
        pieces.append(REDACTED)
        end = PRIVATE_END.search(text, begin.end())
        if end is None:
            cursor = len(text)
            break
        cursor = end.end()
    text = "".join(pieces)
    if clipped:
        # A provider token cut at the scan boundary can be shorter than its normal recognition
        # threshold. Drop that partial token rather than retain a credential fragment.
        text = re.sub(r"\b(?:sk-|gh[pousr]_|xox[baprs]-|github_pat_|AKIA|ASIA|A3T[A-Z0-9])[A-Za-z0-9_./+=-]*$", REDACTED, text)
    for pattern in PROVIDERS:
        text = pattern.sub(REDACTED, text)
    text = BEARER.sub("Bearer " + REDACTED, text)

    # Tokenize keys once, then scan each selected value once. Avoid keyword-dense regex
    # backtracking and Python 3.11-only possessive expressions.
    pieces = []
    cursor = 0
    for match in ASSIGNMENT.finditer(text):
        if match.start() < cursor or not secret_key(match.group(2)):
            continue
        start = match.end()
        if text[start:start + len(REDACTED)] == REDACTED:
            continue
        end = start
        quote = text[start:start + 1]
        if quote in ("'", '"'):
            end += 1
            escaped = False
            while end < len(text):
                char = text[end]
                end += 1
                if char == quote and not escaped:
                    break
                escaped = char == "\\" and not escaped
            replacement = quote + REDACTED + (quote if end <= len(text) and text[end - 1:end] == quote else "")
        elif text[start:start + 7].lower() == "bearer ":
            end = start + 7
            while end < len(text) and not text[end].isspace():
                end += 1
            replacement = "Bearer " + REDACTED
        else:
            while end < len(text) and not text[end].isspace() and text[end] not in ',}]':
                end += 1
            replacement = REDACTED
        if end > start:
            pieces.append(text[cursor:start])
            pieces.append(replacement)
            cursor = end
    pieces.append(text[cursor:])
    return "".join(pieces) + (CLIPPED if clipped else "")


def identity(value, optional=False):
    if optional and value is None:
        return None
    if not isinstance(value, str) or (not optional and not value.strip()) or len(value) > 4096:
        raise OutboxError("invalid_capture")
    if redact_text(value) != value:
        raise OutboxError("unsafe_identity")
    return value


def sanitize_event(event):
    if not isinstance(event, dict):
        raise OutboxError("invalid_capture")
    remaining = [MAX_NODES]

    def walk(value, depth=0):
        remaining[0] -= 1
        if remaining[0] < 0 or depth > MAX_DEPTH:
            raise OutboxError("capture_too_complex")
        if value is None or isinstance(value, bool) or isinstance(value, int):
            return value
        if isinstance(value, float):
            if not math.isfinite(value):
                raise OutboxError("invalid_capture")
            return value
        if isinstance(value, str):
            return redact_text(value)
        if isinstance(value, list):
            return [walk(item, depth + 1) for item in value]
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                if not isinstance(key, str) or len(key) > 256:
                    raise OutboxError("invalid_capture")
                kind = key_kind(key)
                if kind == "rawhook":
                    continue
                safe_key = redact_text(key)
                if secret_key(key):
                    result[safe_key] = REDACTED
                elif kind in IDENTITY_KEYS:
                    result[safe_key] = identity(item, optional=True)
                else:
                    result[safe_key] = walk(item, depth + 1)
            return result
        raise OutboxError("invalid_capture")

    sanitized = {}
    for field in ("source", "clientSessionId", "eventType"):
        sanitized[field] = identity(event.get(field))
    for field in ("turnId", "role", "cwd", "toolName"):
        sanitized[field] = identity(event.get(field), optional=True)
    text = event.get("text")
    if text is not None and not isinstance(text, str):
        raise OutboxError("invalid_capture")
    sanitized["text"] = walk(text)
    for field in ("toolInput", "toolOutput"):
        sanitized[field] = walk(event.get(field))
    metadata = event.get("metadata")
    if metadata is not None and not isinstance(metadata, dict):
        raise OutboxError("invalid_capture")
    sanitized["metadata"] = walk(metadata or {})
    observed = event.get("observedAt")
    if observed is None:
        observed = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    if not isinstance(observed, str) or len(observed) > 64:
        raise OutboxError("invalid_capture")
    try:
        parsed = datetime.datetime.fromisoformat(observed.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError()
    except ValueError:
        raise OutboxError("invalid_capture") from None
    sanitized["observedAt"] = observed
    data = json.dumps(sanitized, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    if len(data) + 64 > MAX_PAYLOAD:
        raise OutboxError("capture_too_large")
    return data


def normalized_origin(value):
    # Parsing only this grammar excludes DNS names, credentials, paths, escapes, query,
    # fragment, implicit ports, and parser normalization of an explicitly supplied URL.
    if not isinstance(value, str):
        raise OutboxError("invalid_origin")
    match = re.fullmatch(r"http://(\[[0-9a-fA-F:]+\]|[0-9.]+):([0-9]{1,5})", value, re.I)
    if match is None:
        raise OutboxError("invalid_origin")
    try:
        address = ipaddress.ip_address(match.group(1).strip("[]"))
        port = int(match.group(2))
    except ValueError:
        raise OutboxError("invalid_origin") from None
    if not address.is_loopback or not 1 <= port <= 65535:
        raise OutboxError("invalid_origin")
    host = str(address)
    wire_host = "[" + host + "]" if address.version == 6 else host
    return "http://" + wire_host + ":" + str(port), host, port


def open_private_directory(path):
    path = Path(path).expanduser()
    if not path.is_absolute() or ".." in path.parts:
        raise OutboxError("unsafe_directory")
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for index, part in enumerate(path.parts[1:]):
            try:
                os.mkdir(part, 0o700, dir_fd=descriptor)
            except FileExistsError:
                pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            info = os.fstat(child)
            mode = stat.S_IMODE(info.st_mode)
            final = index == len(path.parts) - 2
            safe_parent = info.st_uid in (0, os.getuid()) and (
                not mode & 0o022 or (info.st_uid == 0 and mode & stat.S_ISVTX))
            if (final and (info.st_uid != os.getuid() or mode != 0o700)) or (not final and not safe_parent):
                os.close(child)
                raise OutboxError("unsafe_directory")
            os.close(descriptor)
            descriptor = child
        if len(path.parts) < 2:
            raise OutboxError("unsafe_directory")
        return path, descriptor
    except BaseException:
        os.close(descriptor)
        raise


def private_file(directory_fd, name, create=False, writable=False):
    flags = (os.O_RDWR if writable else os.O_RDONLY) | os.O_NOFOLLOW | os.O_NONBLOCK
    if create:
        flags |= os.O_CREAT
    descriptor = os.open(name, flags, 0o600, dir_fd=directory_fd)
    info = os.fstat(descriptor)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
        os.close(descriptor)
        raise OutboxError("unsafe_file")
    return descriptor


class Queue:
    def __init__(self, directory):
        self.path, self.directory_fd = open_private_directory(directory)
        self.db = None
        try:
            self.check_files()
            descriptor = private_file(self.directory_fd, DB_NAME, create=True, writable=True)
            try:
                expected = os.fstat(descriptor)
                self.db = sqlite3.connect(str(self.path / DB_NAME), timeout=0.2, isolation_level=None)
                current = os.stat(DB_NAME, dir_fd=self.directory_fd, follow_symlinks=False)
                if (current.st_dev, current.st_ino) != (expected.st_dev, expected.st_ino):
                    raise OutboxError("unsafe_file")
            finally:
                os.close(descriptor)
            self.db.execute("PRAGMA busy_timeout=200")
            self.db.execute("PRAGMA temp_store=MEMORY")
            if self.db.execute("PRAGMA journal_mode=DELETE").fetchone()[0] != "delete":
                raise OutboxError("unsafe_database")
            self.db.execute("PRAGMA synchronous=FULL")
            self.db.execute("PRAGMA secure_delete=ON")
            self.db.execute("BEGIN IMMEDIATE")
            version = self.db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, 1):
                raise OutboxError("unsupported_database")
            self.db.execute("""CREATE TABLE IF NOT EXISTS captures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                origin TEXT NOT NULL,
                capture_id TEXT NOT NULL UNIQUE,
                event_bytes BLOB NOT NULL,
                sanitizer_version INTEGER NOT NULL,
                created_at REAL NOT NULL,
                logical_bytes INTEGER NOT NULL,
                category TEXT NOT NULL DEFAULT 'pending' CHECK(category IN ('pending','retry','paused','rejected')),
                attempts INTEGER NOT NULL DEFAULT 0,
                reason TEXT
            )""")
            self.db.execute("CREATE INDEX IF NOT EXISTS capture_delivery ON captures(origin, category, id)")
            self.db.execute("PRAGMA user_version=1")
            self.db.execute("COMMIT")
            self.check_files()
        except BaseException:
            self.close()
            raise

    def check_files(self):
        for name in (DB_NAME, DB_NAME + "-journal", LOCK_NAME):
            try:
                descriptor = private_file(self.directory_fd, name)
            except FileNotFoundError:
                continue
            os.close(descriptor)
        for name in (DB_NAME + "-wal", DB_NAME + "-shm"):
            try:
                os.stat(name, dir_fd=self.directory_fd, follow_symlinks=False)
            except FileNotFoundError:
                continue
            raise OutboxError("unsafe_database")

    def close(self):
        if self.db is not None:
            self.db.close()
            self.db = None
        if self.directory_fd is not None:
            os.close(self.directory_fd)
            self.directory_fd = None

    def enqueue(self, origin, data):
        capture_id = str(uuid.uuid4())
        logical_bytes = len(data) + len(origin.encode("ascii")) + 64
        self.check_files()
        self.db.execute("BEGIN IMMEDIATE")
        try:
            count, size = self.db.execute("SELECT count(*), coalesce(sum(logical_bytes),0) FROM captures").fetchone()
            if count >= MAX_QUEUE_ROWS or size + logical_bytes > MAX_QUEUE_BYTES:
                raise OutboxError("queue_full")
            self.db.execute("""INSERT INTO captures
                (origin,capture_id,event_bytes,sanitizer_version,created_at,logical_bytes)
                VALUES (?,?,?,?,?,?)""", (origin, capture_id, data, SANITIZER_VERSION, time.time(), logical_bytes))
            self.db.execute("COMMIT")
        except BaseException:
            if self.db.in_transaction:
                self.db.execute("ROLLBACK")
            raise
        return capture_id

    def status(self, origin):
        rows = self.db.execute("""SELECT category,count(*),coalesce(sum(logical_bytes),0),min(created_at)
            FROM captures WHERE origin=? GROUP BY category""", (origin,)).fetchall()
        if any(row[0] not in ("pending", "retry", "paused", "rejected") for row in rows):
            raise OutboxError("invalid_database")
        return {"origin": origin, "count": sum(row[1] for row in rows),
                "logicalBytes": sum(row[2] for row in rows),
                "oldestAgeSeconds": max([0] + [max(0, int(time.time() - row[3])) for row in rows]),
                "categories": {row[0]: row[1] for row in rows}}

    def drain(self, origin, host, port, deadline, max_events, retry_paused=False):
        self.check_files()
        lock = private_file(self.directory_fd, LOCK_NAME, create=True, writable=True)
        sent = 0
        try:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return sent
            if retry_paused:
                self.db.execute("UPDATE captures SET category='retry',reason=NULL WHERE origin=? AND category='paused'", (origin,))
            if self.db.execute("SELECT 1 FROM captures WHERE origin=? AND category='paused' LIMIT 1", (origin,)).fetchone():
                return sent
            for unused in range(max_events):
                if time.monotonic() >= deadline:
                    break
                row = self.db.execute("""SELECT id,capture_id,event_bytes,sanitizer_version FROM captures
                    WHERE origin=? AND category IN ('pending','retry') ORDER BY id LIMIT 1""", (origin,)).fetchone()
                if row is None:
                    break
                row_id, capture_id, data, version = row
                if (version != SANITIZER_VERSION or not canonical_uuid(capture_id)
                        or not isinstance(data, bytes) or len(data) + 64 > MAX_PAYLOAD):
                    category, reason = "rejected", "unsupported_capture"
                else:
                    category, reason = deliver(host, port, capture_id, data, deadline)
                self.check_files()
                if category == "acknowledged":
                    self.db.execute("DELETE FROM captures WHERE id=? AND capture_id=? AND origin=?", (row_id, capture_id, origin))
                    sent += 1
                else:
                    self.db.execute("UPDATE captures SET category=?,reason=?,attempts=attempts+1 WHERE id=?", (category, reason, row_id))
                    if category in ("retry", "paused"):
                        break
            return sent
        finally:
            os.close(lock)


def canonical_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON member")
        value[key] = item
    return value


def deliver(host, port, capture_id, data, deadline):
    connection = None
    try:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return "retry", "deadline"
        connection = http.client.HTTPConnection(host, port, timeout=remaining)
        body = b'{"captureId":"' + capture_id.encode("ascii") + b'","event":' + data + b'}'
        connection.request("POST", "/api/events/idempotent", body=body, headers={"Content-Type": "application/json"})
        response = connection.getresponse()
        if response.status in (401, 403, 404, 405):
            return "paused", "endpoint_unavailable"
        if response.status in (400, 409, 413, 422):
            return "rejected", "request_rejected"
        if response.status != 200:
            return "retry", "http_failure"
        if response.getheader("Content-Encoding", "identity").lower() != "identity":
            return "retry", "invalid_acknowledgement"
        declared = response.getheader("Content-Length")
        if declared is not None and (not declared.isdigit() or int(declared) > MAX_ACK_BYTES):
            return "retry", "invalid_acknowledgement"
        raw = response.read(MAX_ACK_BYTES + 1)
        if len(raw) > MAX_ACK_BYTES:
            return "retry", "invalid_acknowledgement"
        ack = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
        if (not isinstance(ack, dict) or ack.get("captureId") != capture_id
                or not canonical_uuid(ack.get("captureId")) or not canonical_uuid(ack.get("eventId"))
                or not canonical_uuid(ack.get("sessionId")) or type(ack.get("replayed")) is not bool):
            return "retry", "invalid_acknowledgement"
        return "acknowledged", None
    except (OSError, ValueError, http.client.HTTPException):
        return "retry", "delivery_failed"
    finally:
        if connection is not None:
            connection.close()


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise OutboxError("invalid_arguments")


def interrupted(unused_signum, unused_frame):
    raise OutboxError("interrupted")


def supervise_hook(arguments):
    """Wait for one hook invocation; never leave its normalization/delivery children running."""
    child = None
    os.umask(0o077)
    try:
        if len(arguments) != 1:
            raise OutboxError("invalid_arguments")
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        child = subprocess.Popen(["/bin/bash", str(Path(__file__).with_name("sba-agent-hook.sh")),
                                  arguments[0], "--outbox-supervised"], start_new_session=True)
        child.wait(timeout=3.0)
    except subprocess.TimeoutExpired:
        print("Black Box outbox: deadline.", file=sys.stderr)
    except (Exception, KeyboardInterrupt):
        print("Black Box outbox: operation_deferred.", file=sys.stderr)
    finally:
        if child is not None and child.poll() is None:
            # Kill only the process group created above, including a blocked reader or jq.
            # SQLite's journal protects a committed queue row if this interrupts local deletion.
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
    return 0


def main(argv=None):
    os.umask(0o077)
    queue = None
    try:
        parser = Parser(description=__doc__)
        parser.add_argument("command", choices=("enqueue", "status", "drain"))
        parser.add_argument("--url", default=os.environ.get("SBA_AGENTIC_URL", "http://127.0.0.1:8766"))
        parser.add_argument("--directory", default=os.environ.get("SBA_CAPTURE_OUTBOX_DIR", str(Path.home() / ".blackbox" / "outbox")))
        parser.add_argument("--max-events", type=int, default=20)
        parser.add_argument("--max-seconds", type=float, default=3.0)
        parser.add_argument("--retry-paused", action="store_true")
        args = parser.parse_args(argv)
        if not 1 <= args.max_events <= 1000 or not 0 < args.max_seconds <= 30 or not math.isfinite(args.max_seconds):
            raise OutboxError("invalid_arguments")
        if args.retry_paused and args.command != "drain":
            raise OutboxError("invalid_arguments")
        origin, host, port = normalized_origin(args.url)
        signal.signal(signal.SIGALRM, interrupted)
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        signal.setitimer(signal.ITIMER_REAL, args.max_seconds)
        deadline = time.monotonic() + args.max_seconds
        data = None
        if args.command == "enqueue":
            raw = sys.stdin.buffer.read(MAX_PAYLOAD + 1)
            if len(raw) > MAX_PAYLOAD:
                raise OutboxError("capture_too_large")
            data = sanitize_event(json.loads(raw.decode("utf-8")))
        queue = Queue(args.directory)
        if args.command == "status":
            print(json.dumps(queue.status(origin), sort_keys=True))
        else:
            if data is not None:
                queue.enqueue(origin, data)
            sent = queue.drain(origin, host, port, deadline, args.max_events, args.retry_paused)
            if args.command == "drain":
                print(json.dumps({"sent": sent}))
    except OutboxError as error:
        # OutboxError values are fixed internal codes. Never include exception text from other
        # libraries: URLs, paths, SQL, requests, responses, and secrets can occur in those messages.
        print("Black Box outbox: " + str(error) + ".", file=sys.stderr)
    except (Exception, KeyboardInterrupt):
        print("Black Box outbox: operation_deferred.", file=sys.stderr)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        if queue is not None:
            queue.close()
    return 0


if __name__ == "__main__":
    sys.exit(supervise_hook(sys.argv[2:]) if sys.argv[1:2] == ["_hook"] else main())
