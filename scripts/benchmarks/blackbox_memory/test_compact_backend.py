import base64
import hashlib
import json
import random
import tempfile
import unittest
import uuid
from pathlib import Path

import compact_backend as c
import history_search as h
import test_history_search as lit

PROJECT = "/blackbox-compact-corpus"


def _b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


class FakeCanonicalApi:
    """In-process model of GET /api/search/compact?mode=canonical over stored event rows.

    Rows: dicts with id, session_id, client_session_id, cwd, text, tool_name, metadata_json and
    observed_at (stored Instant text). Mirrors the NAT-320 contract closely enough to exercise the
    adapter: AND of case-sensitive literal terms over text/tool name/metadata JSON, inclusive cutoff,
    (observed, id) descending keyset pages, byte fitting that may return fewer than limit hits.
    `fault(page_number, status, page)` may rewrite any response for failure tests.
    """

    def __init__(self, rows, fault=None, excerpt_marker=None):
        self.rows = rows
        self.fault = fault
        self.excerpt_marker = excerpt_marker
        self.requests = []

    @staticmethod
    def error(status):
        return {"status": status, "mode": "canonical", "count": 0, "items": [], "appliedFilters": {},
                "hasMore": False, "nextBefore": None, "budgetLimited": False, "excerptsTruncated": False,
                "limit": 50, "maxBytes": 64000, "diagnostics": ["fake error"]}

    def __call__(self, params):
        self.requests.append(list(params))
        status, page = self.handle(params)
        if self.fault is not None:
            changed = self.fault(len(self.requests), status, page)
            if changed is not None:
                status, page = changed
        body = page if isinstance(page, bytes) else json.dumps(
            page, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        return status, body

    def handle(self, params):
        values = {}
        for key, value in params:
            values.setdefault(key, []).append(value)
        if values.get("mode") != ["canonical"] or any(len(v) > 1 for k, v in values.items() if k != "term"):
            return 400, self.error("invalid_request")
        terms = sorted(set(values.get("term", [])), key=c.utf16_key)
        limit, budget = int(values["limit"][0]), int(values["maxBytes"][0])
        project = values.get("projectExact", [None])[0]
        until = c.parse_instant(values["until"][0])
        before = values.get("before", [None])[0]
        fingerprint = hashlib.sha256(json.dumps([terms, project, until]).encode()).hexdigest()[:16]
        position = None
        if before is not None:
            decoded = json.loads(base64.urlsafe_b64decode(before + "=" * (-len(before) % 4)))
            if decoded["f"] != fingerprint:
                return 400, self.error("cursor_mismatch")
            position = (decoded["k"], decoded["i"])
        matched = []
        for row in self.rows:
            ns = c.parse_instant(row["observed_at"])
            haystacks = (row["text"] or "", row["tool_name"] or "", row["metadata_json"] or "")
            if ns > until or (project is not None and row["cwd"].rstrip("/") != project):
                continue
            if not all(any(term in hay for hay in haystacks) for term in terms):
                continue
            if position is not None and not (ns, row["id"]) < position:
                continue
            matched.append((ns, row))
        matched.sort(key=lambda pair: (pair[0], pair[1]["id"]), reverse=True)
        rows = matched[:limit + 1]
        delivered = rows[:limit]
        filters = {"terms": terms, "projectExact": project, "until": c.java_instant_text(until)}
        hits = [self.hit(row) for _, row in delivered]

        def page(kept, limited):
            more = len(rows) > limit or len(kept) < len(hits)
            cursor = None
            if more and kept:
                ns, row = delivered[len(kept) - 1]
                cursor = _b64(json.dumps({"f": fingerprint, "k": ns, "i": row["id"]}).encode())
            return {"status": "ok", "mode": "canonical", "count": len(kept), "items": list(kept),
                    "appliedFilters": filters, "hasMore": more, "nextBefore": cursor, "budgetLimited": limited,
                    "excerptsTruncated": any(k["excerptTruncated"] for k in kept), "limit": limit,
                    "maxBytes": budget, "diagnostics": ["Literal case-sensitive matches."]}

        def size(value):
            return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))

        kept = list(hits)
        result = page(kept, False)
        while size(result) > budget and len(kept) > 1:
            kept.pop()
            result = page(kept, True)
        if size(result) > budget:
            return 400, self.error("budget_exceeded")
        return 200, result

    def hit(self, row):
        text = row["text"]
        excerpt = None if text is None else (self.excerpt_marker or text[:600])
        return {"eventId": row["id"], "sessionId": row["session_id"], "clientSessionId": row["client_session_id"],
                "source": "blackbox-compact-corpus", "eventType": "CorpusItem", "role": None,
                "observedAt": row["observed_at"], "excerpt": excerpt,
                "excerptTruncated": text is not None and len(text) > 600, "backends": ["local"],
                "provenance": "unknown", "sourceReference": {"status": "recorded_event"},
                "similarEventIds": [], "similarCount": 1, "similarMembersTruncated": False}


def build(corpus, seed=0):
    """Rows and verified index the way the server owner would record them (random UUID event IDs)."""
    rng = random.Random(seed)
    rows, entries, sessions = [], {}, {}
    for item in corpus.items:
        pair = (item.project, item.session)
        if pair not in sessions:
            sessions[pair] = (str(uuid.UUID(int=rng.getrandbits(128))), "corpus-%d" % len(sessions))
        session_id, client = sessions[pair]
        event_id = str(uuid.UUID(int=rng.getrandbits(128)))
        metadata = json.dumps({"sourceRef": item.source_ref}, ensure_ascii=False, separators=(",", ":"))
        rows.append({"id": event_id, "session_id": session_id, "client_session_id": client, "cwd": PROJECT,
                     "text": None if c.java_blank(item.text) else item.text, "tool_name": None,
                     "metadata_json": metadata, "observed_at": c.java_instant_text(item.observed_ns)})
        entries[event_id] = c.IndexEntry(item, session_id, client, metadata)
    return rows, c.CompactIndex(corpus, PROJECT, entries)


class CompactFixture(lit.CorpusFixture):
    def compact(self, items, seed=0, fault=None, excerpt_marker=None, **overrides):
        corpus = self.corpus(items, **overrides)
        rows, index = build(corpus, seed)
        api = FakeCanonicalApi(rows, fault, excerpt_marker)
        session = h.Session(corpus, c.CompactCanonicalBackend(api, index))
        first = lit.decode(session.start())
        return session, first, api


class TimeAndTermTests(unittest.TestCase):
    def test_instant_text_matches_java_vectors(self):
        cases = {0: "1970-01-01T00:00:00Z", 1: "1970-01-01T00:00:00.000000001Z", 1000: "1970-01-01T00:00:00.000001Z",
                 5 * 10 ** 8: "1970-01-01T00:00:00.500Z", -1: "1969-12-31T23:59:59.999999999Z",
                 1790000000 * 10 ** 9 + 10: "2026-09-21T14:13:20.000000010Z"}
        for ns, text in cases.items():
            with self.subTest(ns=ns):
                self.assertEqual(c.java_instant_text(ns), text)
                self.assertEqual(c.parse_instant(text), ns)
                self.assertEqual(c.parse_instant(c.utc_text(ns)), ns)
        # Offsets can normalize past Python's datetime range; both ends stay exact integers.
        low = h.parse_time("0001-01-01T00:00:00+23:59", "t")
        high = h.parse_time("9999-12-31T23:59:59.999999999-23:59", "t")
        self.assertEqual(c.java_instant_text(low), "0000-12-31T00:01:00Z")
        self.assertEqual(c.java_instant_text(high), "+10000-01-01T23:58:59.999999999Z")
        self.assertEqual((c.parse_instant(c.java_instant_text(low)), c.parse_instant(c.utc_text(high))), (low, high))
        for bad in ("2026-02-30T00:00:00Z", "10000-01-01T00:00:00Z", "+9999-01-01T00:00:00Z", "-0000-01-01T00:00:00Z",
                    "2026-01-01T00:00:00+00:00", "2026-01-01T24:00:00Z", "2026-01-01T00:00:00.1234567891Z", 5, None):
            with self.subTest(bad=bad):
                self.assertIsNone(c.parse_instant(bad))
        self.assertEqual(c.parse_instant("2024-02-29T00:00:00Z"), 1709164800 * 10 ** 9)
        self.assertEqual(c.canonical_instant("1970-01-01T00:00:00.500Z"), 5 * 10 ** 8)
        for spelled in ("1970-01-01T00:00:00.5Z", "1970-01-01T00:00:00.000000000Z", "-00001-01-01T00:00:00Z"):
            with self.subTest(spelled=spelled):
                self.assertIsNone(c.canonical_instant(spelled))

    def test_java_blank_and_utf16_order(self):
        self.assertTrue(all(map(c.java_blank, ("", " ", "\t\n\r\x0b\x0c\x1c\x1f", " 　"))))
        self.assertFalse(any(map(c.java_blank, ("\xa0", " ", "\x85", "\x00", "a"))))
        self.assertEqual(sorted(["￿", "\U00010000", "b"], key=c.utf16_key), ["b", "\U00010000", "￿"])

    def test_terms_are_raw_case_tokens_validated_by_the_frozen_parser(self):
        self.assertEqual(c.query_terms("Alpha  alpha\tAlpha 50%_off"),
                         (["Alpha", "alpha", "Alpha", "50%_off"], ["Alpha", "alpha", "50%_off"]))
        for bad in ("", "   ", "a\x01", "x" * 513, " ".join("t%d" % i for i in range(17)), 7, "\ud800"):
            with self.subTest(bad=bad):
                self.assertIsNone(c.query_terms(bad))
        widest = " ".join(["é" * 15] * 15 + ["y" * 47])  # 16 tokens, exactly 512 UTF-8 bytes.
        self.assertEqual(len(widest.encode()), 512)
        self.assertEqual(len(c.query_terms(widest)[0]), 16)
        self.assertEqual(c.query_terms("界" * 170)[1], ["界" * 170])


class CompactSearchTests(CompactFixture):
    def test_complete_pagination_over_ties_is_deterministic_across_event_ids(self):
        tie = "2026-09-20T00:00:00.000000001Z"
        items = [lit.item("t%03d" % n, "needle tie %d" % n, observed=tie) for n in range(150)]
        items += [lit.item("o%03d" % n, "needle old %d" % n, observed="2026-09-%02dT00:00:00Z" % (1 + n % 19))
                  for n in range(80)]
        outputs = []
        for seed in (1, 2):
            session, _, api = self.compact(items, seed=seed)
            payload = session.search("needle")
            outputs.append(payload)
            body = lit.decode(payload)
            shown = [r["id"] for r in body["results"]]
            self.assertEqual((body["total_matches"], body["omitted_results"]), (230, 230 - len(shown)))
            self.assertEqual(shown, ["t%03d" % n for n in range(len(shown))])
            self.assertGreater(len(shown), 10)
            self.assertEqual(len(api.requests), 5)
            self.assertEqual(session.backend.host_log[-1], {"terms": 1, "pages": 5, "matches": 230})
        self.assertEqual(outputs[0], outputs[1])

    def test_byte_short_pages_still_enumerate_every_match(self):
        items = [lit.item("m%03d" % n, "needle " + "😀" * 700, observed="2026-09-01T00:00:%02d.%dZ" % (n % 60, n))
                 for n in range(120)]
        session, _, api = self.compact(items)
        body = lit.decode(session.search("needle"))
        self.assertEqual(body["total_matches"], 120)
        pages = session.backend.host_log[-1]["pages"]
        self.assertGreater(pages, 120 // c.PAGE_LIMIT + 1)  # Fewer than 50 hits fit per page.
        self.assertEqual(len(api.requests), pages)
        cursors = [dict(r).get("before") for r in api.requests]
        self.assertEqual(len(set(cursors)), pages)

    def test_case_sensitive_and_terms_punctuation_and_metadata_matches(self):
        items = [lit.item("a", 'Alpha beta 50%_off "quoted" {brace}'), lit.item("b", "alpha beta"),
                 lit.item("c", "Alpha only", ref='src/Main.java "q"'), lit.item("d", "beta Alpha", ref="x")]
        session, _, api = self.compact(items)
        body = lit.decode(session.search("Alpha beta"))
        self.assertEqual(sorted(r["id"] for r in body["results"]), ["a", "d"])
        self.assertEqual(body["query_terms"], ["Alpha", "beta"])
        self.assertEqual([p for p in api.requests[-1] if p[0] == "term"], [("term", "Alpha"), ("term", "beta")])
        self.assertEqual(lit.decode(session.search('50%_off "quoted"'))["total_matches"], 1)
        self.assertEqual(lit.decode(session.search("50XYoff"))["total_matches"], 0)
        # Stored metadata JSON is searchable: the key, braces and JSON escapes match natively.
        everyone = lit.decode(session.search("sourceRef"))
        self.assertEqual(everyone["total_matches"], 4)
        self.assertTrue(all(r["match"] == {"exact": False, "terms": ["sourceRef"], "fields": ["metadata"],
                                           "anchor": None} for r in everyone["results"]))
        escaped = lit.decode(session.search('\\"q\\"'))
        self.assertEqual([r["id"] for r in escaped["results"]], ["c"])
        both = lit.decode(session.search("Main.java Alpha"))
        self.assertEqual(both["results"][0]["match"]["fields"], ["text", "metadata"])

    def test_match_details_exact_phrase_anchor_and_original_provenance(self):
        text = "pad " * 50 + "Export  Writer then Export Writer"
        session, _, _ = self.compact([lit.item("a", text, observed="2026-09-01T00:00:00.123456789+02:00",
                                               recorded_at="2026-09-02T00:00:00Z")], excerpt_marker="API-EXCERPT")
        body = lit.decode(session.search("Export Writer"))
        result = body["results"][0]
        start = text.index("Export Writer")
        self.assertEqual(result["match"], {"exact": True, "terms": ["Export", "Writer"], "fields": ["text"],
                                           "anchor": [start, start + 13]})
        self.assertEqual(result["observed_at"], "2026-09-01T00:00:00.123456789+02:00")
        self.assertEqual(result["recorded_at"], "2026-09-02T00:00:00Z")
        self.assertEqual(result["excerpt"]["text"], text[start - 100:])
        self.assertNotIn(b"API-EXCERPT", session.search("Writer Export"))
        loose = lit.decode(session.search("Writer Export"))["results"][0]["match"]
        self.assertEqual((loose["exact"], loose["anchor"]), (False, [text.index("Export"), text.index("Export") + 6]))

    def test_session_budgets_and_wire_invariants_stay_with_the_session(self):
        items = [lit.item("n%02d" % i, ("界😀 needle " * 300) + str(i), observed="2026-09-%02dT00:00:00Z" % (i + 1))
                 for i in range(20)] + [lit.item("h", "交" * 5000, kind="handoff")]
        session, first, _ = self.compact(items)
        self.assertEqual(first["backend"], c.BACKEND)
        payloads = [session.search("needle") for _ in range(6)]
        delivered = [p for p in payloads if p]
        self.assertTrue(all(len(p) <= h.MAX_DELIVERY_BYTES for p in delivered))
        record = session.accounting()
        self.assertLessEqual(record["delivered_bytes"], h.MAX_TOTAL_BYTES)
        self.assertEqual(record["close_reason"], "byte_budget")
        self.assertNotIn("infrastructure_error", record)

    def test_invalid_queries_are_charged_without_any_request(self):
        session, _, api = self.compact([lit.item("a", "alpha")])
        body = lit.decode(session.search("bad\x01query"))
        self.assertEqual((body["status"], body["error"]["code"]), ("error", "invalid_query"))
        self.assertEqual(api.requests, [])

    def test_literal_golden_is_unchanged(self):
        data = lit.golden_trace()
        self.assertEqual((hashlib.sha256(data).hexdigest(), len(data)),
                         (lit.GOLDEN_LITERAL_SHA256, lit.GOLDEN_LITERAL_BYTES))


def _swap(page):
    page["items"][0], page["items"][1] = page["items"][1], page["items"][0]


class CompactFailureTests(CompactFixture):
    ITEMS = [lit.item("x%03d" % n, "needle %d" % n, observed="2026-09-%02dT00:00:00Z" % (1 + n % 28))
             for n in range(120)]

    def fails(self, expected, fault=None, query="needle", fetch=None, clock=None):
        session, _, api = self.compact(self.ITEMS, fault=fault)
        if fetch is not None:
            session.backend.fetch = fetch
        if clock is not None:
            session.backend.clock = clock
        before = (session.delivered, list(session.log))
        self.assertIsNone(session.search(query))
        self.assertIsNone(session.search(query))
        self.assertIsNone(session.handle_line(b'{"query":"needle"}'))
        record = session.accounting()
        self.assertEqual((session.delivered, session.log), before)
        self.assertEqual((record["closed"], record["close_reason"], record["terminal_emitted"]),
                         (True, "infrastructure_error", False))
        self.assertEqual(record["infrastructure_error"]["code"], expected)
        self.assertNotIn("needle", json.dumps(record["infrastructure_error"]))
        return api

    def test_every_response_doubt_closes_without_model_bytes(self):
        def on(number, change):
            def fault(n, status, page):
                if n == number:
                    result = change(page)
                    return result if result is not None else (status, page)
                return None
            return fault

        def edit(fn):
            def change(page):
                fn(page)
            return change

        cases = [  # A list: every case runs, even when expectations repeat.
            ("api_cursor_unavailable", on(2, lambda p: (400, FakeCanonicalApi.error("cursor_unavailable")))),
            ("api_budget_exceeded", on(1, lambda p: (400, FakeCanonicalApi.error("budget_exceeded")))),
            ("http_status", on(1, lambda p: (500, b"<html>"))),
            ("invalid_response", on(1, lambda p: (200, b"not json"))),
            ("response_too_large", on(1, lambda p: (200, b" " * (c.PAGE_MAX_BYTES + 1)))),
            ("filter_mismatch", on(1, edit(lambda p: p["appliedFilters"].update(projectExact="/other")))),
            ("unknown_event", on(2, edit(lambda p: p["items"][0].update(eventId=str(uuid.uuid4()))))),
            ("scope_mismatch", on(1, edit(lambda p: p["items"][3].update(sessionId="other")))),
            ("timestamp_mismatch", on(1, edit(lambda p: p["items"][0].update(
                observedAt=p["items"][0]["observedAt"][:-1] + ".000000001Z")))),
            ("order_violation", on(1, edit(_swap))),
            ("incomplete_matches", on(1, edit(lambda p: (p["items"].pop(10), p.update(count=49))))),
            ("invalid_response", on(1, edit(lambda p: p.update(limit=50.0)))),
            ("timestamp_mismatch", on(1, edit(lambda p: p["items"][0].update(
                observedAt=p["items"][0]["observedAt"][:-1] + ".0Z")))),
        ]
        self.assertEqual(len(cases), 13)
        for expected, fault in cases:
            with self.subTest(expected=expected):
                self.fails(expected, fault)

    def test_shape_and_cursor_faults(self):
        def mutate(number, fn):
            def fault(n, status, page):
                if n == number:
                    fn(page)
                return None
            return fault

        state = {}

        def remember(n, status, page):
            if n == 1:
                state["cursor"] = page["nextBefore"]
            if n == 2:
                page["nextBefore"] = state["cursor"]
            return None

        def duplicate(n, status, page):
            if n == 1:
                state["last"] = dict(page["items"][-1])
            if n == 2:
                page["items"][0] = state["last"]
            return None

        cases = [
            ("invalid_response", mutate(1, lambda p: p.update(extra=1))),
            ("invalid_response", mutate(1, lambda p: p.pop("diagnostics"))),
            ("invalid_response", mutate(1, lambda p: p.update(mode="legacy"))),
            ("invalid_response", mutate(1, lambda p: p.update(limit=10))),
            ("invalid_response", mutate(1, lambda p: p.update(count=49))),
            ("invalid_response", mutate(1, lambda p: p.update(hasMore=1))),
            ("invalid_response", mutate(1, lambda p: p.update(nextBefore=None))),
            ("invalid_response", mutate(3, lambda p: p.update(nextBefore="stale"))),
            ("invalid_response", mutate(1, lambda p: p["items"][0].pop("excerpt"))),
            ("filter_mismatch", mutate(1, lambda p: p["appliedFilters"]["terms"].append("x"))),
            ("filter_mismatch", mutate(1, lambda p: p["appliedFilters"].update(until="2026-09-30T12:00:00.5Z"))),
            ("empty_progress", mutate(2, lambda p: p.update(items=[], count=0))),
            ("cursor_invalid", remember),
            ("cursor_invalid", mutate(1, lambda p: p.update(nextBefore="c" * 1025))),
            ("order_violation", duplicate),
        ]
        for expected, fault in cases:
            with self.subTest(expected=expected):
                self.fails(expected, fault)

    def test_duplicate_json_keys_and_non_finite_numbers_are_rejected(self):
        for raw in (b'{"status":"ok","status":"ok"}', b'{"n": NaN}'):
            with self.subTest(raw=raw):
                self.fails("invalid_response", lambda n, s, p, raw=raw: (200, raw))

    def test_transport_failures_deadline_and_unsupported_hits(self):
        def timeout(params):
            raise TimeoutError("fixture did not answer")

        def refused(params):
            raise h.BackendFailure("listener_changed", "owned listener changed")

        self.fails("transport_error", fetch=timeout)
        self.fails("transport_error", fetch=lambda params: None)
        self.fails("listener_changed", fetch=refused)
        ticks = iter(range(0, 10 ** 6, 200))
        self.fails("deadline", clock=lambda: next(ticks))

        def substitute(old, new):  # Server answers another query but echoes the requested filters.
            def fetch(params):
                status, body = api([(k, new if v == old else v) for k, v in params])
                page = json.loads(body)
                page["appliedFilters"]["terms"] = sorted({v for k, v in params if k == "term"}, key=c.utf16_key)
                return status, json.dumps(page).encode()
            return fetch
        for old, new, code in (("7", "needle", "incomplete_matches"), ("7", "8", "unsupported_hit")):
            with self.subTest(code=code):
                session, _, api = self.compact(self.ITEMS)
                session.backend.fetch = substitute(old, new)
                self.assertIsNone(session.search("needle 7"))  # 21 matches for "7"; also 21 for "8".
                self.assertEqual(session.accounting()["infrastructure_error"]["code"], code)

    def test_terminal_page_returning_after_the_deadline_is_not_delivered(self):
        now = [0.0]
        session, _, api = self.compact([lit.item("a", "alpha")])
        session.backend.clock = lambda: now[0]

        def slow(params):
            result = api(params)  # A valid, exhausted page ...
            now[0] += session.backend.deadline_seconds + 1  # ... that arrives after the deadline.
            return result
        session.backend.fetch = slow
        before = (session.delivered, list(session.log))
        self.assertIsNone(session.search("alpha"))
        self.assertEqual((session.delivered, session.log), before)
        self.assertEqual(session.accounting()["infrastructure_error"]["code"], "deadline")

    def test_deadline_crossed_during_final_matching_is_not_delivered(self):
        now = [0.0]
        session, _, api = self.compact(self.ITEMS)
        backend = session.backend
        backend.clock = lambda: now[0]
        original = backend._match

        def slow_match(*args):  # Pages were valid and complete; local finishing work runs late.
            now[0] = backend.deadline_seconds + 1
            return original(*args)
        backend._match = slow_match
        before = (session.delivered, list(session.log))
        self.assertIsNone(session.search("needle"))
        self.assertEqual((session.delivered, session.log), before)
        record = session.accounting()
        self.assertEqual((record["close_reason"], record["infrastructure_error"]["code"]),
                         ("infrastructure_error", "deadline"))
        self.assertIsNone(session.search("needle"))

    def test_corpus_identity_and_unexpected_errors(self):
        session, _, _ = self.compact([lit.item("a", "alpha")])
        other = self.corpus([lit.item("a", "alpha")])
        with self.assertRaises(h.BackendFailure) as caught:
            session.backend.search(other, "alpha")
        self.assertEqual(caught.exception.code, "corpus_mismatch")
        session.backend.index = None
        self.assertIsNone(session.search("alpha"))
        self.assertEqual(session.accounting()["infrastructure_error"], {"code": "backend_error",
                                                                        "message": "AttributeError"})

    def test_index_must_cover_every_item_once(self):
        corpus = self.corpus([lit.item("a", "alpha"), lit.item("b", "beta")])
        _, index = build(corpus)
        first = dict(list(index.entries.items())[:1])
        with self.assertRaises(ValueError):
            c.CompactIndex(corpus, PROJECT, first)
        with self.assertRaises(ValueError):
            c.CompactIndex(corpus, "", index.entries)


if __name__ == "__main__":
    unittest.main()
