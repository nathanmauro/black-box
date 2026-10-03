import hashlib
import json
from pathlib import Path
import string
import subprocess
import sys
import tempfile
import unittest

import history_search as h

SCRIPT = Path(__file__).with_name("history_search.py")
CUTOFF = "2026-09-30T12:00:00.5Z"


def item(item_id, text, observed="2026-09-01T00:00:00Z", kind="message", session="s1", ref=None, **extra):
    return {"id": item_id, "kind": kind, "project": "/repos/demo", "session": session,
            "observed_at": observed, "source_ref": ref or f"transcript:{session}#{item_id}", "text": text, **extra}


def write_corpus(root, items, items_bytes=None, **overrides):
    """Write a synthetic frozen corpus; returns (manifest path, manifest SHA-256)."""
    root = Path(root)
    data = items_bytes if items_bytes is not None else json.dumps(
        {"schema": h.ITEMS_SCHEMA, "items": items}, ensure_ascii=False).encode("utf-8")
    (root / "items.json").write_bytes(data)
    manifest = {"schema": h.MANIFEST_SCHEMA, "corpus_id": "synthetic-demo", "provenance": "synthetic-development",
                "scope": {"project": "/repos/demo", "sessions": ["s1", "s2"]}, "cutoff": CUTOFF,
                "items_file": "items.json", "items_sha256": hashlib.sha256(data).hexdigest(),
                "item_count": len(items), "exclusions": [{"id": "later-fix", "reason": "post-checkpoint fix discussion"}]}
    manifest.update(overrides)
    raw = json.dumps(manifest).encode("utf-8")
    path = root / "manifest.json"
    path.write_bytes(raw)
    return path, hashlib.sha256(raw).hexdigest()


def compact(count):
    """Smallest-metadata text matches: twenty fit one delivery, so only the result ceiling omits."""
    items = [dict(item(c, "x", ref="y", session="s"), project="/p") for c in string.ascii_letters[:count]]
    return items, {"scope": {"project": "/p", "sessions": ["s"]}}


def decode(payload):
    text = payload.decode("utf-8")
    assert text.endswith("\n") and text.count("\n") == 1
    return json.loads(text)


class CorpusFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def corpus(self, items, **overrides):
        return h.load_corpus(*write_corpus(self.root, items, **overrides))

    def session(self, items, **overrides):
        session = h.Session(self.corpus(items, **overrides))
        first = decode(session.start())
        return session, first

    def rejects(self, code, items=(), items_bytes=None, sha=None, **overrides):
        path, digest = write_corpus(self.root, list(items), items_bytes, **overrides)
        with self.assertRaises(h.CorpusError) as caught:
            h.load_corpus(path, sha or digest)
        self.assertEqual(caught.exception.code, code)


class CorpusValidationTests(CorpusFixture):
    def test_hash_tampering_and_selection_fail_closed(self):
        path, digest = write_corpus(self.root, [item("a", "alpha")])
        self.rejects("manifest_hash_mismatch", [item("a", "alpha")], sha="0" * 64)
        self.rejects("invalid_expected_hash", [item("a", "alpha")], sha=digest.upper())
        data = json.dumps({"schema": h.ITEMS_SCHEMA, "items": [item("a", "alpha")]}).encode()
        (self.root / "items.json").write_bytes(data.replace(b"alpha", b"alphb"))
        with self.assertRaises(h.CorpusError) as caught:
            h.load_corpus(path, digest)
        self.assertEqual(caught.exception.code, "items_hash_mismatch")
        self.rejects("invalid_schema", [item("a", "x")], items_file="../items.json")
        self.rejects("missing_file", [item("a", "x")], items_file="absent.json")

    def test_scope_cutoff_exclusions_count_and_duplicates(self):
        self.rejects("out_of_scope", [dict(item("a", "x"), project="/repos/other")])
        self.rejects("out_of_scope", [item("a", "x", session="s3")])
        self.rejects("after_cutoff", [item("a", "x", observed="2026-09-30T12:00:00.500000001Z")])
        self.rejects("after_cutoff", [item("a", "x", observed="2026-09-30T08:00:01-04:00")])
        self.rejects("after_cutoff", [item("a", "x", recorded_at="2026-10-01T00:00:00Z")])
        self.rejects("excluded_item_present", [item("later-fix", "the reference patch")])
        self.rejects("item_count_mismatch", [item("a", "x")], item_count=2)
        self.rejects("duplicate_item_id", [item("a", "same"), item("a", "same", session="s2")])
        self.assertEqual(len(self.corpus([item("a", "x", observed="2026-09-30T12:00:00.500000000Z")]).items), 1)

    def test_pre_epoch_dates_and_explicit_epoch_zero(self):
        old = {"cutoff": "1969-01-01T00:00:00Z"}
        corpus = self.corpus([item("pre", "x", kind="handoff", observed="1968-01-01T00:00:00Z")], **old)
        self.assertEqual(corpus.handoff.id, "pre")
        self.rejects("after_cutoff", [item("a", "x", observed="1968-01-01T00:00:00Z",
                                           recorded_at="1970-01-01T00:00:00Z")], **old)
        self.rejects("after_cutoff", [item("a", "x", observed="1969-01-01T00:00:00.000000001Z")], **old)
        epoch = {"cutoff": "1970-01-01T00:00:00Z"}
        self.assertEqual(len(self.corpus([item("a", "x", observed="1970-01-01T00:00:00Z",
                                               recorded_at="1970-01-01T00:00:00Z")], **epoch).items), 1)
        self.rejects("after_cutoff", [item("a", "x", observed="1969-12-31T23:59:59Z",
                                           recorded_at="1970-01-01T00:00:00.000000001Z")], **epoch)
        self.assertEqual(h.parse_time("1970-01-01T00:00:00Z", "t"), 0)
        self.assertEqual(h.parse_time("1969-12-31T23:59:59.9Z", "t"), -10 ** 8)
        self.assertLess(h.parse_time("1969-12-31T23:59:59.1Z", "t"), h.parse_time("1969-12-31T23:59:59.9Z", "t"))
        newest = self.corpus([item("h-a", "a", kind="handoff", observed="1968-06-01T00:00:00.000000001Z"),
                              item("h-b", "b", kind="handoff", observed="1968-06-01T00:00:00Z")], **old)
        self.assertEqual(newest.handoff.id, "h-a")

    def test_schema_utf8_json_and_bounds(self):
        good = json.dumps({"schema": h.ITEMS_SCHEMA, "items": [item("a", "x")]}).encode()
        self.rejects("invalid_utf8", [item("a", "x")], items_bytes=good.replace(b'"x"', b'"\xff"'))
        self.rejects("invalid_utf8", [item("a", "x")], items_bytes=good.replace(b'"x"', b'"\\ud800"'))
        self.rejects("invalid_json", [item("a", "x")], items_bytes=good.replace(b'"text"', b'"kind": "message", "text"'))
        self.rejects("invalid_json", [item("a", "x")], items_bytes=good[:-1])
        self.rejects("invalid_json", [], items_bytes=b'{"schema": "x", "items": ' + b"[" * 5000 + b"]" * 5000 + b"}")
        self.rejects("invalid_json", [item("a", "x")], items_bytes=good.replace(b'"items"', b'"n": NaN, "items"'))
        self.rejects("invalid_schema", [dict(item("a", "x"), extra=1)])
        self.rejects("invalid_schema", [item("a", "x", kind="summary")])
        self.rejects("invalid_schema", [item("a b", "x")])
        self.rejects("invalid_schema", [item("a", "x", ref="file\x07.py")])
        self.rejects("too_large", [item("a", "x" * (h.MAX_TEXT_BYTES + 1))])
        self.rejects("invalid_schema", [item("a", "x")], item_count=True)
        self.rejects("invalid_schema", [item("a", "x")], scope={"project": "/repos/demo", "sessions": ["s1", "s1"]})
        for bad in ("2026-09-01T00:00:00", "2026-09-01T00:00:00.1234567890Z", "2026-02-30T00:00:00Z",
                    "2026-09-01T23:59:60Z", "2026-09-01T00:00:00+24:00", "２０２６-09-01T00:00:00Z"):
            with self.subTest(timestamp=bad):
                self.rejects("invalid_timestamp", [item("a", "x", observed=bad)])
        big = self.root / "big.json"
        big.write_bytes(b" " * (h.MAX_MANIFEST_BYTES + 1))
        with self.assertRaises(h.CorpusError) as caught:
            h.load_corpus(big, hashlib.sha256(big.read_bytes()).hexdigest())
        self.assertEqual(caught.exception.code, "too_large")

    def test_fractional_and_offset_ordering_is_exact(self):
        self.assertLess(h.parse_time("2026-09-01T00:00:00.09Z", "t"), h.parse_time("2026-09-01T00:00:00.1Z", "t"))
        self.assertLess(h.parse_time("2026-09-01T00:00:00.000000001Z", "t"),
                        h.parse_time("2026-09-01T00:00:00.000000002Z", "t"))
        self.assertEqual(h.parse_time("2026-09-01T01:00:00.5+01:00", "t"), h.parse_time("2026-09-01T00:00:00.500Z", "t"))
        corpus = self.corpus([
            item("h-a", "older by a nanosecond", kind="handoff", observed="2026-09-01T00:00:00.123456788Z"),
            item("h-b", "newest", kind="handoff", observed="2026-09-01T00:00:00.123456789Z"),
            item("h-c", "rounds equal at microseconds", kind="handoff", observed="2026-09-01T00:00:00.1234567Z")])
        self.assertEqual(corpus.handoff.id, "h-b")
        tie = self.corpus([item("h-z", "z", kind="handoff", observed="2026-09-02T00:00:00Z"),
                           item("h-y", "y", kind="handoff", observed="2026-09-01T20:00:00-04:00")])
        self.assertEqual(tie.handoff.id, "h-y")


class DeliveryTests(CorpusFixture):
    def test_no_history_differs_from_no_handoff(self):
        _, empty = self.session([])
        self.assertEqual((empty["status"], empty["results"]), ("no_history", []))
        session, messages = self.session([item("m", "a message without any handoff")])
        self.assertEqual((messages["status"], messages["results"]), ("no_handoff", []))
        self.assertEqual(decode(session.search("message"))["results"][0]["id"], "m")

    def test_handoff_delivered_once_before_search_with_provenance(self):
        session = h.Session(self.corpus([item("h", "resume at the parser", kind="handoff", recorded_at="2026-09-02T00:00:00Z")]))
        with self.assertRaises(RuntimeError):
            session.search("parser")
        first = decode(session.start())
        with self.assertRaises(RuntimeError):
            session.start()
        result = first["results"][0]
        self.assertEqual((first["type"], first["status"], first["attempt"], first["attempts_remaining"]),
                         ("handoff", "ok", None, 6))
        self.assertEqual({k: result[k] for k in ("id", "kind", "session", "observed_at", "recorded_at", "source_ref")},
                         {"id": "h", "kind": "handoff", "session": "s1", "observed_at": "2026-09-01T00:00:00Z",
                          "recorded_at": "2026-09-02T00:00:00Z", "source_ref": "transcript:s1#h"})
        self.assertEqual(result["excerpt"]["text"], "resume at the parser")
        self.assertEqual(session.attempts, 0)

    def test_large_handoff_is_clipped_at_code_points_and_charged(self):
        text = "交接" * 3000 + "😀" * 500
        session, first = self.session([item("h", text, kind="handoff")])
        excerpt = first["results"][0]["excerpt"]
        self.assertTrue(first["truncated"] and excerpt["budget_clipped"])
        self.assertEqual(excerpt["text"], text[excerpt["start"]:excerpt["end"]])
        self.assertLessEqual(session.delivered, h.MAX_DELIVERY_BYTES)
        self.assertGreater(session.delivered, h.MAX_DELIVERY_BYTES - 4)
        self.assertEqual(h.MAX_TOTAL_BYTES - session.remaining, session.delivered)

    def test_ranking_exact_then_terms_then_recency_then_id(self):
        session, _ = self.session([
            item("terms-two-old", "cursor and page handled", observed="2026-09-01T00:00:00Z"),
            item("one-term-new", "only cursor here", observed="2026-09-03T00:00:00Z"),
            item("exact-old", "the empty page cursor bug", observed="2026-08-01T00:00:00Z"),
            item("terms-two-b", "page then cursor", observed="2026-09-02T00:00:00Z"),
            item("terms-two-a", "page and cursor", observed="2026-09-02T00:00:00Z"),
            item("unrelated", "nothing relevant")])
        first = decode(session.search("PAGE   Cursor"))
        self.assertEqual(first["query_terms"], ["page", "cursor"])
        self.assertEqual([r["id"] for r in first["results"]],
                         ["exact-old", "terms-two-a", "terms-two-b", "terms-two-old", "one-term-new"])
        self.assertEqual(first["results"][0]["match"]["exact"], True)
        self.assertEqual(first["total_matches"], 5)
        second = decode(session.search("page cursor"))
        self.assertEqual(second["results"], first["results"])

    def test_filename_and_identifier_queries_stay_whole(self):
        session, _ = self.session([
            item("ref-only", "Patched the writer.", ref="src/export/SummaryWriter.java"),
            item("ident", "Tracked as NAT-315 in the plan."),
            item("split", "NAT and 315 separately; SummaryWriter mentioned.")])
        by_file = decode(session.search("src/export/summarywriter.java"))
        self.assertEqual([r["id"] for r in by_file["results"]], ["ref-only"])
        self.assertEqual(by_file["results"][0]["match"]["fields"], ["source_ref"])
        self.assertIsNone(by_file["results"][0]["match"]["anchor"])
        by_id = decode(session.search("nat-315"))
        self.assertEqual([r["id"] for r in by_id["results"]], ["ident"])
        anchor = by_id["results"][0]["match"]["anchor"]
        self.assertEqual("Tracked as NAT-315 in the plan."[anchor[0]:anchor[1]], "NAT-315")

    def test_casefold_expansion_keeps_original_offsets(self):
        text = "Die Straße führt nach İstanbul; ﬁnal ǅ check."
        session, _ = self.session([item("u", text)])
        for query, expected in (("STRASSE", "Straße"), ("final", "ﬁnal"), ("i̇stanbul", "İstanbul"),
                                ("ǆ", "ǅ"), ("trass", "traß"), ("ss", "ß")):
            with self.subTest(query=query):
                result = decode(session.search(query))["results"][0]
                start, end = result["match"]["anchor"]
                self.assertEqual(text[start:end], expected)
                self.assertEqual(result["excerpt"]["text"], text[result["excerpt"]["start"]:result["excerpt"]["end"]])
                if session.attempts == h.MAX_SEARCHES:
                    break

    def test_multi_term_phrase_spans_original_whitespace(self):
        text = "x" * 300 + " retry\n\t  BUDGET exceeded"
        session, _ = self.session([item("w", text)])
        result = decode(session.search("retry budget"))["results"][0]
        start, end = result["match"]["anchor"]
        self.assertEqual(text[start:end], "retry\n\t  BUDGET")
        self.assertEqual(result["excerpt"]["start"], start - h.EXCERPT_BEFORE)

    def test_identical_text_with_distinct_provenance_and_stale_evidence(self):
        session, _ = self.session([
            item("old", "Use the global event id.", observed="2026-08-01T00:00:00Z", session="s1"),
            item("copy", "Use the global event id.", observed="2026-08-01T00:00:00Z", session="s2",
                 ref="transcript:s2#copy"),
            item("new", "Event id is tenant-scoped now; the global event id rule is stale.",
                 observed="2026-09-20T00:00:00Z")])
        results = decode(session.search("global event id"))["results"]
        self.assertEqual([(r["id"], r["session"], r["observed_at"]) for r in results],
                         [("new", "s1", "2026-09-20T00:00:00Z"), ("copy", "s2", "2026-08-01T00:00:00Z"),
                          ("old", "s1", "2026-08-01T00:00:00Z")])


class BudgetTests(CorpusFixture):
    def test_repeated_queries_empty_results_and_errors_are_charged(self):
        session, _ = self.session([item("a", "alpha beta")])
        bodies = []
        for request in (b'{"query": "alpha"}', b'{"query": "alpha"}', b'{"query": "zeta"}', b"not json",
                        b'{"query": "  "}', b'{"query": "alpha", "extra": 1}'):
            before = session.delivered
            payload = session.handle_line(request)
            self.assertEqual(session.delivered - before, len(payload))
            bodies.append(decode(payload))
        statuses = [entry["status"] for entry in session.log[1:]]
        self.assertEqual(statuses, ["ok", "ok", "empty", "error", "error", "error"])
        self.assertEqual(bodies[0]["results"], bodies[1]["results"])
        self.assertEqual((bodies[0]["attempt"], bodies[1]["attempt"]), (1, 2))
        self.assertEqual(sum(entry["bytes"] for entry in session.log), session.delivered)
        self.assertEqual(session.attempts, 6)

    def test_result_ceiling_is_disclosed_as_omitted(self):
        for count, results, omitted in ((20, 20, 0), (25, 20, 5)):
            with self.subTest(count=count):
                items, overrides = compact(count)
                session, _ = self.session(items, **overrides)
                body = decode(session.search("x"))
                self.assertEqual((body["total_matches"], len(body["results"]), body["omitted_results"]),
                                 (count, results, omitted))
                self.assertEqual(body["truncated"], omitted > 0)
                self.assertFalse(any(r["excerpt"]["budget_clipped"] for r in body["results"]))
                self.assertEqual([r["id"] for r in body["results"]], sorted(i["id"] for i in items)[:20])

    def test_attempt_limit_counts_invalid_requests_then_closes(self):
        session, _ = self.session([item("a", "alpha")])
        codes = []
        for raw in (b"\xff", b"[" * 3000 + b"]" * 1000, b"[]", b'{"query": 7}', b'{"query": "a\\u0000b"}',
                    b'{"query": "' + b"x " * 17 + b'"}'):
            codes.append(decode(session.handle_line(raw))["error"]["code"])
        self.assertEqual(codes, ["invalid_utf8", "invalid_json", "invalid_request", "invalid_query",
                                 "invalid_query", "invalid_query"])
        terminal = decode(session.handle_line(b'{"query": "alpha"}'))
        self.assertEqual((terminal["type"], terminal["status"], terminal["error"]["code"]),
                         ("terminal", "exhausted", "attempt_limit"))
        self.assertIsNone(session.handle_line(b'{"query": "alpha"}'))
        self.assertIsNone(session.search("alpha"))
        self.assertEqual((session.attempts, session.closed, session.close_reason, len(session.log)),
                         (6, True, "attempt_limit", 8))

    def test_request_bounds(self):
        session, _ = self.session([item("a", "alpha")])
        self.assertEqual(decode(session.handle_line(b"x" * (h.MAX_LINE_BYTES + 1)))["error"]["code"], "request_too_large")
        long_query = json.dumps({"query": "é" * 257}).encode()
        self.assertEqual(decode(session.handle_line(long_query))["error"]["code"], "invalid_query")
        self.assertEqual(decode(session.handle_line(b'{"query": "a\\ud800"}'))["error"]["code"], "invalid_query")
        self.assertEqual(decode(session.handle_line(b'{"query": "a", "query": "b"}'))["error"]["code"], "invalid_json")
        ok = decode(session.handle_line(json.dumps({"query": " ".join("t%d" % i for i in range(16))}).encode()))
        self.assertEqual(ok["status"], "empty")

    def test_per_delivery_and_total_ceilings_with_multibyte_clipping(self):
        items = [item("big-%02d" % i, ("ü😀界 needle " * 80) + str(i), observed="2026-09-%02dT00:00:00Z" % (i + 1))
                 for i in range(25)] + [item("h", "长" * 4000, kind="handoff")]
        session, first = self.session(items)
        payloads = [session.search(q) for q in ("needle", "needle 3", "界", "needle", "ü", "😀")]
        emitted = [p for p in payloads if p is not None]
        for payload in emitted:
            body = decode(payload)
            self.assertLessEqual(len(payload), h.MAX_DELIVERY_BYTES)
            self.assertTrue(body["truncated"])
            self.assertGreater(body["omitted_results"], 0)
            for result in body["results"]:
                original = next(i["text"] for i in items if i["id"] == result["id"])
                self.assertEqual(result["excerpt"]["text"], original[result["excerpt"]["start"]:result["excerpt"]["end"]])
        self.assertEqual(len(emitted), 3)
        self.assertLessEqual(session.delivered, h.MAX_TOTAL_BYTES)
        self.assertEqual(session.delivered, sum(e["bytes"] for e in session.log))
        self.assertEqual((session.closed, session.close_reason, session.terminal_emitted), (True, "byte_budget", False))
        self.assertEqual(session.attempts, 4)

    def test_terminal_or_silent_close_near_end_of_budget(self):
        outcomes = set()
        query = " ".join("absentterm%02d" % i for i in range(16))
        for remaining in range(0, 1200, 7):
            session, _ = self.session([item("a", "alpha")])
            session.delivered = h.MAX_TOTAL_BYTES - remaining
            payload = session.search(query)
            if payload is None:
                outcome = "silent"
                self.assertTrue(session.closed and not session.terminal_emitted)
            else:
                body = decode(payload)
                outcome = body["status"]
                self.assertLessEqual(len(payload), remaining)
                if outcome == "exhausted":
                    self.assertEqual(body["error"]["code"], "byte_budget")
                    self.assertTrue(session.closed)
                    self.assertIsNone(session.search("alpha"))
            self.assertLessEqual(session.delivered, h.MAX_TOTAL_BYTES)
            outcomes.add(outcome)
        self.assertEqual(outcomes, {"silent", "exhausted", "empty"})


class CliJourneyTests(CorpusFixture):
    def run_cli(self, args, lines=(), raw=None):
        stdin = raw if raw is not None else b"".join(line + b"\n" for line in lines)
        done = subprocess.run([sys.executable, "-B", str(SCRIPT)] + args, input=stdin, capture_output=True, timeout=60)
        return done.returncode, done.stdout, done.stderr.decode("utf-8")

    def serve(self, items=None, lines=(), raw=None, path=None, sha=None):
        if path is None:
            path, sha = write_corpus(self.root, items)
        return self.run_cli(["serve", "--manifest", str(path), "--manifest-sha256", sha], lines, raw)

    def check_stream(self, code, stdout, stderr):
        self.assertEqual(code, 0)
        lines = stdout.split(b"\n")
        self.assertEqual(lines[-1], b"")
        bodies = [json.loads(line.decode("utf-8")) for line in lines[:-1]]
        sizes = [len(line) + 1 for line in lines[:-1]]
        accounting = json.loads(stderr.strip().splitlines()[-1])["host_accounting"]
        self.assertEqual(sum(sizes), len(stdout))
        self.assertEqual(accounting["delivered_bytes"], len(stdout))
        self.assertEqual([d["bytes"] for d in accounting["deliveries"]], sizes)
        self.assertTrue(all(size <= h.MAX_DELIVERY_BYTES for size in sizes))
        self.assertLessEqual(len(stdout), h.MAX_TOTAL_BYTES)
        self.assertEqual([b["seq"] for b in bodies], list(range(len(bodies))))
        for body in bodies:
            if body["type"] == "search" and body["status"] in ("ok", "empty"):
                self.assertEqual(len(body["results"]) + body["omitted_results"], body["total_matches"])
                self.assertLessEqual(len(body["results"]), h.MAX_RESULTS)
                self.assertEqual(body["truncated"], body["omitted_results"] > 0 or any(
                    r["excerpt"]["budget_clipped"] for r in body["results"]))
        return bodies, accounting

    def test_cli_rejects_corpus_failures_without_stdout(self):
        cases = (("manifest_hash_mismatch", [item("a", "x")], "f" * 64),
                 ("after_cutoff", [item("a", "x", observed="2026-10-01T00:00:00Z")], None),
                 ("out_of_scope", [item("a", "x", session="other")], None))
        for expected, items, sha in cases:
            with self.subTest(expected=expected):
                path, digest = write_corpus(self.root, items)
                code, stdout, stderr = self.serve(path=path, sha=sha or digest, lines=[b'{"query": "x"}'])
                self.assertEqual((code, stdout), (2, b""))
                self.assertEqual(json.loads(stderr)["controller_error"]["code"], expected)

    def test_cli_validate_and_start_statuses(self):
        path, digest = write_corpus(self.root, [item("h", "handoff", kind="handoff")])
        code, stdout, _ = self.run_cli(["validate", "--manifest", str(path), "--manifest-sha256", digest])
        self.assertEqual((code, json.loads(stdout)["handoff_id"]), (0, "h"))
        for items, status in (([], "no_history"), ([item("m", "message")], "no_handoff")):
            bodies, accounting = self.check_stream(*self.serve(items, [b'{"query": "message"}']))
            self.assertEqual(bodies[0]["status"], status)
            self.assertEqual(accounting["start_status"], status)
            self.assertEqual(bodies[1]["status"], "empty" if not items else "ok")

    def test_cli_journey_mixed_queries_then_attempt_limit(self):
        items = [item("h-1", "Resume: export writer still drops symlinked notes.", kind="handoff",
                      observed="2026-09-29T10:00:00.25Z"),
                 item("h-0", "Older handoff, superseded.", kind="handoff", observed="2026-09-29T10:00:00.2499Z"),
                 item("e-1", "Failure in Straße fixture for NAT-315.", ref="src/export/SummaryWriter.java",
                      observed="2026-09-29T09:00:00Z"),
                 item("e-2", "Failure in Straße fixture for NAT-315.", session="s2", ref="transcript:s2#e-2",
                      observed="2026-09-29T09:00:00Z")]
        lines = [b'{"query": "SummaryWriter.java"}', b'{"query": "strasse nat-315"}', b'{"query": "strasse nat-315"}',
                 b'{"query": "missing-term"}', b"{bad json", b"x" * 5000, b'{"query": "seventh request"}',
                 b'{"query": "unread after close"}']
        bodies, accounting = self.check_stream(*self.serve(items, lines))
        self.assertEqual(bodies[0]["results"][0]["id"], "h-1")
        self.assertEqual([r["id"] for r in bodies[1]["results"]], ["e-1"])
        self.assertEqual([r["id"] for r in bodies[2]["results"]], ["e-1", "e-2"])
        self.assertEqual(bodies[3]["results"], bodies[2]["results"])
        self.assertNotEqual(bodies[2]["attempt"], bodies[3]["attempt"])
        self.assertEqual([b["status"] for b in bodies[4:]], ["empty", "error", "error", "exhausted"])
        self.assertEqual(bodies[6]["error"]["code"], "request_too_large")
        self.assertEqual(bodies[7]["error"]["code"], "attempt_limit")
        self.assertEqual(len(bodies), 8)
        self.assertEqual((accounting["search_attempts"], accounting["close_reason"]), (6, "attempt_limit"))

    def test_cli_byte_budget_journey_and_unicode_boundaries(self):
        items = [item("n%02d" % i, ("界😀ß needle " * 120) + str(i), observed="2026-09-%02dT00:00:00.%dZ" % (i + 1, i))
                 for i in range(20)] + [item("h", "交" * 5000, kind="handoff")]
        lines = [b'{"query": "needle"}'] * 6
        bodies, accounting = self.check_stream(*self.serve(items, lines))
        self.assertEqual([b["type"] for b in bodies], ["handoff", "search", "search", "search"])
        self.assertTrue(all(b["truncated"] for b in bodies))
        self.assertGreater(accounting["delivered_bytes"], h.MAX_TOTAL_BYTES - 16)
        self.assertEqual((accounting["closed"], accounting["close_reason"], accounting["terminal_emitted"]),
                         (True, "byte_budget", False))
        self.assertEqual(accounting["search_attempts"], 4)
        by_id = {i["id"]: i["text"] for i in items}
        for body in bodies:
            for result in body["results"]:
                excerpt = result["excerpt"]
                self.assertEqual(excerpt["text"], by_id[result["id"]][excerpt["start"]:excerpt["end"]])

    def test_cli_pre_epoch_corpus_serves(self):
        path, digest = write_corpus(self.root, [item("pre", "before the epoch", kind="handoff",
                                                     observed="1968-01-01T00:00:00Z")], cutoff="1969-01-01T00:00:00Z")
        bodies, accounting = self.check_stream(*self.serve(path=path, sha=digest, lines=[b'{"query": "epoch"}']))
        self.assertEqual((bodies[0]["status"], bodies[0]["results"][0]["observed_at"]), ("ok", "1968-01-01T00:00:00Z"))
        self.assertEqual([r["id"] for r in bodies[1]["results"]], ["pre"])
        self.assertEqual(accounting["start_status"], "ok")

    def test_cli_result_ceiling_and_byte_omissions_are_disclosed(self):
        items, overrides = compact(25)
        path, digest = write_corpus(self.root, items, **overrides)
        bodies, _ = self.check_stream(*self.serve(path=path, sha=digest, lines=[b'{"query": "x"}']))
        self.assertEqual((len(bodies[1]["results"]), bodies[1]["omitted_results"], bodies[1]["truncated"]), (20, 5, True))
        self.assertFalse(any(r["excerpt"]["budget_clipped"] for r in bodies[1]["results"]))
        root_repro = [item("m%d" % i, "", ref="x") for i in range(31)]
        bodies, _ = self.check_stream(*self.serve(root_repro, [b'{"query": "x"}']))
        search = bodies[1]
        self.assertEqual((search["total_matches"], len(search["results"]), search["omitted_results"]), (31, 19, 12))
        self.assertEqual([r["id"] for r in search["results"]], sorted("m%d" % i for i in range(31))[:len(search["results"])])

    def test_cli_crlf_and_trailing_line_without_newline(self):
        raw = b'{"query": "alpha"}\r\n{"query": "alpha"}'
        bodies, accounting = self.check_stream(*self.serve([item("a", "alpha")], raw=raw))
        self.assertEqual([b["status"] for b in bodies[1:]], ["ok", "ok"])
        self.assertEqual(accounting["search_attempts"], 2)


if __name__ == "__main__":
    unittest.main()
