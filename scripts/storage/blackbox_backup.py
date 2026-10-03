#!/usr/bin/env python3
"""Native Black Box database snapshots. No connections or writes without --execute."""

import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time


class BackupError(Exception):
    """Stable public error code; underlying exceptions must never reach stdout/stderr."""


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise BackupError("invalid_arguments")


def parse_args(argv):
    parser = Parser(description=__doc__, allow_abbrev=False)
    modes = parser.add_subparsers(dest="backend", required=True, parser_class=Parser)
    local = modes.add_parser("sqlite", allow_abbrev=False)
    local.add_argument("--source", required=True)
    remote = modes.add_parser("postgres", allow_abbrev=False)
    for name in ("host", "port", "database", "username", "schema"):
        remote.add_argument("--" + name, required=True)
    for mode in (local, remote):
        mode.add_argument("--output", required=True)
        mode.add_argument("--execute", action="store_true")
    return parser.parse_args(argv)


def plain(value):
    if not value or any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise BackupError("invalid_arguments")
    return value


def absolute(value):
    return Path(os.path.abspath(plain(value)))


def make_plan(args):
    output = absolute(args.output)
    if args.backend == "sqlite":
        source = absolute(args.source)
        if source == output:
            raise BackupError("source_is_output")
        scope = {"source": str(source)}
        archive_format = "sqlite3"
    else:
        # libpq interprets a dbname containing '=' or a URI as connection options;
        # allowing one would override the explicitly selected host/user/database.
        database = plain(args.database)
        if "=" in database or database.startswith(("postgres://", "postgresql://")):
            raise BackupError("invalid_database_name")
        host = plain(args.host)
        if not re.fullmatch(r"[A-Za-z0-9_.:\-]+", host):
            raise BackupError("invalid_host")
        if not args.port.isascii() or not args.port.isdecimal():
            raise BackupError("invalid_port")
        port = int(args.port)
        if not 1 <= port <= 65535:
            raise BackupError("invalid_port")
        schema = plain(args.schema)
        if len(schema.encode("utf-8")) > 63:
            raise BackupError("invalid_schema")
        scope = {"host": host, "port": port, "database": database,
                 "username": plain(args.username), "schema": schema}
        archive_format = "postgres-custom"
    return {"status": "planned", "backend": args.backend, "format": archive_format,
            "scope": scope, "output": str(output)}


def open_parent(path):
    """Anchor publication to a directory fd, refusing symlinks in every component."""
    fd = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parent.parts[1:]:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                              dir_fd=fd)
            os.close(fd)
            fd = next_fd
        return fd
    except OSError:
        os.close(fd)
        raise BackupError("unsafe_or_missing_output_parent") from None


def ensure_absent(parent_fd, name):
    try:
        os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    except FileNotFoundError:
        return
    raise BackupError("output_exists")


def snapshot_sqlite(source, staged):
    try:
        info = source.lstat()
    except OSError:
        raise BackupError("invalid_sqlite_source") from None
    if not stat.S_ISREG(info.st_mode) or info.st_size == 0:
        raise BackupError("invalid_sqlite_source")
    # mode=ro sees committed WAL pages. immutable=1 must not be used: it can ignore WAL.
    deadline = time.monotonic() + 300

    def progress(status, remaining, total):
        if time.monotonic() > deadline:
            raise BackupError("sqlite_backup_timeout")

    try:
        with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as reader:
            with closing(sqlite3.connect(staged)) as writer:
                reader.backup(writer, pages=256, progress=progress, sleep=0.05)
                # Only the destination is checkpointed; ship one standalone DB file.
                writer.execute("PRAGMA journal_mode=DELETE")
                # Avoid invoking application-defined CHECK functions. quick_check
                # still checks physical structure and does not load virtual modules.
                writer.execute("PRAGMA ignore_check_constraints=ON")
                if writer.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                    raise BackupError("sqlite_integrity_failed")
    except sqlite3.Error:
        raise BackupError("sqlite_backup_failed") from None


def postgres_environment():
    # Credentials have exactly two explicit input channels. Do not implicitly read
    # ~/.pgpass or let a service/PGOPTIONS/PGHOSTADDR redirect the requested scope.
    allowed = {"PGPASSWORD", "PGPASSFILE", "PGSSLMODE", "PGSSLROOTCERT", "PGSSLCERT",
               "PGSSLKEY", "PGSSLCRL", "PGSSLCRLDIR", "PGCHANNELBINDING", "PGGSSENCMODE",
               "PGSSLMINPROTOCOLVERSION", "PGSSLMAXPROTOCOLVERSION", "PGSSLNEGOTIATION",
               "PGSSLSNI", "PGSSLCERTMODE", "PGREQUIREAUTH"}
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("PG") or key in allowed}
    env.setdefault("PGPASSFILE", os.devnull)
    env["PGCONNECT_TIMEOUT"] = "10"
    env["PGAPPNAME"] = "blackbox-backup"
    return env


def snapshot_postgres(scope, staged):
    dump = shutil.which("pg_dump")
    restore = shutil.which("pg_restore")
    if not dump or not restore:
        raise BackupError("postgres_tools_missing")
    # pg_dump --schema is a psql pattern. Quoting the complete identifier makes
    # metacharacters literal; doubled embedded quotes remain part of the name.
    schema_pattern = '"' + scope["schema"].replace('"', '""') + '"'
    command = [dump, "--format=custom", "--no-password", "--strict-names",
               "--host=" + scope["host"], "--port=" + str(scope["port"]),
               "--dbname=" + scope["database"], "--username=" + scope["username"],
               "--schema=" + schema_pattern]
    env = postgres_environment()
    try:
        with staged.open("wb") as artifact:
            result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=artifact,
                                    stderr=subprocess.DEVNULL, env=env, timeout=300)
        if result.returncode != 0:
            raise BackupError("postgres_dump_failed")
        with staged.open("rb") as artifact:
            if staged.stat().st_size < 64 or artifact.read(5) != b"PGDMP":
                raise BackupError("postgres_archive_invalid")
        # Decode the complete archive without a connection. --list alone can miss
        # truncated data blocks. SQL is discarded; it is never executed or logged.
        result = subprocess.run([restore, "--file=" + os.devnull, "--no-owner",
                                 "--no-privileges", str(staged)],
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, env=env, timeout=300)
        if result.returncode != 0:
            raise BackupError("postgres_archive_invalid")
    except subprocess.TimeoutExpired:
        raise BackupError("postgres_backup_timeout") from None
    except OSError:
        raise BackupError("postgres_tool_failed") from None


def execute(plan):
    output = Path(plan["output"])
    parent_fd = open_parent(output)
    staged_dir = None
    published = False
    staged_info = None
    try:
        ensure_absent(parent_fd, output.name)
        # The caller supplies an existing, trusted output directory. Staging is
        # private even when the caller's umask would otherwise permit access.
        staged_dir = Path(tempfile.mkdtemp(prefix=".blackbox-backup-", dir=output.parent))
        os.chmod(staged_dir, 0o700)
        staged = staged_dir / "artifact"
        fd = os.open(staged, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        os.close(fd)
        if plan["backend"] == "sqlite":
            snapshot_sqlite(Path(plan["scope"]["source"]), staged)
        else:
            snapshot_postgres(plan["scope"], staged)
        digest = hashlib.sha256()
        with staged.open("rb") as artifact:
            for block in iter(lambda: artifact.read(1024 * 1024), b""):
                digest.update(block)
            os.fsync(artifact.fileno())
        staged_info = staged.stat()
        if staged_info.st_size == 0:
            raise BackupError("empty_artifact")
        # Unlike replace()/rename(), link() atomically refuses an existing name,
        # including a dangling symlink created after the initial check.
        try:
            os.link(staged, output.name, dst_dir_fd=parent_fd, follow_symlinks=False)
        except FileExistsError:
            raise BackupError("output_exists") from None
        published = True
        os.fsync(parent_fd)
        return dict(plan, status="complete", bytes=staged_info.st_size,
                    sha256=digest.hexdigest())
    except BaseException:
        if published:
            try:
                current = os.stat(output.name, dir_fd=parent_fd, follow_symlinks=False)
                if (current.st_dev, current.st_ino) == (staged_info.st_dev, staged_info.st_ino):
                    os.unlink(output.name, dir_fd=parent_fd)
            except OSError:
                pass
        raise
    finally:
        if staged_dir is not None:
            shutil.rmtree(staged_dir, ignore_errors=True)
        os.close(parent_fd)


def main(argv=None):
    try:
        args = parse_args(argv)
        plan = make_plan(args)
        result = execute(plan) if args.execute else plan
        code = 0
    except BackupError as error:
        result, code = {"status": "failed", "error": str(error)}, 1
    except KeyboardInterrupt:
        result, code = {"status": "failed", "error": "interrupted"}, 1
    except Exception:
        # Never forward path-bearing errors, connection strings, environment, or
        # child process stderr. Detailed diagnosis belongs in a separate safe run.
        result, code = {"status": "failed", "error": "backup_failed"}, 1
    print(json.dumps(result, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
