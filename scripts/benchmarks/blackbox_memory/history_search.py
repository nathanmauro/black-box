#!/usr/bin/env python3
"""Deterministic offline latest-handoff and literal-search adapter.

Development infrastructure for the proposed ordinary handoff/search comparison arm. It reads one
explicitly selected, hash-pinned synthetic corpus and serves a single controller-owned session with
fixed attempt and byte budgets. It never reads live history, databases, credentials or the network,
is not wired into any model runner, and supports no efficacy claim. See
docs/continuation-comparison-protocol.md for the frozen contract.
"""

import argparse
import calendar
import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata

MANIFEST_SCHEMA = "blackbox.history-corpus/v1"
ITEMS_SCHEMA = "blackbox.history-items/v1"
DELIVERY_SCHEMA = "blackbox.history-delivery/v1"
BACKEND = "literal-v1"

MAX_SEARCHES = 6
MAX_DELIVERY_BYTES = 6000
MAX_TOTAL_BYTES = 24000
MAX_RESULTS = 20
MAX_MANIFEST_BYTES = 64 * 1024
MAX_ITEMS_BYTES = 8 * 1024 * 1024
MAX_ITEMS = 10000
MAX_TEXT_BYTES = 64 * 1024
MAX_REF_BYTES = 1024
MAX_NAME_BYTES = 256
MAX_EXCLUSIONS = 1000
MAX_LINE_BYTES = 4096
MAX_QUERY_BYTES = 512
MAX_TERMS = 16
EXCERPT_BEFORE = 100
EXCERPT_CHARS = 640

KINDS = ("handoff", "message", "event", "decision", "observation")
ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
FILE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z")
SHA_RE = re.compile(r"[0-9a-f]{64}\Z")
TIME_RE = re.compile(r"([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})"
                     r"(?:\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:[0-9]{2})\Z")

ERRORS = {
    "request_too_large": "Request line exceeds 4096 bytes.",
    "invalid_utf8": "Request is not valid UTF-8.",
    "invalid_json": "Request is not valid JSON.",
    "invalid_request": "Request must be an object with exactly one field: query.",
    "invalid_query": "Query must be a non-empty string of at most 512 UTF-8 bytes and 16 terms "
                     "without control characters.",
    "attempt_limit": "Search attempt limit reached; delivery closed.",
    "byte_budget": "History byte budget exhausted; delivery closed.",
}


class CorpusError(Exception):
    """Controller-side corpus rejection. Raised before any evidence is delivered."""

    def __init__(self, code, message):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


def parse_time(value, field):
    """Exact UTC nanoseconds for an RFC 3339 timestamp; fractions are never rounded."""
    match = TIME_RE.match(value) if isinstance(value, str) else None
    if not match:
        raise CorpusError("invalid_timestamp", f"{field} must be YYYY-MM-DDTHH:MM:SS[.1-9 digits] with Z or ±HH:MM")
    year, month, day, hour, minute, second = (int(g) for g in match.groups()[:6])
    zone = match.group(8)
    try:
        datetime.datetime(year, month, day, hour, minute, second)
        if zone != "Z" and (int(zone[1:3]) > 23 or int(zone[4:6]) > 59):
            raise ValueError("offset")
    except ValueError:
        raise CorpusError("invalid_timestamp", f"{field} is not a valid calendar time") from None
    offset = 0 if zone == "Z" else (1 if zone[0] == "+" else -1) * (int(zone[1:3]) * 3600 + int(zone[4:6]) * 60)
    seconds = calendar.timegm((year, month, day, hour, minute, second, 0, 0, 0)) - offset
    return seconds * 10 ** 9 + int((match.group(7) or "").ljust(9, "0"))


def fold(text, with_map=False):
    """Per-character casefold with whitespace runs collapsed to one space.

    With with_map, also return a list mapping each folded index to its original code-point index,
    so matches inside expansions (ß -> ss) widen to whole original characters.
    """
    out, index, space = [], [], False
    for position, char in enumerate(text):
        if char.isspace():
            if not space:
                out.append(" ")
                index.append(position)
                space = True
            continue
        space = False
        for folded in char.casefold():
            out.append(folded)
            index.append(position)
    return ("".join(out), index) if with_map else "".join(out)


def _reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CorpusError("invalid_json", f"duplicate key {key!r}")
        result[key] = value
    return result


def _reject_constant(name):
    raise CorpusError("invalid_json", f"non-finite number {name}")


def _load_json(data, label):
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise CorpusError("invalid_utf8", f"{label} is not valid UTF-8") from None
    try:
        return json.loads(text, object_pairs_hook=_reject_duplicates, parse_constant=_reject_constant)
    except (ValueError, RecursionError):
        raise CorpusError("invalid_json", f"{label} is not valid JSON") from None


def _read_bounded(path, limit, label):
    path = Path(path)
    if not path.is_file():
        raise CorpusError("missing_file", f"{label} is not a regular file")
    with path.open("rb") as handle:
        data = handle.read(limit + 1)
    if len(data) > limit:
        raise CorpusError("too_large", f"{label} exceeds {limit} bytes")
    return data


def _object(value, required, optional, where):
    if not isinstance(value, dict):
        raise CorpusError("invalid_schema", f"{where} must be an object")
    missing = [k for k in required if k not in value]
    unknown = sorted(set(value) - set(required) - set(optional))
    if missing or unknown:
        raise CorpusError("invalid_schema", f"{where} missing {missing} or unknown {unknown}")
    return value


def _string(value, where, limit, metadata=True):
    if not isinstance(value, str):
        raise CorpusError("invalid_schema", f"{where} must be a string")
    try:
        size = len(value.encode("utf-8"))
    except UnicodeEncodeError:
        raise CorpusError("invalid_utf8", f"{where} contains an unpaired surrogate") from None
    if size > limit:
        raise CorpusError("too_large", f"{where} exceeds {limit} bytes")
    if metadata and (not value or any(unicodedata.category(c) == "Cc" for c in value)):
        raise CorpusError("invalid_schema", f"{where} must be non-empty without control characters")
    return value


def _identifier(value, where, pattern=ID_RE):
    if not isinstance(value, str) or not pattern.match(value):
        raise CorpusError("invalid_schema", f"{where} must match {pattern.pattern}")
    return value


class Item:
    __slots__ = ("id", "kind", "project", "session", "observed_at", "recorded_at", "source_ref", "text",
                 "observed_ns", "recorded_ns", "folded_text", "folded_ref")

    def __init__(self, raw, position):
        where = f"items[{position}]"
        _object(raw, ("id", "kind", "project", "session", "observed_at", "source_ref", "text"),
                ("recorded_at",), where)
        self.id = _identifier(raw["id"], f"{where}.id")
        if raw["kind"] not in KINDS:
            raise CorpusError("invalid_schema", f"{where}.kind must be one of {list(KINDS)}")
        self.kind = raw["kind"]
        self.project = _string(raw["project"], f"{where}.project", MAX_NAME_BYTES)
        self.session = _string(raw["session"], f"{where}.session", MAX_NAME_BYTES)
        self.source_ref = _string(raw["source_ref"], f"{where}.source_ref", MAX_REF_BYTES)
        self.text = _string(raw["text"], f"{where}.text", MAX_TEXT_BYTES, metadata=False)
        self.observed_at = raw["observed_at"]
        self.observed_ns = parse_time(self.observed_at, f"{where}.observed_at")
        self.recorded_at = raw.get("recorded_at")
        self.recorded_ns = None if self.recorded_at is None else parse_time(self.recorded_at, f"{where}.recorded_at")
        self.folded_text = fold(self.text)
        self.folded_ref = fold(self.source_ref)

    def provenance(self):
        return {"id": self.id, "kind": self.kind, "project": self.project, "session": self.session,
                "observed_at": self.observed_at, "recorded_at": self.recorded_at,
                "source_ref": self.source_ref}


class Corpus:
    def __init__(self, manifest, manifest_sha256, items_sha256, items):
        self.corpus_id = manifest["corpus_id"]
        self.provenance = manifest["provenance"]
        self.cutoff = manifest["cutoff"]
        self.scope = manifest["scope"]
        self.exclusions = manifest["exclusions"]
        self.manifest_sha256 = manifest_sha256
        self.items_sha256 = items_sha256
        self.items = tuple(items)
        handoffs = [i for i in self.items if i.kind == "handoff"]
        self.handoff = min(handoffs, key=lambda i: (-i.observed_ns, i.id)) if handoffs else None

    def start_status(self):
        if not self.items:
            return "no_history"
        return "ok" if self.handoff else "no_handoff"

    def summary(self):
        return {"corpus_id": self.corpus_id, "provenance": self.provenance,
                "manifest_sha256": self.manifest_sha256, "items_sha256": self.items_sha256,
                "item_count": len(self.items), "cutoff": self.cutoff, "scope": self.scope,
                "exclusions": len(self.exclusions), "start_status": self.start_status(),
                "handoff_id": self.handoff.id if self.handoff else None}


def load_corpus(manifest_path, expected_sha256):
    """Validate a frozen corpus completely, or raise CorpusError before any delivery."""
    if not isinstance(expected_sha256, str) or not SHA_RE.match(expected_sha256):
        raise CorpusError("invalid_expected_hash", "expected manifest SHA-256 must be 64 lowercase hex digits")
    manifest_path = Path(manifest_path)
    data = _read_bounded(manifest_path, MAX_MANIFEST_BYTES, "manifest")
    manifest_sha256 = hashlib.sha256(data).hexdigest()
    if manifest_sha256 != expected_sha256:
        raise CorpusError("manifest_hash_mismatch", "manifest bytes do not match the selected SHA-256")
    manifest = _object(_load_json(data, "manifest"),
                       ("schema", "corpus_id", "provenance", "scope", "cutoff", "items_file", "items_sha256",
                        "item_count", "exclusions"), (), "manifest")
    if manifest["schema"] != MANIFEST_SCHEMA:
        raise CorpusError("invalid_schema", f"manifest.schema must be {MANIFEST_SCHEMA}")
    _identifier(manifest["corpus_id"], "manifest.corpus_id")
    _string(manifest["provenance"], "manifest.provenance", MAX_NAME_BYTES)
    scope = _object(manifest["scope"], ("project", "sessions"), (), "manifest.scope")
    _string(scope["project"], "manifest.scope.project", MAX_NAME_BYTES)
    sessions = scope["sessions"]
    if not isinstance(sessions, list) or not sessions or len(sessions) > MAX_ITEMS:
        raise CorpusError("invalid_schema", "manifest.scope.sessions must be a non-empty list")
    for position, session in enumerate(sessions):
        _string(session, f"manifest.scope.sessions[{position}]", MAX_NAME_BYTES)
    if len(set(sessions)) != len(sessions):
        raise CorpusError("invalid_schema", "manifest.scope.sessions must be unique")
    cutoff_ns = parse_time(manifest["cutoff"], "manifest.cutoff")
    _identifier(manifest["items_file"], "manifest.items_file", FILE_RE)
    if not isinstance(manifest["items_sha256"], str) or not SHA_RE.match(manifest["items_sha256"]):
        raise CorpusError("invalid_schema", "manifest.items_sha256 must be 64 lowercase hex digits")
    count = manifest["item_count"]
    if type(count) is not int or not 0 <= count <= MAX_ITEMS:
        raise CorpusError("invalid_schema", f"manifest.item_count must be an integer from 0 to {MAX_ITEMS}")
    exclusions = manifest["exclusions"]
    if not isinstance(exclusions, list) or len(exclusions) > MAX_EXCLUSIONS:
        raise CorpusError("invalid_schema", f"manifest.exclusions must be a list of at most {MAX_EXCLUSIONS}")
    excluded = set()
    for position, exclusion in enumerate(exclusions):
        where = f"manifest.exclusions[{position}]"
        _object(exclusion, ("id", "reason"), (), where)
        excluded.add(_identifier(exclusion["id"], f"{where}.id"))
        _string(exclusion["reason"], f"{where}.reason", MAX_NAME_BYTES * 2)
    if len(excluded) != len(exclusions):
        raise CorpusError("invalid_schema", "manifest.exclusions ids must be unique")

    items_data = _read_bounded(manifest_path.parent / manifest["items_file"], MAX_ITEMS_BYTES, "items file")
    items_sha256 = hashlib.sha256(items_data).hexdigest()
    if items_sha256 != manifest["items_sha256"]:
        raise CorpusError("items_hash_mismatch", "items file bytes do not match manifest.items_sha256")
    document = _object(_load_json(items_data, "items file"), ("schema", "items"), (), "items file")
    if document["schema"] != ITEMS_SCHEMA:
        raise CorpusError("invalid_schema", f"items file schema must be {ITEMS_SCHEMA}")
    raw_items = document["items"]
    if not isinstance(raw_items, list) or len(raw_items) != count:
        raise CorpusError("item_count_mismatch", "items list length differs from manifest.item_count")
    items, seen = [], set()
    for position, raw in enumerate(raw_items):
        item = Item(raw, position)
        if item.id in seen:
            raise CorpusError("duplicate_item_id", f"item id {item.id} appears more than once")
        seen.add(item.id)
        if item.id in excluded:
            raise CorpusError("excluded_item_present", f"item {item.id} is declared excluded")
        if item.project != scope["project"] or item.session not in sessions:
            raise CorpusError("out_of_scope", f"item {item.id} is outside the declared project/session scope")
        if item.observed_ns > cutoff_ns or (item.recorded_ns is not None and item.recorded_ns > cutoff_ns):
            raise CorpusError("after_cutoff", f"item {item.id} is dated after the corpus cutoff")
        items.append(item)
    return Corpus(manifest, manifest_sha256, items_sha256, items)


def encode(envelope):
    """Exactly metered delivery bytes: compact UTF-8 JSON plus one newline."""
    return (json.dumps(envelope, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def parse_query(query):
    """Return (folded tokens, distinct terms) or None for an invalid query."""
    if not isinstance(query, str):
        return None
    try:
        size = len(query.encode("utf-8"))
    except UnicodeEncodeError:
        return None
    if size > MAX_QUERY_BYTES or any(unicodedata.category(c) == "Cc" and not c.isspace() for c in query):
        return None
    tokens = [t for t in fold(query).split(" ") if t]
    if not tokens or len(tokens) > MAX_TERMS:
        return None
    return tokens, list(dict.fromkeys(tokens))


def _anchor(item, phrase, terms, exact):
    """Original code-point span of the first exact phrase, else the earliest term, in the text."""
    folded, index = fold(item.text, with_map=True)
    span = None
    if exact and phrase in folded:
        start = folded.find(phrase)
        span = (start, start + len(phrase))
    else:
        best = None
        for term in terms:
            start = folded.find(term)
            if start >= 0 and (best is None or (start, -len(term)) < (best[0], -(best[1] - best[0]))):
                best = (start, start + len(term))
        span = best
    if span is None:
        return None
    return [index[span[0]], index[span[1] - 1] + 1]


def excerpt_window(item, anchor):
    """Shared excerpt rule: 100 code points before the anchor, 640 total, widened to the anchor."""
    if anchor is None:
        return (0, min(len(item.text), EXCERPT_CHARS))
    start = max(0, anchor[0] - EXCERPT_BEFORE)
    return (start, min(len(item.text), max(start + EXCERPT_CHARS, anchor[1])))


class BackendFailure(Exception):
    """Host infrastructure failure. The session closes without emitting any further delivery."""

    def __init__(self, code, message):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class LiteralBackend:
    """Deterministic case-folded substring ranking over the frozen corpus."""

    name = BACKEND

    def search(self, corpus, query):
        """Return (terms, total, ranked) or None for an invalid query.

        ranked holds at most MAX_RESULTS (item, match) pairs in delivery order and total counts
        every match exactly. A backend that cannot establish an exact total or a deterministic
        order must raise BackendFailure instead of returning a partial outcome.
        """
        parsed = parse_query(query)
        if parsed is None:
            return None
        tokens, terms = parsed
        phrase = " ".join(tokens)
        hits = []
        for item in corpus.items:
            fields = [name for name, folded in (("text", item.folded_text), ("source_ref", item.folded_ref))
                      if any(term in folded for term in terms)]
            if not fields:
                continue
            matched = [t for t in terms if t in item.folded_text or t in item.folded_ref]
            exact = phrase in item.folded_text or phrase in item.folded_ref
            hits.append(((not exact, -len(matched), -item.observed_ns, item.id), item, exact, matched, fields))
        hits.sort(key=lambda hit: hit[0])
        ranked = [(item, {"exact": exact, "terms": matched, "fields": fields,
                          "anchor": _anchor(item, phrase, matched, exact)})
                  for _, item, exact, matched, fields in hits[:MAX_RESULTS]]
        return terms, len(hits), ranked


def _result(item, window, match=None):
    start, end = window
    return {**item.provenance(), "match": match,
            "excerpt": {"start": start, "end": end, "text_chars": len(item.text), "budget_clipped": False,
                        "text": item.text[start:end]}}


class Session:
    """One controller-owned delivery session: handoff once, then at most six bounded searches."""

    def __init__(self, corpus, backend=None):
        self.corpus = corpus
        self.backend = backend or LiteralBackend()
        self.infrastructure_error = None
        self.attempts = 0
        self.delivered = 0
        self.started = False
        self.closed = False
        self.close_reason = None
        self.terminal_emitted = False
        self.log = []

    @property
    def remaining(self):
        return MAX_TOTAL_BYTES - self.delivered

    def _envelope(self, kind, status, cap, results, omitted, truncated, terms=None, total=None,
                  error=None, attempt=None):
        return {"schema": DELIVERY_SCHEMA, "backend": self.backend.name, "corpus_id": self.corpus.corpus_id,
                "seq": len(self.log), "type": kind, "status": status, "attempt": attempt,
                "attempts_remaining": MAX_SEARCHES - self.attempts,
                "budget": {"remaining_before": self.remaining, "delivery_cap": cap},
                "query_terms": terms, "total_matches": total, "results": results,
                "omitted_results": omitted, "truncated": truncated, "error": error}

    def _fit(self, build, candidates, cap, capped=0):
        """Largest rank-order prefix that fits; clip the first non-fitting excerpt at code points.

        capped counts ranked matches already excluded by the result ceiling; they are always
        disclosed as omitted, together with candidates dropped for bytes.
        """
        def payload(included, clipped):
            omitted = capped + len(candidates) - len(included)
            return encode(build(included, omitted, clipped or omitted > 0))

        best = payload([], False)
        if len(best) > cap:
            return None
        included = []
        for result in candidates:
            full = payload(included + [result], False)
            if len(full) <= cap:
                included.append(result)
                best = full
                continue
            excerpt = result["excerpt"]
            low, high, clipped = 0, len(excerpt["text"]) - 1, None
            while low <= high:
                keep = (low + high) // 2
                trial = {**result, "excerpt": {**excerpt, "end": excerpt["start"] + keep,
                                               "budget_clipped": True, "text": excerpt["text"][:keep]}}
                encoded = payload(included + [trial], True)
                if len(encoded) <= cap:
                    clipped, low = encoded, keep + 1
                else:
                    high = keep - 1
            if clipped is not None:
                best = clipped
            break
        return best

    def _emit(self, payload, kind, status):
        self.log.append({"seq": len(self.log), "type": kind, "status": status, "bytes": len(payload)})
        self.delivered += len(payload)
        assert len(payload) <= MAX_DELIVERY_BYTES and self.delivered <= MAX_TOTAL_BYTES
        return payload

    def _close(self, reason):
        self.closed = True
        self.close_reason = reason
        return None

    def _terminal(self, code):
        cap = min(MAX_DELIVERY_BYTES, self.remaining)
        payload = encode(self._envelope("terminal", "exhausted", cap, [], 0, False,
                                        error={"code": code, "message": ERRORS[code]}))
        if len(payload) > cap:
            return self._close(code)
        self.terminal_emitted = True
        self._emit(payload, "terminal", "exhausted")
        self._close(code)
        return payload

    def _deliver(self, kind, status, candidates, capped=0, **fields):
        cap = min(MAX_DELIVERY_BYTES, self.remaining)
        payload = self._fit(lambda included, omitted, truncated: self._envelope(
            kind, status, cap, included, omitted, truncated, **fields), candidates, cap, capped)
        if payload is None:
            return self._terminal("byte_budget")
        return self._emit(payload, kind, status)

    def start(self):
        """Deliver the latest eligible handoff exactly once, before any search."""
        if self.started:
            raise RuntimeError("handoff already delivered for this session")
        self.started = True
        handoff = self.corpus.handoff
        candidates = [_result(handoff, (0, len(handoff.text)))] if handoff else []
        return self._deliver("handoff", self.corpus.start_status(), candidates)

    def handle_line(self, raw, overlong=False):
        """Handle one JSONL request (bytes without the newline). Returns metered bytes or None."""
        if not self.started:
            raise RuntimeError("start() must deliver the handoff before searching")
        if self.closed:
            return None
        if self.attempts >= MAX_SEARCHES:
            return self._terminal("attempt_limit")
        self.attempts += 1
        if overlong or len(raw) > MAX_LINE_BYTES:
            return self._error("request_too_large")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            return self._error("invalid_utf8")
        try:
            request = json.loads(text, object_pairs_hook=_reject_duplicates, parse_constant=_reject_constant)
        except (ValueError, RecursionError, CorpusError):
            return self._error("invalid_json")
        if not isinstance(request, dict) or set(request) != {"query"}:
            return self._error("invalid_request")
        return self._search(request["query"])

    def search(self, query):
        """Library form of one search attempt; identical accounting to handle_line."""
        if not self.started:
            raise RuntimeError("start() must deliver the handoff before searching")
        if self.closed:
            return None
        if self.attempts >= MAX_SEARCHES:
            return self._terminal("attempt_limit")
        self.attempts += 1
        return self._search(query)

    def _error(self, code):
        return self._deliver("search", "error", [], attempt=self.attempts,
                             error={"code": code, "message": ERRORS[code]})

    def _search(self, query):
        try:
            outcome = self.backend.search(self.corpus, query)
        except BackendFailure as failure:
            # Never let the model see an unmetered or partial diagnostic: record it host-side only.
            self.infrastructure_error = {"code": failure.code, "message": failure.message}
            return self._close("infrastructure_error")
        if outcome is None:
            return self._error("invalid_query")
        terms, total, ranked = outcome
        candidates = [_result(item, excerpt_window(item, match["anchor"]), match) for item, match in ranked]
        return self._deliver("search", "ok" if total else "empty", candidates, capped=total - len(candidates),
                             terms=terms, total=total, attempt=self.attempts)

    def accounting(self):
        """Host-only accounting; never part of a metered delivery."""
        record = {**self.corpus.summary(), "deliveries": list(self.log), "delivered_bytes": self.delivered,
                  "remaining_bytes": self.remaining, "search_attempts": self.attempts, "closed": self.closed,
                  "close_reason": self.close_reason, "terminal_emitted": self.terminal_emitted,
                  "limits": {"searches": MAX_SEARCHES, "delivery_bytes": MAX_DELIVERY_BYTES,
                             "total_bytes": MAX_TOTAL_BYTES}}
        if self.infrastructure_error is not None:
            record["infrastructure_error"] = self.infrastructure_error
        return record


def _host(stream, record):
    stream.write(json.dumps(record, sort_keys=True) + "\n")
    stream.flush()


def serve(args, stdin, stdout, stderr):
    try:
        corpus = load_corpus(args.manifest, args.manifest_sha256)
    except CorpusError as error:
        _host(stderr, {"controller_error": {"code": error.code, "message": error.message}})
        return 2
    session = Session(corpus)

    def emit(payload):
        if payload:
            stdout.write(payload)
            stdout.flush()

    emit(session.start())
    while not session.closed:
        line = stdin.readline(MAX_LINE_BYTES + 1)
        if not line:
            break
        overlong = len(line) > MAX_LINE_BYTES and not line.endswith(b"\n")
        while overlong:
            rest = stdin.readline(65536)
            if not rest or rest.endswith(b"\n"):
                break
        line = line[:-1] if line.endswith(b"\n") else line
        line = line[:-1] if line.endswith(b"\r") else line
        emit(session.handle_line(b"" if overlong else line, overlong))
    _host(stderr, {"host_accounting": session.accounting()})
    return 0


def main(argv=None, stdin=None, stdout=None, stderr=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    for name, text in (("validate", "Validate a frozen corpus and print a host-only summary."),
                       ("serve", "Emit the handoff, then answer JSONL {\"query\": ...} lines from stdin.")):
        command = commands.add_parser(name, help=text)
        command.add_argument("--manifest", required=True, help="Frozen corpus manifest JSON file.")
        command.add_argument("--manifest-sha256", required=True, help="Exact registered manifest SHA-256.")
    args = parser.parse_args(argv)
    stdin = stdin or sys.stdin.buffer
    stdout = stdout or sys.stdout.buffer
    stderr = stderr or sys.stderr
    if args.command == "validate":
        try:
            summary = load_corpus(args.manifest, args.manifest_sha256).summary()
        except CorpusError as error:
            _host(stderr, {"controller_error": {"code": error.code, "message": error.message}})
            return 2
        _host(sys.stdout, summary)
        return 0
    return serve(args, stdin, stdout, stderr)


if __name__ == "__main__":
    sys.exit(main())
