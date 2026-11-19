#!/usr/bin/env python3
"""Encrypted offsite SQLite backups with a verified restore test.

`backup` snapshots each database with the native backup API (WAL-safe), checks it, encrypts it,
publishes it to a local directory and an offsite directory, then restores the offsite copy into a
scratch directory and compares it with the manifest. `restore-test` repeats that last step for
any published manifest. Nothing is read or written without --execute.
"""

import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
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
BLOCK = 1024 * 1024
PIPELINE_TIMEOUT_SECONDS = 6 * 60 * 60


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
    """First line of a private file, the same bytes `openssl -pass file:` uses."""
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
            passphrase = handle.readline().rstrip(b"\r\n")
    except OSError:
        raise BackupError("passphrase_file_missing") from None

    if not passphrase:
        raise BackupError("passphrase_empty")

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
        for process in processes:
            process.kill()
        return False

    return all(code == 0 for code in codes)


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

    archive = body.get("archive", {})
    supported = (
        body.get("format") == FORMAT
        and archive.get("cipher") == CIPHER
        and archive.get("iterations") == KDF_ITERATIONS
        and archive.get("compression") in ARCHIVE_SUFFIXES
        and Path(str(archive.get("name"))).name == archive.get("name")
    )
    if not supported:
        raise BackupError("manifest_unsupported")

    return body


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
    """Copy under a hidden partial name, rename, then re-read what actually landed."""
    partial = directory / ("." + name + ".partial")
    final = directory / name
    try:
        shutil.copyfile(source, partial)
        os.replace(partial, final)
    except OSError:
        try:
            partial.unlink()
        except OSError:
            pass
        raise BackupError("publish_failed") from None

    return final


def prune(directory, label, keep):
    """Keep the newest `keep` sets for one label. Only this tool's own names are removed."""
    pattern = re.compile(
        re.escape(label) + r"-(\d{8}-\d{6})(\.manifest\.json|\.sqlite3(\.zst)?\.enc)"
    )
    stamps = {}

    for entry in directory.iterdir():
        match = pattern.fullmatch(entry.name)
        if match and entry.is_file() and not entry.is_symlink():
            stamps.setdefault(match.group(1), []).append(entry)

    removed = []
    for stamp in sorted(stamps, reverse=True)[max(keep, 1) :]:
        for entry in stamps[stamp]:
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

    staging = Path(tempfile.mkdtemp(prefix=".offsite-backup-", dir=local_dir))
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

        # The archive lands before its manifest, so a manifest never names a missing archive.
        for directory in (local_dir, offsite_dir):
            published = publish(staged_archive, directory, archive_name)
            if sha256_file(published) != archive_sha256:
                raise BackupError("publish_checksum_mismatch")
            publish(staged_manifest, directory, manifest_name)

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
            "restore_test": "skipped",
        }

        if not options["skip_restore_test"]:
            # Prove the copy that left the machine, not the one still on local disk.
            restore_test(
                offsite_dir / manifest_name,
                options["passphrase_file"],
                staging / "restore-test",
            )
            result["restore_test"] = "verified"

        result["pruned_local"] = prune(local_dir, label, options["keep_local"])
        result["pruned_offsite"] = prune(offsite_dir, label, options["keep_offsite"])

        return result
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def backup_plan(args):
    stamp = args.stamp or datetime.now().strftime("%Y%m%d-%H%M%S")
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

    results = []
    for label, source in options["databases"]:
        try:
            results.append(backup_one(label, source, options, passphrase))
        except BackupError as error:
            failure = BackupError(str(error))
            failure.label = label
            failure.completed = [result["label"] for result in results]
            raise failure from None

    return dict(summary, status="complete", databases=results)


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
    try:
        args = parse_args(argv)
        result = run_backup(args) if args.command == "backup" else run_restore_test(args)
        code = 0
    except BackupError as error:
        result, code = {"status": "failed", "error": str(error)}, 1
        if getattr(error, "label", None):
            result["database"] = error.label
            result["completed"] = error.completed
    except KeyboardInterrupt:
        result, code = {"status": "failed", "error": "interrupted"}, 1
    except Exception:
        # Same contract as blackbox_backup.py: no paths, environment or child stderr.
        result, code = {"status": "failed", "error": "backup_failed"}, 1

    print(json.dumps(result, sort_keys=True))

    return code


if __name__ == "__main__":
    sys.exit(main())
