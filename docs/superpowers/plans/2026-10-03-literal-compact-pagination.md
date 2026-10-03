# Literal compact pagination (NAT-320)

Status: implemented on `codex/compact-search-pagination` (integrated base `073e78a8`); root owns packaged HTTP,
PostgreSQL acceptance, CI and merge.

## Problem

`GET /api/search/compact` (MCP `searchContext`) is bounded discovery: each backend reads at most 200
candidates, the response keeps at most 50 hits and then trims to `maxBytes`. It has no continuation
and no exact total at the candidate ceiling (ungrouped counts below it remain exact), so a caller
cannot traverse more than 200 matches or a tie group larger than
the page. Ties at one timestamp are broken by random event IDs, so which tied subset survives the
cut is arbitrary. Free text goes through the `EventQuery` grammar: a term cannot contain an embedded
double quote, facet-looking text is parsed as a facet, and SQL `LIKE` treats `%` and `_` as
wildcards. Legacy matching is case-insensitive.

Baseline evidence (source test
`CompactPageHttpTest#legacyBoundedSearchCannotTraverseButCanonicalPagesDo`, private SQLite): 230 matches including 60 tied at one instant. Legacy compact with `limit=50` returns 50
hits (with `maxBytes=64000`; the default budget trims further, to 28 here),
`coverage.local.candidates=200`, `candidateLimitReached=true`, and no cursor field; the other 180
matches are unreachable through this API. Observed output: `Baseline legacy compact: matches=230
items=50 candidates=200 cursor=absent` and, after the change, `Canonical pages traversed=230
pages=33` (limit 7).

## Contract

Additive opt-in mode on the existing route: `GET /api/search/compact?mode=canonical&...`.
Without `mode`, grammar, grouping, Elasticsearch participation, response shape and the MCP
`searchContext` signature are unchanged. Newly reserved canonical-only parameters are rejected
instead of silently ignored. MCP gains no tool in this slice.

### Request

| Parameter | Rule |
|---|---|
| `mode` | Absent = legacy. `canonical` = this contract. Any other value: 400. |
| `term` | Repeatable, 1–16 values. Each is a literal, case-sensitive substring; all must match (AND). 1–512 Unicode code points each, at most 2,048 UTF-8 bytes in total. Empty values, C0/C1 control characters other than tab, LF and CR (including U+0000 and U+007F), and unpaired surrogates are rejected. Tab, LF, CR, quotes, `%`, `_`, backslashes, commas, `key:value` text and other Unicode are literal. Duplicates collapse; order is irrelevant. |
| `projectExact` | Optional. Canonicalized like `ProjectKey` (trim, strip trailing `/`, `/` stays `/`); compared with the same canonical `agent_sessions.cwd` expression as `project_exact:`. `__no_project__` selects sessions without a working directory. Raw exact path, not grouped project identity. At most 1,024 code points, no control characters, nonblank. |
| `sessionId` | Optional. Exact internal `agent_sessions.id` only (client session IDs are not consulted). 1–256 characters, no control characters. |
| `until` | Optional inclusive ISO-8601 instant upper bound on `observedAt`, compared at nanosecond precision. When absent on the first page, the server's request time is used. The effective value is echoed in `appliedFilters.until` and carried in the cursor so later pages do not drift. |
| `before` | Optional opaque cursor from a previous `nextBefore`, at most 1,024 characters. |
| `limit` | 1–50, default 10. Out of range is rejected (legacy clamps). |
| `maxBytes` | 2,048–64,000, default 24,000, same meaning as legacy. |

Rejected combinations (400, never silently ignored): `mode=canonical` with `q`, `groupSimilar` or
`excludeSession`; canonical-only parameters (`term`, `projectExact`, `sessionId`, `until`, `before`)
without `mode=canonical`; a scalar canonical parameter supplied more than once. Missing `q` without
`mode` keeps the legacy missing-parameter response. Values are read from the raw parameter list, so
a comma inside one `term` is never split.

The term bounds cover the existing comparison query domain (16 tokens within 512 UTF-8 bytes)
without changing its frozen baseline. The aggregate 2,048-byte bound remains unchanged. Shared
HTTP tests exercise 16 distinct terms, a 512-code-point term, exactly 2,048 UTF-8 bytes and
one-byte/count/code-point overflow.

### Matching

Searchable fields are the existing compact-search fields: `agent_events.text`, `tool_name` and the
stored `metadata_json` text. Each term must occur in at least one of them. Metadata is matched
against its stored serialized JSON, so a quote or backslash inside a metadata value appears there
JSON-escaped; `text` and `tool_name` are matched raw. Matching uses `instr` (SQLite) / `strpos`
(PostgreSQL) on bound parameters: no wildcard, escape or case folding. Source references, session
IDs, event types and other columns are not searched.

### Ordering and pages

Order is `observedAt DESC, eventId DESC` using the existing `SqlInstant` nanosecond key, so 0, 3, 6,
9-digit and whole-second timestamps interleave correctly. The adapter reads `limit + 1` rows after
the cursor; there is no global candidate ceiling across pages and no total count. The response
reports `hasMore` and `nextBefore` (null at exhaustion). Grouping and Elasticsearch never apply.

### Cursor

`nextBefore` is unpadded base64url of compact JSON
`{"v":1,"f":<filter fingerprint>,"u":<until>,"k":<order key>,"i":<event id>}`.
The fingerprint is the base64url SHA-256 of normalized terms, canonical project, session and the
effective `until`. Decoding is strict: canonical base64url, exactly these five fields, string types,
version 1, strict UTF-8 (malformed bytes are rejected, never replaced), a well-formed order key that
resolves to a real instant anywhere in the supported `Instant.MIN`..`Instant.MAX` range and not after
`until`, and an event ID of 1–512 characters without control characters or unpaired surrogates.
Encoding also enforces the 1,024-character cursor bound, including UTF-8 and JSON escaping; an
unrepresentable stored position returns `cursor_unavailable` instead of an unusable cursor.
Malformed input returns `invalid_cursor`.
A cursor presented with different filters, or with an explicit `until` different from the one it
carries, returns `cursor_mismatch`. The cursor is not a credential: filters are re-bound from the
request, and the position cannot leave the requested scope or move past `until`.

Consistency: a fixed `until` stops new events with later timestamps from appearing, but pages read
live tables. Ordinary concurrent, backdated or deleted writes inside the window are not
snapshot-isolated and can appear or disappear between pages. Exactly-once traversal is guaranteed
for a frozen corpus, such as a fixture ingested before the first page. Reset generation binding was
evaluated and rejected for this slice because it would need control-plane/schema state.

### Byte budget

`maxBytes` bounds the full serialized page as in legacy. Hits are dropped from the end of the page
until it fits; a sole remaining hit has its excerpt shortened. `nextBefore` always comes from the
last hit actually delivered, never from a dropped or extra row, so dropped rows reappear on the
next page. `budgetLimited` reports that the page was shortened or an excerpt cut to fit;
`excerptsTruncated` reports any shortened excerpt (including the 600-code-point cap); `hasMore`
reports pagination only. If one hit with an empty excerpt plus the envelope cannot fit, the
response is `budget_exceeded` with no items and no cursor, so a client cannot loop on empty pages.
Hits never echo metadata or tool JSON; source references are the existing exact event/browse paths.

### Response

`CompactSearchPage`: `status`, `mode`, `count`, `items` (existing `CompactSearchResult.Hit`,
`backends=["local"]`, no similarity grouping), `appliedFilters` (`terms`, optional `projectExact`
and `sessionId`, `until`), `hasMore`, `nextBefore`, `budgetLimited`, `excerptsTruncated`, `limit`,
`maxBytes`, `diagnostics`. Errors use the same shape with HTTP 400 and statuses
`invalid_request`, `invalid_cursor`, `cursor_mismatch`, `budget_exceeded`, or `cursor_unavailable`
(a stored row whose timestamp key or ID cannot form a valid cursor; not expected for recorded
events). Ordinary HTTP integer-binding errors retain the standard `ApiError` envelope, and
requests without `mode` retain the legacy error shape.

## Tasks

1. Baseline source test proving legacy cannot traverse >200 matches / >50 ties. (done)
2. Port + adapter literal page query for SQLite and PostgreSQL. (done)
3. Cursor helper and service page method with validation and byte fitting. (done)
4. Controller routing and rejection rules; REST matrix row optional inputs; wire fixture for the new
   record in the hand-maintained portion of the fixture file, checked by the serializer contract. (done)
5. Shared SQLite/PostgreSQL HTTP contract: 230 matches with 60 ties traversed once, mixed fraction
   order, `until` ±1 ns, literals, conjunction, project/session boundaries, cursor failures,
   budget-trimmed pages, irreducible budget, empty and final pages. (done)
6. Docs: `docs/agent-integration.md`. (done)

## Verification (this checkout)

- SQLite: `CompactPageHttpTest` (7), `CompactPageCursorTest` (4), `CompactSearchServiceTest` (10), `CompactSearchHttpTest`,
  wire/REST/MCP contract snapshots pass. MCP tool inventory unchanged.
- Root cursor review (2026-10-03) reproduced two helper defects, now fixed with regressions in
  `CompactPageCursorTest` and `CompactPageHttpTest#extremeInstantsPaginateAndInvalidUtf8CursorsFail`:
  extreme-year keys failed because validation went through `LocalDateTime`, and invalid UTF-8 was
  silently replaced with U+FFFD.
- Root reproduced and fixed a third cursor edge: a valid multibyte or escaped stored event ID
  could encode beyond the decoder limit. A failing-before/passing-after regression now requires
  explicit rejection before returning such a cursor.
- Root ran all 22 `PostgresBackendContractTest` cases against an owned disposable PostgreSQL 16
  fixture, including the shared canonical HTTP contract; none skipped. Root also reproduced the
  old packaged endpoint with 240 matches: 50 hits from 200 candidates, no continuation, and
  literal percent/underscore overmatching. That isolated SQLite fixture was removed afterward.
- Root full Java 21 package verification passed: 1,107 tests, zero failures/errors, four optional
  environment-gated skips. After the term-bound adjustment, all 51 selected service, cursor, HTTP,
  PostgreSQL and wire/REST/MCP contract tests passed again.
- Independent packaged HTTP acceptance seeded 273 rows: all 240 target matches traversed once in
  seven pages; 12 large matches traversed in 12 byte-fitted pages without skips. Nine literal
  cases, exact scopes and nanosecond cutoffs, cursor and request failures, Unicode clipping and
  budget retries passed. Reads/errors preserved stored rows; owned processes and storage were
  removed. No installed deployment changed.
- The new `CompactSearchPage` wire fixture row was added by hand alongside the existing hand-written
  compact rows; the fixture generator only rewrites its listed types.

## Out of scope

MCP tool for paged mode, Elasticsearch participation, recording/schema changes, frontend, benchmark
files, total counts, snapshot isolation.
