#!/usr/bin/env python3
"""Canonical compact-search backend for the offline history_search session (NAT-319).

Development infrastructure. It serves the frozen history_search delivery contract from
GET /api/search/compact?mode=canonical over a corpus that a private, controller-owned server holds.
It never opens a URL itself: the controller supplies a fetch callable bound to that private server
and a verified event-ID -> corpus-item mapping (see compact_server.py). Any doubt about a response
raises BackendFailure, so the session closes without another model delivery. No efficacy claim.
"""

import json
import re
import time
import unicodedata

import history_search as h

BACKEND = "compact-canonical-v1"
PAGE_LIMIT = 50
PAGE_MAX_BYTES = 64000
MAX_API_TERMS = 16
MAX_API_TERM_CODE_POINTS = 512
MAX_API_TERM_BYTES = 2048
MAX_CURSOR_CHARS = 1024
SEARCH_DEADLINE_SECONDS = 300.0

PAGE_KEYS = frozenset(("status", "mode", "count", "items", "appliedFilters", "hasMore", "nextBefore",
                       "budgetLimited", "excerptsTruncated", "limit", "maxBytes", "diagnostics"))
HIT_KEYS = frozenset(("eventId", "sessionId", "clientSessionId", "source", "eventType", "role", "observedAt",
                      "excerpt", "excerptTruncated", "backends", "provenance", "sourceReference",
                      "similarEventIds", "similarCount", "similarMembersTruncated"))
STATUS_RE = re.compile(r"[a-z_]{1,40}\Z")
INSTANT_RE = re.compile(r"([+-]?)([0-9]{4,9})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})"
                        r"(?:\.([0-9]{1,9}))?Z\Z")
NS = 10 ** 9
DAY_NS = 86400 * NS
# Character.isWhitespace: Unicode space separators except no-break spaces, plus these controls.
_JAVA_SPACE_CONTROLS = frozenset("\t\n\x0b\x0c\r\x1c\x1d\x1e\x1f")
_JAVA_NO_BREAK = frozenset("   ")


def days_from_civil(year, month, day):
    """Proleptic Gregorian day number relative to 1970-01-01, exact for any integer year."""
    year -= month <= 2
    era = year // 400
    yoe = year - era * 400
    doy = (153 * (month + (-3 if month > 2 else 9)) + 2) // 5 + day - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def civil_from_days(days):
    days += 719468
    era = days // 146097
    doe = days - era * 146097
    yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
    doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
    mp = (5 * doy + 2) // 153
    day = doy - (153 * mp + 2) // 5 + 1
    month = mp + (3 if mp < 10 else -9)
    return yoe + era * 400 + (month <= 2), month, day


def _fields(ns):
    days, rest = divmod(ns, DAY_NS)
    year, month, day = civil_from_days(days)
    seconds, nanos = divmod(rest, NS)
    return year, month, day, seconds // 3600, seconds // 60 % 60, seconds % 60, nanos


def _year(year):
    if year > 9999:
        return "+%d" % year
    return ("-%04d" % -year) if year < 0 else "%04d" % year


def utc_text(ns):
    """Request form: UTC with exactly nine fractional digits; integer arithmetic, never float."""
    year, month, day, hour, minute, second, nanos = _fields(ns)
    return "%s-%02d-%02dT%02d:%02d:%02d.%09dZ" % (_year(year), month, day, hour, minute, second, nanos)


def java_instant_text(ns):
    """Exactly java.time.Instant.toString(): fraction omitted, else 3, 6 or 9 digits."""
    year, month, day, hour, minute, second, nanos = _fields(ns)
    text = "%s-%02d-%02dT%02d:%02d:%02d" % (_year(year), month, day, hour, minute, second)
    if nanos:
        digits = "%09d" % nanos
        text += "." + (digits[:3] if nanos % 10 ** 6 == 0 else digits[:6] if nanos % 1000 == 0 else digits)
    return text + "Z"


def parse_instant(value):
    """Exact UTC nanoseconds of an Instant-style string (Z only), or None when malformed."""
    match = INSTANT_RE.match(value) if isinstance(value, str) else None
    if not match:
        return None
    sign, digits = match.group(1), match.group(2)
    # Instant.toString(): four unsigned digits for 0..9999, "+" only above 9999, "-" below zero.
    if (sign == "" and len(digits) != 4) or (sign == "+" and (len(digits) < 5 or digits[0] == "0")) \
            or (sign == "-" and int(digits) == 0):
        return None
    year = -int(digits) if sign == "-" else int(digits)
    month, day, hour, minute, second = (int(match.group(n)) for n in range(3, 8))
    if not (1 <= month <= 12 and 1 <= day and hour < 24 and minute < 60 and second < 60):
        return None
    if day > days_from_civil(year + (month == 12), month % 12 + 1, 1) - days_from_civil(year, month, 1):
        return None
    fraction = int((match.group(8) or "").ljust(9, "0"))
    return (days_from_civil(year, month, day) * 86400 + hour * 3600 + minute * 60 + second) * NS + fraction


def canonical_instant(value):
    """Nanoseconds only for the exact Instant.toString() spelling the server stores and returns."""
    ns = parse_instant(value)
    return ns if ns is not None and value == java_instant_text(ns) else None


def java_blank(text):
    """String.isBlank(): empty or only Character.isWhitespace code points."""
    return all(c in _JAVA_SPACE_CONTROLS
               or (unicodedata.category(c) in ("Zs", "Zl", "Zp") and c not in _JAVA_NO_BREAK) for c in text)


def utf16_key(text):
    """java.lang.String natural order (UTF-16 code units), as the API's TreeSet sorts terms."""
    return text.encode("utf-16-be")


def query_terms(query):
    """(raw tokens, distinct raw terms) for a query the frozen parser accepts, else None.

    Validity is the literal adapter's parse_query exactly; terms keep their raw case and are never
    folded. Every valid query fits the API: at most 16 tokens of at most 512 bytes in total.
    """
    if h.parse_query(query) is None:
        return None
    tokens = query.split()
    terms = list(dict.fromkeys(tokens))
    if (not terms or len(terms) > MAX_API_TERMS
            or sum(len(t.encode("utf-8")) for t in terms) > MAX_API_TERM_BYTES
            or any(len(t) > MAX_API_TERM_CODE_POINTS for t in terms)):
        raise h.BackendFailure("term_contract", "valid query does not fit the canonical term limits")
    return tokens, terms


class IndexEntry:
    __slots__ = ("item", "session_id", "client_session_id", "metadata_json")

    def __init__(self, item, session_id, client_session_id, metadata_json):
        self.item = item
        self.session_id = session_id
        self.client_session_id = client_session_id
        self.metadata_json = metadata_json


class CompactIndex:
    """Verified, read-only mapping from server event IDs to frozen corpus items."""

    def __init__(self, corpus, project_exact, entries):
        if not isinstance(project_exact, str) or not project_exact:
            raise ValueError("project_exact must be a non-empty string")
        entries = dict(entries)
        if sorted(id(e.item) for e in entries.values()) != sorted(id(i) for i in corpus.items):
            raise ValueError("index must map every corpus item exactly once")
        self.corpus = corpus
        self.project_exact = project_exact
        self.entries = entries
        self.until_ns = h.parse_time(corpus.cutoff, "cutoff")


class _Duplicate(Exception):
    pass


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise _Duplicate()
        result[key] = value
    return result


def _constant(_name):
    raise _Duplicate()


class CompactCanonicalBackend:
    """Exhaustive canonical paging with local verification; delivery order is (newest, item ID)."""

    name = BACKEND

    def __init__(self, fetch, index, deadline_seconds=SEARCH_DEADLINE_SECONDS, clock=time.monotonic):
        self.fetch = fetch
        self.index = index
        self.deadline_seconds = deadline_seconds
        self.clock = clock
        self.host_log = []

    def search(self, corpus, query):
        try:
            parsed = query_terms(query)
            if parsed is None:
                return None
            if corpus is not self.index.corpus:
                raise h.BackendFailure("corpus_mismatch", "session corpus differs from the indexed corpus")
            return self._search(*parsed)
        except h.BackendFailure:
            raise
        except Exception as error:  # Never let an unexpected local failure reach the model.
            raise h.BackendFailure("backend_error", type(error).__name__) from None

    def _fail(self, code, message, record):
        record["failure"] = code
        raise h.BackendFailure(code, message)

    def _search(self, tokens, terms):
        index = self.index
        record = {"terms": len(terms), "pages": 0, "matches": None}
        self.host_log.append(record)
        expected_filters = {"terms": sorted(terms, key=utf16_key), "projectExact": index.project_exact,
                            "until": java_instant_text(index.until_ns)}
        base = ([("mode", "canonical")] + [("term", t) for t in terms]
                + [("projectExact", index.project_exact), ("until", utc_text(index.until_ns)),
                   ("limit", str(PAGE_LIMIT)), ("maxBytes", str(PAGE_MAX_BYTES))])
        deadline = self.clock() + self.deadline_seconds
        found, cursors, cursor, last = [], set(), None, None
        while True:
            if record["pages"] >= len(index.entries) + 1:
                self._fail("page_bound", "pagination exceeded corpus size plus one", record)
            if self.clock() >= deadline:
                self._fail("deadline", "search deadline exceeded", record)
            params = base + ([("before", cursor)] if cursor is not None else [])
            record["pages"] += 1
            try:
                status, body = self.fetch(params)
            except h.BackendFailure as failure:
                self._fail(failure.code, failure.message, record)
            except Exception as error:
                self._fail("transport_error", type(error).__name__, record)
            if self.clock() >= deadline:  # A page that arrives late is never accepted.
                self._fail("deadline", "search deadline exceeded", record)
            if not isinstance(body, (bytes, bytearray)) or len(body) > PAGE_MAX_BYTES:
                self._fail("response_too_large", "response body missing or above maxBytes", record)
            page = self._page(status, bytes(body), record)
            if page["appliedFilters"] != expected_filters:
                self._fail("filter_mismatch", "applied filters differ from the request", record)
            hits = page["items"]
            if not hits and (cursor is not None or page["hasMore"]):
                self._fail("empty_progress", "page made no progress", record)
            for hit in hits:
                entry = self._hit(hit, record)
                key = (entry.item.observed_ns, hit["eventId"])
                if last is not None and not key < last:
                    self._fail("order_violation", "hits are not strictly newest-first by (observed, eventId)", record)
                last = key
                found.append(entry)
            if not page["hasMore"]:
                if page["nextBefore"] is not None:
                    self._fail("invalid_response", "exhausted page carries a cursor", record)
                break
            cursor = page["nextBefore"]
            if (not isinstance(cursor, str) or not cursor or len(cursor) > MAX_CURSOR_CHARS
                    or cursor in cursors):
                self._fail("cursor_invalid", "missing, oversized or repeated cursor", record)
            cursors.add(cursor)
        if len({id(e) for e in found}) != len(found):
            self._fail("duplicate_event", "an event was delivered twice", record)
        # Verification, not substitution: the index holds the exact stored text and metadata bytes,
        # so every indexed event containing all terms must have been returned.
        expected = sum(1 for e in index.entries.values()
                       if all(t in e.item.text or t in e.metadata_json for t in terms))
        if expected != len(found):
            self._fail("incomplete_matches", "returned matches differ from the verified corpus", record)
        record["matches"] = len(found)
        found.sort(key=lambda e: (-e.item.observed_ns, e.item.id))
        ranked = [(e.item, self._match(e, tokens, terms, record)) for e in found[:h.MAX_RESULTS]]
        for entry in found[h.MAX_RESULTS:]:
            self._match(entry, tokens, terms, record)  # Soundness check for every counted hit.
        if self.clock() >= deadline:  # Local verification may also run late; never deliver after it.
            self._fail("deadline", "search deadline exceeded", record)
        return terms, len(found), ranked

    def _page(self, status, body, record):
        try:
            page = json.loads(body.decode("utf-8"), object_pairs_hook=_pairs, parse_constant=_constant)
        except (ValueError, RecursionError, _Duplicate):
            page = None
        if status != 200:
            api = page.get("status") if isinstance(page, dict) else None
            record["http_status"] = status if isinstance(status, int) else None
            self._fail("api_" + api if isinstance(api, str) and STATUS_RE.match(api) else "http_status",
                       "canonical search did not return HTTP 200", record)
        if not isinstance(page, dict) or set(page) != PAGE_KEYS:
            self._fail("invalid_response", "response is not a canonical page object", record)
        bools = ("hasMore", "budgetLimited", "excerptsTruncated")
        if (page["status"] != "ok" or page["mode"] != "canonical" or page["limit"] != PAGE_LIMIT
                or page["maxBytes"] != PAGE_MAX_BYTES or type(page["limit"]) is not int
                or type(page["maxBytes"]) is not int or any(type(page[k]) is not bool for k in bools)
                or not isinstance(page["items"], list) or type(page["count"]) is not int
                or page["count"] != len(page["items"]) or len(page["items"]) > PAGE_LIMIT
                or not isinstance(page["diagnostics"], list)
                or not all(isinstance(d, str) for d in page["diagnostics"])
                or not (page["nextBefore"] is None or isinstance(page["nextBefore"], str))):
            self._fail("invalid_response", "page fields are inconsistent", record)
        if page["hasMore"] and page["nextBefore"] is None:
            self._fail("invalid_response", "page has more hits but no cursor", record)
        return page

    def _hit(self, hit, record):
        if not isinstance(hit, dict) or set(hit) != HIT_KEYS or not isinstance(hit["eventId"], str):
            self._fail("invalid_response", "hit is not a canonical hit object", record)
        entry = self.index.entries.get(hit["eventId"])
        if entry is None:
            self._fail("unknown_event", "hit names an event outside the verified corpus index", record)
        if hit["sessionId"] != entry.session_id or hit["clientSessionId"] != entry.client_session_id:
            self._fail("scope_mismatch", "hit session differs from the verified mapping", record)
        observed = canonical_instant(hit["observedAt"])
        if observed is None or observed != entry.item.observed_ns or observed > self.index.until_ns:
            self._fail("timestamp_mismatch", "hit timestamp differs from the corpus item", record)
        return entry

    def _match(self, entry, tokens, terms, record):
        text, metadata = entry.item.text, entry.metadata_json
        if any(t not in text and t not in metadata for t in terms):
            self._fail("unsupported_hit", "a hit lacks a term in its stored text and metadata", record)
        fields = [name for name, value in (("text", text), ("metadata", metadata)) if any(t in value for t in terms)]
        phrase = " ".join(tokens)
        exact = phrase in text
        if exact:
            start = text.find(phrase)
            anchor = [start, start + len(phrase)]
        else:
            anchor = None
            for term in terms:
                start = text.find(term)
                if start >= 0 and (anchor is None or (start, -len(term)) < (anchor[0], anchor[0] - anchor[1])):
                    anchor = [start, start + len(term)]
        return {"exact": exact, "terms": list(terms), "fields": fields, "anchor": anchor}
