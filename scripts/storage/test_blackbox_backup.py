#!/usr/bin/env python3
"""Actual CLI checks; every database, executable, and credential is a disposable fixture."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import stat
import subprocess
import sys
import tempfile
import unittest
import unicodedata
from unittest import mock


SCRIPT = Path(__file__).with_name("blackbox_backup.py").resolve()
SPEC = importlib.util.spec_from_file_location("blackbox_backup", SCRIPT)
BACKUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)


class BackupCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.source = self.root / "source.db"
        self.output = self.root / "backup.db"
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("PG")}

    def cli(self, *args, success=True, env=None):
        result = subprocess.run([sys.executable, str(SCRIPT), *map(str, args)],
                                capture_output=True, text=True, env=env or self.env,
                                timeout=20, preexec_fn=lambda: os.umask(0))
        self.assertEqual(result.stderr, "")
        self.assertEqual(result.returncode == 0, success, result.stdout)
        data = json.loads(result.stdout)
        if not success:
            self.assertEqual(set(data), {"status", "error"})
            self.assertEqual(data["status"], "failed")
            self.assertNotIn("fixture-secret", result.stdout)
        return data

    def sqlite(self, *extra, **kwargs):
        return self.cli("sqlite", "--source", self.source, "--output", self.output,
                        *extra, **kwargs)

    def seed(self, wal=False):
        db = sqlite3.connect(self.source)
        if wal:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA wal_autocheckpoint=0")
        db.executescript("""
            CREATE TABLE agent_events(event_id TEXT PRIMARY KEY, payload BLOB);
            INSERT INTO agent_events(rowid, event_id, payload) VALUES (73, 'evt-1', X'00FF8000');
            CREATE TABLE tasks(id TEXT PRIMARY KEY, old_field TEXT);
            INSERT INTO tasks VALUES('legacy-1', 'still present');
            CREATE TABLE future_table(id INTEGER PRIMARY KEY, value TEXT);
            INSERT INTO future_table VALUES(41, 'unknown');
            CREATE INDEX future_index ON future_table(value);
            CREATE VIEW future_view AS SELECT value FROM future_table;
            CREATE TRIGGER future_trigger AFTER INSERT ON future_table BEGIN
                UPDATE future_table SET value=upper(value) WHERE id=new.id;
            END;
            CREATE VIRTUAL TABLE event_fts USING fts5(payload);
            INSERT INTO event_fts(rowid, payload) VALUES(73, 'retrieval fixture');
        """)
        db.commit()
        return db

    def assert_artifact(self, data, archive_format="sqlite3"):
        self.assertEqual(data["status"], "complete")
        self.assertEqual(data["format"], archive_format)
        raw = self.output.read_bytes()
        self.assertEqual(data["bytes"], len(raw))
        self.assertEqual(data["sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(data["output"], str(self.output))
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o600)
        self.assertEqual(list(self.root.glob(".blackbox-backup-*")), [])

    def fake_postgres(self):
        bindir = self.root / "bin"
        bindir.mkdir()
        dump = bindir / "pg_dump"
        dump.write_text("#!" + sys.executable + "\n" + '''
import json, os, pathlib, sys
pathlib.Path(os.environ['FAKE_LOG']).write_text(json.dumps({
    'argv': sys.argv[1:], 'env': dict(os.environ), 'stdin': sys.stdin.read()}))
if os.environ.get('FAKE_COLLISION'):
    target=pathlib.Path(os.environ['FAKE_COLLISION'])
    if os.environ.get('FAKE_SYMLINK'):
        target.symlink_to(os.environ['FAKE_SYMLINK'])
    else:
        target.write_bytes(b'created concurrently')
mode=os.environ.get('FAKE_MODE', 'ok')
if mode == 'fail':
    print('password=fixture-secret host=private-fixture.invalid', file=sys.stderr)
    sys.stdout.buffer.write(b'partial')
    sys.exit(1)
if mode == 'empty':
    sys.exit(0)
if mode == 'short':
    sys.stdout.buffer.write(b'PGDMP')
else:
    sys.stdout.buffer.write(b'PGDMP' + bytes(128))
''')
        restore = bindir / "pg_restore"
        restore.write_text("#!" + sys.executable + "\n" + '''
import json, os, pathlib, sys
pathlib.Path(os.environ['FAKE_RESTORE_LOG']).write_text(json.dumps(sys.argv[1:]))
if os.environ.get('FAKE_MODE') == 'truncated':
    print('cannot decode fixture-secret', file=sys.stderr)
    sys.exit(1)
''')
        dump.chmod(0o700)
        restore.chmod(0o700)
        return dict(self.env, PATH=str(bindir), FAKE_LOG=str(self.root / "dump-log.json"),
                    FAKE_RESTORE_LOG=str(self.root / "restore-log.json"))

    def postgres(self, *extra, **kwargs):
        return self.cli("postgres", "--host", "127.0.0.1", "--port", "5544",
                        "--database", "fixture_db", "--username", "fixture_user",
                        "--schema", "fixture_schema", "--output", self.output,
                        *extra, **kwargs)

    def test_sqlite_plan_never_opens_source_or_creates_output_parent(self):
        self.output = self.root / "absent" / "backup.db"
        data = self.sqlite()
        self.assertEqual(data["status"], "planned")
        self.assertEqual(data["scope"], {"source": str(self.source)})
        self.assertFalse(self.source.exists())
        self.assertFalse(self.output.parent.exists())
        self.assertEqual(list(self.root.iterdir()), [])

    def test_postgres_plan_never_invokes_tools_or_creates_output(self):
        env = self.fake_postgres()
        data = self.postgres(env=env)
        self.assertEqual(data["status"], "planned")
        self.assertEqual(data["format"], "postgres-custom")
        self.assertFalse(Path(env["FAKE_LOG"]).exists())
        self.assertFalse(self.output.exists())

    def test_sqlite_preserves_rowids_legacy_unknown_objects_fts_and_private_permissions(self):
        self.seed().close()
        before = self.source.read_bytes()
        result = self.sqlite("--execute")
        self.assert_artifact(result)
        self.assertEqual(self.source.read_bytes(), before)
        with sqlite3.connect(self.output) as db:
            self.assertEqual(db.execute("SELECT rowid,event_id,payload FROM agent_events").fetchall(),
                             [(73, "evt-1", bytes.fromhex("00ff8000"))])
            self.assertEqual(db.execute("SELECT * FROM tasks").fetchall(),
                             [("legacy-1", "still present")])
            self.assertEqual(db.execute("SELECT * FROM future_view").fetchall(), [("unknown",)])
            self.assertEqual(db.execute("SELECT rowid FROM event_fts WHERE event_fts MATCH 'retrieval'").fetchall(), [(73,)])
            db.execute("INSERT INTO future_table VALUES (42, 'trigger works')")
            self.assertEqual(db.execute("SELECT value FROM future_table WHERE id=42").fetchone(), ("TRIGGER WORKS",))
            self.assertEqual(db.execute("PRAGMA integrity_check").fetchall(), [("ok",)])
        self.assertFalse(Path(str(self.output) + "-wal").exists())

    def test_sqlite_reads_committed_wal_without_checkpointing_source(self):
        db = self.seed(wal=True)
        self.addCleanup(db.close)
        wal = Path(str(self.source) + "-wal")
        before = (self.source.read_bytes(), wal.read_bytes())
        self.assertTrue(before[1])
        result = self.sqlite("--execute")
        self.assert_artifact(result)
        self.assertEqual((self.source.read_bytes(), wal.read_bytes()), before)
        with sqlite3.connect(self.output) as backup:
            self.assertEqual(backup.execute("SELECT count(*) FROM agent_events").fetchone(), (1,))
        self.assertFalse(Path(str(self.output) + "-wal").exists())

    def test_sqlite_preserves_unavailable_virtual_module_and_shadow_pages(self):
        self.seed().close()
        # Same-length schema edit simulates an unavailable extension without loading
        # arbitrary native code. Shadow pages remain real FTS5 pages and must survive.
        raw = self.source.read_bytes()
        self.assertIn(b"USING fts5(payload)", raw)
        self.source.write_bytes(raw.replace(b"USING fts5(payload)", b"USING vecx(payload)"))
        with sqlite3.connect(self.source) as db:
            expected = db.execute("SELECT id,block FROM event_fts_data ORDER BY id").fetchall()
        self.assert_artifact(self.sqlite("--execute"))
        with sqlite3.connect(self.output) as db:
            self.assertIn("USING vecx(payload)", db.execute("SELECT sql FROM sqlite_master WHERE name='event_fts'").fetchone()[0])
            self.assertEqual(db.execute("SELECT id,block FROM event_fts_data ORDER BY id").fetchall(), expected)

    def test_sqlite_custom_check_function_does_not_require_extension_loading(self):
        with sqlite3.connect(self.source) as db:
            db.create_function("custom_check", 1, lambda value: value == 1)
            db.execute("CREATE TABLE unknown(id INTEGER CHECK(custom_check(id)))")
            db.execute("INSERT INTO unknown VALUES(1)")
        self.assert_artifact(self.sqlite("--execute"))

    def test_filename_uri_characters_are_literal(self):
        self.source = self.root / "source ?#%.db"
        self.seed().close()
        self.assert_artifact(self.sqlite("--execute"))

    def test_missing_empty_corrupt_and_directory_sources_fail_without_output(self):
        for kind in ("missing", "empty", "corrupt", "directory"):
            with self.subTest(kind=kind):
                self.source = self.root / kind
                if kind == "empty":
                    self.source.touch()
                elif kind == "corrupt":
                    self.source.write_text("fixture-secret: not a database")
                elif kind == "directory":
                    self.source.mkdir()
                self.sqlite("--execute", success=False)
                self.assertFalse(self.output.exists())
                self.assertEqual(list(self.root.glob(".blackbox-backup-*")), [])

    def test_source_symlink_is_rejected(self):
        target = self.root / "real.db"
        with sqlite3.connect(target) as db:
            db.execute("CREATE TABLE example(id)")
        self.source.symlink_to(target)
        self.assertEqual(self.sqlite("--execute", success=False)["error"], "invalid_sqlite_source")
        self.assertFalse(self.output.exists())

    def test_output_collision_preserves_existing_file(self):
        self.seed().close()
        self.output.write_bytes(b"keep this")
        self.assertEqual(self.sqlite("--execute", success=False)["error"], "output_exists")
        self.assertEqual(self.output.read_bytes(), b"keep this")

    def test_existing_and_dangling_output_symlinks_are_never_followed(self):
        self.seed().close()
        for exists in (False, True):
            with self.subTest(exists=exists):
                target = self.root / "symlink-target"
                if exists:
                    target.write_bytes(b"keep target")
                self.output.symlink_to(target)
                self.assertEqual(self.sqlite("--execute", success=False)["error"], "output_exists")
                self.assertEqual(target.exists(), exists)
                if exists:
                    self.assertEqual(target.read_bytes(), b"keep target")
                self.output.unlink()

    def test_output_parent_symlink_is_rejected(self):
        self.seed().close()
        real = self.root / "real-directory"
        real.mkdir()
        link = self.root / "linked-directory"
        link.symlink_to(real, target_is_directory=True)
        self.output = link / "backup.db"
        self.assertEqual(self.sqlite("--execute", success=False)["error"], "unsafe_or_missing_output_parent")
        self.assertEqual(list(real.iterdir()), [])

    def test_missing_output_parent_is_not_created(self):
        self.seed().close()
        self.output = self.root / "missing-parent" / "backup.db"
        self.sqlite("--execute", success=False)
        self.assertFalse(self.output.parent.exists())

    def test_source_cannot_equal_output(self):
        self.output = self.source
        self.assertEqual(self.sqlite(success=False)["error"], "source_is_output")

    def test_sidecar_plans_reject_normalized_names_without_filesystem_or_tool_io(self):
        source = self.root / "missing" / "source.db"
        with mock.patch.object(BACKUP.os, "open") as opened, \
                mock.patch.object(BACKUP.Path, "stat") as stated, \
                mock.patch.object(BACKUP.Path, "lstat") as lstated, \
                mock.patch.object(BACKUP.sqlite3, "connect") as connected, \
                mock.patch.object(BACKUP.tempfile, "mkdtemp") as staged, \
                mock.patch.object(BACKUP.subprocess, "run") as invoked:
            for suffix in ("-journal", "-wal", "-shm"):
                with self.subTest(suffix=suffix):
                    output = source.parent / "unused" / ".." / (source.name + suffix)
                    args = BACKUP.parse_args(["sqlite", "--source", str(source),
                                              "--output", str(output)])
                    with self.assertRaisesRegex(BACKUP.BackupError, "^sqlite_sidecar_destination$"):
                        BACKUP.make_plan(args)
            for action in (opened, stated, lstated, connected, staged, invoked):
                action.assert_not_called()
        self.assertEqual(list(self.root.iterdir()), [])

    def test_actual_cli_rejects_missing_and_existing_sidecars_including_parent_alias(self):
        for alias in (False, True):
            for suffix in ("-journal", "-wal", "-shm"):
                for exists in (False, True):
                    with self.subTest(alias=alias, suffix=suffix, exists=exists):
                        with tempfile.TemporaryDirectory(dir=self.root) as temp:
                            root = Path(temp)
                            real = root / "real"
                            real.mkdir()
                            source = real / "source.db"
                            with sqlite3.connect(source) as db:
                                db.execute("CREATE TABLE fixture(id)")
                                db.execute("INSERT INTO fixture VALUES (1)")
                            db.close()
                            supplied = source
                            if alias:
                                link = root / "alias"
                                link.symlink_to(real, target_is_directory=True)
                                supplied = link / source.name
                            output = real / (source.name + suffix)
                            before = source.read_bytes()
                            if exists:
                                output.write_bytes(b"existing sidecar sentinel")
                            result = self.cli("sqlite", "--source", supplied,
                                              "--output", output, "--execute", success=False)
                            self.assertEqual(result["error"], "sqlite_sidecar_destination")
                            self.assertEqual(source.read_bytes(), before)
                            self.assertEqual(output.exists(), exists)
                            if exists:
                                self.assertEqual(output.read_bytes(), b"existing sidecar sentinel")
                            self.assertEqual(list(real.glob(".blackbox-backup-*")), [])

    def test_actual_cli_reserves_sidecar_case_variants(self):
        self.seed().close()
        for suffix in ("-JOURNAL", "-WAL", "-SHM"):
            with self.subTest(suffix=suffix):
                self.output = self.root / (self.source.name.upper() + suffix)
                self.assertEqual(self.sqlite(success=False)["error"], "sqlite_sidecar_destination")
                self.assertFalse(self.output.exists())

    def test_actual_cli_rejects_canonically_equivalent_sidecar_names_and_parent_aliases(self):
        for source_form, output_form in (("NFC", "NFD"), ("NFD", "NFC")):
            for alias in (False, True):
                for suffix in ("-JOURNAL", "-WAL", "-SHM"):
                    with self.subTest(source_form=source_form, alias=alias, suffix=suffix):
                        with tempfile.TemporaryDirectory(dir=self.root) as temp:
                            root = Path(temp)
                            real = root / "real"
                            real.mkdir()
                            source = real / unicodedata.normalize(source_form, "café.db")
                            with sqlite3.connect(source) as db:
                                db.execute("CREATE TABLE fixture(id)")
                                db.execute("INSERT INTO fixture VALUES (1)")
                            db.close()
                            supplied = source
                            if alias:
                                link = root / "alias"
                                link.symlink_to(real, target_is_directory=True)
                                supplied = link / source.name
                            output = real / (unicodedata.normalize(output_form, "CAFÉ.DB") + suffix)
                            before = source.read_bytes()
                            result = self.cli("sqlite", "--source", supplied,
                                              "--output", output, "--execute", success=False)
                            self.assertEqual(result["error"], "sqlite_sidecar_destination")
                            self.assertEqual(source.read_bytes(), before)
                            self.assertFalse(output.exists())
                            self.assertEqual(list(real.glob(".blackbox-backup-*")), [])

    def test_unicode_sidecar_planning_is_pure_and_uses_canonical_not_compatibility_normalization(self):
        with mock.patch.object(BACKUP.os, "open") as opened, \
                mock.patch.object(BACKUP.Path, "stat") as stated, \
                mock.patch.object(BACKUP.Path, "lstat") as lstated, \
                mock.patch.object(BACKUP.sqlite3, "connect") as connected, \
                mock.patch.object(BACKUP.tempfile, "mkdtemp") as staged, \
                mock.patch.object(BACKUP.subprocess, "run") as invoked:
            for source_form, output_form in (("NFC", "NFD"), ("NFD", "NFC")):
                for suffix in ("-JOURNAL", "-WAL", "-SHM"):
                    with self.subTest(source_form=source_form, suffix=suffix):
                        source = self.root / unicodedata.normalize(source_form, "café.db")
                        output = self.root / (unicodedata.normalize(output_form, "CAFÉ.DB") + suffix)
                        args = BACKUP.parse_args(["sqlite", "--source", str(source),
                                                  "--output", str(output)])
                        with self.assertRaisesRegex(BACKUP.BackupError, "^sqlite_sidecar_destination$"):
                            BACKUP.make_plan(args)
            args = BACKUP.parse_args(["sqlite", "--source", str(self.root / "①.db"),
                                      "--output", str(self.root / "1.db-journal")])
            self.assertEqual(BACKUP.make_plan(args)["status"], "planned")
            for action in (opened, stated, lstated, connected, staged, invoked):
                action.assert_not_called()
        self.assertEqual(list(self.root.iterdir()), [])

    def test_actual_cli_rejects_normalized_sidecar_plan(self):
        self.source = self.root / "missing" / ".." / "source.db"
        self.output = self.root / "source.db-wal"
        self.assertEqual(self.sqlite(success=False)["error"], "sqlite_sidecar_destination")
        self.assertEqual(list(self.root.iterdir()), [])

    def test_parent_alias_plan_stays_argument_only_but_execution_checks_before_staging(self):
        real = self.root / "real"
        real.mkdir()
        alias = self.root / "alias"
        alias.symlink_to(real, target_is_directory=True)
        # No source file exists: the directory identity alone suffices to refuse.
        self.source = alias / "source.db"
        self.output = real / "source.db-journal"
        plan = self.sqlite()
        self.assertEqual(plan["status"], "planned")
        with mock.patch.object(BACKUP, "snapshot_sqlite") as snapshot, \
                mock.patch.object(BACKUP.Path, "lstat") as leaf_stat, \
                mock.patch.object(BACKUP.tempfile, "mkdtemp") as staged, \
                mock.patch.object(BACKUP.subprocess, "run") as invoked, \
                mock.patch.object(BACKUP.os, "close", wraps=os.close) as closed:
            with self.assertRaisesRegex(BACKUP.BackupError, "^sqlite_sidecar_destination$"):
                BACKUP.execute(plan)
            for action in (snapshot, leaf_stat, staged, invoked):
                action.assert_not_called()
            # The final output-directory descriptor is closed on guard failure.
            with self.assertRaises(OSError):
                os.fstat(closed.call_args.args[0])
        self.assertEqual(list(real.iterdir()), [])

    def snapshot_survives_source_writes(self, wal=False, alias=False):
        db = self.seed(wal=wal)
        self.addCleanup(db.close)
        original = self.source
        if alias:
            linked = self.root / "alias"
            linked.symlink_to(self.root, target_is_directory=True)
            self.source = linked / original.name
        report = self.sqlite("--execute")
        self.assert_artifact(report)
        captured = self.output.read_bytes()
        db.execute("INSERT INTO agent_events VALUES ('evt-2', X'012345')")
        db.commit()
        self.assertEqual(db.execute("SELECT count(*) FROM agent_events").fetchone(), (2,))
        self.assertEqual(self.output.read_bytes(), captured)
        self.assertEqual(hashlib.sha256(captured).hexdigest(), report["sha256"])
        with sqlite3.connect(self.output) as restored:
            self.assertEqual(restored.execute("SELECT rowid,event_id,hex(payload) FROM agent_events").fetchall(),
                             [(73, "evt-1", "00FF8000")])
        restored.close()
        self.assertEqual(list(self.root.glob(".blackbox-backup-*")), [])

    def test_ordinary_snapshot_survives_later_delete_journal_source_commit(self):
        self.snapshot_survives_source_writes()

    def test_ordinary_snapshot_survives_later_wal_source_commit_with_parent_alias(self):
        self.snapshot_survives_source_writes(wal=True, alias=True)

    def test_sidecar_named_snapshot_in_different_directory_is_valid(self):
        other = self.root / "backups"
        other.mkdir()
        self.output = other / "source.db-journal"
        self.snapshot_survives_source_writes()

    def test_postgres_exact_scope_credentials_and_offline_archive_validation(self):
        env = self.fake_postgres()
        env.update(PGPASSWORD="fixture-secret", PGHOST="evil.invalid", PGHOSTADDR="192.0.2.99",
                   PGPORT="1", PGDATABASE="wrong", PGUSER="wrong", PGOPTIONS="-c role=wrong",
                   PGSERVICE="wrong", PGSERVICEFILE="/nonexistent/private-service",
                   PGSSLMODE="verify-full", PGSSLMINPROTOCOLVERSION="TLSv1.3",
                   PGREQUIREAUTH="scram-sha-256")
        schema = 'MiXeD.*"schema'
        result = self.postgres("--schema", schema, "--execute", env=env)
        self.assert_artifact(result, "postgres-custom")
        log = json.loads(Path(env["FAKE_LOG"]).read_text())
        self.assertEqual(log["argv"], ["--format=custom", "--no-password", "--strict-names",
                                     "--host=127.0.0.1", "--port=5544", "--dbname=fixture_db",
                                     "--username=fixture_user", '--schema="MiXeD.*""schema"'])
        self.assertEqual(log["stdin"], "")
        self.assertNotIn("fixture-secret", " ".join(log["argv"]))
        self.assertEqual(log["env"]["PGPASSWORD"], "fixture-secret")
        self.assertEqual(log["env"]["PGPASSFILE"], os.devnull)
        self.assertEqual(log["env"]["PGSSLMODE"], "verify-full")
        self.assertEqual(log["env"]["PGSSLMINPROTOCOLVERSION"], "TLSv1.3")
        self.assertEqual(log["env"]["PGREQUIREAUTH"], "scram-sha-256")
        for name in ("PGHOST", "PGHOSTADDR", "PGPORT", "PGDATABASE", "PGUSER", "PGOPTIONS",
                     "PGSERVICE", "PGSERVICEFILE"):
            self.assertNotIn(name, log["env"])
        restore_args = json.loads(Path(env["FAKE_RESTORE_LOG"]).read_text())
        self.assertEqual(restore_args[:3], ["--file=" + os.devnull, "--no-owner", "--no-privileges"])
        self.assertNotIn("--dbname", " ".join(restore_args))

    def test_postgres_explicit_passfile_channel_is_preserved(self):
        env = self.fake_postgres()
        env["PGPASSFILE"] = str(self.root / "explicit-passfile")
        self.postgres("--execute", env=env)
        log = json.loads(Path(env["FAKE_LOG"]).read_text())
        self.assertEqual(log["env"]["PGPASSFILE"], env["PGPASSFILE"])

    def test_postgres_tool_failure_empty_short_and_truncated_archives_never_publish(self):
        env = self.fake_postgres()
        for mode in ("fail", "empty", "short", "truncated"):
            with self.subTest(mode=mode):
                env["FAKE_MODE"] = mode
                self.postgres("--execute", env=env, success=False)
                self.assertFalse(self.output.exists())
                self.assertEqual(list(self.root.glob(".blackbox-backup-*")), [])

    def test_publication_collision_after_dump_never_overwrites(self):
        env = self.fake_postgres()
        env["FAKE_COLLISION"] = str(self.output)
        self.assertEqual(self.postgres("--execute", env=env, success=False)["error"], "output_exists")
        self.assertEqual(self.output.read_bytes(), b"created concurrently")

    def test_publication_symlink_race_never_follows_or_replaces(self):
        env = self.fake_postgres()
        target = self.root / "never-create"
        env.update(FAKE_COLLISION=str(self.output), FAKE_SYMLINK=str(target))
        self.assertEqual(self.postgres("--execute", env=env, success=False)["error"], "output_exists")
        self.assertTrue(self.output.is_symlink())
        self.assertFalse(target.exists())

    def test_postgres_missing_native_tools_fails_without_artifact(self):
        env = dict(self.env, PATH=str(self.root / "no-tools"))
        self.assertEqual(self.postgres("--execute", env=env, success=False)["error"], "postgres_tools_missing")
        self.assertFalse(self.output.exists())

    def test_postgres_rejects_connection_override_and_multihost_inputs(self):
        for name, value in (("database", "host=evil password=fixture-secret"),
                            ("database", "postgresql://user:fixture-secret@evil/db"),
                            ("host", "localhost,evil"), ("host", "user@evil"),
                            ("host", "/tmp/socket"), ("port", "0"), ("port", "65536"),
                            ("port", "abc"), ("schema", ""), ("schema", "a" * 64)):
            with self.subTest(name=name, value=value):
                self.postgres("--" + name, value, success=False)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_argument_errors_do_not_echo_sensitive_values(self):
        self.cli("sqlite", "--unknown=fixture-secret", success=False)
        self.cli("postgres", "--password=fixture-secret", success=False)


if __name__ == "__main__":
    unittest.main()
