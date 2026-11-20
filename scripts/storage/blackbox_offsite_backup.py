#!/usr/bin/env python3
"""Encrypted offsite SQLite backups with a verified restore test.

`backup` snapshots each database with the native backup API (WAL-safe), checks it, encrypts it,
publishes it to a local directory and an offsite directory, then restores the offsite copy into a
scratch directory and compares it with the manifest. `restore-test` repeats that last step for
any published manifest. Nothing is read or written without --execute.
"""

import argparse
import fcntl
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import signal
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

# Importing the sibling module must not leave a __pycache__ beside the scripts.
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

import blackbox_backup  # noqa: E402

BackupError = blackbox_backup.BackupError

FORMAT = "blackbox-offsite-backup/1"
CIPHER = "aes-256-cbc"
KDF = "pbkdf2-hmac-sha256"
KDF_ITERATIONS = 600000
ARCHIVE_SUFFIXES = {"zstd": ".sqlite3.zst.enc", "none": ".sqlite3.enc"}
MANIFEST_SUFFIX = ".manifest.json"
LABEL = re.compile(r"[a-z0-9][a-z0-9-]{0,62}")
STAMP = re.compile(r"\d{8}-\d{6}")
SET_FILE = re.compile(
    r"[a-z0-9][a-z0-9-]{0,62}-\d{8}-\d{6}(?:\.manifest\.json|\.sqlite3(?:\.zst)?\.enc)"
)
STAGING_PREFIX = ".offsite-backup-"
BLOCK = 1024 * 1024
PIPELINE_TIMEOUT_SECONDS = 6 * 60 * 60
# `openssl -pass file:` reads at most this many bytes of the first line.
MAX_PASSPHRASE_BYTES = 1023


class Terminated(BaseException):
    """SIGTERM (launchd bootout, shutdown) as an exception, so every cleanup block still runs."""


def terminate(signum, frame):
    raise Terminated()


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise BackupError("invalid_arguments")


def parse_args(argv):
    parser = Parser(description=__doc__, allow_abbrev=False)
    modes = parser.add_subparsers(dest="command", required=True, parser_class=Parser)

    backup = modes.add_parser("backup", allow_abbrev=False)
    backup.add_argument("--database", action="append", required=True, metavar="LABEL=PATH")
    backup.add_argument("--local-dir", required=True)
    backup.add_argument("--offsite-dir", required=True)
    backup.add_argument("--passphrase-file", required=True)
    backup.add_argument("--keep-local", type=int, default=1)
    backup.add_argument("--keep-offsite", type=int, default=3)
    backup.add_argument("--compression", choices=("auto", "zstd", "none"), default="auto")
    backup.add_argument("--skip-restore-test", action="store_true")
    backup.add_argument("--stamp")
    backup.add_argument("--execute", action="store_true")

    restore = modes.add_parser("restore-test", allow_abbrev=False)
    restore.add_argument("--manifest", required=True)
    restore.add_argument("--passphrase-file", required=True)
    restore.add_argument("--scratch-dir", required=True)
    restore.add_argument("--keep-restored", action="store_true")
    restore.add_argument("--execute", action="store_true")

    return parser.parse_args(argv)


def absolute(value):
    return blackbox_backup.absolute(value)


def parse_databases(values):
    databases = []
    seen = set()

    for value in values:
        label, separator, path = value.partition("=")
        if not separator or not LABEL.fullmatch(label) or label in seen:
            raise BackupError("invalid_database_argument")
        seen.add(label)
        databases.append((label, absolute(path)))

    return databases


def resolve_compression(choice):
    if choice == "none":
        return "none"

    available = shutil.which("zstd") is not None
    if choice == "zstd" and not available:
        raise BackupError("zstd_missing")

    return "zstd" if available else "none"


def read_passphrase(path):
    """First line of a private file, the same bytes `openssl -pass file:` uses.

    openssl cuts the line at its newline only, stops at a NUL and reads at most 1023 bytes. A
    passphrase where that differs from the whole line (a CRLF file) is refused, so the manifest
    key and the archive key always come from the same bytes.
    """
    try:
        info = os.stat(path)
    except OSError:
        raise BackupError("passphrase_file_missing") from None

    if not stat.S_ISREG(info.st_mode):
        raise BackupError("passphrase_file_missing")
    # A passphrase other accounts can read protects nothing.
    if info.st_mode & 0o077:
        raise BackupError("passphrase_file_permissions")

    try:
        with open(path, "rb") as handle:
            line = handle.readline()
    except OSError:
        raise BackupError("passphrase_file_missing") from None

    passphrase = line[:-1] if line.endswith(b"\n") else line
    if not passphrase:
        raise BackupError("passphrase_empty")
    if b"\r" in passphrase or b"\0" in passphrase or len(passphrase) > MAX_PASSPHRASE_BYTES:
        raise BackupError("passphrase_invalid")

    return passphrase


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(BLOCK), b""):
            digest.update(block)

    return digest.hexdigest()


def quote_identifier(name):
    return '"' + name.replace('"', '""') + '"'


def inspect_database(path):
    """Integrity, schema fingerprint and per-table row counts of a standalone database file."""
    try:
        with closing(sqlite3.connect(Path(path).as_uri() + "?mode=ro", uri=True)) as db:
            # CHECK constraints may call application functions this process does not have.
            db.execute("PRAGMA ignore_check_constraints=ON")
            if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                raise BackupError("sqlite_integrity_failed")

            schema = db.execute(
                "SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY type, name"
            ).fetchall()

            tables = {}
            for kind, name, _, sql in schema:
                # A virtual table needs its module loaded; its shadow tables are ordinary
                # tables and are counted like any other.
                virtual = (sql or "").lstrip().upper().startswith("CREATE VIRTUAL TABLE")
                if kind != "table" or virtual:
                    continue
                count = db.execute("SELECT COUNT(*) FROM " + quote_identifier(name)).fetchone()
                tables[name] = count[0]
    except sqlite3.Error:
        raise BackupError("sqlite_integrity_failed") from None

    canonical = json.dumps(schema, sort_keys=True, separators=(",", ":")).encode("utf-8")

    return {
        "integrity_check": "ok",
        "schema_sha256": hashlib.sha256(canonical).hexdigest(),
        "tables": tables,
        "total_rows": sum(tables.values()),
    }


def verify_restored(path, expected):
    """Compare a restored database with the `database` section of its manifest."""
    if os.path.getsize(path) != expected["bytes"] or sha256_file(path) != expected["sha256"]:
        raise BackupError("restored_checksum_mismatch")

    actual = inspect_database(path)
    if actual["schema_sha256"] != expected["schema_sha256"]:
        raise BackupError("schema_mismatch")
    if actual["tables"] != expected["tables"]:
        raise BackupError("row_count_mismatch")

    return actual


def openssl_command(decrypt, passphrase_file):
    command = ["openssl", "enc"]
    if decrypt:
        command.append("-d")
    command += ["-" + CIPHER, "-pbkdf2", "-iter", str(KDF_ITERATIONS), "-md", "sha256"]
    if not decrypt:
        command.append("-salt")
    command += ["-pass", "file:" + str(passphrase_file)]

    return command


def run_pipeline(commands, source, destination):
    """Run `source | commands... > destination`; every stage must exit 0."""
    processes = []
    try:
        with open(source, "rb") as stdin, open(destination, "wb") as stdout:
            upstream = stdin
            for index, command in enumerate(commands):
                last = index == len(commands) - 1
                process = subprocess.Popen(
                    command,
                    stdin=upstream,
                    stdout=stdout if last else subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                )
                if upstream is not stdin:
                    # The child owns the read end now; closing ours lets SIGPIPE propagate.
                    upstream.close()
                upstream = process.stdout
                processes.append(process)

            deadline = time.monotonic() + PIPELINE_TIMEOUT_SECONDS
            codes = [p.wait(timeout=max(1, deadline - time.monotonic())) for p in processes]
            stdout.flush()
            os.fsync(stdout.fileno())
    except (OSError, subprocess.TimeoutExpired):
        stop_processes(processes)
        return False
    except BaseException:
        # Interrupted: no child may keep writing into a directory that is about to be removed.
        stop_processes(processes)
        raise

    return all(code == 0 for code in codes)


def stop_processes(processes):
    for process in processes:
        process.kill()
    for process in processes:
        process.wait()


def encrypt(snapshot, archive, compression, passphrase_file):
    commands = [openssl_command(False, passphrase_file)]
    if compression == "zstd":
        commands.insert(0, ["zstd", "-q", "-T0", "-6", "-c"])

    if not run_pipeline(commands, snapshot, archive):
        raise BackupError("encrypt_failed")


def decrypt(archive, restored, compression, passphrase_file):
    commands = [openssl_command(True, passphrase_file)]
    if compression == "zstd":
        commands.append(["zstd", "-q", "-d", "-c"])

    if not run_pipeline(commands, archive, restored):
        raise BackupError("decrypt_failed")


def canonical_bytes(body):
    return json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")


def manifest_mac(body, passphrase, salt):
    key = hashlib.pbkdf2_hmac("sha256", passphrase, salt, KDF_ITERATIONS, 32)

    return hmac.new(key, canonical_bytes(body), hashlib.sha256).hexdigest()


def seal_manifest(body, passphrase):
    """The checksums only prove anything if the manifest itself cannot be rewritten."""
    salt = secrets.token_bytes(16)

    return {
        "manifest": body,
        "authentication": {
            "algorithm": "hmac-sha256",
            "kdf": KDF,
            "iterations": KDF_ITERATIONS,
            "salt": salt.hex(),
            "mac": manifest_mac(body, passphrase, salt),
        },
    }


def open_manifest(path, passphrase):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            envelope = json.load(handle)
        body = envelope["manifest"]
        auth = envelope["authentication"]
        salt = bytes.fromhex(auth["salt"])
        mac = auth["mac"]
        # Fixed parameters: an edited manifest must not choose its own work factor.
        known = (auth["algorithm"], auth["kdf"], auth["iterations"]) == (
            "hmac-sha256",
            KDF,
            KDF_ITERATIONS,
        )
    except (OSError, ValueError, KeyError, TypeError):
        raise BackupError("manifest_invalid") from None

    if not known or not isinstance(mac, str):
        raise BackupError("manifest_invalid")
    if not hmac.compare_digest(manifest_mac(body, passphrase, salt), mac):
        raise BackupError("manifest_authentication_failed")

    if not supported_manifest(body, Path(path).name):
        raise BackupError("manifest_unsupported")

    return body


def supported_manifest(body, file_name):
    """The format this tool writes, with names that tie the manifest to one label and stamp.

    The label becomes a file name in the scratch directory, and a manifest renamed to another
    stamp would let an older set pass for a newer one, so both are checked, not trusted.
    """
    if not isinstance(body, dict) or not isinstance(body.get("archive"), dict):
        return False

    archive = body["archive"]
    label = body.get("label")
    stamp = body.get("stamp")
    compression = archive.get("compression")
    named = (
        isinstance(label, str)
        and isinstance(stamp, str)
        and isinstance(compression, str)
        and LABEL.fullmatch(label) is not None
        and STAMP.fullmatch(stamp) is not None
        and compression in ARCHIVE_SUFFIXES
    )
    if not named:
        return False

    base = label + "-" + stamp

    return (
        body.get("format") == FORMAT
        and archive.get("cipher") == CIPHER
        and archive.get("iterations") == KDF_ITERATIONS
        and archive.get("name") == base + ARCHIVE_SUFFIXES[compression]
        and file_name == base + MANIFEST_SUFFIX
    )


def restore_test(manifest_path, passphrase_file, scratch_dir, keep_restored=False):
    """Decrypt the archive beside a manifest into a new scratch directory and verify it."""
    passphrase = read_passphrase(passphrase_file)
    body = open_manifest(manifest_path, passphrase)
    archive_info = body["archive"]
    archive = Path(manifest_path).parent / archive_info["name"]

    if not archive.is_file():
        raise BackupError("archive_missing")
    if archive.stat().st_size != archive_info["bytes"]:
        raise BackupError("archive_checksum_mismatch")
    if sha256_file(archive) != archive_info["sha256"]:
        raise BackupError("archive_checksum_mismatch")
    if archive_info["compression"] == "zstd" and shutil.which("zstd") is None:
        raise BackupError("zstd_missing")

    scratch = Path(scratch_dir)
    try:
        # Never restore beside existing files: an old -wal or -journal would be replayed.
        os.mkdir(scratch, 0o700)
    except FileExistsError:
        raise BackupError("scratch_dir_exists") from None
    except OSError:
        raise BackupError("scratch_dir_unavailable") from None

    restored = scratch / (body["label"] + ".sqlite3")
    try:
        decrypt(archive, restored, archive_info["compression"], passphrase_file)
        actual = verify_restored(restored, body["database"])
    except BaseException:
        shutil.rmtree(scratch, ignore_errors=True)
        raise

    if not keep_restored:
        shutil.rmtree(scratch, ignore_errors=True)

    return {
        "status": "restore_verified",
        "label": body["label"],
        "stamp": body["stamp"],
        "sha256": body["database"]["sha256"],
        "integrity_check": actual["integrity_check"],
        "tables": len(actual["tables"]),
        "total_rows": actual["total_rows"],
        "restored": str(restored) if keep_restored else None,
    }


def publish(source, directory, name):
    """Copy under a hidden partial name, flush, rename, then re-read what actually landed."""
    partial = directory / ("." + name + ".partial")
    final = directory / name
    try:
        # A partial left by a killed run, or a symlink planted there, is removed, never written
        # through.
        remove_file(partial)
        copy_durably(source, partial)
        os.replace(partial, final)
        sync_directory(directory)
    except OSError:
        try:
            remove_file(partial)
        except OSError:
            pass
        raise BackupError("publish_failed") from None

    return final


def remove_file(path):
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass


def copy_durably(source, destination):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    with (
        open(source, "rb") as reader,
        os.fdopen(os.open(destination, flags, 0o600), "wb") as writer,
    ):
        shutil.copyfileobj(reader, writer, BLOCK)
        writer.flush()
        flush_to_disk(writer.fileno())


def flush_to_disk(fd):
    """fsync, as F_FULLFSYNC on macOS: a plain fsync there can stop in the drive's cache."""
    full_sync = getattr(fcntl, "F_FULLFSYNC", None)
    if full_sync is not None:
        try:
            fcntl.fcntl(fd, full_sync)
            return
        except OSError:
            pass
    os.fsync(fd)


def sync_directory(directory):
    """Make a rename durable. Some file systems refuse fsync on a directory; that is not fatal."""
    try:
        fd = os.open(directory, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def withdraw(paths):
    """Remove a set that failed its checks, so it neither counts as a kept set nor looks healthy."""
    for path in paths:
        try:
            remove_file(path)
        except OSError:
            pass


def prune(directory, label, keep, current):
    """Keep `current` and the newest complete sets for one label, `keep` sets in all.

    A set is complete when its archive and its manifest are both present. An incomplete set is
    what a killed run left behind: it is removed, and never counted against `keep`. Only this
    tool's own names are removed.
    """
    pattern = re.compile(
        re.escape(label) + r"-(\d{8}-\d{6})(?:(\.manifest\.json)|\.sqlite3(?:\.zst)?\.enc)"
    )
    stamps = {}

    for entry in directory.iterdir():
        match = pattern.fullmatch(entry.name)
        if match and entry.is_file() and not entry.is_symlink():
            kind = "manifest" if match.group(2) else "archive"
            stamps.setdefault(match.group(1), []).append((kind, entry))

    complete = sorted(
        (
            stamp
            for stamp, entries in stamps.items()
            if {kind for kind, _ in entries} == {"manifest", "archive"}
        ),
        reverse=True,
    )
    # The set just written and verified is never pruned, even when a clock change makes an older
    # stamp sort after it.
    kept = {current}
    for stamp in complete:
        if len(kept) >= max(keep, 1):
            break
        kept.add(stamp)

    removed = []
    for stamp, entries in stamps.items():
        if stamp in kept:
            continue
        for _, entry in entries:
            entry.unlink()
            removed.append(entry.name)

    return sorted(removed)


def backup_one(label, source, options, passphrase):
    local_dir = options["local_dir"]
    offsite_dir = options["offsite_dir"]
    compression = options["compression"]
    base = label + "-" + options["stamp"]
    archive_name = base + ARCHIVE_SUFFIXES[compression]
    manifest_name = base + MANIFEST_SUFFIX

    for directory in (local_dir, offsite_dir):
        if (directory / archive_name).exists() or (directory / manifest_name).exists():
            raise BackupError("output_exists")

    staging = Path(tempfile.mkdtemp(prefix=STAGING_PREFIX, dir=local_dir))
    try:
        os.chmod(staging, 0o700)
        snapshot = staging / "snapshot.sqlite3"
        plan = blackbox_backup.make_plan(
            argparse.Namespace(backend="sqlite", source=str(source), output=str(snapshot))
        )
        snapshot_result = blackbox_backup.execute(plan)

        database = inspect_database(snapshot)
        database["bytes"] = snapshot_result["bytes"]
        database["sha256"] = snapshot_result["sha256"]

        staged_archive = staging / archive_name
        encrypt(snapshot, staged_archive, compression, options["passphrase_file"])
        snapshot.unlink()

        archive_sha256 = sha256_file(staged_archive)
        body = {
            "format": FORMAT,
            "label": label,
            "stamp": options["stamp"],
            "created_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "archive": {
                "name": archive_name,
                "bytes": staged_archive.stat().st_size,
                "sha256": archive_sha256,
                "cipher": CIPHER,
                "kdf": KDF,
                "iterations": KDF_ITERATIONS,
                "compression": compression,
            },
            "database": database,
        }
        staged_manifest = staging / manifest_name
        with open(staged_manifest, "w", encoding="utf-8") as handle:
            json.dump(seal_manifest(body, passphrase), handle, sort_keys=True, indent=2)
            handle.write("\n")

        published = []
        try:
            # The archive lands before its manifest, so a manifest never names a missing archive.
            for directory in (local_dir, offsite_dir):
                archive_copy = publish(staged_archive, directory, archive_name)
                published.append(archive_copy)
                if sha256_file(archive_copy) != archive_sha256:
                    raise BackupError("publish_checksum_mismatch")
                published.append(publish(staged_manifest, directory, manifest_name))

            if not options["skip_restore_test"]:
                # Prove the copy that left the machine, not the one still on local disk.
                restored = restore_test(
                    offsite_dir / manifest_name,
                    options["passphrase_file"],
                    staging / "restore-test",
                )
                if (
                    restored["sha256"] != database["sha256"]
                    or restored["stamp"] != options["stamp"]
                ):
                    raise BackupError("restore_test_mismatch")
        except BaseException:
            withdraw(published)
            raise

        result = {
            "label": label,
            "archive": archive_name,
            "manifest": manifest_name,
            "archive_bytes": body["archive"]["bytes"],
            "archive_sha256": archive_sha256,
            "database_bytes": database["bytes"],
            "database_sha256": database["sha256"],
            "integrity_check": database["integrity_check"],
            "tables": len(database["tables"]),
            "total_rows": database["total_rows"],
            "compression": compression,
            "restore_test": "skipped" if options["skip_restore_test"] else "verified",
        }

        result["pruned_local"] = prune(local_dir, label, options["keep_local"], options["stamp"])
        result["pruned_offsite"] = prune(
            offsite_dir, label, options["keep_offsite"], options["stamp"]
        )

        return result
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def backup_plan(args):
    # UTC: a local stamp jumps back at a DST change or a time-zone move, and prune and the
    # diagnostics endpoint both read the newest set from the stamp.
    stamp = args.stamp or datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    if not STAMP.fullmatch(stamp) or args.keep_local < 1 or args.keep_offsite < 1:
        raise BackupError("invalid_arguments")

    return {
        "databases": parse_databases(args.database),
        "local_dir": absolute(args.local_dir),
        "offsite_dir": absolute(args.offsite_dir),
        "passphrase_file": absolute(args.passphrase_file),
        "keep_local": args.keep_local,
        "keep_offsite": args.keep_offsite,
        "compression": args.compression,
        "skip_restore_test": args.skip_restore_test,
        "stamp": stamp,
    }


def run_backup(args):
    options = backup_plan(args)
    summary = {
        "status": "planned",
        "stamp": options["stamp"],
        "databases": [
            {"label": label, "source": str(path)} for label, path in options["databases"]
        ],
        "local_dir": str(options["local_dir"]),
        "offsite_dir": str(options["offsite_dir"]),
    }
    if not args.execute:
        return summary

    if shutil.which("openssl") is None:
        raise BackupError("openssl_missing")
    options["compression"] = resolve_compression(args.compression)
    passphrase = read_passphrase(options["passphrase_file"])
    for directory, code in (
        (options["local_dir"], "local_dir_missing"),
        (options["offsite_dir"], "offsite_dir_missing"),
    ):
        # A missing offsite directory usually means an unmounted drive; never create it.
        if not directory.is_dir():
            raise BackupError(code)

    local = options["local_dir"].resolve()
    offsite = options["offsite_dir"].resolve()
    # Staging holds plaintext in the local directory: inside the offsite one it would be uploaded,
    # and one shared directory would get the smaller of the two retention counts.
    if local == offsite or offsite in local.parents:
        raise BackupError("local_dir_inside_offsite_dir")

    lock = lock_directory(options["local_dir"])
    try:
        sweep_leftovers(options["local_dir"], options["offsite_dir"])
        results, failures = back_up_each(options, passphrase)
    finally:
        os.close(lock)

    if failures:
        failure = BackupError(failures[0]["error"])
        failure.label = failures[0]["database"]
        failure.completed = [result["label"] for result in results]
        failure.failures = failures
        raise failure

    return dict(summary, status="complete", databases=results)


def back_up_each(options, passphrase):
    """Every database is attempted: one that fails must not leave the others without a backup."""
    results = []
    failures = []
    for label, source in options["databases"]:
        try:
            results.append(backup_one(label, source, options, passphrase))
        except BackupError as error:
            failures.append({"database": label, "error": str(error)})

    return results, failures


def lock_directory(directory):
    """One run per local directory: a second one would sweep or prune the first one's files."""
    fd = os.open(directory, os.O_RDONLY)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(fd)
        raise BackupError("backup_in_progress") from None

    return fd


def sweep_leftovers(local_dir, offsite_dir):
    """Remove what a killed run left: plaintext staging directories and partial copies.

    Runs under the directory lock, so nothing removed here belongs to a run still in progress.
    """
    for entry in local_dir.iterdir():
        if entry.name.startswith(STAGING_PREFIX) and entry.is_dir() and not entry.is_symlink():
            shutil.rmtree(entry, ignore_errors=True)

    for directory in (local_dir, offsite_dir):
        for entry in directory.iterdir():
            name = entry.name
            partial = name.startswith(".") and name.endswith(".partial")
            if partial and SET_FILE.fullmatch(name[1 : -len(".partial")]) and not entry.is_dir():
                remove_file(entry)


def run_restore_test(args):
    manifest = absolute(args.manifest)
    scratch = absolute(args.scratch_dir)
    passphrase_file = absolute(args.passphrase_file)
    if not args.execute:
        return {"status": "planned", "manifest": str(manifest), "scratch_dir": str(scratch)}

    if shutil.which("openssl") is None:
        raise BackupError("openssl_missing")

    return restore_test(manifest, passphrase_file, scratch, args.keep_restored)


def main(argv=None):
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        args = parse_args(argv)
        result = run_backup(args) if args.command == "backup" else run_restore_test(args)
        code = 0
    except BackupError as error:
        result, code = {"status": "failed", "error": str(error)}, 1
        if getattr(error, "label", None):
            result["database"] = error.label
            result["completed"] = error.completed
            result["failures"] = error.failures
    except (KeyboardInterrupt, Terminated):
        result, code = {"status": "failed", "error": "interrupted"}, 1
    except Exception:
        # Same contract as blackbox_backup.py: no paths, environment or child stderr.
        result, code = {"status": "failed", "error": "backup_failed"}, 1
    finally:
        signal.signal(signal.SIGTERM, previous)

    print(json.dumps(result, sort_keys=True))

    return code


if __name__ == "__main__":
    sys.exit(main())
