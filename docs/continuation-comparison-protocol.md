# Continuation comparison: evidence and proposed protocol

Protocol preparation and offline qualification, 2026-10-03. No new model comparison has run.
The [development inventory](evaluation-candidates/2026-10-03-development-pool.json) records
17 real fixes in 12 proposed clusters. All are familiar development examples with published
reference fixes. None is held out; this inventory does not satisfy the twenty-candidate gate.
An offline, synthetic-fixture adapter for the ordinary latest-handoff/literal-search arm now
exists ([below](#offline-literal-adapter)), as does a development
[compact canonical adapter](#compact-canonical-adapter) qualified against the packaged server
with synthetic corpora. Neither is wired into any model runner, and the comparison itself has not run.

The [benchmark and offline qualifier](memory-benchmark.md) remain authoritative for completed
runs. This proposal does not change the runner, its flags, the earlier negative difficulty result,
or acceptance thresholds.

## What was verified

Each entry pins an implementation commit and its immediate parent, the Git committer date,
and a regression file at the fix revision. Git verified those relationships, ancestry to the
recorded main revision, and file existence. A commit date is **not** a bug-discovery date,
event source date, or historical-corpus cutoff. Existing tests are supporting evidence, not
newly replayed independent graders.

| Cluster | Candidate symptoms | Qualification caution |
| --- | --- | --- |
| Time ordering | SQL pagination/bounds; Python 3.9 fractional UTC parsing | Different runtimes; one task per cluster in the development screen |
| Stream replay | Late committed events disappear on reconnect | Migration/restart scope may exceed a bounded task |
| Vector index | Existing embeddings absent from a rebuilt accelerator | Pin a working native extension; skips do not count as failures |
| Export filesystem | Linked paths or failed replacement damage notes | Preserve the trusted stable-directory assumption |
| Outbox concurrency | Short writer contention; legitimate journal unlink | Deterministic synchronization; later baseline includes earlier repair |
| Credential redaction | Quoted/short synthetic credentials survive masking | Same family as the familiar structured-redaction qualifier |
| Capture acknowledgement | Optional listener hides committed capture success | Existing helper may make this a ceiling control |
| Capture CLI | Help side effects, JVM exit, stdin EOF, argument validation | Four triggers share routing and implementation history |
| Reader layout | Mobile controls leave almost no readable transcript | Packaged browser and measurable reader height required |
| Reader reactivity | Optional lists stay stale after evidence selection | Retrieval already returns the correct arrays |
| Reader source navigation | Idea link opens an unrelated human session | Existing helper makes this a potentially easy control |
| Reader numeric evidence | Number coercion hides distinct captured tool results | Group preview disclosure with identity loss, not another family |

These proposed clusters prevent related variants from inflating the pool. All still share one
repository's history. Sample at most one candidate per cluster in a five-task development screen
and report that dependence. Do not pad to twenty with documentation changes, new features or
variants of the same failure.

## Qualify before involving a model

1. Export the pinned pre-fix tree without Git history, later plans, reference patches or hidden
   tests. Preserve legitimate tests/docs already in that revision. Hash the exact worker input,
   task prompt, toolchain and controller-owned grader.
2. Write identical independent checks through interfaces available in **both** snapshots. Some
   existing regressions use helpers or fault-injection seams introduced by their fix; copying
   them onto the baseline is not valid qualification.
3. Require the baseline to fail named behavioral assertions while preservation checks pass, and
   the reference to pass the complete identical grader. Verify test names/counts, exit status and
   input hashes. Concurrency checks need deterministic synchronization, not a lucky stress loop.
4. Separate behavior, timeout and infrastructure failure. Compilation/dependency failures,
   skipped tests, missing native/browser runtimes, flakes or an already-passing baseline
   disqualify that registration. Corrected fixtures need a new version and fresh qualification;
   never silently repair a case after seeing arm outcomes.

Four inventory candidates now pass scoped offline contracts. The summary-export fixture reproduces
three named baseline failures, preserves four other behaviors, and passes all seven checks on the
reference. Its [qualification report](evaluation-results/2026-10-03-summary-export-qualification.json)
records the immutable snapshots and worker-input hash. The checks cover linked destinations, hard-link
alias preservation, repeated exports with an explicit mode, root aliases, template failure and
traversal. They do not establish interrupted-write or crash durability or adversarial rename safety.
The event-chronology fixture independently exercises the canonical SQLite feed: mixed-precision
first-page order, complete bounded pagination and inclusive nanosecond windows fail on the baseline;
four preservation checks pass, and all seven checks pass on the reference. Its
[qualification report](evaluation-results/2026-10-03-event-chronology-qualification.json) records
fixed inputs and outcomes. Its scope excludes PostgreSQL and broader recall/search behavior.
The capture-ack fixture is a potentially easy control, not evidence of hard recovery or recall
benefit: an optional-publication helper already existed on the baseline. Against the real ingestion
service and transactional SQLite store, four named post-commit acknowledgement and independent
terminal-notification checks fail on the baseline; four preservation checks (acknowledgement shape,
append-only retry, publication order, rollback without publication) pass, and all eight pass on the
reference. Its [qualification report](evaluation-results/2026-10-03-capture-ack-qualification.json)
records fixed inputs and outcomes. It qualifies the service commit boundary only, not the REST/MCP
acknowledgement claim.
The journal-race fixture is a familiar development concurrency qualification run through a fixed
isolated Python recipe. A real second SQLite connection is deterministically committed between the
outbox's journal open and its check. The grader proves that the opened inode went from one link to
zero; without that proof it reports an infrastructure error. Enqueue during the race, queue
construction during the race and safe replacement-journal recheck fail on the baseline. Ordinary
enqueue, unsafe replacement rejection, unlinked database/lock rejection and bounded repeated
disappearance with closed descriptors pass on both snapshots, and all seven pass on the reference.
Its [qualification report](evaluation-results/2026-10-03-journal-race-qualification.json) records
fixed inputs, outcomes and runtime versions. It does not cover delivery, the hook entry point,
other filesystems or crash durability.
The existing structured-redaction fixture, outside this inventory, was also rerun successfully with
its original inputs. All five are familiar development fixtures; qualification establishes neither
task difficulty nor recall benefit. The inventory remains below the twenty-candidate gate.

## Freeze the evidence corpus

For a prospective case, freeze the corpus at the actual interruption checkpoint **before** the
continuation or fix begins. Register the exact cutoff, immutable snapshot hash, project/session
scope and provenance. For a historical development replay, prove each included item existed at
that checkpoint. A fix timestamp or caller-supplied/backdated observedAt alone is insufficient.

Both history arms can access the same allowed information: original scoped transcript/event
content and handoffs or structured intent that already existed. Preserve source dates, known
staleness and contradictions. Apply the same declared sanitization before either arm consumes
it. Record exclusions and reasons. Exclude later fix discussions, reference patches, hidden
answers and hindsight summaries; freeze/hash derived indexes too. Record and separately cost
any model-assisted preparation instead of hiding it inside a baseline.

No corpus is staged and no personal transcripts or live rows are exported by this change. Mark
missing authentic history as no_history: such cases can qualify as coding controls with empty
history in every arm, but cannot establish recall benefit. Label authored synthetic history as
development material.

## Proposed ordinary-search comparison

All arms share current source, public task prompt, model/version, effort, coding tools, toolchain,
isolated worker setup and fixed wall-clock budget. Exact model and time values must be explicit
in the registration; none is selected or run here.

| Arm | Historical evidence |
| --- | --- |
| Bare | None; the current task/source remain available |
| Ordinary handoff/search | Latest eligible handoff plus bounded literal search over the frozen transcript/event corpus |
| Black Box | The same latest handoff plus bounded Black Box retrieval over the same allowed corpus |

Use one visible history_search(query) interface for both history arms, with identical provenance
fields, result/error schema, call limit and byte accounting. Allow model-selected follow-up queries
so the ordinary baseline is not weakened to a single fixed query. Its adapter needs documented,
deterministic lexical matching and stable ordering, including filenames/exact identifiers. Freeze
its tokenizer, ranking, deduplication, ties and extraction rules before running; qualify it with
relevance controls also used for the Black Box adapter. Neither arm may access unrestricted
history or the other adapter through another tool.

Proposed development defaults: 24,000 UTF-8 evidence bytes total per history arm, at most six
searches, and 6,000 bytes per batch. The common handoff occupies at most 6,000 bytes and counts
against that total. Dates, headers, source references, diagnostics and duplicate results consume
the budget. A wrapper enforces remaining bytes, clips at valid Unicode boundaries and discloses
truncation. Record under-filled arms rather than padding them. These are **proposed adapter
settings, not existing runner options**.

This bounds historical evidence, not total model context. Record actual input/output and cached
tokens when available, retrieval/model/full task time, failed commands and delivered history bytes.
Unknown counts stay null. Apply the same agent-context and time policies; report preparation,
indexing and qualification costs separately. Verify identical task/source inputs and common
handoff bytes for every paired trial. Infrastructure failures invalidate pairs; do not quietly
replace unfavorable observations.

The existing synthetic handoff/Black Box arm checks round-trip parity, not this search comparison.
A future adapter must first pass offline fake-corpus and budget/provenance checks; those checks
would still establish infrastructure only.

## Offline literal adapter

`scripts/benchmarks/blackbox_memory/history_search.py` implements the ordinary arm's handoff and
`history_search(query)` contract against an explicitly selected frozen corpus. It is standard
library only, runs on Python 3.9, and has been exercised only with synthetic fixtures. It never
reads live history, transcripts, databases, credentials or servers, and never calls a provider
or the network. It is a development comparator, **not** a model-runner integration: no arm,
budget, gate or existing runner changed, and it supplies no efficacy evidence.

**Frozen corpus.** The controller passes one manifest path plus that manifest's exact SHA-256.
The manifest (`blackbox.history-corpus/v1`) names a sibling items file and its SHA-256, the item
count, one project and a session allowlist, an inclusive cutoff, declared exclusions with reasons
and a provenance label. Each item (`blackbox.history-items/v1`) has an ID, kind
(`handoff`, `message`, `event`, `decision`, `observation`), project, session, `observed_at`, an
optional `recorded_at`, a source reference and text. Validation rejects the **whole** corpus
before any delivery on a hash or count mismatch, non-UTF-8 bytes, unpaired surrogates,
duplicate JSON keys, non-finite numbers, unknown or missing fields, duplicate item IDs, an item
whose ID is declared excluded, an out-of-scope project/session, a date after the cutoff, a
malformed timestamp or a size bound (manifest 64 KiB, items file 8 MiB, 10,000 items, 64 KiB
text, 1 KiB source reference). Timestamps take 1–9 fractional digits and `Z`/`±HH:MM`; they
are compared as exact integer UTC nanoseconds (dates before 1970 included), and original strings
are delivered verbatim. `recorded_at` is checked against the cutoff only when present.
A matching hash and in-range `observed_at` show only that the declared metadata is consistent;
they do **not** prove an item was authentically available at the checkpoint. Chronology stays a
registration requirement for any real corpus.

**Handoff first.** `start()` delivers, exactly once and before any search, the `handoff` item with
the greatest `observed_at` (ties: smallest item ID). It is not a search attempt, is capped at 6,000
bytes and counts against the 24,000-byte total. The start status is `no_history` for an empty
corpus, `no_handoff` when items exist but none is a handoff, and otherwise `ok`.

**Literal search.** Text, source references and queries are case-folded per character with
`str.casefold()`; whitespace runs collapse to one space. No Unicode normalization or stemming is
applied, so NFC and NFD spellings differ. Query terms are whitespace-separated folded tokens,
de-duplicated in first-seen order, with punctuation kept, so `src/export/SummaryWriter.java` and
`NAT-315` stay single terms. A term matches as a substring of an item's folded text or folded
source reference; the exact full query is the token sequence joined by single spaces. Ranking:
exact full-query match, then distinct matched-term count, then newer `observed_at`, then smaller
ID. At most 20 ranked results are rendered per search. Matches beyond that ceiling and results
dropped for bytes are both counted in `omitted_results` and set `truncated`, so for every successful search
delivery the returned results plus omitted results equal `total_matches`. Items are never merged: identical text from
different items returns separate results with their own provenance, and stale or contradictory
items stay in the ranking with their dates. Repeated queries are executed and charged again.

**Excerpts.** Offsets are code-point offsets into the original text. A folded-to-original index
map widens any match to whole original characters, so case-fold expansions (`ß`→`ss`, `ﬁ`→`fi`,
`İ`→`i̇`) report correct original spans. The anchor is the first exact phrase in the text, else the
earliest matched term in the text, else none (a source-reference-only match). The window starts
100 code points before the anchor and spans 640 code points, extended to cover the anchor.

**Shared envelope.** Every delivery is one compact UTF-8 JSON line with schema
`blackbox.history-delivery/v1`: backend, corpus ID, sequence, type (`handoff`, `search`,
`terminal`), status (`ok`, `no_history`, `no_handoff`, `empty`, `error`, `exhausted`), attempt
counters, the pre-delivery budget (`remaining_before`, `delivery_cap`), echoed folded query
terms, total matches, results, omitted-result count, a truncation flag and an error object.
Each result carries ID, kind, project, session, both dates, source reference, match details
(exact, matched terms, matched fields, anchor) and an excerpt (start, end, text length,
budget-clip flag, text). A future Black Box backend should emit this same envelope. Payloads never
contain their own byte count; exact sizes are kept in host-only accounting.

**Budget.** At most six search attempts, invalid requests included. Each delivery is at most
`min(6000, remaining)` bytes, counting the whole JSON line and newline: provenance, echoed terms,
diagnostics and repeated content included. The session total is at most 24,000 bytes. Results
are added in rank order; the first one that does not fit has its excerpt clipped at a code-point
boundary to the largest prefix that still fits, later results are omitted and counted, and
`truncated` is set. Empty results and error responses consume an attempt and bytes. A request
after the sixth attempt receives one metered `terminal`/`attempt_limit` response, and delivery
closes. If a request's smallest valid response cannot fit, a metered `terminal`/`byte_budget`
response is sent when it fits; otherwise delivery closes **without emitting anything**. A closed
session emits nothing further, so no diagnostic is ever unmetered. Request lines are bounded
(4 KiB line, 512-byte query, 16 terms, no non-whitespace control characters).

**Single controller-owned session.** State lives in one in-process `Session`; there is no
persistent ledger or database. The CLI is a single-process demonstration, not a deployed tool or
tamper-resistant sandbox:

```bash
# Host-only summary of a frozen corpus (prints hashes, counts, start status).
python3 scripts/benchmarks/blackbox_memory/history_search.py validate \
  --manifest path/to/manifest.json --manifest-sha256 <sha256>

# Emits the handoff line, then answers one {"query": "..."} JSON line per stdin line.
python3 scripts/benchmarks/blackbox_memory/history_search.py serve \
  --manifest path/to/manifest.json --manifest-sha256 <sha256>
```

Corpus validation failures exit 2 with a controller error on stderr and nothing on stdout. On
exit, `serve` writes host accounting (deliveries, exact bytes, attempts, close reason) to stderr.
A future model runner must keep this one session for the whole trial, prevent restart or reset
(restarting the process would restore the full budget), and deny any alternate history access.
Still outstanding:

- runner integration;
- an authentic staged corpus with registered availability evidence (the
  [snapshot builder](#frozen-snapshot-corpus-builder) is development tooling only);
- adjudication;
- the difficulty and accepted-action studies.

### Backend seam and the compact-search blocker

`Session` now takes an optional backend. `LiteralBackend` is the default and holds the ranking
above. A backend returns the folded terms, an **exact** total and at most 20 ranked
`(item, match)` pairs. The session still owns the handoff, excerpt rendering, envelope, attempts and
bytes, and `backend` in the envelope is the backend's name. A backend that cannot prove an exact
total or a deterministic order must raise `BackendFailure`. The session then closes as
`infrastructure_error` and emits nothing, so the model never sees an unmetered or partial
diagnostic. Host accounting records the error. A golden trace pinned from the pre-seam code checks
that literal delivery bytes and accounting stayed identical: seven scripted sessions covering ranking,
case-fold expansion, deep excerpt anchors, clipping, both budget closes, invalid requests,
`no_handoff` and `no_history`.

A `compact-search-v1` backend over the original `GET /api/search/compact` was evaluated (NAT-319)
and **not built**. It would have been a compact-search arm only, not the full, hybrid or semantic Black Box arm.
That API could not satisfy this envelope for useful corpora without a product change:

- **Bounded totals and incomplete hits.** SQLite candidates stop at 200. Below that ceiling,
  ungrouped candidate counts remain exact, including omitted hits; at the ceiling they cannot
  establish the total. At most 50 hits are returned, with further byte fitting and no cursor to
  enumerate the remaining match IDs.
- **Nondeterministic ties.** Ingested event IDs are random UUIDs, and order is observed instant then
  event ID descending. A run of equal-instant matches that crosses the returned window can select
  different source items on fresh databases. Sorting the returned hits cannot recover missing ones.
- **Unrepresentable terms.** A whole-token quoted term keeps facet-looking text literal. A term
  containing `"` has no escape, though, and `%`/`_` are live `LIKE` wildcards with no `ESCAPE`
  clause. Matching is also ASCII-only case-insensitive, and `{}` matches every event through the stored
  metadata JSON.

Restricting corpus size, timestamp ties or query characters would selectively abort otherwise valid
trials and bias paired outcomes. Such restrictions do not satisfy the shared corpus/query contract. The prerequisite was a separate read-only product
API slice with typed literal terms and exact keyset pagination (NAT-320, `mode=canonical`). The
adapter below uses that slice. No model comparison or accepted-action result exists. The usefulness gate stays not_cleared.

## Compact canonical adapter

`compact_history_search.py`, `compact_server.py` and `compact_backend.py` (NAT-319) serve the
unchanged `history_search` session from `GET /api/search/compact?mode=canonical`. Standard library,
Python 3.9. **Status:** fake contracts and independent packaged-JAR qualification passed. The
[qualification report](evaluation-results/2026-10-03-compact-corpus-qualification.json) records
26 fresh private server runs and 10 pre-launch rejection checks, with identical delivery bytes
across each repeated batch, exact database readback, budget checks and owned cleanup. This
qualifies the compact-search adapter only; no model comparison or efficacy result exists.

**Unchanged literal arm.** The literal parser, ranking, `source_ref` matching, the 32 literal tests
and the pinned golden trace are unchanged. The previous inline stdin/stdout loop is now
`history_search.stream()`, a pure move. A pinned CLI transcript over the golden corpora is
byte-identical before and after.

**Private server owner.** `PrivateCompactServer` is a context manager that takes:

- an explicit trusted JAR path and its SHA-256;
- an explicit Java 21 executable;
- a corpus already validated by `load_corpus`.

There is no server-URL parameter, no reuse or discovery of databases, and no download.

On launch it:

- creates a fresh 0700 temporary root holding the SQLite file, a private HOME/TMP/XDG and the log;
- copies the JAR into that root and re-hashes the copy;
- builds the environment from scratch, so no `SBA_*`, `SPRING_*`, JVM, proxy or provider variable
  is inherited;
- loads only the classpath `application.yml`, overridden on the command line. Redaction is off,
  `max-text-length` is 65,536, the summary backend is `local` with local AI off, and embeddings,
  ask-embeddings, Elasticsearch, judge, editor and workflow retirement are off. Transcript roots
  point inside the private HOME;
- binds a fresh loopback port, never 8766, 8799 or 18879, in the server's own process group.

Identity checks:

- Before any write: the child is the only listener on the port, holds the private database file,
  has a command line naming the copied JAR and database, and has an empty database.
- Before every request, captures and searches included: the child is still the only listener.
- Missing `lsof`/`ps` fails closed.

Cleanup:

- It signals only its own process group, whose ID is the launched session leader's PID. Liveness
  checks use `ps` and the listener, never `wait`, so the leader stays unreaped and its PID, which
  is also the group ID, cannot be reused while the group is signalled.
- TERM, then a bounded wait until no live group member remains, then KILL. Descendants that ignore
  TERM or outlive an already-exited leader are included. Only then is the leader reaped. If the
  group cannot be emptied, the storage is kept and cleanup fails closed.
- It removes the root only after marker, inode and owner checks.
- SIGTERM/SIGHUP unwind through the same path.

This isolates trusted code; it is not a JVM sandbox.

**Capture and fidelity.** Every item goes through `POST /api/events/idempotent`:

- The receipt UUID is uuid5 of a fixed namespace, the manifest SHA-256 and the item ID.
- The synthetic client session is a hash of the original (project, session) pair and never
  becomes a path.
- All events share one synthetic cwd/project. `load_corpus` already enforces the selected scope.
- `text` is unmodified, `toolName` is null and `metadata` is `{"sourceRef": source_ref}`.
- The event type is neutral: not terminal, embeddable or a prompt type.
- `observedAt` is the UTC instant computed with integer arithmetic and nine fractional digits.
  Offset normalization can reach year 0 or year 10000; those stay in the domain.

Before `Session.start()`, a read-only SQLite proof requires:

- exactly N events and N receipts with the expected capture IDs;
- each acknowledgement's event and session IDs;
- text exactly equal, and NULL exactly when the item is Java-blank;
- a NULL tool name;
- metadata JSON semantically equal, with the stored bytes kept for matching;
- the exact observed nanoseconds, spelled exactly as `Instant.toString()`;
- a one-to-one session mapping with the synthetic cwd.

Any failure before the start is a host-only `ControllerError`: no delivery, exit 2.

**Search.** Query validity is the frozen `parse_query` only. Terms are the distinct raw-case tokens
of `query.split()`. Every valid query (at most 512 bytes and 16 tokens) fits the API limits of
16 terms, 512 code points each and 2,048 bytes in total. There is no case folding, no local
inclusion fallback and no union or intersection substitute. Server inclusion is authoritative.

The backend pages to exhaustion with `limit=50`, `maxBytes=64000`, the fixed project and the
corpus cutoff as `until`. It verifies:

- HTTP 200 and the exact page and hit key sets;
- `mode`, `limit`, `maxBytes`, count and booleans;
- `appliedFilters`: UTF-16-sorted terms, project and the Java `Instant.toString()` cutoff;
- every event ID is known, with its verified session mapping and exact observed nanoseconds in
  canonical `Instant.toString()` form;
- strictly descending (observed, event ID) across pages;
- a non-repeating cursor with no empty progress, and a null cursor only on exhaustion;
- bodies of at most 64,000 bytes;
- integer `limit` and `maxBytes`;
- completeness: the returned set must equal every indexed event whose stored text or metadata
  contains all terms. This verifies the server; it never substitutes for it.

Bounds:

- Pages are bounded by N+1, not ceil(N/50)+1, because byte fitting may return fewer than 50 hits.
- Each request has an absolute 10-second wall-clock deadline across connect, headers and body, so a
  dribbled reply cannot extend it. Each search has a 300-second deadline, checked again after
  every page and after local validation, so late results are never accepted.
- Any HTTP error, including `cursor_unavailable` and `budget_exceeded`, malformed response,
  transport failure or identity loss raises `BackendFailure`. The session closes silently and the
  pair is invalid; the CLI exits 3. A controller failure after the handoff, including a cleanup
  failure, also exits 3 and is reported as `phase: cleanup` with `pair_invalid: true`. Exit 2 is
  reserved for failures before any delivery.

Delivery:

- `total_matches` is the exact count.
- Order is newest `observed_at` first, then smaller corpus item ID, which makes equal-instant ties
  deterministic across fresh random event IDs. Up to the first 20 fit within the delivery budget.
- `match.terms` lists every raw term; the API ANDs them.
- `match.fields` is `text` and/or `metadata`, whichever actually contain a term. Every hit must
  contain each term in one of them, or the search fails.
- `exact` means the single-space-joined raw phrase occurs in the original text.
- `anchor` is the first exact phrase, else the earliest raw term in the text, else `null`.
- The session still owns the handoff, excerpts, six attempts, 6,000/24,000 bytes and full UTF-8
  wire accounting. Results carry the original corpus provenance and text, never API excerpts.

**Disclosed differences from `literal-v1`.**

| Aspect | `literal-v1` | `compact-canonical-v1` |
| --- | --- | --- |
| Case | Per-character casefold | Case-sensitive raw tokens |
| Terms | Any term (OR), ranked by coverage | Every term (AND) |
| Echoed terms | Folded | Raw |
| Ranking | Exact phrase, term count, recency, ID | Recency, then ID |
| Whitespace in exact phrase | Runs collapsed | Single spaces in raw text |
| Source reference | Matched as plain `source_ref` text (`fields: source_ref`) | Matched inside stored metadata JSON (`fields: metadata`) |

Because metadata is JSON, the key `sourceRef`, braces, quotes and JSON escapes (`\"`, `\\`) are
matchable. That is native API behavior, disclosed rather than filtered. A query naming them can
match every item.

**Limitations.**

- Real Jackson/SQLite handling of NUL, years 0 and +10000, and 64 KiB text passed for the
  pinned JAR on macOS. This does not establish behavior on every runtime; mismatches fail closed.
- Each request pays an `lsof` identity check, so large corpora capture slowly.
- `recorded_at` is not sent to the server; it is delivered from the corpus.
- Searching after an embedded NUL passed in the pinned runtime. If another runtime misses a
  term, the completeness check fails closed.
- The database proof needs the JVM to hold the SQLite file open, as verified in every private
  server run. Without that, it fails closed.
- The pre-launch free-port check treats `lsof` exit 1 with no output as no listener. Every later
  check requires the launched PID exactly.
- A SIGKILL of the controller orphans the server's own session; nothing reaps it.
- The page bound is defense in depth; the order check fires first.

## Frozen snapshot corpus builder

`history_corpus_build.py` (NAT-325) converts one hash-pinned standalone Black Box SQLite snapshot
into the unchanged `blackbox.history-corpus/v1` manifest and `blackbox.history-items/v1` items file
that both adapters load. Standard library, Python 3.9. **Status:** synthetic-snapshot qualification through both adapters,
including four fresh packaged-server runs; see the
[qualification report](evaluation-results/2026-10-03-frozen-corpus-builder-qualification.json).
No development inventory history is staged by this change; every inventory member stays
`not_staged` until a separately registered build with chronology evidence exists.

**Availability is unverified.** A snapshot hash identifies bytes; it never shows that an event was
available before the cutoff. The schema has no `recorded_at` column, so items omit it, the manifest
provenance says `availability unverified`, and the host result reports
`"availability": "unverified"`. No registration or receive time is invented.

**Inputs.** All explicit; nothing is inferred:

- `--snapshot` and `--snapshot-sha256`: a standalone file, such as one produced by
  `scripts/storage/blackbox_backup.py`;
- `--corpus-id` and an exact `--project` label;
- one or more `--session`, each an internal `agent_sessions.id`. `client_session_id` is unique only
  together with `source` and is never accepted as a selector;
- `--cutoff`: inclusive, RFC 3339 with 1–9 fractional digits and `Z` or an offset, compared as
  exact integer nanoseconds;
- `--exclusions`: a JSON list of `{"id", "reason"}`, possibly empty, with unique IDs and non-blank
  reasons;
- `--output`: a directory that must not exist yet.

Dry run is the default. It validates syntax and reads only the exclusions file: no snapshot read,
no SQLite connection, no output. `--execute` materializes. The exclusions file is opened
non-blocking without following a symlink and must be a regular file, so a FIFO or device fails with
`invalid_exclusions` instead of hanging.

**Scope.** An event is included when its session is allowlisted, its attributed project equals the
label, and its `observed_at` is at or before the cutoff, minus declared exclusions:

- Attribution is the event's `metadata.repo` when that is a string that is not blank by Java
  `String.isBlank` (the server's `firstNonBlank` rule). Otherwise it is the snapshot's
  `agent_sessions.cwd`.
- The comparison is exact: no trimming, case folding, trailing-slash, alias or worktree
  normalization.
- The cwd fallback is mutable current session state, not historical attribution proof.
- Late, other-project and unattributed rows are filtered with explicit counts; included rows are
  never truncated.
- Every exclusion must match an in-scope event, so a typo fails rather than becoming a no-op.

**Mapping.** Canonical text only:

| Item field | Value |
| --- | --- |
| `id` | `agent_events.id` |
| `kind` | exact `event_type` `Handoff`, `Decision`, `Observation` map to `handoff`, `decision`, `observation`; every other type (messages, tools, `Idea`, `Evidence`, `Projection`) maps to `event`; `message` is not produced |
| `project` | the declared label |
| `session` | internal session ID |
| `observed_at` | stored text verbatim, nanoseconds kept |
| `source_ref` | `blackbox-sqlite:<snapshot sha256>:agent_events:<event id>` |
| `text` | `agent_events.text` exactly; empty, whitespace, Unicode and NUL preserved |

Omitted and disclosed in the host result: `role`, `turn_id`, `tool_name`, tool input/output JSON,
`metadata_json` (read only for `repo`), `human_text`, event `source`/`client_session_id`, other
session columns, every other table, and `recorded_at`. A selected event whose text is NULL fails
the build unless it is explicitly excluded. Empty text is never invented, and payloads never stand
in for authored text. This is a text corpus, not full event history.

**Validation order.** The first failure stops the build:

1. Arguments.
2. The output parent is opened without following symlinks, and the output name must be absent.
   If it is the same directory as the snapshot's parent (compared by directory identity, so
   differently spelled aliases count), the output may not use the snapshot's own name or its
   `-wal`, `-shm` or `-journal` name. Names are compared under canonical Unicode normalization
   plus casefold, the same contract as `blackbox_backup.py`; compatibility-distinct names are not
   merged. This is checked before any output mutation, as `output_is_snapshot` or
   `output_is_sidecar`.
3. The source parent and leaf are opened without following symlinks. The source must be a
   regular, non-empty file within 2 GiB, with no `-wal`, `-shm` or `-journal` sidecar.
4. A raw streamed copy goes into a private 0700 staging directory while hashing. The staging
   directory is created exclusively; a name collision fails as `staging_collision` and the
   existing path is left untouched. Source identity,
   size and timestamps must be unchanged and the sidecars still absent. The hash must equal the
   expected value.
5. The copy's header must be SQLite with rollback-journal format bytes; WAL mode is refused.
6. Only the verified copy is opened, with `mode=ro&immutable=1`, `trusted_schema=OFF`,
   `query_only=ON`. The source is never opened with SQLite. Checks:
   UTF-8 encoding, `quick_check`, and that `agent_sessions` and `agent_events` are ordinary tables
   whose required columns are not generated. Views, virtual tables and missing columns are
   refused.
7. Every allowlisted session exists exactly once with TEXT identity columns.
8. Rows of allowlisted sessions, in ID order, at most 1,000,000:
   - duplicate event ID check;
   - event `source`/`client_session_id` must agree with the session;
   - TEXT `observed_at` must parse;
   - late rows are filtered;
   - metadata must be a bounded JSON object with unique keys and finite numbers, and `repo` a
     string, null or absent;
   - unattributed and other-project rows are filtered;
   - exclusions are removed;
   - event ID syntax is checked;
   - NULL text fails;
   - text must be TEXT, strict UTF-8 and at most 64 KiB.
9. Every exclusion must have matched.
10. The private snapshot copy is unlinked, and its absence verified, before anything is
    published. If that fails, the build stops with `cleanup_failed`.
11. The existing item, items-file and manifest limits are checked, then `load_corpus` validates
    the staged files.
12. Publish: the output directory is created exclusively (0700). Items are linked first and the
    manifest last, as the commit marker.

One 300-second deadline covers the copy, every query and row, and the final steps. It is checked
again after rendering, after self-validation, before the output directory is created and before
the manifest link. Expiry fails with `build_timeout`, and the output is rolled back.

Cleanup removes only entries whose identity the builder recorded when it created them: the fixed
staged files, its staging directory, and its own output links and directory. Pre-existing or
replaced paths and foreign files are preserved. Errors are stable codes only
(`{"status":"failed","error":"..."}`), never row text, paths or exception detail. A failed cleanup
step is never reported as success. The build exits non-zero with `"error":"cleanup_failed"` plus
these booleans:

- `published`: whether both the items and manifest links are in the output, so the corpus
  loads;
- `staging_removed`: whether the staging directory is gone.

When an earlier failure caused the cleanup, its stable code is kept as `cause`.

**Publication failures.** If publication fails after the output directory exists, for example a
failed directory `fsync` after both links, the builder rolls back its own links and directory:

- **Complete rollback:** the original error is reported (for example `build_failed` or
  `build_timeout`) and no output remains. A directory kept only because foreign files were added
  counts as complete; the foreign files are preserved.
- **Incomplete rollback:** the result is `cleanup_failed`, with the original `cause`, `published`,
  `staging_removed`, and two more fields. `output_removed` says whether the builder removed its
  output directory. `output_entries` lists which of `items.json` and `manifest.json` remain.

  A failed rollback is never reported as success. When both links remain, `published` is `true`
  and the corpus still validates.

**Success with a staging failure.** A build that published successfully but could not remove
staging reports `cleanup_failed` with `published: true`. Staging then holds only items and
manifest links, because the snapshot copy was already removed.

These guarantees cover ordinary errors and interrupts handled by the process. They do not cover
abrupt termination such as `SIGKILL`, power loss or a crash. Then a `.history-corpus-build-*`
staging directory, which may hold the full snapshot copy, or a partial output directory may
remain. An output without `manifest.json` is not a published corpus.

The corpus schema and parser bounds are unchanged. Identical inputs give byte-identical manifest and
items files: items are ordered by observed nanoseconds then ID, sessions and exclusions by ID. The
builder adds no build-time timestamps and no snapshot, staging or output filesystem paths. Source
timestamps (`observed_at`, the cutoff) and declared labels, including the project path, are kept
verbatim.

## Registration and unchanged gates

Before model runs, commit candidate/cluster IDs, source/grader hashes, corpus scope/cutoff/hashes,
adapter/controller versions, exact model/settings, byte/time/tool budgets, paired seeds and
randomized arm schedule, disqualification rules and the outcome rubric. Freeze the shared context
window/compaction policy, tool-output retention/truncation policy, wall-clock start/end, and whether
retrieval is charged to that clock. Equal policies and caps do not imply identical consumed tokens.
Keep reference patches,
hidden graders and later history inaccessible to workers. Do not tune after outcomes; deviations
need a new registration and must not be silently pooled with the original run.

The five-task development difficulty screen is unchanged: continue only at **one through four
bare passes**. Zero is a floor result; five is a ceiling result. Stop and record either result.
A repaired historical checkout is a replay outcome, not recovered useful current work. Report
paired losses as well as wins.

The existing usefulness gate remains not_cleared and requires:

- Twenty evidence-backed historical candidates adjudicated for current usefulness, staleness,
  duplication and source dates.
- Five resumed tasks and the same-model/budget/source-window ordinary handoff/search comparator.
- At least 70% useful supported suggestions and below 10% stale/duplicate suggestions, under the
  adjudication rubric to be defined and preregistered before adjudication.
- At least three **observed useful actions accepted by a human in actual follow-up work** that
  the comparator missed, for a positive usefulness result.

If the thresholds are missed, report an explicit negative finding; that is a valid study outcome,
not a cleared positive usefulness gate. This proposed protocol additionally recommends a
prospectively held-out evaluation set separate from this exposed development pool. That is a
proposed study-design control, not a change to the existing numeric gate.

Freeze matched suggestion identities and blind human adjudication to arm labels where practical.
Record approval separately from implementation and verify what happened. Hidden-test success,
a model verdict, patch review or this document cannot manufacture an accepted operational action.
Make denominators and missing outcomes explicit. Make no efficacy claim from this familiar pool.

Next: qualify a small number of different clusters offline, then register a same-model comparison
using the qualified ordinary literal and compact canonical adapters with identical budgets.
Summary export, precise chronology, the capture-acknowledgement easy control and the journal race
are qualified; other runtime-dependent cases remain conditional.
Collect prospective held-out cases separately. No model spend, deployment, transcript export or
threshold change is part of
this protocol and qualification slice.
