# Continuation comparison: evidence and proposed protocol

Protocol preparation and offline qualification, 2026-10-03. No new model comparison has run.
The [development inventory](evaluation-candidates/2026-10-03-development-pool.json) records
17 real fixes in 12 proposed clusters. All are familiar development examples with published
reference fixes. None is held out; this inventory does not satisfy the twenty-candidate gate.
An offline, synthetic-fixture adapter for the ordinary latest-handoff/literal-search arm now
exists ([below](#offline-literal-adapter)); it is not wired into any model runner, and the
comparison itself has not run.

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

Three inventory candidates now pass scoped offline contracts. The summary-export fixture reproduces
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
The existing structured-redaction fixture, outside this inventory, was also rerun successfully with
its original inputs. All four are familiar development fixtures; qualification establishes neither
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
That runner integration, the Black Box backend, an authentic corpus builder, registration and
adjudication, and the difficulty and accepted-action studies remain outstanding.

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

A `compact-search-v1` backend over the real `GET /api/search/compact` was evaluated (NAT-319) and
**not built**. It would have been a compact-search arm only, not the full, hybrid or semantic Black Box arm.
The current read-only API cannot satisfy this envelope for useful corpora without a
product change:

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
trials and bias paired outcomes. Such restrictions do not satisfy the shared corpus/query contract. The prerequisite is a separate read-only product
API slice with typed literal terms and exact keyset pagination. No compact arm, model run or
accepted action exists. The usefulness gate stays not_cleared.

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

Next: qualify a small number of different clusters offline. Qualify a Black Box backend against
the same corpus contract and delivery envelope once the read-only search prerequisite above exists.
Summary export, precise chronology and the capture-acknowledgement easy control are qualified;
runtime-dependent cases remain conditional.
Collect prospective held-out cases separately. No model spend, deployment, transcript export or
threshold change is part of
this protocol and qualification slice.
