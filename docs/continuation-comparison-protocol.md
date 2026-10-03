# Continuation comparison: evidence and proposed protocol

Preparation only, 2026-10-03. No new model comparison or fixture qualification has run.
The [development inventory](evaluation-candidates/2026-10-03-development-pool.json) records
17 real fixes in 12 proposed clusters. All are familiar development examples with published
reference fixes. None is held out; this inventory does not satisfy the twenty-candidate gate.
The ordinary latest-handoff/search comparator below is **not implemented**.

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

This inventory qualifies zero new tasks. The existing Java redaction qualifier retains its
previously documented result; it has not been rerun or extended here.

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

Next: qualify a small number of different clusters offline and freeze the ordinary-search adapter
design against the common corpus contract. Summary export and precise chronology are reasonable
qualification candidates; runtime-dependent cases remain conditional. Collect prospective held-out
cases separately. No model spend, deployment, transcript export or threshold change is part of
this documentation slice.
