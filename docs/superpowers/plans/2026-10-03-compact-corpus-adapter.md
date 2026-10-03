# Compact Canonical Corpus Adapter (NAT-319)

> Development infrastructure only. No model run, efficacy claim, NAT-7 gate change or runner wiring.

**Goal:** Serve the existing `history_search(query)` delivery contract from the canonical
`GET /api/search/compact?mode=canonical` API (NAT-320) over a frozen, hash-validated corpus that a
private, throwaway Black Box server holds, without changing the frozen `literal-v1` adapter.

**Prerequisite:** NAT-320 (typed literal terms, keyset pagination). Contract inspected read-only:
repeated case-sensitive `term` values ANDed across event text, tool name and stored metadata JSON;
`projectExact`, internal `sessionId`, inclusive nanosecond `until`, opaque `before`; at most 16
terms, 512 code points each and 2,048 UTF-8 bytes in aggregate; `limit` 1–50; `maxBytes`
2,048–64,000; `hasMore`/`nextBefore` advance from the last delivered hit even when byte fitting
returns fewer than `limit`; exhaustion returns `nextBefore: null`; errors (including
`cursor_unavailable` and `budget_exceeded`) are HTTP 400.

## Scope

In: `compact_backend.py`, `compact_server.py`, `compact_history_search.py`, their tests, this plan,
and the two protocol/benchmark docs. A pure move of the existing stdin/stdout stream loop in
`history_search.py` into a reusable function, proven byte-identical.

Out: Java/API/frontend, frozen fixtures, the literal parser/ranking/`source_ref`, NAT-7 gates,
model runners, Maven, real product servers, packaged-JAR acceptance (root runs it after NAT-320
merges), Git publication.

## Safety contract

- The backend never talks to a URL. It receives a controller-owned fetch callable and a verified
  event-ID → corpus-item mapping. The server owner exposes only fixed loopback routes.
- `PrivateCompactServer` takes an explicit trusted JAR path plus SHA-256, a Java 21 executable and
  a loaded `Corpus`. No server URL, no discovery or reuse of databases, no downloads.
- Fresh 0700 temp root (SQLite file, private HOME/TMP/XDG, log); the JAR is copied and re-hashed
  before launch; the environment is built from scratch (nothing inherited); classpath-only
  application config plus explicit overrides disabling optional subsystems; loopback port chosen
  fresh and never 8766, 8799 or 18879; own process group.
- Before any write and before every request: the child is alive and is the only listener on the
  port; before the first write the child holds the private DB file and its command line names the
  copied JAR and DB. Missing platform proof (`lsof`, `ps`) fails closed.
- Cleanup TERMs the owned process group, waits, KILLs if needed, reaps, and removes the temp root
  only after marker, inode and owner checks. Signals (TERM/HUP/INT) unwind through the same path.
- Host-only diagnostics are fixed strings and small integers; no corpus text, response bodies or
  credentials are printed.
- Failure before `Session.start()` → `ControllerError`, zero deliveries. Failure during a search →
  `BackendFailure`, session closes silently, pair invalidated.

## Selected design (no approval needed)

1. Query validity uses the frozen `parse_query` only. Terms are the distinct raw-case tokens of
   `query.split()`; no casefold, no local inclusion substitute, no union/intersection fallback.
   Every valid query (≤512 bytes, ≤16 tokens) fits the API limits.
2. Exhaust every page; validate status, exact response shape, `mode`, `appliedFilters`
   (UTF-16-sorted terms, project, Java `Instant.toString()` cutoff), `limit`, `maxBytes`, count,
   known and unique event IDs, internal session mapping, exact observed nanoseconds, strictly
   descending `(observed, eventId)` order, non-repeating cursor, no empty progress, bounded body
   bytes. Pages bounded by N+1 (N = corpus items), not ceil(N/50)+1. Fixed per-request timeout and
   per-search deadline. Any doubt → `BackendFailure`.
3. Exact total = len(matches). Delivery order: (-observed_ns, item.id), top 20. Server inclusion is
   authoritative; each hit must contain every term in its stored text or metadata (soundness).
4. Match details: `terms` = all distinct raw tokens; `fields` truthfully `text`/`metadata`;
   `exact` = joined raw phrase in the original text; `anchor` = first exact phrase, else earliest
   raw term in the original text, else `null`.
5. The Session keeps sole ownership of handoff, excerpts, six attempts, 6,000/24,000 bytes and the
   full UTF-8 wire budget. Results render original corpus provenance and text, never API excerpts.
6. Capture: idempotent route, receipt UUID = uuid5(fixed namespace, manifest SHA-256 + item ID);
   synthetic client session per original (project, session) pair (hashed, never a path); one
   synthetic cwd/project; text unmodified; `toolName` null; `metadata={"sourceRef": source_ref}`;
   neutral `eventType`; `observedAt` sent as computed UTC with nine fractional digits (integer
   arithmetic, no `datetime`, so offset-normalized years 0 and 10000 stay in domain).
7. Read-only SQLite fidelity proof before `Session.start()`: exact N events and N receipts with the
   expected capture IDs, text exact (whitespace-only may be NULL), metadata JSON semantically equal
   (raw stored bytes kept for metadata matching), tool name NULL, exact observed nanoseconds,
   session/cwd mapping.

## Tasks

1. [x] Pure-move `serve` loop into `history_search.stream()`; prove golden digest, 32 literal tests
   and a pinned CLI transcript digest unchanged (pre-change CLI digest
   `1052d56292d487d5fc823ffb9a23753a807a5214df37aac3b36282d244b365e8`).
2. [x] `compact_backend.py` + `test_compact_backend.py` with an in-process fake canonical API:
   >200 tied matches, byte-short pages, 16-token / 512-byte queries, punctuation/raw case/metadata
   matches, exact timestamps, every validation failure with zero leaked model bytes.
3. [x] `compact_server.py` + `test_compact_server.py` with a fake `java` process (real subprocess,
   real loopback HTTP, real SQLite, real `lsof`): happy path, fidelity mismatches, identity races,
   missing platform proof, early exit, signal cleanup, marker tampering, environment scrub.
4. [x] `compact_history_search.py` CLI over the shared stream; controller-error and mid-session
   failure journeys.
5. [x] Docs: protocol + benchmark sections, honest limitations, reproducible commands.
6. [x] Full Python suite, `git diff --check`, file hashes, freeze.

## Observed results

- The pure move of the stream loop kept the literal golden digest and the pinned CLI transcript digest
  (`1052d562…`) byte-identical.
- The Python suite has 154 test methods: 125 baseline, 16 backend and 13 server/CLI. The
  server/CLI tests use a real fake-`java` subprocess, loopback HTTP, SQLite, `lsof`/`ps` and
  process groups. No JVM, Maven or real product server was run.
- An independent read-only review led to five fixes, each with regression coverage:
  - a completeness check, after an injected dropped hit silently reduced the total;
  - integer-typed `limit` and `maxBytes`;
  - canonical `Instant.toString()` spelling for hits and stored rows;
  - a strict blank → NULL rule;
  - ignoring repeated signals during cleanup.
- Deviation from the first draft: the backend now also verifies completeness against the index.
  It still never substitutes local results.
- Lifecycle review, round 2. A second independent review reported five more issues. None was
  covered by the round-1 fixes. Each was reproduced by a new regression, and all six failed
  before the fix:
  1. A TERM-ignoring descendant survived cleanup (`child_ignores_term`), and so did an orphaned
     group member after the leader had already exited (`orphan_listener`). Both regressions
     failed: the descendant was still alive after cleanup. Fix: liveness checks never reap the
     leader. `_stop` signals the group, waits until `ps` shows no live member, uses KILL if
     needed, and only then reaps the leader. Storage is removed only after the group is proven
     stopped. macOS returns EPERM for a group holding only its zombie leader. That is accepted
     only when no live member remains.
  2. A reply dribbled in four chunks 0.05 s apart, with `REQUEST_TIMEOUT=0.10`, was delivered.
     Fix: an absolute deadline timer shuts the socket down, and a late reply raises
     `TimeoutError`, which becomes the host-only `transport_error`. The bounded failure now takes
     under 1 s.
  3. A valid terminal page returned after the search deadline was delivered. Fix: the deadline is
     checked again after every fetch.
  4. The host report said `events: 0` against 9 items because the acknowledgements were consumed
     by the fidelity proof. Fix: the count comes from the verified index.
  5. A cleanup failure after delivery exited 2, which is reserved for pre-delivery errors. Fix:
     it now exits 3 with `phase: cleanup` and `pair_invalid: true`; stdout is unchanged.
- After round 2 the suite has 159 test methods (125 baseline, 17 backend, 17 server/CLI). They
  pass on Python 3.9.6. The literal, backend and server suites also pass on 3.12.14 and 3.13.15.
  The golden CLI digest is unchanged, and no fake process or temporary root leaked.
- Root preliminary proof (root-run, not full acceptance): a pinned copy of the round-2 draft ran
  against the trusted PR119 JAR. All 266 synthetic records read back with exact text, metadata
  and nanosecond timestamps. Three queries returned 240, 266 and 80 matches over 5, 6 and 2 pages
  (18,078 bytes). The owned processes were fully cleaned up, and the installed 8766 service was
  untouched.
- Round 3 had three narrow findings:
  1. The search deadline was not checked after final-page validation, the completeness scan,
     sorting and matching. Regression `test_deadline_crossed_during_final_matching_is_not_delivered`
     advances an injected clock during `_match`. Before the fix it failed with a full 120-match
     delivery. Fix: one more deadline check immediately before a successful return. It produces a
     host-only `deadline` failure, no delivery, and an invalid pair.
  2. The backend fault table was a dict with duplicate `invalid_response` and `timestamp_mismatch`
     keys, so the malformed-JSON and changed-nanosecond cases never ran. It is now a list of 13
     `(expected, fault)` pairs with a count assertion. Both restored cases pass without code
     changes.
  3. The `orphan_listener` fake leader could exit before its child ignored TERM and wrote its PID
     file. It now waits on a pipe handshake, bounded to 5 s, and closes both ends before exiting.
     The orphaned-member cleanup regression is kept. The race was not observed in earlier
     reruns, so it has no failing-before trace.
- Coordinator acceptance completed after integrating the canonical API and capture-ack fixture
  from main. The integrated Python 3.9 suite passed 185 tests with no failures or errors.
- Independent packaged-JAR qualification passed 26 fresh private server runs and 10 corpus
  rejections before launch. All 18 batches were repeated with byte-identical stdout. The
  [machine-neutral report](../../evaluation-results/2026-10-03-compact-corpus-qualification.json)
  records source/JAR/corpus hashes and per-batch traversal and delivery evidence.
- Exact readback covered all 266 primary records and both year-zero-cutoff records in their
  respective fresh server runs: original text including NUL/blank/64 KiB values, stored metadata bytes, and
  nanosecond timestamps with year 0/+10000 normalization. Searches covered more than 200
  matches, 80 timestamp ties, punctuation/JSON/Unicode, 16-term/512-byte query boundaries,
  exact numbers, and six-attempt/24,000-byte closure. All owned processes and storage were
  cleaned up; the installed service was unchanged.
- Remaining scope: no model continuation, historical-corpus, held-out difficulty or efficacy
  result; NAT7 remains not_cleared. Linux real-JAR execution and controller-SIGKILL recovery
  remain unverified/outside this qualification.
