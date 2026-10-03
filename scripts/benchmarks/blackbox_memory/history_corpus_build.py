#!/usr/bin/env python3
"""Build a frozen history text corpus from one hash-pinned standalone Black Box SQLite snapshot.

Offline development tooling (NAT-325). Dry run is the default and prints a syntactic plan only: no
snapshot read, no SQLite connection, no output. --execute copies the snapshot into private staging
while hashing it, opens only that verified copy read-only/immutable, and publishes the existing
blackbox.history-corpus/v1 manifest and blackbox.history-items/v1 items file into a fresh directory.
It exports canonical agent_events.text only, never tool payloads or metadata, and records no
recorded_at: a snapshot hash does not prove that any event was available before the cutoff. No
credentials, network, providers or live services. See docs/continuation-comparison-protocol.md.
"""

import argparse
import errno
import hashlib
import json
import os
from pathlib import Path
import secrets
import sqlite3
import stat
import sys
import time
import unicodedata

import compact_backend
import history_search as h

MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024 * 1024
MAX_EXCLUSIONS_FILE_BYTES = 256 * 1024
MAX_METADATA_BYTES = 1024 * 1024
MAX_SCANNED_ROWS = 1000000
DEADLINE_SECONDS = 300
COPY_CHUNK = 1024 * 1024
SIDECARS = ("-wal", "-shm", "-journal")
MANIFEST_FILE = "manifest.json"
ITEMS_FILE = "items.json"
SNAPSHOT_COPY = "snapshot.sqlite"

# Exact stored event_type values with a matching corpus kind; every other type is a plain event.
KIND_MAP = {"Handoff": "handoff", "Decision": "decision", "Observation": "observation"}
REQUIRED_COLUMNS = {
    "agent_sessions": ("id", "source", "client_session_id", "cwd"),
    "agent_events": ("id", "session_id", "source", "client_session_id", "event_type", "text",
                     "metadata_json", "observed_at"),
}
OMITTED_FIELDS = (
    "agent_events.role", "agent_events.turn_id", "agent_events.tool_name", "agent_events.tool_input_json",
    "agent_events.tool_output_json", "agent_events.metadata_json (repo read for attribution only)",
    "agent_events.human_text", "agent_events.source", "agent_events.client_session_id",
    "agent_sessions columns other than id/source/client_session_id/cwd", "all other tables", "recorded_at",
)


class BuildError(Exception):
    """Stable public error code. Row text, paths and underlying exceptions never reach output."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class CleanupFailed(BuildError):
    """A cleanup step failed. Reports whether the corpus is published and whether staging is gone."""

    def __init__(self, published, staging_removed, cause=None, output=None):
        super().__init__("cleanup_failed")
        self.published = published
        self.staging_removed = staging_removed
        self.cause = cause
        self.output = output  # failed publication rollback: {"output_removed", "output_entries"}


def _code(error):
    if isinstance(error, BuildError):
        return error.code
    return "interrupted" if isinstance(error, KeyboardInterrupt) else "build_failed"


def check_deadline(deadline):
    if time.monotonic() > deadline:
        raise BuildError("build_timeout")


class Staging:
    """Private staging directory plus the fixed files this run created in it.

    Only entries whose identity was recorded at creation are ever removed: a pre-existing or
    replaced path is foreign and is left in place (and makes cleanup report failure).
    """

    def __init__(self, parent_fd):
        self.parent_fd = parent_fd
        self.name = self.fd = self.identity = None
        self.files = {}

    def create(self):
        name = ".history-corpus-build-" + secrets.token_hex(8)
        try:
            os.mkdir(name, 0o700, dir_fd=self.parent_fd)
        except FileExistsError:
            raise BuildError("staging_collision") from None
        created = os.stat(name, dir_fd=self.parent_fd, follow_symlinks=False)
        self.name, self.identity = name, (created.st_dev, created.st_ino)
        fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=self.parent_fd)
        info = os.fstat(fd)
        if (info.st_dev, info.st_ino) != self.identity:
            os.close(fd)
            raise BuildError("staging_changed")
        self.fd = fd

    def create_file(self, name):
        fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=self.fd)
        info = os.fstat(fd)
        self.files[name] = (info.st_dev, info.st_ino)
        return fd

    def remove(self, name):
        """Unlink one owned file and verify it is gone; raises OSError/BuildError on failure."""
        identity = self.files.get(name)
        if identity is None:
            return
        try:
            current = os.stat(name, dir_fd=self.fd, follow_symlinks=False)
        except FileNotFoundError:
            del self.files[name]
            return
        if (current.st_dev, current.st_ino) != identity:
            raise BuildError("staging_changed")
        os.unlink(name, dir_fd=self.fd)
        if _exists(self.fd, name):
            raise BuildError("staging_changed")
        del self.files[name]

    def cleanup(self):
        """Remove owned files, then the owned directory. True only when all of it is gone."""
        if self.name is None:
            return True
        ok = True
        for name in sorted(self.files):
            try:
                self.remove(name)
            except (OSError, BuildError):
                ok = False
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
        if not ok:
            return False
        try:
            current = os.stat(self.name, dir_fd=self.parent_fd, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != self.identity:
                return False
            os.rmdir(self.name, dir_fd=self.parent_fd)
        except FileNotFoundError:
            pass  # Already gone: nothing of ours remains.
        except OSError:
            return False
        self.name = None
        return True


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise BuildError("invalid_arguments")


def parse_args(argv):
    parser = Parser(description=__doc__.splitlines()[0], allow_abbrev=False)
    parser.add_argument("--snapshot", required=True, help="Standalone SQLite snapshot file (no symlinks).")
    parser.add_argument("--snapshot-sha256", required=True, help="Expected snapshot SHA-256.")
    parser.add_argument("--corpus-id", required=True)
    parser.add_argument("--project", required=True, help="Exact project label; no normalization.")
    parser.add_argument("--session", action="append", required=True,
                        help="Internal agent_sessions.id to include; repeat for each session.")
    parser.add_argument("--cutoff", required=True, help="Inclusive RFC 3339 cutoff (1-9 fractional digits).")
    parser.add_argument("--exclusions", required=True, help="JSON file: list of {\"id\", \"reason\"}.")
    parser.add_argument("--output", required=True, help="Fresh output directory; must not exist.")
    parser.add_argument("--execute", action="store_true", help="Materialize; default is a dry run.")
    return parser.parse_args(argv)


def _path(value):
    if not value or any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise BuildError("invalid_arguments")
    return Path(os.path.abspath(value))


def _checked(code, check, *args):
    try:
        return check(*args)
    except h.CorpusError:
        raise BuildError(code) from None


def _read_exclusions(path):
    # Non-blocking, no-follow open and fstat before any read: a FIFO or device must not hang a dry run.
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        raise BuildError("invalid_exclusions") from None
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise BuildError("invalid_exclusions")
        chunks, size = [], 0
        while size <= MAX_EXCLUSIONS_FILE_BYTES:
            chunk = os.read(fd, MAX_EXCLUSIONS_FILE_BYTES + 1 - size)
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
        data = b"".join(chunks)
    except OSError:
        raise BuildError("invalid_exclusions") from None
    finally:
        os.close(fd)
    if len(data) > MAX_EXCLUSIONS_FILE_BYTES:
        raise BuildError("invalid_exclusions")
    raw = _checked("invalid_exclusions", h._load_json, data, "exclusions")
    if not isinstance(raw, list) or len(raw) > h.MAX_EXCLUSIONS:
        raise BuildError("invalid_exclusions")
    exclusions = {}
    for entry in raw:
        if not isinstance(entry, dict) or set(entry) != {"id", "reason"}:
            raise BuildError("invalid_exclusions")
        exclusion_id = _checked("invalid_exclusions", h._identifier, entry["id"], "exclusion.id")
        reason = _checked("invalid_exclusions", h._string, entry["reason"], "exclusion.reason", h.MAX_NAME_BYTES * 2)
        if compact_backend.java_blank(reason):
            raise BuildError("invalid_exclusions")
        if exclusion_id in exclusions:
            raise BuildError("duplicate_exclusion")
        exclusions[exclusion_id] = reason
    return exclusions


def make_plan(args):
    """Syntactic validation only. Reads the exclusions file; never touches the snapshot."""
    if not h.SHA_RE.match(args.snapshot_sha256):
        raise BuildError("invalid_snapshot_sha256")
    _checked("invalid_corpus_id", h._identifier, args.corpus_id, "corpus_id")
    _checked("invalid_project", h._string, args.project, "project", h.MAX_NAME_BYTES)
    for session in args.session:
        _checked("invalid_session", h._string, session, "session", h.MAX_NAME_BYTES)
    if len(set(args.session)) != len(args.session):
        raise BuildError("duplicate_session")
    if len(args.session) > h.MAX_ITEMS:
        raise BuildError("too_many_sessions")
    cutoff_ns = _checked("invalid_cutoff", h.parse_time, args.cutoff, "cutoff")
    snapshot, output = _path(args.snapshot), _path(args.output)
    if snapshot == output or snapshot in output.parents:
        raise BuildError("output_is_snapshot")
    if output.parent == snapshot.parent:
        reserved_output_name(snapshot, output)
    exclusions = _read_exclusions(_path(args.exclusions))
    return {"snapshot": snapshot, "snapshot_sha256": args.snapshot_sha256, "corpus_id": args.corpus_id,
            "project": args.project, "sessions": sorted(args.session), "cutoff": args.cutoff,
            "cutoff_ns": cutoff_ns, "exclusions": exclusions, "output": output}


def filename_key(name):
    # Same contract as scripts/storage/blackbox_backup.py sqlite_filename_key: canonical (NFC)
    # equivalence plus casefold only; compatibility-distinct names are not merged.
    return unicodedata.normalize("NFC", unicodedata.normalize("NFC", name).casefold())


def reserved_output_name(snapshot, output):
    """Raise when output names the snapshot or one of its SQLite sidecars, assuming a shared parent."""
    output_key, source_key = filename_key(output.name), filename_key(snapshot.name)
    if output_key == source_key:
        raise BuildError("output_is_snapshot")
    if output_key in [source_key + suffix for suffix in SIDECARS]:
        raise BuildError("output_is_sidecar")


def check_output_parent(snapshot, output, parent_fd):
    """Reserved names are refused when the parents are the same directory under any spelling."""
    try:
        reserved_output_name(snapshot, output)
    except BuildError as reserved:
        try:
            # Follow parent aliases only; the snapshot leaf itself is never resolved here.
            source_parent = os.stat(snapshot.parent)
        except OSError:
            raise BuildError("unsafe_snapshot") from None
        output_parent = os.fstat(parent_fd)
        if (source_parent.st_dev, source_parent.st_ino) == (output_parent.st_dev, output_parent.st_ino):
            raise reserved from None


def describe(plan):
    return {"status": "planned", "mode": "dry_run", "snapshot": str(plan["snapshot"]),
            "snapshot_sha256": plan["snapshot_sha256"], "corpus_id": plan["corpus_id"],
            "project": plan["project"], "sessions": plan["sessions"], "cutoff": plan["cutoff"],
            "exclusions": len(plan["exclusions"]), "output": str(plan["output"]),
            "snapshot_read": False, "availability": "unverified"}


def open_dir(path, code):
    """Directory fd for path, refusing a symlink in every component."""
    fd = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:]:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        return fd
    except OSError:
        os.close(fd)
        raise BuildError(code) from None


def _exists(dir_fd, name):
    try:
        os.stat(name, dir_fd=dir_fd, follow_symlinks=False)
    except FileNotFoundError:
        return False
    return True


def _identity(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def _read_chunk(fd, size):
    return os.read(fd, size)


def copy_snapshot(plan, staging, deadline):
    """Raw no-follow copy into private staging; the source is never opened with SQLite."""
    snapshot = plan["snapshot"]
    parent_fd = open_dir(snapshot.parent, "unsafe_snapshot")
    try:
        def sidecars():
            if any(_exists(parent_fd, snapshot.name + suffix) for suffix in SIDECARS):
                raise BuildError("sqlite_sidecar_present")

        sidecars()
        try:
            source = os.open(snapshot.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent_fd)
        except OSError:
            raise BuildError("unsafe_snapshot") from None
        try:
            before = os.fstat(source)
            if not stat.S_ISREG(before.st_mode) or before.st_size == 0:
                raise BuildError("unsafe_snapshot")
            if before.st_size > MAX_SNAPSHOT_BYTES:
                raise BuildError("snapshot_too_large")
            target = staging.create_file(SNAPSHOT_COPY)
            digest, total = hashlib.sha256(), 0
            try:
                while True:
                    if time.monotonic() > deadline:
                        raise BuildError("build_timeout")
                    chunk = _read_chunk(source, COPY_CHUNK)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > before.st_size:
                        raise BuildError("snapshot_changed")
                    digest.update(chunk)
                    view = memoryview(chunk)
                    while view:
                        view = view[os.write(target, view):]
                os.fsync(target)
            finally:
                os.close(target)
            after = os.fstat(source)
        finally:
            os.close(source)
        try:
            named = os.stat(snapshot.name, dir_fd=parent_fd, follow_symlinks=False)
        except OSError:
            raise BuildError("snapshot_changed") from None
        if total != before.st_size or _identity(after) != _identity(before) or _identity(named) != _identity(before):
            raise BuildError("snapshot_changed")
        sidecars()
    finally:
        os.close(parent_fd)
    if digest.hexdigest() != plan["snapshot_sha256"]:
        raise BuildError("snapshot_hash_mismatch")
    return total


def check_header(staging_fd):
    fd = os.open(SNAPSHOT_COPY, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=staging_fd)
    try:
        header = os.read(fd, 100)
    finally:
        os.close(fd)
    if len(header) < 100 or header[:16] != b"SQLite format 3\x00":
        raise BuildError("invalid_snapshot")
    # Bytes 18/19 are the write/read format versions: 1 is rollback journal, 2 is WAL.
    if header[18] == 2 or header[19] == 2:
        raise BuildError("wal_mode_snapshot")
    if header[18] != 1 or header[19] != 1:
        raise BuildError("invalid_snapshot")


def _text(value, kind, code):
    """Decode one SQLite TEXT cell (typeof checked), else fail with code."""
    if kind != b"text" or not isinstance(value, bytes):
        raise BuildError(code)
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError:
        raise BuildError(code) from None


def open_copy(path, deadline):
    connection = sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True, isolation_level=None)
    connection.text_factory = bytes
    connection.set_progress_handler(lambda: 1 if time.monotonic() > deadline else 0, 10000)
    for pragma in ("trusted_schema=OFF", "query_only=ON", "ignore_check_constraints=ON"):
        connection.execute("PRAGMA " + pragma)
    return connection


def check_schema(connection):
    if connection.execute("PRAGMA encoding").fetchall() != [(b"UTF-8",)]:
        raise BuildError("unsupported_encoding")
    if connection.execute("PRAGMA quick_check").fetchall() != [(b"ok",)]:
        raise BuildError("sqlite_integrity_failed")
    for table, columns in REQUIRED_COLUMNS.items():
        rows = connection.execute("SELECT type, sql FROM sqlite_master WHERE name = ?", (table,)).fetchall()
        if len(rows) != 1 or rows[0][0] != b"table" or not isinstance(rows[0][1], bytes) \
                or rows[0][1].lstrip().upper().startswith(b"CREATE VIRTUAL"):
            raise BuildError("unsupported_schema")
        # table_xinfo column 6 is "hidden": generated/hidden columns are not plain stored values.
        info = {row[1]: row[6] for row in connection.execute("PRAGMA table_xinfo(" + table + ")")}
        if any(info.get(column.encode()) != 0 for column in columns):
            raise BuildError("unsupported_schema")


def load_sessions(connection, plan):
    allowlist = json.dumps(plan["sessions"])
    rows = connection.execute(
        "SELECT id, typeof(id), source, typeof(source), client_session_id, typeof(client_session_id), "
        "cwd, typeof(cwd) FROM agent_sessions WHERE id IN (SELECT value FROM json_each(?)) ORDER BY id",
        (allowlist,)).fetchall()
    sessions = {}
    for row in rows:
        session_id = _text(row[0], row[1], "malformed_session")
        if session_id in sessions:
            raise BuildError("ambiguous_session")
        cwd = None if row[7] == b"null" else _text(row[6], row[7], "malformed_session")
        sessions[session_id] = (_text(row[2], row[3], "malformed_session"),
                                _text(row[4], row[5], "malformed_session"), cwd)
    if sorted(sessions) != plan["sessions"]:
        raise BuildError("unknown_session")
    return sessions


def _repo(raw, kind):
    if kind == b"null":
        return None
    if kind != b"text":
        raise BuildError("malformed_metadata")
    if len(raw) > MAX_METADATA_BYTES:
        raise BuildError("metadata_too_large")
    metadata = _checked("malformed_metadata", h._load_json, raw, "metadata")
    if not isinstance(metadata, dict):
        raise BuildError("malformed_metadata")
    repo = metadata.get("repo")
    if repo is not None and not isinstance(repo, str):
        raise BuildError("malformed_repo")
    return repo


def attribution(repo, cwd):
    """metadata.repo when non-blank (String.isBlank), else the snapshot's mutable session cwd."""
    for candidate in (repo, cwd):
        if candidate is not None and not compact_backend.java_blank(candidate):
            return candidate
    return None


def select_items(connection, plan, sessions, snapshot_sha256, deadline):
    counts = {"snapshot_events": connection.execute("SELECT count(*) FROM agent_events").fetchone()[0],
              "allowlisted_session_events": 0, "after_cutoff": 0, "unattributed": 0, "other_project": 0,
              "excluded": 0, "included": 0}
    cursor = connection.execute(
        "SELECT id, typeof(id), session_id, source, typeof(source), client_session_id, typeof(client_session_id), "
        "observed_at, typeof(observed_at), metadata_json, typeof(metadata_json), event_type, typeof(event_type), "
        "text, typeof(text) FROM agent_events WHERE session_id IN (SELECT value FROM json_each(?)) ORDER BY id",
        (json.dumps(plan["sessions"]),))
    items, matched, previous, text_bytes = [], set(), None, 0
    for row in cursor:
        counts["allowlisted_session_events"] += 1
        if counts["allowlisted_session_events"] > MAX_SCANNED_ROWS:
            raise BuildError("too_many_rows")
        if time.monotonic() > deadline:
            raise BuildError("build_timeout")
        event_id = _text(row[0], row[1], "malformed_event")
        if event_id == previous:
            raise BuildError("duplicate_event_id")
        previous = event_id
        session_id = row[2].decode("utf-8")
        source, client, cwd = sessions[session_id]
        if (_text(row[3], row[4], "session_identity_mismatch"), _text(row[5], row[6], "session_identity_mismatch")) \
                != (source, client):
            raise BuildError("session_identity_mismatch")
        observed_at = _text(row[7], row[8], "invalid_observed_at")
        observed_ns = _checked("invalid_observed_at", h.parse_time, observed_at, "observed_at")
        if observed_ns > plan["cutoff_ns"]:
            counts["after_cutoff"] += 1
            continue
        label = attribution(_repo(row[9], row[10]), cwd)
        if label is None:
            counts["unattributed"] += 1
            continue
        if label != plan["project"]:
            counts["other_project"] += 1
            continue
        if event_id in plan["exclusions"]:
            matched.add(event_id)
            counts["excluded"] += 1
            continue
        if not h.ID_RE.match(event_id):
            raise BuildError("invalid_event_id")
        kind = KIND_MAP.get(_text(row[11], row[12], "malformed_event"), "event")
        if row[14] == b"null":
            raise BuildError("null_text_not_excluded")
        if row[14] != b"text":
            raise BuildError("malformed_text")
        if len(row[13]) > h.MAX_TEXT_BYTES:
            raise BuildError("text_too_large")
        text = _text(row[13], row[14], "invalid_utf8_text")
        text_bytes += len(row[13])
        if len(items) >= h.MAX_ITEMS:
            raise BuildError("too_many_items")
        if text_bytes > h.MAX_ITEMS_BYTES:
            raise BuildError("items_too_large")
        items.append((observed_ns, event_id, {
            "id": event_id, "kind": kind, "project": plan["project"], "session": session_id,
            "observed_at": observed_at,
            "source_ref": "blackbox-sqlite:%s:agent_events:%s" % (snapshot_sha256, event_id),
            "text": text}))
    if set(plan["exclusions"]) - matched:
        raise BuildError("exclusion_not_selected")
    items.sort(key=lambda entry: entry[:2])
    counts["included"] = len(items)
    return [entry[2] for entry in items], counts


def _encode(document):
    return (json.dumps(document, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def render(plan, items):
    """Deterministic items and manifest bytes in the existing corpus schema."""
    items_data = _encode({"schema": h.ITEMS_SCHEMA, "items": items})
    if len(items_data) > h.MAX_ITEMS_BYTES:
        raise BuildError("items_too_large")
    manifest = {
        "schema": h.MANIFEST_SCHEMA, "corpus_id": plan["corpus_id"],
        "provenance": "blackbox-sqlite-snapshot sha256:%s; agent_events text only; availability unverified"
                      % plan["snapshot_sha256"],
        "scope": {"project": plan["project"], "sessions": plan["sessions"]}, "cutoff": plan["cutoff"],
        "items_file": ITEMS_FILE, "items_sha256": hashlib.sha256(items_data).hexdigest(),
        "item_count": len(items),
        "exclusions": [{"id": key, "reason": plan["exclusions"][key]} for key in sorted(plan["exclusions"])],
    }
    manifest_data = _encode(manifest)
    if len(manifest_data) > h.MAX_MANIFEST_BYTES:
        raise BuildError("manifest_too_large")
    return items_data, manifest_data


def _write(staging, name, data):
    fd = staging.create_file(name)
    try:
        view = memoryview(data)
        while view:
            view = view[os.write(fd, view):]
        os.fsync(fd)
    finally:
        os.close(fd)


def rollback(parent_fd, name, created, out_fd, linked):
    """Undo a failed publication. None when nothing of ours remains, else the actual output state.

    Only our own identity-checked links and directory are removed. A directory left non-empty
    solely by foreign entries is preserved by design and does not count as a failed rollback.
    """
    remaining = []
    for file_name, staged in linked:
        try:
            current = os.stat(file_name, dir_fd=out_fd, follow_symlinks=False)
            if (current.st_dev, current.st_ino) != (staged.st_dev, staged.st_ino):
                continue  # replaced: foreign, preserved
            os.unlink(file_name, dir_fd=out_fd)
        except FileNotFoundError:
            continue
        except OSError:
            remaining.append(file_name)
    removed, ok = False, not remaining
    try:
        current = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
        if (current.st_dev, current.st_ino) == (created.st_dev, created.st_ino):
            os.rmdir(name, dir_fd=parent_fd)
            removed = True
    except FileNotFoundError:
        removed = True
    except OSError as error:
        if error.errno not in (errno.ENOTEMPTY, errno.EEXIST):
            ok = False
    if ok:
        return None
    return {"published": sorted(remaining) == sorted((ITEMS_FILE, MANIFEST_FILE)),
            "output_removed": removed, "output_entries": sorted(remaining)}


def publish(parent_fd, staging_fd, name, deadline):
    """Create the output exclusively, then link items and the manifest last as the commit marker.

    On failure the publication is rolled back; if the rollback is incomplete, CleanupFailed carries
    the original cause and what remains, so a usable corpus is never reported as rolled back.
    """
    check_deadline(deadline)
    try:
        os.mkdir(name, 0o700, dir_fd=parent_fd)
    except FileExistsError:
        raise BuildError("output_exists") from None
    created = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    linked, out_fd = [], None
    try:
        out_fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent_fd)
        if (os.fstat(out_fd).st_dev, os.fstat(out_fd).st_ino) != (created.st_dev, created.st_ino):
            raise BuildError("output_changed")
        for file_name in (ITEMS_FILE, MANIFEST_FILE):
            # Last chance before the manifest commit marker becomes visible.
            check_deadline(deadline)
            staged = os.stat(file_name, dir_fd=staging_fd, follow_symlinks=False)
            os.link(file_name, file_name, src_dir_fd=staging_fd, dst_dir_fd=out_fd, follow_symlinks=False)
            linked.append((file_name, staged))
        os.fsync(out_fd)
        os.fsync(parent_fd)
    except BaseException as error:
        state = rollback(parent_fd, name, created, out_fd, linked)
        if state is not None:
            raise CleanupFailed(state.pop("published"), None, _code(error), state) from None
        raise
    finally:
        if out_fd is not None:
            os.close(out_fd)


def execute(plan):
    deadline = time.monotonic() + DEADLINE_SECONDS
    output = plan["output"]
    parent_fd = open_dir(output.parent, "unsafe_output_parent")
    try:
        check_output_parent(plan["snapshot"], output, parent_fd)
    except BaseException:
        os.close(parent_fd)
        raise
    staging = Staging(parent_fd)
    published = False
    try:
        try:
            if _exists(parent_fd, output.name):
                raise BuildError("output_exists")
            # The output parent is caller-trusted; staging inside it is private and on the same filesystem.
            staging.create()
            snapshot_bytes = copy_snapshot(plan, staging, deadline)
            check_header(staging.fd)
            connection = None
            try:
                connection = open_copy(output.parent / staging.name / SNAPSHOT_COPY, deadline)
                check_schema(connection)
                sessions = load_sessions(connection, plan)
                items, counts = select_items(connection, plan, sessions, plan["snapshot_sha256"], deadline)
            except sqlite3.Error:
                raise BuildError("build_timeout" if time.monotonic() > deadline else "sqlite_error") from None
            finally:
                if connection is not None:
                    connection.close()
            # The full private snapshot copy must be gone before anything is published.
            try:
                staging.remove(SNAPSHOT_COPY)
            except (OSError, BuildError):
                raise CleanupFailed(False, False) from None
            items_data, manifest_data = render(plan, items)
            check_deadline(deadline)
            _write(staging, ITEMS_FILE, items_data)
            _write(staging, MANIFEST_FILE, manifest_data)
            manifest_sha256 = hashlib.sha256(manifest_data).hexdigest()
            corpus = _checked("corpus_self_check_failed", h.load_corpus,
                              output.parent / staging.name / MANIFEST_FILE, manifest_sha256)
            if len(corpus.items) != len(items):
                raise BuildError("corpus_self_check_failed")
            check_deadline(deadline)
            publish(parent_fd, staging.fd, output.name, deadline)
            published = True
        except BaseException as error:
            removed = staging.cleanup()
            if isinstance(error, CleanupFailed):
                raise CleanupFailed(error.published, removed, error.cause, error.output) from None
            if not removed:
                raise CleanupFailed(published, False, _code(error)) from None
            raise
        # Published links keep their inodes; only the staging names are removed here.
        if not staging.cleanup():
            raise CleanupFailed(True, False)
    finally:
        os.close(parent_fd)
    kinds = {}
    sessions_included = {session: 0 for session in plan["sessions"]}
    for item in items:
        kinds[item["kind"]] = kinds.get(item["kind"], 0) + 1
        sessions_included[item["session"]] += 1
    return {"status": "complete", "corpus_id": plan["corpus_id"], "output": str(output),
            "manifest": str(output / MANIFEST_FILE), "manifest_sha256": manifest_sha256,
            "items_sha256": hashlib.sha256(items_data).hexdigest(), "item_count": len(items),
            "snapshot_sha256": plan["snapshot_sha256"], "snapshot_bytes": snapshot_bytes,
            "project": plan["project"], "cutoff": plan["cutoff"], "counts": counts, "kinds": kinds,
            "sessions_included": sessions_included, "exclusions": len(plan["exclusions"]),
            "availability": "unverified", "recorded_at": "absent", "content": "agent_events.text only",
            "omitted_fields": list(OMITTED_FIELDS)}


def main(argv=None, stdout=None):
    stdout = stdout or sys.stdout
    try:
        args = parse_args(argv)
        plan = make_plan(args)
        result = execute(plan) if args.execute else describe(plan)
        code = 0
    except CleanupFailed as error:
        # Honest state only: whether the corpus is published and whether staging was removed.
        result, code = {"status": "failed", "error": error.code, "published": error.published,
                        "staging_removed": error.staging_removed}, 1
        if error.cause is not None:
            result["cause"] = error.cause
        if error.output is not None:
            result.update(error.output)
    except BuildError as error:
        result, code = {"status": "failed", "error": error.code}, 1
    except KeyboardInterrupt:
        result, code = {"status": "failed", "error": "interrupted"}, 1
    except Exception:
        # Never forward row text, paths or exception detail.
        result, code = {"status": "failed", "error": "build_failed"}, 1
    stdout.write(json.dumps(result, sort_keys=True, ensure_ascii=False) + "\n")
    stdout.flush()
    return code


if __name__ == "__main__":
    sys.exit(main())
