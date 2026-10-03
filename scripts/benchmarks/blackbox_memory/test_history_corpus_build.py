import errno
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import compact_backend as c
import compact_server as cs
import history_corpus_build as b
import history_search as h
import test_compact_backend as tc

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SCHEMA = (REPO / "src/main/resources/schema.sql").read_text(encoding="utf-8")
BACKUP = REPO / "scripts/storage/blackbox_backup.py"
BUILDER = HERE / "history_corpus_build.py"
LITERAL = HERE / "history_search.py"
PROJECT = tc.PROJECT
CUTOFF = "2026-09-30T00:00:00Z"
SESSION = ("s1", "codex", "c1", PROJECT)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def event(event_id, text="text", event_type="user_message", observed="2026-09-01T00:00:00Z", session="s1",
          source="codex", client="c1", metadata=None, **extra):
    return dict(id=event_id, session_id=session, source=source, client_session_id=client, event_type=event_type,
                text=text, observed_at=observed, metadata_json=metadata, **extra)


class Fixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        # The builder refuses symlinked components; macOS temp roots sit under the /var symlink.
        self.root = Path(os.path.realpath(self.tmp.name))
        self.count = 0

    def tearDown(self):
        self.tmp.cleanup()

    def source(self, events, sessions=(SESSION,), schema=SCHEMA, raw_sql=()):
        """A synthetic Black Box database: the real schema plus the given rows."""
        self.count += 1
        path = self.root / ("source-%d.db" % self.count)
        with sqlite3.connect(path) as db:
            db.executescript(schema)
            for sid, source, client, cwd in sessions:
                db.execute("INSERT INTO agent_sessions (id, source, client_session_id, title, cwd, started_at, "
                           "last_seen_at) VALUES (?, ?, ?, 't', ?, 'x', 'x')", (sid, source, client, cwd))
            for row in events:
                columns = sorted(row)
                db.execute("INSERT INTO agent_events (%s) VALUES (%s)" % (
                    ", ".join(columns), ", ".join("?" * len(columns))), [row[k] for k in columns])
            for statement in raw_sql:
                db.execute(statement)
        return path

    def snapshot(self, source):
        """Standalone snapshot through the real backup CLI; returns (path, sha256)."""
        out = self.root / ("snap-%d.sqlite" % self.count)
        result = subprocess.run([sys.executable, str(BACKUP), "sqlite", "--source", str(source), "--output",
                                 str(out), "--execute"], capture_output=True, text=True, check=True)
        report = json.loads(result.stdout)
        self.assertEqual(report["status"], "complete")
        self.assertEqual(report["sha256"], sha256(out))
        return out, report["sha256"]

    def snap(self, events, **kwargs):
        return self.snapshot(self.source(events, **kwargs))

    def args(self, snapshot, digest, sessions=("s1",), exclusions=(), output="out", cutoff=CUTOFF,
             project=PROJECT, execute=True):
        excl = self.root / ("exclusions-%s.json" % output.replace("/", "_"))
        excl.write_text(json.dumps(list(exclusions)), encoding="utf-8")
        argv = ["--snapshot", str(snapshot), "--snapshot-sha256", digest, "--corpus-id", "synthetic-build",
                "--project", project, "--cutoff", cutoff, "--exclusions", str(excl), "--output",
                str(self.root / output)]
        for session in sessions:
            argv += ["--session", session]
        return argv + (["--execute"] if execute else [])

    def build(self, snapshot, digest, **kwargs):
        stdout = io.StringIO()
        code = b.main(self.args(snapshot, digest, **kwargs), stdout=stdout)
        return code, json.loads(stdout.getvalue())

    def corpus(self, events, **kwargs):
        snapshot, digest = self.snap(events)
        code, result = self.build(snapshot, digest, **kwargs)
        self.assertEqual((code, result["status"]), (0, "complete"), result)
        return h.load_corpus(result["manifest"], result["manifest_sha256"]), result

    def leftovers(self):
        return sorted(p.name for p in self.root.iterdir() if p.name.startswith(".history-corpus-build-"))

    def rejects(self, code, snapshot, digest, output="out", **kwargs):
        exit_code, result = self.build(snapshot, digest, output=output, **kwargs)
        self.assertEqual((exit_code, result), (1, {"status": "failed", "error": code}))
        self.assertFalse((self.root / output).exists())
        self.assertEqual(self.leftovers(), [])

    def rejects_rows(self, code, events, sessions=(SESSION,), **kwargs):
        self.rejects(code, *self.snap(events, sessions=sessions), **kwargs)


class DryRunAndArgumentTests(Fixture):
    def test_dry_run_is_syntactic_and_never_reads_the_snapshot(self):
        argv = self.args(self.root / "missing.sqlite", "a" * 64, sessions=("s2", "s1"), execute=False)
        stdout = io.StringIO()
        with mock.patch.object(b.sqlite3, "connect", side_effect=AssertionError("connected")), \
                mock.patch.object(b, "copy_snapshot", side_effect=AssertionError("read")):
            self.assertEqual(b.main(argv, stdout=stdout), 0)
        plan = json.loads(stdout.getvalue())
        self.assertEqual((plan["status"], plan["mode"], plan["sessions"], plan["snapshot_read"],
                          plan["availability"]), ("planned", "dry_run", ["s1", "s2"], False, "unverified"))
        self.assertEqual(sorted(p.name for p in self.root.iterdir()), ["exclusions-out.json"])

    def test_argument_errors_are_stable_codes(self):
        snapshot = self.root / "snap.sqlite"
        cases = [
            ("invalid_snapshot_sha256", dict(digest="A" * 64)),
            ("invalid_corpus_id", dict(replace=("synthetic-build", "bad id"))),
            ("invalid_project", dict(project="")),
            ("invalid_session", dict(sessions=("s\n1",))),
            ("duplicate_session", dict(sessions=("s1", "s1"))),
            ("invalid_cutoff", dict(cutoff="2026-09-30 00:00:00Z")),
            ("invalid_cutoff", dict(cutoff="2026-09-30T00:00:00.1234567890Z")),
            ("duplicate_exclusion", dict(exclusions=[{"id": "e1", "reason": "r"}, {"id": "e1", "reason": "r"}])),
            ("invalid_exclusions", dict(exclusions=[{"id": "e1", "reason": " \t"}])),
            ("invalid_exclusions", dict(exclusions=[{"id": "e1", "reason": "r", "extra": 1}])),
            ("invalid_exclusions", dict(exclusions=[{"id": "bad id", "reason": "r"}])),
            ("output_is_snapshot", dict(output="snap.sqlite")),
            ("output_is_sidecar", dict(output="snap.sqlite-wal")),
        ]
        for code, case in cases:
            with self.subTest(code=code, case=case):
                replace = case.pop("replace", None)
                argv = self.args(snapshot, case.pop("digest", "a" * 64), execute=False, **case)
                if replace:
                    argv = [replace[1] if arg == replace[0] else arg for arg in argv]
                stdout = io.StringIO()
                self.assertEqual(b.main(argv, stdout=stdout), 1)
                self.assertEqual(json.loads(stdout.getvalue()), {"status": "failed", "error": code})
        stdout = io.StringIO()
        self.assertEqual(b.main(["--snapshot", str(snapshot)], stdout=stdout), 1)
        self.assertEqual(json.loads(stdout.getvalue())["error"], "invalid_arguments")


class JourneyTests(Fixture):
    EVENTS = [
        event("e-handoff", "resume: run the parser test", "Handoff", "2026-09-02T00:00:00.123456789Z"),
        event("e-decision", "Use exact labels\u0000only", "Decision", "2026-09-01T00:00:00.5Z"),
        event("e-observe", "naïve café — 東京 🚀", "Observation", "2026-09-01T00:00:00.5Z"),
        event("e-empty", "", "user_message", "2026-09-01T00:00:01Z"),
        event("e-space", " \t\n ", "agent_message", "2026-09-01T00:00:02Z"),
        event("e-idea", "idea text", "Idea", "2026-09-01T00:00:03Z"),
        event("e-tool", "tool ran", "tool_call", "2026-09-01T00:00:04Z", tool_name="Bash",
              tool_input_json='{"cmd":"SECRET_INPUT"}', tool_output_json='"SECRET_OUTPUT"', human_text="HUMAN_ONLY",
              metadata='{"note":"SECRET_META"}'),
    ]

    def test_cli_build_loads_validates_serves_and_rebuilds_byte_identically(self):
        snapshot, digest = self.snap(self.EVENTS)
        before = (snapshot.read_bytes(), os.stat(snapshot).st_mtime_ns, sorted(os.listdir(self.root)))
        run = subprocess.run([sys.executable, str(BUILDER)] + self.args(snapshot, digest),
                             capture_output=True, text=True)
        self.assertEqual((run.returncode, run.stderr), (0, ""))
        result = json.loads(run.stdout)
        self.assertEqual((result["availability"], result["recorded_at"], result["content"]),
                         ("unverified", "absent", "agent_events.text only"))
        self.assertEqual(result["counts"], {"snapshot_events": 7, "allowlisted_session_events": 7, "after_cutoff": 0,
                                            "unattributed": 0, "other_project": 0, "excluded": 0, "included": 7})
        out = self.root / "out"
        self.assertEqual(stat.S_IMODE(out.stat().st_mode), 0o700)
        self.assertEqual(sorted(os.listdir(out)), ["items.json", "manifest.json"])
        self.assertTrue(all(stat.S_IMODE((out / n).stat().st_mode) == 0o600 for n in os.listdir(out)))

        corpus = h.load_corpus(out / "manifest.json", result["manifest_sha256"])
        self.assertEqual([i.id for i in corpus.items], ["e-decision", "e-observe", "e-empty", "e-space", "e-idea",
                                                         "e-tool", "e-handoff"])
        by_id = {i.id: i for i in corpus.items}
        self.assertEqual({k: by_id[k].kind for k in by_id}, {
            "e-handoff": "handoff", "e-decision": "decision", "e-observe": "observation", "e-empty": "event",
            "e-space": "event", "e-idea": "event", "e-tool": "event"})
        for row in self.EVENTS:
            item = by_id[row["id"]]
            self.assertEqual((item.text, item.observed_at, item.session, item.project, item.recorded_at),
                             (row["text"], row["observed_at"], "s1", PROJECT, None))
            self.assertEqual(item.source_ref, "blackbox-sqlite:%s:agent_events:%s" % (digest, row["id"]))
        raw_items = json.loads((out / "items.json").read_text(encoding="utf-8"))["items"]
        self.assertTrue(all("recorded_at" not in i for i in raw_items))
        for secret in ("SECRET_INPUT", "SECRET_OUTPUT", "SECRET_META", "HUMAN_ONLY", "Bash"):
            self.assertNotIn(secret, (out / "items.json").read_text(encoding="utf-8"))
        self.assertEqual(corpus.summary()["handoff_id"], "e-handoff")
        self.assertIn("availability unverified", corpus.provenance)

        validate = subprocess.run([sys.executable, str(LITERAL), "validate", "--manifest", str(out / "manifest.json"),
                                   "--manifest-sha256", result["manifest_sha256"]], capture_output=True, text=True)
        self.assertEqual(validate.returncode, 0)
        self.assertEqual(json.loads(validate.stdout)["item_count"], 7)
        serve = subprocess.run([sys.executable, str(LITERAL), "serve", "--manifest", str(out / "manifest.json"),
                                "--manifest-sha256", result["manifest_sha256"]],
                               input='{"query": "東京"}\n'.encode("utf-8"), capture_output=True)
        lines = [json.loads(line) for line in serve.stdout.decode("utf-8").splitlines()]
        self.assertEqual(serve.returncode, 0)
        self.assertEqual((lines[0]["type"], lines[0]["results"][0]["id"]), ("handoff", "e-handoff"))
        self.assertEqual([r["id"] for r in lines[1]["results"]], ["e-observe"])

        run2 = subprocess.run([sys.executable, str(BUILDER)] + self.args(snapshot, digest, output="out2"),
                              capture_output=True, text=True)
        self.assertEqual(json.loads(run2.stdout)["manifest_sha256"], result["manifest_sha256"])
        for name in ("manifest.json", "items.json"):
            self.assertEqual((out / name).read_bytes(), (self.root / "out2" / name).read_bytes())
        self.assertEqual((snapshot.read_bytes(), os.stat(snapshot).st_mtime_ns), before[:2])
        self.assertEqual(sorted(set(os.listdir(self.root)) - set(before[2])),
                         ["exclusions-out.json", "exclusions-out2.json", "out", "out2"])

    def test_sqlite_only_opens_the_verified_private_copy(self):
        snapshot, digest = self.snap(self.EVENTS)
        opened = []
        real = sqlite3.connect

        def spy(target, *args, **kwargs):
            opened.append(target)
            return real(target, *args, **kwargs)

        with mock.patch.object(b.sqlite3, "connect", spy):
            self.assertEqual(self.build(snapshot, digest)[0], 0)
        self.assertEqual(len(opened), 1)
        self.assertIn("/.history-corpus-build-", opened[0])
        self.assertTrue(opened[0].endswith("/snapshot.sqlite?mode=ro&immutable=1"))

    def test_compact_backend_contract_with_fakes(self):
        corpus, _ = self.corpus(self.EVENTS)
        rows, index = tc.build(corpus)
        session = h.Session(corpus, c.CompactCanonicalBackend(tc.FakeCanonicalApi(rows), index))
        first = json.loads(session.start())
        self.assertEqual((first["status"], first["results"][0]["id"]), ("ok", "e-handoff"))
        found = json.loads(session.search("exact"))
        self.assertEqual([r["id"] for r in found["results"]], ["e-decision"])
        self.assertEqual(found["results"][0]["excerpt"]["text"], "Use exact labels\u0000only")
        for item in corpus.items:
            payload = cs.capture_payload(corpus, item)
            self.assertEqual((payload["event"]["text"], payload["event"]["metadata"]),
                             (item.text, {"sourceRef": item.source_ref}))
            self.assertEqual(c.parse_instant(payload["event"]["observedAt"]), item.observed_ns)


class ScopeTests(Fixture):
    def test_internal_session_ids_separate_duplicate_client_session_ids(self):
        sessions = [SESSION, ("s2", "claude", "c1", PROJECT)]
        events = [event("a", "from codex"), event("b", "from claude", session="s2", source="claude")]
        snapshot, digest = self.snapshot(self.source(events, sessions=sessions))
        code, result = self.build(snapshot, digest)
        self.assertEqual(result["counts"]["included"], 1)
        corpus = h.load_corpus(result["manifest"], result["manifest_sha256"])
        self.assertEqual([(i.id, i.session) for i in corpus.items], [("a", "s1")])
        code, result = self.build(snapshot, digest, sessions=("s2", "s1"), output="both")
        self.assertEqual((result["sessions_included"], result["counts"]["snapshot_events"]), ({"s1": 1, "s2": 1}, 2))
        self.rejects("unknown_session", snapshot, digest, sessions=("c1",), output="by-client")
        self.rejects("unknown_session", snapshot, digest, sessions=("s1", "s9"), output="missing")

    def test_event_identity_must_agree_with_its_session(self):
        self.rejects_rows("session_identity_mismatch", [event("a", client="c-other")])
        self.rejects_rows("session_identity_mismatch", [event("a", source="claude")])

    def test_repo_overrides_cwd_with_exact_labels_and_counts(self):
        sessions = [SESSION, ("s2", "codex", "c2", "/elsewhere"), ("s3", "codex", "c3", None)]
        repo = lambda value: json.dumps({"repo": value})
        events = [
            event("a-other", metadata=repo("/repos/other")),
            event("b-blank", metadata=repo(" \t ")),
            event("c-null", metadata=repo(None)),
            event("d-nbsp", metadata=repo(" ")),
            event("e-slash", metadata=repo(PROJECT + "/")),
            event("f-case", metadata=repo(PROJECT.upper())),
            event("g-override", session="s2", client="c2", metadata=repo(PROJECT)),
            event("h-cwd", session="s2", client="c2"),
            event("i-none", session="s3", client="c3"),
            event("j-plain"),
        ]
        snapshot, digest = self.snapshot(self.source(events, sessions=sessions))
        code, result = self.build(snapshot, digest, sessions=("s1", "s2", "s3"))
        self.assertEqual(code, 0, result)
        self.assertEqual({k: result["counts"][k] for k in ("other_project", "unattributed", "included")},
                         {"other_project": 5, "unattributed": 1, "included": 4})
        corpus = h.load_corpus(result["manifest"], result["manifest_sha256"])
        self.assertEqual(sorted(i.id for i in corpus.items), ["b-blank", "c-null", "g-override", "j-plain"])

    def test_malformed_selected_metadata_fails_but_late_rows_are_only_counted(self):
        for code, metadata in (("malformed_metadata", "{not json"), ("malformed_metadata", "[1]"),
                               ("malformed_metadata", '{"repo":"/a","repo":"/b"}'),
                               ("malformed_metadata", '{"n":NaN}'), ("malformed_repo", '{"repo":5}')):
            with self.subTest(metadata=metadata):
                self.rejects_rows(code, [event("a", metadata=metadata)])
        corpus, result = self.corpus([event("a"), event("late", observed="2026-10-01T00:00:00Z", metadata="{bad")])
        self.assertEqual((result["counts"]["after_cutoff"], [i.id for i in corpus.items]), (1, ["a"]))


class CutoffTests(Fixture):
    def test_inclusive_cutoff_with_offset_and_nanoseconds(self):
        events = [event("at", observed="2026-09-01T00:00:00.000000001Z"),
                  event("before", observed="2026-09-01T00:00:00Z"),
                  event("after", observed="2026-09-01T00:00:00.000000002Z"),
                  event("offset-late", observed="2026-09-01T01:00:00+00:30")]
        corpus, result = self.corpus(events, cutoff="2026-09-01T02:00:00.000000001+02:00")
        self.assertEqual([i.id for i in corpus.items], ["before", "at"])
        self.assertEqual(corpus.cutoff, "2026-09-01T02:00:00.000000001+02:00")
        self.assertEqual(result["counts"]["after_cutoff"], 2)

    def test_malformed_observed_at_fails_closed(self):
        self.rejects_rows("invalid_observed_at", [event("a", observed="2026-09-01 00:00:00")])
        self.rejects_rows("invalid_observed_at", [event("a", observed="+12026-09-01T00:00:00Z")])
        self.rejects("invalid_observed_at", *self.snap(
            [event("a")], raw_sql=["UPDATE agent_events SET observed_at = 20260901"]))


class ExclusionAndTextTests(Fixture):
    def test_exclusions_remove_selected_events_and_must_match_selected_evidence(self):
        events = [event("a"), event("z-fix", "later fix"), event("late", observed="2026-10-02T00:00:00Z"),
                  event("other", metadata='{"repo":"/repos/other"}')]
        exclusions = [{"id": "z-fix", "reason": "post-checkpoint fix"}, {"id": "a", "reason": "duplicate"}]
        corpus, result = self.corpus(events, exclusions=exclusions)
        self.assertEqual([i.id for i in corpus.items], [])
        self.assertEqual(corpus.exclusions, sorted(exclusions, key=lambda e: e["id"]))
        self.assertEqual(result["counts"]["excluded"], 2)
        snapshot, digest = self.snap(events)
        for missing in ("typo", "late", "other"):
            with self.subTest(missing=missing):
                self.rejects("exclusion_not_selected", snapshot, digest, output="x-" + missing,
                             exclusions=[{"id": missing, "reason": "r"}])

    def test_null_text_requires_an_explicit_exclusion_and_is_never_invented(self):
        null_tool = event("n", None, tool_input_json='{"cmd":"payload"}')
        self.rejects_rows("null_text_not_excluded", [event("a", ""), null_tool])
        corpus, result = self.corpus([event("a", ""), null_tool], exclusions=[{"id": "n", "reason": "no text"}])
        self.assertEqual([(i.id, i.text) for i in corpus.items], [("a", "")])

    def test_non_text_cells_and_invalid_ids_fail_closed(self):
        self.rejects_rows("malformed_text", [event("a", sqlite3.Binary(b"blob"))])
        self.rejects("invalid_utf8_text", *self.snap(
            [event("a")], raw_sql=["UPDATE agent_events SET text = CAST(x'ff' AS TEXT)"]))
        self.rejects_rows("invalid_event_id", [event("bad id")])
        self.rejects_rows("malformed_event", [event("a", event_type=sqlite3.Binary(b"Handoff"))])


class LimitTests(Fixture):
    def test_text_limit_is_exact(self):
        corpus, _ = self.corpus([event("a", "x" * h.MAX_TEXT_BYTES), event("b", "é" * (h.MAX_TEXT_BYTES // 2))])
        self.assertEqual(len(corpus.items), 2)
        self.rejects_rows("text_too_large", [event("a", "x" * (h.MAX_TEXT_BYTES + 1))], output="over")

    def test_item_count_and_items_bytes_limits(self):
        many = [event("e%05d" % n, "") for n in range(h.MAX_ITEMS)]
        corpus, _ = self.corpus(many)
        self.assertEqual(len(corpus.items), h.MAX_ITEMS)
        self.rejects_rows("too_many_items", many + [event("e99999", "")], output="over")
        big = [event("b%03d" % n, "x" * h.MAX_TEXT_BYTES) for n in range(h.MAX_ITEMS_BYTES // h.MAX_TEXT_BYTES)]
        self.rejects_rows("items_too_large", big, output="big")

    def test_scan_and_runtime_bounds(self):
        snapshot, digest = self.snap([event("a"), event("b")])
        with mock.patch.object(b, "MAX_SCANNED_ROWS", 1):
            self.rejects("too_many_rows", snapshot, digest)
        with mock.patch.object(b, "DEADLINE_SECONDS", -1):
            self.rejects("build_timeout", snapshot, digest)


class SchemaTests(Fixture):
    def test_unsupported_or_ambiguous_schema_is_refused(self):
        base = ("CREATE TABLE agent_sessions (id TEXT, source TEXT, client_session_id TEXT, title TEXT, cwd TEXT, "
                "started_at TEXT, last_seen_at TEXT);")
        columns = "id TEXT, session_id TEXT, source TEXT, client_session_id TEXT, event_type TEXT, %s observed_at TEXT"
        cases = {
            "view": base + "CREATE TABLE raw (%s); CREATE VIEW agent_events AS SELECT * FROM raw;"
                    % (columns % "text TEXT, metadata_json TEXT,"),
            "missing": base + "CREATE TABLE agent_events (%s);" % (columns % "text TEXT,"),
            "generated": base + "CREATE TABLE agent_events (%s, text TEXT GENERATED ALWAYS AS (id) VIRTUAL);"
                         % (columns % "metadata_json TEXT,"),
        }
        for name, schema in cases.items():
            with self.subTest(name=name):
                self.rejects("unsupported_schema", *self.snapshot(self.source([], schema=schema)), output=name)
        duplicates = base + "CREATE TABLE agent_events (%s);" % (columns % "text TEXT, metadata_json TEXT,")
        self.rejects("duplicate_event_id", *self.snapshot(self.source([event("a"), event("a")], schema=duplicates)),
                     output="dup-event")
        self.rejects("ambiguous_session", *self.snapshot(self.source([], sessions=[SESSION, SESSION],
                                                                     schema=duplicates)), output="dup-session")


class SnapshotSafetyTests(Fixture):
    def test_hash_sidecars_wal_and_unsafe_sources(self):
        snapshot, digest = self.snap([event("a")])
        self.rejects("snapshot_hash_mismatch", snapshot, "0" * 64)
        for suffix in b.SIDECARS:
            sidecar = Path(str(snapshot) + suffix)
            sidecar.write_bytes(b"")
            self.rejects("sqlite_sidecar_present", snapshot, digest)
            sidecar.unlink()
        link = self.root / "link.sqlite"
        link.symlink_to(snapshot)
        self.rejects("unsafe_snapshot", link, digest)
        (self.root / "linkdir").symlink_to(self.root)
        self.rejects("unsafe_snapshot", self.root / "linkdir" / snapshot.name, digest)
        os.mkfifo(self.root / "fifo")
        self.rejects("unsafe_snapshot", self.root / "fifo", digest)
        self.rejects("unsafe_snapshot", self.root / "absent.sqlite", digest)
        text = self.root / "text.sqlite"
        text.write_bytes(b"not a database" * 10)
        self.rejects("invalid_snapshot", text, sha256(text))

        wal = self.root / "wal.sqlite"
        with sqlite3.connect(wal) as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        db.close()
        # Apple's SQLite may keep an empty persistent -wal; only the header must be refused here.
        for suffix in ("-wal", "-shm"):
            Path(str(wal) + suffix).unlink() if Path(str(wal) + suffix).exists() else None
        self.assertEqual(wal.read_bytes()[18:20], b"\x02\x02")
        self.rejects("wal_mode_snapshot", wal, sha256(wal))

    def test_source_mutation_during_copy_fails_closed(self):
        real = b._read_chunk

        def grow(path):
            with open(path, "ab") as handle:
                handle.write(b"\0" * 4096)

        def rewrite_copied_byte(path):
            # Same size, and the copy already holds the original byte: only source identity reveals it.
            with open(path, "r+b") as handle:
                handle.seek(100)
                original = handle.read(1)
                handle.seek(100)
                handle.write(bytes([original[0] ^ 1]))

        for change in (grow, rewrite_copied_byte):
            with self.subTest(change=change.__name__):
                snapshot, digest = self.snap([event("a")])
                calls = []

                def mutate(fd, size):
                    chunk = real(fd, size)
                    if not calls:
                        change(snapshot)
                    calls.append(size)
                    return chunk

                with mock.patch.object(b, "COPY_CHUNK", 4096), mock.patch.object(b, "_read_chunk", mutate):
                    self.rejects("snapshot_changed", snapshot, digest, output="out-" + change.__name__)


class ReviewRegressionTests(Fixture):
    """Root-reproduced NAT-325 review findings: FIFO input, staging ownership, deadline, cleanup."""

    def test_fifo_or_symlink_exclusions_fail_fast_and_valid_exclusions_are_kept(self):
        fifo = self.root / "exclusions.fifo"
        os.mkfifo(fifo)
        argv = self.args(self.root / "snap.sqlite", "a" * 64, execute=False)
        argv[argv.index("--exclusions") + 1] = str(fifo)
        # No writer ever opens the FIFO; a blocking open would hang until the timeout kills it.
        run = subprocess.run([sys.executable, str(BUILDER)] + argv, capture_output=True, text=True, timeout=20)
        self.assertEqual((run.returncode, json.loads(run.stdout), run.stderr),
                         (1, {"status": "failed", "error": "invalid_exclusions"}, ""))
        link = self.root / "exclusions-link.json"
        link.symlink_to(self.root / "exclusions-out.json")
        argv[argv.index("--exclusions") + 1] = str(link)
        stdout = io.StringIO()
        self.assertEqual(b.main(argv, stdout=stdout), 1)
        self.assertEqual(json.loads(stdout.getvalue())["error"], "invalid_exclusions")
        valid = self.args(self.root / "snap.sqlite", "a" * 64, execute=False,
                          exclusions=[{"id": "e1", "reason": "later fix"}, {"id": "e0", "reason": "dup"}])
        run = subprocess.run([sys.executable, str(BUILDER)] + valid, capture_output=True, text=True, timeout=20)
        self.assertEqual((run.returncode, json.loads(run.stdout)["exclusions"]), (0, 2))

    def test_staging_name_collision_preserves_the_foreign_directory(self):
        snapshot, digest = self.snap([event("a")])
        foreign = self.root / ".history-corpus-build-fixed"
        foreign.mkdir()
        (foreign / "sentinel").write_text("foreign", encoding="utf-8")
        with mock.patch.object(b.secrets, "token_hex", return_value="fixed"):
            self.assertEqual(self.build(snapshot, digest), (1, {"status": "failed", "error": "staging_collision"}))
        self.assertEqual((foreign / "sentinel").read_text(encoding="utf-8"), "foreign")
        self.assertEqual(os.listdir(foreign), ["sentinel"])
        self.assertFalse((self.root / "out").exists())

    def clock(self, **advance_after):
        """Fake monotonic clock that jumps 1000 s after the named builder step returns."""
        now = [1.0]
        patches = [mock.patch.object(b.time, "monotonic", lambda: now[0])]
        for name in advance_after:
            owner = b.h if name == "load_corpus" else b
            real = getattr(owner, name)

            def wrapped(*args, _real=real, **kwargs):
                result = _real(*args, **kwargs)
                now[0] += 1000
                return result
            patches.append(mock.patch.object(owner, name, wrapped))
        return patches

    def test_deadline_expiry_after_render_or_self_check_publishes_nothing(self):
        snapshot, digest = self.snap([event("a")])
        for step in ("render", "load_corpus"):
            with self.subTest(step=step):
                patches = self.clock(**{step: True})
                for patch in patches:
                    patch.start()
                try:
                    self.rejects("build_timeout", snapshot, digest, output="out-" + step)
                finally:
                    for patch in reversed(patches):
                        patch.stop()

    def test_deadline_expiry_between_items_and_manifest_links_rolls_back(self):
        snapshot, digest = self.snap([event("a")])
        now = [1.0]
        real_link = os.link

        def link(src, dst, **kwargs):
            real_link(src, dst, **kwargs)
            now[0] += 1000

        with mock.patch.object(b.time, "monotonic", lambda: now[0]), mock.patch.object(b.os, "link", link):
            self.rejects("build_timeout", snapshot, digest)

    def failing(self, call, name, times=None):
        """os.<call> raising EIO for one fixed staged name (all calls, or only the first `times`)."""
        real = getattr(os, call)
        failures = [0]

        def fake(target, *args, **kwargs):
            if target == name and (times is None or failures[0] < times):
                failures[0] += 1
                raise OSError(errno.EIO, "injected")
            return real(target, *args, **kwargs)
        return mock.patch.object(b.os, call, fake)

    def staging_dirs(self):
        return [self.root / name for name in self.leftovers()]

    def test_snapshot_copy_removal_failure_never_publishes(self):
        snapshot, digest = self.snap([event("a")])
        with self.failing("unlink", b.SNAPSHOT_COPY):
            code, result = self.build(snapshot, digest)
        self.assertEqual((code, result), (1, {"status": "failed", "error": "cleanup_failed", "published": False,
                                              "staging_removed": False}))
        self.assertFalse((self.root / "out").exists())
        [staging] = self.staging_dirs()
        self.assertEqual(os.listdir(staging), [b.SNAPSHOT_COPY])
        with self.failing("unlink", b.SNAPSHOT_COPY, times=1):
            code, result = self.build(snapshot, digest, output="retry")
        self.assertEqual(result, {"status": "failed", "error": "cleanup_failed", "published": False,
                                  "staging_removed": True})
        self.assertFalse((self.root / "retry").exists())
        self.assertEqual(self.staging_dirs(), [staging])

    def test_failure_plus_cleanup_failure_keeps_the_cause(self):
        snapshot, _ = self.snap([event("a")])
        with self.failing("unlink", b.SNAPSHOT_COPY):
            code, result = self.build(snapshot, "0" * 64)
        self.assertEqual(result, {"status": "failed", "error": "cleanup_failed", "published": False,
                                  "staging_removed": False, "cause": "snapshot_hash_mismatch"})
        self.assertFalse((self.root / "out").exists())

    def test_cleanup_failure_after_publication_is_reported_honestly(self):
        snapshot, digest = self.snap([event("a")])
        for call, name, residue in (("unlink", b.ITEMS_FILE, [b.ITEMS_FILE]), ("rmdir", None, [])):
            with self.subTest(call=call):
                for staging in self.staging_dirs():
                    for child in staging.iterdir():
                        child.unlink()
                    staging.rmdir()
                output = "out-" + call
                if name is None:
                    real = os.rmdir

                    def fake(target, *args, **kwargs):
                        if str(target).startswith(".history-corpus-build-"):
                            raise OSError(errno.EACCES, "injected")
                        return real(target, *args, **kwargs)
                    patch = mock.patch.object(b.os, "rmdir", fake)
                else:
                    patch = self.failing(call, name)
                with patch:
                    code, result = self.build(snapshot, digest, output=output)
                self.assertEqual((code, result), (1, {"status": "failed", "error": "cleanup_failed",
                                                      "published": True, "staging_removed": False}))
                [staging] = self.staging_dirs()
                self.assertEqual(sorted(os.listdir(staging)), residue)
                self.assertNotIn(b.SNAPSHOT_COPY, os.listdir(staging))
                manifest = self.root / output / b.MANIFEST_FILE
                self.assertEqual(len(h.load_corpus(manifest, sha256(manifest)).items), 1)


class SidecarAliasTests(Fixture):
    """Output must never create the snapshot's own name or a reserved SQLite sidecar name."""

    def reserved(self, snapshot):
        keys = [b.filename_key(snapshot.name + suffix) for suffix in ("",) + b.SIDECARS]
        return sorted(name for name in os.listdir(snapshot.parent) if b.filename_key(name) in keys)

    def test_cli_refuses_case_variant_sidecar_before_any_mutation(self):
        snapshot, digest = self.snap([event("a")])
        before = (snapshot.read_bytes(), sorted(os.listdir(self.root)))
        for suffix in ("-WAL", "-Shm", "-JOURNAL"):
            with self.subTest(suffix=suffix):
                output = snapshot.name + suffix
                run = subprocess.run([sys.executable, str(BUILDER)] + self.args(snapshot, digest, output=output),
                                     capture_output=True, text=True, timeout=60)
                self.assertEqual((run.returncode, json.loads(run.stdout)),
                                 (1, {"status": "failed", "error": "output_is_sidecar"}))
                self.assertEqual(self.reserved(snapshot), [snapshot.name])
                self.assertEqual(self.leftovers(), [])
        self.assertEqual(snapshot.read_bytes(), before[0])
        self.assertEqual(sorted(set(os.listdir(self.root)) - set(before[1])),
                         sorted("exclusions-%s-%s.json" % (snapshot.name, s) for s in ("WAL", "Shm", "JOURNAL")))

    def test_plan_names_use_canonical_casefold_but_not_compatibility_folding(self):
        cases = [
            ("snap.sqlite", "SNAP.SQLITE-wal", "output_is_sidecar"),
            ("snap.sqlite", "Snap.Sqlite", "output_is_snapshot"),
            ("café.sqlite", "café.sqlite-shm", "output_is_sidecar"),
            ("café.sqlite", "CAFÉ.SQLITE-JOURNAL", "output_is_sidecar"),
            ("straße.sqlite", "STRASSE.sqlite-wal", "output_is_sidecar"),
            ("snap.sqlite", "ｓnap.sqlite-wal", None),  # fullwidth s: compatibility-distinct
            ("snap.sqlite", "snap.sqlite-wal2", None),
            ("snap.sqlite", "snap.sqlite.wal", None),
        ]
        for source, output, code in cases:
            with self.subTest(source=source, output=output):
                stdout = io.StringIO()
                argv = self.args(self.root / source, "a" * 64, output=output, execute=False)
                b.main(argv, stdout=stdout)
                result = json.loads(stdout.getvalue())
                self.assertEqual(result.get("error"), code)

    def test_parent_identity_catches_differently_spelled_same_directory(self):
        data = self.root / "data"
        (data / "x").mkdir(parents=True)
        other = self.root / "other"
        other.mkdir()
        snapshot = data / "x" / ".." / "snap.sqlite"  # same parent, different spelling
        for parent, output_name, expected in ((data, "SNAP.sqlite-wal", "output_is_sidecar"),
                                              (data, "snap.SQLITE", "output_is_snapshot"),
                                              (data, "snap.sqlite-wal2", None),
                                              (other, "snap.sqlite-wal", None)):
            with self.subTest(parent=parent.name, output=output_name):
                fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    if expected is None:
                        b.check_output_parent(snapshot, parent / output_name, fd)
                    else:
                        with self.assertRaises(b.BuildError) as caught:
                            b.check_output_parent(snapshot, parent / output_name, fd)
                        self.assertEqual(caught.exception.code, expected)
                finally:
                    os.close(fd)

    def test_case_alias_parent_on_this_filesystem(self):
        snapshot, digest = self.snap([event("a")])
        moved = self.root / "Data"
        moved.mkdir()
        snapshot = snapshot.rename(moved / snapshot.name)
        alias = self.root / "DATA"
        if alias.exists():  # case-insensitive filesystem: the alias spelling resolves to the same directory
            self.rejects("output_is_sidecar", snapshot, digest, output="DATA/" + snapshot.name.upper() + "-WAL")
            self.assertEqual(self.reserved(snapshot), [snapshot.name])
        else:
            self.rejects("unsafe_output_parent", snapshot, digest, output="DATA/" + snapshot.name + "-wal")
        code, result = self.build(snapshot, digest, output="elsewhere-" + snapshot.name + "-wal")
        self.assertEqual((code, result["status"]), (0, "complete"))
        self.assertEqual(self.reserved(snapshot), [snapshot.name])


class PublicationRollbackTests(Fixture):
    """A failed publication either rolls back completely or says exactly what remains."""

    def on_output(self, call, fail, output="out"):
        """Patch os.<call> to raise EIO when it targets the output directory and fail(name) is true."""
        real = getattr(os, call)
        root = self.root / output

        def is_output(fd):
            try:
                info, target = os.fstat(fd), os.stat(root)
            except OSError:
                return False
            return (info.st_dev, info.st_ino) == (target.st_dev, target.st_ino)

        def fake(*args, **kwargs):
            fd = args[0] if call == "fsync" else kwargs.get("dir_fd")
            name = None if call == "fsync" else args[0]
            if call == "rmdir":
                hit = name == output and fail(name)
            else:
                hit = fd is not None and is_output(fd) and fail(name)
            if hit:
                raise OSError(errno.EIO, "injected")
            return real(*args, **kwargs)
        return mock.patch.object(b.os, call, fake)

    def build_with(self, *patches, output="out"):
        snapshot, digest = self.snap([event("a"), event("b", "second")])
        for patch in patches:
            patch.start()
        try:
            return self.build(snapshot, digest, output=output)
        finally:
            for patch in reversed(patches):
                patch.stop()

    def test_fsync_failure_with_complete_rollback_reports_the_original_error(self):
        result = self.build_with(self.on_output("fsync", lambda name: True))
        self.assertEqual(result, (1, {"status": "failed", "error": "build_failed"}))
        self.assertFalse((self.root / "out").exists())
        self.assertEqual(self.leftovers(), [])

    def test_fsync_then_rollback_unlink_failure_reports_the_published_corpus(self):
        code, result = self.build_with(self.on_output("fsync", lambda name: True),
                                       self.on_output("unlink", lambda name: True))
        self.assertEqual((code, result), (1, {
            "status": "failed", "error": "cleanup_failed", "cause": "build_failed", "published": True,
            "staging_removed": True, "output_removed": False,
            "output_entries": [b.ITEMS_FILE, b.MANIFEST_FILE]}))
        manifest = self.root / "out" / b.MANIFEST_FILE
        self.assertEqual(len(h.load_corpus(manifest, sha256(manifest)).items), 2)
        self.assertEqual(self.leftovers(), [])

    def test_partial_rollback_states_are_exact(self):
        cases = [
            ("manifest-left", [self.on_output("unlink", lambda name: name == b.MANIFEST_FILE, "manifest-left")],
             {"published": False, "output_removed": False, "output_entries": [b.MANIFEST_FILE]}),
            ("dir-left", [self.on_output("rmdir", lambda name: True, "dir-left")],
             {"published": False, "output_removed": False, "output_entries": []}),
        ]
        for output, patches, state in cases:
            with self.subTest(output=output):
                code, result = self.build_with(self.on_output("fsync", lambda name: True, output), *patches,
                                               output=output)
                expected = {"status": "failed", "error": "cleanup_failed", "cause": "build_failed",
                            "staging_removed": True, **state}
                self.assertEqual((code, result), (1, expected))
                self.assertEqual(sorted(os.listdir(self.root / output)), state["output_entries"])
                self.assertEqual(self.leftovers(), [])


class OutputTests(Fixture):
    def test_existing_or_unsafe_output_is_never_touched(self):
        snapshot, digest = self.snap([event("a")])
        (self.root / "out").mkdir()
        (self.root / "out" / "foreign.txt").write_text("keep", encoding="utf-8")
        code, result = self.build(snapshot, digest)
        self.assertEqual(result, {"status": "failed", "error": "output_exists"})
        self.assertEqual(os.listdir(self.root / "out"), ["foreign.txt"])
        (self.root / "real-parent").mkdir()
        (self.root / "alias").symlink_to(self.root / "real-parent")
        self.rejects("unsafe_output_parent", snapshot, digest, output="alias/out")
        self.rejects("unsafe_output_parent", snapshot, digest, output="missing/out")
        self.assertEqual(os.listdir(self.root / "real-parent"), [])

    def test_failures_publish_nothing_and_preserve_foreign_files(self):
        snapshot, digest = self.snap([event("a")])
        real_link = os.link

        def fail_manifest(src, dst, **kwargs):
            if dst == b.MANIFEST_FILE:
                raise OSError("disk full")
            return real_link(src, dst, **kwargs)

        with mock.patch.object(b.os, "link", fail_manifest):
            self.rejects("build_failed", snapshot, digest)

        def foreign_then_fail(src, dst, **kwargs):
            if dst == b.MANIFEST_FILE:
                (self.root / "out" / "foreign.txt").write_text("keep", encoding="utf-8")
                raise KeyboardInterrupt
            return real_link(src, dst, **kwargs)

        with mock.patch.object(b.os, "link", foreign_then_fail):
            self.assertEqual(self.build(snapshot, digest), (1, {"status": "failed", "error": "interrupted"}))
        self.assertEqual(os.listdir(self.root / "out"), ["foreign.txt"])
        self.assertEqual(self.leftovers(), [])

        with mock.patch.object(b.h, "load_corpus", side_effect=h.CorpusError("x", "y")):
            self.rejects("corpus_self_check_failed", snapshot, digest, output="self-check")

    def test_errors_never_echo_row_text(self):
        snapshot, digest = self.snap([event("a", None, tool_input_json="TOPSECRET"), event("b", "TOPSECRET text")])
        run = subprocess.run([sys.executable, str(BUILDER)] + self.args(snapshot, digest),
                             capture_output=True, text=True)
        self.assertEqual((run.returncode, run.stderr), (1, ""))
        self.assertEqual(json.loads(run.stdout), {"status": "failed", "error": "null_text_not_excluded"})
        self.assertNotIn("TOPSECRET", run.stdout)


if __name__ == "__main__":
    unittest.main()
