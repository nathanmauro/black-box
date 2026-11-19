#!/usr/bin/env python3
"""Real CLI runs against disposable WAL databases; the offsite target is a temp directory."""

import contextlib
import fcntl
import hashlib
import importlib.util
import io
import json
import os
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

# Loading the scripts must not leave a __pycache__ beside them.
sys.dont_write_bytecode = True

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "blackbox_offsite_backup.py"
NIGHTLY = HERE / "nightly-drive-backup.sh"
SPEC = importlib.util.spec_from_file_location("blackbox_offsite_backup", SCRIPT)
OFFSITE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OFFSITE)

MARKER = "plaintext-marker-7f3a9c"
STAMP = "20260102-030405"


@unittest.skipUnless(shutil.which("openssl"), "openssl is required")
class OffsiteBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

        self.local = self.root / "local"
        self.offsite = self.root / "offsite"
        self.local.mkdir()
        self.offsite.mkdir()

        self.passphrase = self.root / "passphrase"
        self.passphrase.write_text("fixture-passphrase-not-a-real-secret\n")
        self.passphrase.chmod(0o600)

        self.blackbox = self.root / "sba-agentic.db"
        self.jobs = self.root / "jobs.sqlite3"

    def cli(self, *args, success=True):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), *map(str, args)],
            capture_output=True,
            text=True,
            timeout=120,
        )
        self.assertEqual(result.stderr, "")
        self.assertEqual(result.returncode == 0, success, result.stdout)
        data = json.loads(result.stdout)
        if not success:
            self.assertEqual(data["status"], "failed")
            self.assertNotIn("fixture-passphrase", result.stdout)

        return data

    def backup(self, *extra, databases=None, success=True, stamp=STAMP, execute=True, local=None):
        args = self.backup_args(databases=databases, stamp=stamp, execute=execute, local=local)

        return self.cli(*args, *extra, success=success)

    def backup_args(self, *extra, databases=None, stamp=STAMP, execute=True, local=None):
        databases = databases or {"blackbox": self.blackbox}
        args = ["backup", "--local-dir", local or self.local, "--offsite-dir", self.offsite]
        args += ["--passphrase-file", self.passphrase, "--stamp", stamp]
        for label, path in databases.items():
            args += ["--database", "{}={}".format(label, path)]

        if execute:
            args.append("--execute")

        return [str(arg) for arg in args + list(extra)]

    def run_in_process(self, args):
        """main() in this process, so a test can replace one step of a real run."""
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = OFFSITE.main(args)

        return code, json.loads(output.getvalue())

    def stamps(self, directory, label="blackbox"):
        """Stamps of the complete sets (archive and manifest) for one label."""
        manifests = {
            path.name[len(label) + 1 : -len(OFFSITE.MANIFEST_SUFFIX)]
            for path in directory.glob(label + "-*" + OFFSITE.MANIFEST_SUFFIX)
        }
        archives = {
            path.name[len(label) + 1 :].split(".", 1)[0]
            for path in directory.glob(label + "-*.enc")
        }

        return sorted(manifests & archives)

    def restore(self, manifest, *extra, success=True, scratch=None):
        scratch = scratch or self.root / "scratch"

        return self.cli(
            "restore-test",
            "--manifest",
            manifest,
            "--passphrase-file",
            self.passphrase,
            "--scratch-dir",
            scratch,
            *extra,
            success=success,
        )

    def seed_wal(self, path, rows=50):
        """A WAL database whose rows live only in the WAL while the connection stays open."""
        db = sqlite3.connect(path)
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA wal_autocheckpoint=0")
        db.executescript("""
            CREATE TABLE agent_events(id INTEGER PRIMARY KEY, payload TEXT);
            CREATE TABLE event_audit(id INTEGER PRIMARY KEY, event_id INTEGER);
            CREATE INDEX event_audit_event ON event_audit(event_id);
            CREATE VIRTUAL TABLE event_fts USING fts5(payload);
        """)
        for index in range(rows):
            self.insert_pair(db, index)
        db.commit()
        self.addCleanup(db.close)

        return db

    def insert_pair(self, db, index):
        payload = "{} row {} {}".format(MARKER, index, "x" * 200)
        cursor = db.execute("INSERT INTO agent_events(payload) VALUES (?)", (payload,))
        db.execute("INSERT INTO event_audit(event_id) VALUES (?)", (cursor.lastrowid,))
        db.execute("INSERT INTO event_fts(payload) VALUES (?)", (payload,))

    def manifest(self, label="blackbox", directory=None, stamp=STAMP):
        return (directory or self.offsite) / "{}-{}.manifest.json".format(label, stamp)

    def manifest_body(self, **kwargs):
        return json.loads(self.manifest(**kwargs).read_text())["manifest"]

    def counts(self, path):
        with closing(sqlite3.connect(path)) as db:
            events = db.execute("SELECT COUNT(*) FROM agent_events").fetchone()[0]
            audits = db.execute("SELECT COUNT(*) FROM event_audit").fetchone()[0]
            orphans = db.execute(
                "SELECT COUNT(*) FROM event_audit WHERE event_id NOT IN (SELECT id FROM agent_events)"
            ).fetchone()[0]

        return events, audits, orphans

    def test_plan_reads_and_writes_nothing(self):
        self.passphrase.unlink()
        shutil.rmtree(self.offsite)

        data = self.backup(execute=False)

        self.assertEqual(data["status"], "planned")
        self.assertEqual(data["databases"], [{"label": "blackbox", "source": str(self.blackbox)}])
        self.assertEqual(list(self.local.iterdir()), [])
        self.assertFalse(self.offsite.exists())
        self.assertFalse(self.blackbox.exists())

    def test_wal_only_rows_survive_an_encrypted_round_trip(self):
        self.seed_wal(self.blackbox, rows=50)
        # Everything committed so far is still in the WAL, not the main file.
        self.assertGreater(Path(str(self.blackbox) + "-wal").stat().st_size, 0)
        self.assertNotIn(MARKER.encode(), self.blackbox.read_bytes())

        data = self.backup()

        self.assertEqual(data["status"], "complete")
        result = data["databases"][0]
        self.assertEqual(result["restore_test"], "verified")
        self.assertEqual(result["integrity_check"], "ok")

        body = self.manifest_body()
        self.assertEqual(body["database"]["tables"]["agent_events"], 50)
        self.assertEqual(body["database"]["tables"]["event_audit"], 50)
        self.assertNotIn("event_fts", body["database"]["tables"])
        self.assertIn("event_fts_content", body["database"]["tables"])

        for directory in (self.local, self.offsite):
            archive = directory / body["archive"]["name"]
            raw = archive.read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), body["archive"]["sha256"])
            self.assertEqual(len(raw), body["archive"]["bytes"])
            # Encrypted: no SQLite header, no zstd frame, no row text.
            self.assertNotIn(b"SQLite format 3", raw)
            self.assertNotIn(b"\x28\xb5\x2f\xfd", raw[:16])
            self.assertNotIn(MARKER.encode(), raw)
            self.assertTrue(raw.startswith(b"Salted__"))

        # No machine path leaves with the manifest, and no staging is left behind.
        self.assertNotIn(str(self.root), self.manifest().read_text())
        self.assertEqual(list(self.local.glob(".*")), [])
        self.assertEqual(list(self.offsite.glob(".*")), [])

    def test_restore_test_recovers_the_exact_snapshot(self):
        self.seed_wal(self.blackbox, rows=30)
        self.backup("--skip-restore-test")
        body = self.manifest_body()

        data = self.restore(self.manifest(), "--execute", "--keep-restored")

        self.assertEqual(data["status"], "restore_verified")
        self.assertEqual(data["total_rows"], body["database"]["total_rows"])
        restored = Path(data["restored"])
        self.assertEqual(
            hashlib.sha256(restored.read_bytes()).hexdigest(), body["database"]["sha256"]
        )
        self.assertEqual(self.counts(restored), (30, 30, 0))
        with closing(sqlite3.connect(restored)) as db:
            hits = db.execute("SELECT COUNT(*) FROM event_fts WHERE event_fts MATCH 'row'")
            self.assertEqual(hits.fetchone()[0], 30)
            self.assertEqual(db.execute("PRAGMA journal_mode").fetchone()[0], "delete")

    def test_restore_test_plan_and_default_cleanup(self):
        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test")
        scratch = self.root / "scratch"

        self.assertEqual(self.restore(self.manifest())["status"], "planned")
        self.assertFalse(scratch.exists())

        data = self.restore(self.manifest(), "--execute")
        self.assertIsNone(data["restored"])
        self.assertFalse(scratch.exists())

    def test_snapshot_is_consistent_while_writers_commit(self):
        db = self.seed_wal(self.blackbox, rows=200)
        db.close()
        stop = threading.Event()
        written = []

        def writer():
            with closing(sqlite3.connect(self.blackbox, timeout=30)) as live:
                index = 1000
                while not stop.is_set():
                    # One transaction per pair: a torn copy would show unequal counts.
                    self.insert_pair(live, index)
                    live.commit()
                    index += 1
                written.append(index - 1000)

        threads = [threading.Thread(target=writer) for _ in range(2)]
        for thread in threads:
            thread.start()
        try:
            time.sleep(0.2)
            first = self.backup("--skip-restore-test", stamp="20260102-030405")
            second = self.backup("--skip-restore-test", stamp="20260102-030406")
        finally:
            stop.set()
            for thread in threads:
                thread.join()

        self.assertEqual(first["status"], "complete")
        self.assertEqual(second["status"], "complete")
        self.assertGreater(sum(written), 0)

        previous = 200
        for stamp in ("20260102-030405", "20260102-030406"):
            scratch = self.root / ("scratch-" + stamp)
            data = self.restore(
                self.manifest(stamp=stamp), "--execute", "--keep-restored", scratch=scratch
            )
            events, audits, orphans = self.counts(data["restored"])
            self.assertEqual(events, audits)
            self.assertEqual(orphans, 0)
            self.assertGreaterEqual(events, previous)
            self.assertLessEqual(events, 200 + sum(written))
            previous = events

    def test_both_databases_are_backed_up_and_restorable(self):
        self.seed_wal(self.blackbox, rows=12)
        with closing(sqlite3.connect(self.jobs)) as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE jobs(id INTEGER PRIMARY KEY, title TEXT)")
            db.executemany("INSERT INTO jobs(title) VALUES (?)", [("job",)] * 7)
            db.commit()

        data = self.backup(databases={"blackbox": self.blackbox, "jobscout": self.jobs})

        self.assertEqual([entry["label"] for entry in data["databases"]], ["blackbox", "jobscout"])
        self.assertEqual({entry["restore_test"] for entry in data["databases"]}, {"verified"})
        self.assertEqual(self.manifest_body(label="jobscout")["database"]["tables"], {"jobs": 7})
        self.assertEqual(self.restore(self.manifest("jobscout"), "--execute")["total_rows"], 7)

    def test_a_failed_database_names_itself_and_keeps_earlier_ones(self):
        self.seed_wal(self.blackbox)

        data = self.backup(
            databases={"blackbox": self.blackbox, "jobscout": self.jobs}, success=False
        )

        self.assertEqual(data["error"], "invalid_sqlite_source")
        self.assertEqual(data["database"], "jobscout")
        self.assertEqual(data["completed"], ["blackbox"])
        self.assertTrue(self.manifest().exists())
        self.assertEqual(list(self.offsite.glob("jobscout-*")), [])
        self.assertEqual(list(self.local.glob(".*")), [])

    def test_tampered_archive_is_rejected_before_decryption(self):
        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test")
        archive = self.offsite / self.manifest_body()["archive"]["name"]
        raw = bytearray(archive.read_bytes())
        raw[len(raw) // 2] ^= 0x01
        archive.write_bytes(raw)

        data = self.restore(self.manifest(), "--execute", success=False)

        self.assertEqual(data["error"], "archive_checksum_mismatch")
        self.assertFalse((self.root / "scratch").exists())

    def test_edited_manifest_fails_authentication(self):
        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test")
        envelope = json.loads(self.manifest().read_text())
        envelope["manifest"]["database"]["tables"]["agent_events"] += 1
        self.manifest().write_text(json.dumps(envelope))

        data = self.restore(self.manifest(), "--execute", success=False)

        self.assertEqual(data["error"], "manifest_authentication_failed")

    def test_wrong_passphrase_fails_authentication(self):
        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test")
        self.passphrase.write_text("a-different-fixture-passphrase\n")

        data = self.restore(self.manifest(), "--execute", success=False)

        self.assertEqual(data["error"], "manifest_authentication_failed")

    def test_manifest_cannot_point_outside_its_directory(self):
        passphrase = OFFSITE.read_passphrase(self.passphrase)
        body = {
            "format": OFFSITE.FORMAT,
            "archive": {
                "name": "../elsewhere.enc",
                "cipher": OFFSITE.CIPHER,
                "iterations": OFFSITE.KDF_ITERATIONS,
                "compression": "none",
            },
        }
        self.manifest().write_text(json.dumps(OFFSITE.seal_manifest(body, passphrase)))

        with self.assertRaisesRegex(OFFSITE.BackupError, "manifest_unsupported"):
            OFFSITE.open_manifest(self.manifest(), passphrase)

    def test_restore_refuses_an_existing_scratch_directory(self):
        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test")
        scratch = self.root / "scratch"
        scratch.mkdir()
        (scratch / "blackbox.sqlite3-wal").write_bytes(b"stale")

        data = self.restore(self.manifest(), "--execute", success=False)

        self.assertEqual(data["error"], "scratch_dir_exists")
        self.assertEqual((scratch / "blackbox.sqlite3-wal").read_bytes(), b"stale")

    def test_row_count_and_schema_drift_are_detected(self):
        db = self.seed_wal(self.blackbox, rows=5)
        db.close()
        expected = OFFSITE.inspect_database(self.blackbox)
        expected["bytes"] = self.blackbox.stat().st_size
        expected["sha256"] = OFFSITE.sha256_file(self.blackbox)
        OFFSITE.verify_restored(self.blackbox, expected)

        fewer = json.loads(json.dumps(expected))
        fewer["tables"]["agent_events"] = 4
        with self.assertRaisesRegex(OFFSITE.BackupError, "row_count_mismatch"):
            OFFSITE.verify_restored(self.blackbox, fewer)

        drifted = dict(expected, schema_sha256="0" * 64)
        with self.assertRaisesRegex(OFFSITE.BackupError, "schema_mismatch"):
            OFFSITE.verify_restored(self.blackbox, drifted)

        with self.assertRaisesRegex(OFFSITE.BackupError, "restored_checksum_mismatch"):
            OFFSITE.verify_restored(self.blackbox, dict(expected, sha256="0" * 64))

    def test_integrity_check_rejects_a_corrupt_database(self):
        with closing(sqlite3.connect(self.blackbox)) as db:
            db.execute("CREATE TABLE agent_events(id INTEGER PRIMARY KEY, payload TEXT)")
            db.execute("CREATE INDEX agent_events_payload ON agent_events(payload)")
            rows = [("payload {:05d} {}".format(index, "y" * 300),) for index in range(2000)]
            db.executemany("INSERT INTO agent_events(payload) VALUES (?)", rows)
            db.commit()
            page_size = db.execute("PRAGMA page_size").fetchone()[0]
            pages = db.execute("PRAGMA page_count").fetchone()[0]

        raw = bytearray(self.blackbox.read_bytes())
        # Scribble over interior pages, leaving the header and schema page readable.
        for page in range(pages // 3, pages // 3 + 6):
            raw[page * page_size : (page + 1) * page_size] = os.urandom(page_size)
        self.blackbox.write_bytes(raw)

        with self.assertRaisesRegex(OFFSITE.BackupError, "sqlite_integrity_failed"):
            OFFSITE.inspect_database(self.blackbox)

        data = self.backup(success=False)
        self.assertIn(data["error"], ("sqlite_integrity_failed", "sqlite_backup_failed"))
        self.assertEqual(list(self.offsite.iterdir()), [])

    def test_passphrase_file_must_be_private_and_present(self):
        self.seed_wal(self.blackbox)

        self.passphrase.chmod(0o644)
        self.assertEqual(self.backup(success=False)["error"], "passphrase_file_permissions")

        self.passphrase.chmod(0o600)
        self.passphrase.write_text("\n")
        self.assertEqual(self.backup(success=False)["error"], "passphrase_empty")

        self.passphrase.unlink()
        self.assertEqual(self.backup(success=False)["error"], "passphrase_file_missing")
        self.assertEqual(list(self.offsite.iterdir()), [])

    def test_missing_offsite_directory_is_never_created(self):
        self.seed_wal(self.blackbox)
        shutil.rmtree(self.offsite)

        data = self.backup(success=False)

        self.assertEqual(data["error"], "offsite_dir_missing")
        self.assertFalse(self.offsite.exists())
        self.assertEqual(list(self.local.iterdir()), [])

    def test_existing_set_is_never_overwritten(self):
        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test")
        before = self.manifest().read_bytes()

        data = self.backup(success=False)

        self.assertEqual(data["error"], "output_exists")
        self.assertEqual(self.manifest().read_bytes(), before)

    def test_invalid_arguments_are_rejected(self):
        for database in ("nolabel", "Bad_Label=x.db", "=x.db"):
            data = self.cli(
                "backup",
                "--database",
                database,
                "--local-dir",
                self.local,
                "--offsite-dir",
                self.offsite,
                "--passphrase-file",
                self.passphrase,
                success=False,
            )
            self.assertEqual(data["error"], "invalid_database_argument")

        self.assertEqual(self.backup(stamp="../../x", success=False)["error"], "invalid_arguments")
        self.assertEqual(
            self.backup("--keep-offsite", "0", success=False)["error"], "invalid_arguments"
        )

    def test_prune_keeps_newest_sets_and_leaves_foreign_files(self):
        self.seed_wal(self.blackbox)
        foreign = [
            self.offsite / "blackbox-nightly-20250101-000000.db.zst",
            self.offsite / "other-20250101-000000.manifest.json",
            self.offsite / "notes.txt",
        ]
        for path in foreign:
            path.write_text("keep me")

        stamps = ["20260102-03040{}".format(index) for index in range(4)]
        for stamp in stamps:
            self.backup(
                "--skip-restore-test", "--keep-offsite", "2", "--keep-local", "1", stamp=stamp
            )

        def kept(directory):
            return sorted(
                path.name.split("-", 1)[1].split(".")[0]
                for path in directory.glob("blackbox-2026*.manifest.json")
            )

        self.assertEqual(kept(self.offsite), stamps[2:])
        self.assertEqual(kept(self.local), stamps[3:])
        self.assertEqual(len(list(self.offsite.glob("blackbox-2026*.enc"))), 2)
        for path in foreign:
            self.assertEqual(path.read_text(), "keep me")

    def test_a_set_that_fails_its_restore_test_is_withdrawn_and_never_counted(self):
        self.seed_wal(self.blackbox)
        nights = ["20260102-030400", "20260103-030400", "20260104-030400"]
        for stamp in nights:
            self.backup("--skip-restore-test", "--keep-offsite", "3", stamp=stamp)

        def failing_restore(*args, **kwargs):
            raise OFFSITE.BackupError("restored_checksum_mismatch")

        failed_night = self.backup_args("--keep-offsite", "3", stamp="20260105-030400")
        with mock.patch.object(OFFSITE, "restore_test", failing_restore):
            code, data = self.run_in_process(failed_night)

        self.assertEqual(code, 1)
        self.assertEqual(data["error"], "restored_checksum_mismatch")
        self.assertEqual(list(self.offsite.glob("*20260105*")), [])
        self.assertEqual(list(self.local.glob("*20260105*")), [])
        self.assertEqual(self.stamps(self.offsite), nights)

        # A run killed between its archive and its manifest leaves an incomplete set behind.
        orphan = self.offsite / "blackbox-20260106-030400.sqlite3.enc"
        orphan.write_bytes(b"left by a killed run")
        self.backup("--skip-restore-test", "--keep-offsite", "3", stamp="20260107-030400")

        self.assertFalse(orphan.exists())
        self.assertEqual(self.stamps(self.offsite), nights[1:] + ["20260107-030400"])

    def test_the_set_just_written_survives_a_clock_that_moved_back(self):
        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test", stamp="20260102-090000")

        data = self.backup("--skip-restore-test", "--keep-local", "1", stamp="20260102-070000")

        self.assertEqual(self.stamps(self.local), ["20260102-070000"])
        self.assertEqual(self.stamps(self.offsite), ["20260102-070000", "20260102-090000"])
        self.assertEqual(data["databases"][0]["pruned_offsite"], [])

    def test_default_stamp_is_utc(self):
        before = datetime.now(timezone.utc).replace(microsecond=0)
        data = self.cli(
            "backup",
            "--database",
            "blackbox={}".format(self.blackbox),
            "--local-dir",
            self.local,
            "--offsite-dir",
            self.offsite,
            "--passphrase-file",
            self.passphrase,
        )
        after = datetime.now(timezone.utc)

        stamp = datetime.strptime(data["stamp"], "%Y%m%d-%H%M%S").replace(tzinfo=timezone.utc)
        self.assertLessEqual(before, stamp)
        self.assertLessEqual(stamp, after)

    def test_sigterm_removes_plaintext_staging_and_publishes_nothing(self):
        self.seed_wal(self.blackbox)
        bindir = self.root / "bin"
        bindir.mkdir()
        started = self.root / "openssl-started"
        # Stands in for an encryption still running when launchd stops the job.
        fake = bindir / "openssl"
        fake.write_text('#!/bin/sh\ntouch "$OPENSSL_STARTED"\nexec sleep 60\n')
        fake.chmod(0o700)
        env = dict(
            os.environ,
            PATH=str(bindir) + os.pathsep + os.environ["PATH"],
            OPENSSL_STARTED=str(started),
        )
        process = subprocess.Popen(
            [sys.executable, str(SCRIPT), *self.backup_args("--compression", "none")],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
        )
        self.addCleanup(process.kill)

        deadline = time.monotonic() + 60
        while not started.exists():
            self.assertIsNone(process.poll(), "the backup exited before encrypting")
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.05)
        self.assertEqual(len(list(self.local.glob(".offsite-backup-*/snapshot.sqlite3"))), 1)

        process.send_signal(signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=30)

        self.assertEqual(process.returncode, 1)
        self.assertEqual(stderr, "")
        self.assertEqual(json.loads(stdout)["error"], "interrupted")
        self.assertEqual(list(self.local.iterdir()), [])
        self.assertEqual(list(self.offsite.iterdir()), [])

    def test_leftovers_of_a_killed_run_are_swept_and_foreign_files_kept(self):
        self.seed_wal(self.blackbox)
        staging = self.local / ".offsite-backup-killed01"
        staging.mkdir()
        (staging / "snapshot.sqlite3").write_bytes(b"plaintext snapshot")
        partial = self.offsite / ".blackbox-20260101-000000.sqlite3.enc.partial"
        partial.write_bytes(b"half an archive")
        foreign = [self.local / ".keep-me", self.offsite / ".notes.partial"]
        for path in foreign:
            path.write_text("keep me")

        self.backup("--skip-restore-test")

        self.assertFalse(staging.exists())
        self.assertFalse(partial.exists())
        for path in foreign:
            self.assertEqual(path.read_text(), "keep me")

    def test_publish_never_writes_through_a_planted_partial(self):
        source = self.root / "archive.bin"
        source.write_bytes(b"archive bytes")
        victim = self.root / "victim.txt"
        victim.write_text("untouched")
        (self.offsite / ".set.enc.partial").symlink_to(victim)

        published = OFFSITE.publish(source, self.offsite, "set.enc")

        self.assertEqual(victim.read_text(), "untouched")
        self.assertFalse(published.is_symlink())
        self.assertEqual(published.read_bytes(), b"archive bytes")
        self.assertEqual(published.stat().st_mode & 0o077, 0)
        self.assertEqual(sorted(path.name for path in self.offsite.iterdir()), ["set.enc"])

    def test_manifest_label_stamp_and_names_must_agree(self):
        passphrase = OFFSITE.read_passphrase(self.passphrase)
        body = {
            "format": OFFSITE.FORMAT,
            "label": "../elsewhere/jobs",
            "stamp": STAMP,
            "archive": {
                "name": "jobs-{}.sqlite3.enc".format(STAMP),
                "cipher": OFFSITE.CIPHER,
                "iterations": OFFSITE.KDF_ITERATIONS,
                "compression": "none",
            },
        }
        forged = self.root / "jobs-{}.manifest.json".format(STAMP)
        forged.write_text(json.dumps(OFFSITE.seal_manifest(body, passphrase)))
        with self.assertRaisesRegex(OFFSITE.BackupError, "manifest_unsupported"):
            OFFSITE.open_manifest(forged, passphrase)

        self.seed_wal(self.blackbox)
        self.backup("--skip-restore-test", stamp="20260102-030405")
        # A genuine older set renamed to pass for a newer stamp.
        renamed = self.manifest(stamp="20260109-030405")
        shutil.copyfile(self.manifest(stamp="20260102-030405"), renamed)

        data = self.restore(renamed, "--execute", success=False)

        self.assertEqual(data["error"], "manifest_unsupported")
        self.assertFalse((self.root / "scratch").exists())

    def test_passphrase_openssl_would_read_differently_is_refused(self):
        self.seed_wal(self.blackbox)
        for content in (b"fixture-passphrase\r\n", b"fixture\0passphrase\n", b"x" * 1024 + b"\n"):
            self.passphrase.write_bytes(content)
            self.assertEqual(self.backup(success=False)["error"], "passphrase_invalid")

        self.assertEqual(list(self.offsite.iterdir()), [])

    def test_a_failed_first_database_does_not_skip_the_next(self):
        with closing(sqlite3.connect(self.jobs)) as db:
            db.execute("CREATE TABLE jobs(id INTEGER PRIMARY KEY, title TEXT)")
            db.execute("INSERT INTO jobs(title) VALUES ('job')")
            db.commit()

        data = self.backup(
            databases={"blackbox": self.root / "missing.db", "jobscout": self.jobs},
            success=False,
        )

        self.assertEqual(data["database"], "blackbox")
        self.assertEqual(data["completed"], ["jobscout"])
        self.assertEqual(data["failures"], [{"database": "blackbox", "error": data["error"]}])
        self.assertTrue(self.manifest(label="jobscout").exists())
        self.assertEqual(list(self.offsite.glob("blackbox-*")), [])

    def test_local_directory_inside_the_offsite_directory_is_refused(self):
        self.seed_wal(self.blackbox)
        inside = self.offsite / "staging"
        inside.mkdir()

        for local in (self.offsite, inside):
            data = self.backup(success=False, local=local)
            self.assertEqual(data["error"], "local_dir_inside_offsite_dir")

        self.assertEqual([path.name for path in self.offsite.iterdir()], ["staging"])
        self.assertEqual(list(inside.iterdir()), [])

    def test_a_second_run_on_the_same_local_directory_is_refused(self):
        self.seed_wal(self.blackbox)
        lock = os.open(self.local, os.O_RDONLY)
        self.addCleanup(os.close, lock)
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

        data = self.backup(success=False)

        self.assertEqual(data["error"], "backup_in_progress")
        self.assertEqual(list(self.offsite.iterdir()), [])

    @unittest.skipUnless(shutil.which("zstd"), "zstd is not installed")
    def test_zstd_and_uncompressed_archives_both_restore(self):
        self.seed_wal(self.blackbox)

        self.backup("--compression", "zstd", stamp="20260102-030405")
        self.backup("--compression", "none", stamp="20260102-030406")

        compressed = self.manifest_body(stamp="20260102-030405")["archive"]
        plain = self.manifest_body(stamp="20260102-030406")["archive"]
        self.assertTrue(compressed["name"].endswith(".sqlite3.zst.enc"))
        self.assertTrue(plain["name"].endswith(".sqlite3.enc"))
        self.assertLess(compressed["bytes"], plain["bytes"])


@unittest.skipUnless(shutil.which("openssl") and shutil.which("bash"), "openssl and bash required")
class NightlyScriptTest(unittest.TestCase):
    """The launchd entry point, with a fake `todo` standing in for the Slack alert."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

        self.drive = self.root / "drive"
        self.drive.mkdir()
        self.alerts = self.root / "alerts.log"

        bindir = self.root / "bin"
        bindir.mkdir()
        todo = bindir / "todo"
        todo.write_text('#!/bin/sh\nprintf \'%s\\n\' "$*" >>"$ALERT_LOG"\n')
        todo.chmod(0o700)

        self.passphrase = self.root / "passphrase"
        self.passphrase.write_text("fixture-passphrase-not-a-real-secret\n")
        self.passphrase.chmod(0o600)

        self.databases = {}
        for name in ("sba-agentic.db", "jobs.sqlite3"):
            path = self.root / name
            db = sqlite3.connect(path)
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA wal_autocheckpoint=0")
            db.execute("CREATE TABLE rows(id INTEGER PRIMARY KEY, body TEXT)")
            db.executemany("INSERT INTO rows(body) VALUES (?)", [(MARKER,)] * 9)
            db.commit()
            self.addCleanup(db.close)
            self.databases[name] = path

        self.env = dict(
            os.environ,
            HOME=str(self.root / "home"),
            PATH=str(bindir) + os.pathsep + os.environ["PATH"],
            ALERT_LOG=str(self.alerts),
            SBA_BACKUP_SOURCE=str(self.databases["sba-agentic.db"]),
            SBA_BACKUP_JOBS_SOURCE=str(self.databases["jobs.sqlite3"]),
            SBA_BACKUP_LOCAL_DIR=str(self.root / "local"),
            SBA_BACKUP_DRIVE_ROOT=str(self.drive),
            SBA_BACKUP_PASSPHRASE_FILE=str(self.passphrase),
        )
        self.remote = self.drive / "Data" / "Backups" / "black-box"

    def run_script(self, **env):
        return subprocess.run(
            ["bash", str(NIGHTLY)],
            capture_output=True,
            text=True,
            env=dict(self.env, **env),
            timeout=120,
        )

    def test_backs_up_both_databases_and_verifies_the_restore(self):
        result = self.run_script()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(self.alerts.exists())
        summary = json.loads(result.stdout.strip().splitlines()[-1].split(" done ", 1)[1])
        self.assertEqual(
            [(entry["label"], entry["restore_test"]) for entry in summary["databases"]],
            [("blackbox-nightly", "verified"), ("jobscout-nightly", "verified")],
        )
        self.assertEqual(len(list(self.remote.glob("blackbox-nightly-*.enc"))), 1)
        self.assertEqual(len(list(self.remote.glob("jobscout-nightly-*.enc"))), 1)
        self.assertEqual(len(list(self.remote.glob("*.manifest.json"))), 2)
        for archive in self.remote.glob("*.enc"):
            self.assertNotIn(MARKER.encode(), archive.read_bytes())

    def test_dry_run_touches_nothing(self):
        result = self.run_script(
            SBA_BACKUP_DRY_RUN="1", SBA_BACKUP_DRIVE_ROOT=str(self.root / "absent")
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('"status": "planned"', result.stdout)
        self.assertFalse((self.root / "local").exists())
        self.assertFalse((self.root / "absent").exists())
        self.assertEqual(list(self.drive.iterdir()), [])

    def test_empty_jobs_source_skips_job_scout(self):
        result = self.run_script(SBA_BACKUP_JOBS_SOURCE="")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(list(self.remote.glob("blackbox-nightly-*.enc"))), 1)
        self.assertEqual(list(self.remote.glob("jobscout-nightly-*")), [])

    def test_failure_alerts_with_a_stable_code_only(self):
        self.passphrase.unlink()

        result = self.run_script()

        self.assertEqual(result.returncode, 1)
        alert = self.alerts.read_text()
        self.assertIn("notify --route alerts Black Box nightly backup failed:", alert)
        self.assertIn("passphrase_file_missing", alert)
        self.assertNotIn(str(self.root), alert)
        self.assertEqual(list(self.remote.glob("*")), [])

    @unittest.skipIf(os.geteuid() == 0, "root ignores directory permissions")
    def test_an_unwritable_drive_folder_still_alerts(self):
        self.drive.chmod(0o500)
        self.addCleanup(self.drive.chmod, 0o700)

        result = self.run_script()

        self.assertEqual(result.returncode, 1)
        self.assertIn("backup_dir_unavailable", self.alerts.read_text())

    def test_unmounted_drive_alerts_before_any_snapshot(self):
        result = self.run_script(SBA_BACKUP_DRIVE_ROOT=str(self.root / "absent"))

        self.assertEqual(result.returncode, 1)
        self.assertIn("drive_not_mounted", self.alerts.read_text())
        self.assertFalse((self.root / "local").exists())


if __name__ == "__main__":
    unittest.main()
