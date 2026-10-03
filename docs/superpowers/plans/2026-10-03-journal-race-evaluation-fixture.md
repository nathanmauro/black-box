# Rollback-journal enqueue race offline fixture qualification (NAT-7 child)

## Scope and safety contract

Add one fixed, familiar development concurrency fixture, `journal-race`, to the offline
repository qualifier. Baseline `4bde822853d8e4b829626faf6e9a2238762f5263` and reference
`855e932f7464f872476d25308f1b81e0bfce06da` are immutable reviewed commits and an immediate
parent pair. Verify parentage, the exact four-path reference delta
(`scripts/hooks/capture_outbox.py`, `scripts/hooks/test_capture_outbox.py`,
`docs/durable-capture.md`, `docs/superpowers/plans/2026-10-03-outbox-journal-race.md`) and pinned
blob hashes before any execution. The only reference overlay is `scripts/hooks/capture_outbox.py`.

This is the first Python fixture. The existing Java/Maven recipe, its export paths, report
classification and all four existing manifests, tasks and graders stay byte-identical in behavior
and bytes. The Python path is selected only by a fixed reviewed spec (`runtime: python`); there is
no arbitrary revision, command, module, discovery, overlay or export.

Worker export is an exact allowlist from the baseline: `scripts/hooks/capture_outbox.py`,
`scripts/hooks/test_capture_outbox.py`, `scripts/hooks/sba-agent-hook.sh`,
`scripts/hooks/fixtures/capture-redaction-v1.json` (a hidden dependency of the hook test suite),
`README.md`, `LICENSE`, plus the neutral task. Any other or missing entry fails closed.
Qualification runs only the controller-owned grader, never the broad hook suite (HTTP,
subprocess, jq, OpenSSL), and never calls `drain` or `_hook`.

Fixed Python recipe: an explicit resolved Python 3.9+ interpreter (`--python`, default the
qualifier's own interpreter), run as `python -I -S -B <grader> <capture_outbox.py> <report>` —
isolated mode, no site, no bytecode, scrubbed environment (PATH/HOME/TMPDIR/locale only), private
home/temp, the module imported explicitly by file path. A version preflight records the Python,
SQLite and platform versions. Existing finite timeout, process-group cleanup, trusted no-follow
inputs, input hashes before/after and private-tree removal apply unchanged.

## Grader (independent of the reference test)

Uses only baseline APIs: `Queue(directory, deadline)`, `enqueue`, `check_files`, `close`, module
constants, and SQLite. Never the reference-only `private_file(deadline=...)`. A real second SQLite
connection takes `BEGIN IMMEDIATE` and makes a content-preserving write, so SQLite itself creates
the rollback journal. The grader intercepts the real `os.open` of that journal and COMMITs the
second connection before the caller's descriptor `fstat`, proving the same opened inode moved
from `st_nlink` 1 to 0. Failure to establish that is an infrastructure error
(`race_not_established`), never an accepted behavioral baseline failure.

Expected baseline failures (hypotheses until replay; baseline rejects with `unsafe_file`):

1. `test_commit_between_journal_open_and_check_keeps_capture` — enqueue succeeds with a distinct
   ID, exact bytes, the initial row and bookkeeping intact, and no open transaction.
2. `test_queue_open_during_concurrent_commit` — the same race during `Queue` construction.
3. `test_safe_replacement_journal_is_rechecked_not_reused` — a genuine new SQLite journal created
   after the unlink is opened afresh (new inode), the stale descriptor is closed first, and the
   capture is accepted once the replacement transaction commits.

Preservation controls (must pass on both):

4. `test_ordinary_enqueue_preserved`.
5. `test_unsafe_journal_replacement_rejected` — hard link to an outside sentinel and wrong-mode
   replacement after the unlink are rejected with `unsafe_file`; sentinel and prior rows unchanged.
6. `test_unlinked_database_and_sender_lock_still_rejected`.
7. `test_repeated_journal_disappearance_is_bounded_and_closes_descriptors` — every journal open is
   raced; the capture is rejected within the shared deadline plus slack, every descriptor closed,
   rows unchanged. The rejection code is not dictated.

## Claim limits

Familiar development concurrency qualification only. No efficacy, hard-task, held-out, model-run
or accepted-action claim. Inventory stays 17 candidates in 12 clusters; qualified members rise to
four (fifth fixture overall) only after actual paired acceptance. NAT-7 remains `not_cleared`.

## Verification

1. Read-only Git evidence for pins, delta and blob hashes.
2. Fake/unit tests for the Python spec, export allowlist, recipe, environment, report
   classification and failure cleanup; then all benchmark tests on Python 3.9.
3. Actual qualification of the pinned pair on Python 3.9; focused replay on newer Python.
4. `git diff --check`; confirm existing fixture bytes/hashes unchanged. Freeze for root review.

## Results

Read-only Git confirmed both commits, the immediate parent relation and the exact four-path delta.
`sba-agent-hook.sh`, the JSON test fixture, README and LICENSE are byte-identical across the pair.
`capture_outbox.py` differs as expected, and the reference test module is pinned only at baseline
because it is never overlaid.

The first grader draft failed closed as `race_not_established` on both revisions. SQLite skips page
writes for a no-op `UPDATE … SET x=x`, so no journal appeared. A content-preserving
`PRAGMA user_version=1` (already 1) creates a real journal. With that change, the identical
grader produced exactly these baseline failures, each `AssertionError: committed concurrent journal
was rejected: unsafe_file`:

- `test_commit_between_journal_open_and_check_keeps_capture`
- `test_queue_open_during_concurrent_commit`
- `test_safe_replacement_journal_is_rechecked_not_reused`

The four controls passed on baseline; all seven passed on reference with zero skips/errors. The
reference's bounded-disappearance control performed about 6,100 real COMMIT races and stopped at its
1 s deadline.

Adversarial self-review ran five wrong fixes against the grader in a throwaway scratch copy: no
link-count check, retry without a deadline, a leaked stale descriptor, nlink 0 accepted for any
name, and reuse of the unlinked descriptor. Every one failed at least one named check as an
assertion. That review found and fixed two grader weaknesses. First, descriptor-closure checks could
pass falsely through fd-number reuse; only closes after the tracked open now count. Second, wrong
fixes surfacing as `OperationalError` were classified as infrastructure; non-outbox exceptions are now
behavioral assertion failures, while `RaceNotEstablished` stays infrastructure.

Qualifier results:

- Python 3.9.6 / SQLite 3.54.0: `passed`, baseline 3 named failures plus 4 passes, reference 7
  passes, stable across five additional runs. The committed
  [report](../../evaluation-results/2026-10-03-journal-race-qualification.json) has worker-input
  SHA-256 `124c56ce54a10c7e0cfcc018239b31d46e733e30c063e7b77a3ccb06a6c9fd8e`.
- Python 3.12.14, 3.13.15 and 3.14.7 / SQLite 3.53.4 (`--python`): `passed` with the identical
  split and worker hash.
- A caller with poisoned `PYTHONPATH` (fake `sqlite3`/`capture_outbox`), `PYTHONSTARTUP` and
  `PYTHONWARNINGS=error` still produced the identical result. No private trees remained.

Unit tests: ten new fake-driven tests cover the spec pins, the exact export allowlist, recipe and
environment, interpreter/version gate, strict report classification, private staging and cleanup,
wrong outcomes, in-run tree/manifest/grader changes, main routing and pinned prior bytes. The full
Python 3.9 benchmark suite passes 160 tests and `scripts/evaluation` passes 22. The qualifier test
module also passes on Python 3.14. The Java recipe, report classification and all prior manifests,
tasks and graders are unchanged. Java fixtures were not replayed in this slice; only the shared
`allowed_export`/`extract_snapshot` gained a defaulted path argument.

Limits: macOS/APFS only. A candidate that stops opening the journal yields `race_not_established`
(fail-closed infrastructure), never a pass. Delivery, the hook entry point, other filesystems and
crash durability are not qualified. The inventory remains 17 candidates in 12 clusters with four
qualified members (five fixtures in total). NAT-7 remains `not_cleared`, with no model runs,
held-out status, difficulty or accepted actions. Root owns Git, CI, PR and merge.

## NAT-324 grader classification correction

Independent grader review found that `accept()` converted every generic exception into an
`AssertionError` without proof of the journal race. A failure of a grader-owned `fstat`, of the
holder `COMMIT`, or of replacement setup could therefore count as an expected behavioral baseline
failure without any inode transition.

Reproduction (before correction): the grader ran in-process against the repository outbox with one
injected fault each. A pre-proof `REAL_FSTAT` `OSError`, a holder `COMMIT` `OperationalError`, and a
second `BEGIN IMMEDIATE` failure while creating the safe replacement after an earlier proof were all
recorded as `failure/AssertionError`. A candidate `OperationalError` after real proof was also
`failure/AssertionError`.

Correction (grader only; no production, Java or prior-fixture change):

- Each grader-owned step runs through `grader_step` and fails as `RaceNotEstablished`. That covers
  the journal open before proof, both `fstat` calls and the holder `COMMIT`, second-connection journal
  creation, unlink-before-`fstat`, unsafe replacement setup, the replacement journal open/`fstat`, and
  the replacement-transaction `COMMIT`. The failure is also recorded on the test.
- `candidate()` re-raises any recorded grader failure, whatever the candidate raised or returned,
  so a blanket catch or conversion inside candidate code cannot mask it. Before proof, any outcome
  (success, `OutboxError` or any other exception) is `RaceNotEstablished`. Only after proof does a
  candidate exception become a behavioral assertion failure. `accept()` and `rejection()` both use
  this. Opens after proof stay candidate-owned (for example, the legitimate `FileNotFoundError` once
  the journal is gone).

After correction, the same injections on both the repository outbox and the pinned baseline source
give `error/RaceNotEstablished` for the three fixture faults and `failure/AssertionError` for the
candidate `OperationalError` after proof. The five wrong-fix mutants from the earlier review are
still behavioral failures, with no errors.

Regression class `JournalRaceGraderClassificationTests` (5 tests) covers:

- pre-proof `fstat` failure;
- holder `COMMIT` failure;
- replacement setup failure after an earlier proof;
- candidate `OperationalError` after proof (behavioral);
- the unmodified grader passing against the current outbox.

Each fixture-fault case is also fed into `classify_python_report`, alongside otherwise-expected
named baseline failures, and must be refused as `race_not_established`. These tests load the
repository's current `scripts/hooks/capture_outbox.py`, so a future incompatible outbox change
would surface here.

Regenerated pins after the grader was final:

- grader `2864687a4aad8f1499ac567e629fba3357bbd26a183804922e6ba6f177be44bd`
  (was `a479c09a…6686`)
- manifest `938e9d0dc08cd07067564ecbf547d2cccf5843e7c606eb0ec1765b39258ecc24`
  (was `65b3dba8…b386`), pinned in the spec
- the task bytes are unchanged

Re-qualification with the corrected grader:

- Python 3.9.6 / SQLite 3.54.0: `passed`, the same three named baseline failures plus four passes,
  then seven reference passes. The worker-input hash is unchanged at `124c56ce…fd8e`, because the
  grader is not part of the worker export.
- Python 3.14.7 / SQLite 3.53.4: `passed` with the identical split.

The regenerated report has SHA-256
`7cc222d67fc59e77617fe55eb0ed2fd92d60e278286df59c98dc3fa9d3ca258c`.

Suites:

- `python3 -B -m unittest discover -v -s scripts/benchmarks/blackbox_memory -p 'test_*.py'` on
  Python 3.9: status 0, 165 tests, all `ok`, with no failures, errors or skips in the complete log.
- `scripts/evaluation`: 22 OK.
- The qualifier module on Python 3.14: 106 OK.

`git diff --check` is clean. The four older fixtures' bytes, `src`, `scripts/hooks` and
`history_search.py` are unchanged, and the index is untouched. Java pairs were not rebuilt.

Final owned hashes at freeze (this plan excluded):

- `repository_fixture.py` `017a0d892ac57cccfef22ca04a8b9a1bfbbbe5bdb545c19042aaf2468b0bfecc`
- `test_repository_fixture.py` `8f353b307f4d7c1967c230b8db4e364132e44fa153b1b1f5d5b1c9b5d93f7e27`
- `journal-race.json` `938e9d0dc08cd07067564ecbf547d2cccf5843e7c606eb0ec1765b39258ecc24`
- `JOURNAL_RACE_TASK.md` `28717fb904ebf1c918ab03eb6b00f19d50940b2b338afd236668807f092f87a9`
- `journal_race_contract.py` `2864687a4aad8f1499ac567e629fba3357bbd26a183804922e6ba6f177be44bd`
- qualification report `7cc222d67fc59e77617fe55eb0ed2fd92d60e278286df59c98dc3fa9d3ca258c`
- `docs/memory-benchmark.md` `7f291702b48663f7ec31c6c21d412a657f5f229da313195f4c533801617bfaf3`
- `docs/continuation-comparison-protocol.md` `b3999644a853378a3f7614dd632ceea5191091faf0030db4bb9629e24285ee3f`
- `development-pool.json` `e13cee8a31281101f04a8f23ea4348fc5fbe999d273b48e4d1c5b96bf10cdbe5`

### NAT-324 closure: candidate assertions before proof

The follow-up review found that `candidate()` re-raised a candidate `AssertionError` before the
proof guard, so an assertion raised before any journal open still counted as behavior. All candidate
exception branches now share one path:

1. A `RaceNotEstablished` raised in candidate code still checks recorded grader failures first.
2. Every other candidate exception, `AssertionError` included, is captured.
3. Recorded grader failures are then raised first.
4. Without proof, every outcome is `RaceNotEstablished`.
5. Only after proof is a captured `AssertionError` (including `UnboundedRetry`) re-raised as
   behavioral; other exceptions become behavioral assertion failures in `accept()`/`rejection()`.

Reproduction against the pinned baseline source with a candidate that raises `AssertionError`
before any journal open: a scratch copy of the pre-fix grader recorded `failure/AssertionError`; the
corrected grader records `error/RaceNotEstablished`.

New regressions in `JournalRaceGraderClassificationTests`:

- `test_candidate_exceptions_before_proof_are_infrastructure`: `AssertionError` and
  `OperationalError` before proof are `error/RaceNotEstablished`, and the qualifier refuses each as
  `race_not_established`.
- `test_candidate_assertion_after_proof_is_behavioral`: a post-proof `AssertionError` remains
  `failure/AssertionError`.

These sit alongside the existing post-proof `OperationalError` case. The accepted grader-owned
`fstat`, `COMMIT` and replacement-setup correction is unchanged.

Regenerated pins:

- grader `ffa9cfded9f8540de7b907a2bfd1b5b15887fbc307d4a29e0a6beebab9c1ce6b` (was `2864687a…44bd`)
- manifest `4e827dfdbd6c0a67a4ee68834c97499d20db70197cc54c5553449194bef501a5` (was `938e9d0d…cc24`),
  pinned in the spec

Replay with the corrected grader on Python 3.9.6 / SQLite 3.54.0: `passed`, the same three named
baseline failures plus four passes, then seven reference passes; worker hash `124c56ce…fd8e` is
unchanged. The report was regenerated
(`5fedccd352617e0ae46d8eb8f19ca9e75ffb54e90c3293c605b63dbb5732d583`).

Tests:

- Focused journal-race qualifier and classification tests: 17 OK.
- Full `unittest discover -v -s scripts/benchmarks/blackbox_memory` on Python 3.9: status 0,
  167 tests, all `ok`, with no failures, errors or skips in the complete log.

`git diff --check` is clean. The older fixtures, `src`, `scripts/hooks` and `history_search.py` are
unchanged, and the index is untouched.

Final owned hashes at freeze (this plan excluded):

- `repository_fixture.py` `58e262af474d7ddf6eafaf2f43514f4961f4caf62d7b7318381d2909716bfc18`
- `test_repository_fixture.py` `ede99756f27b03216190a34f3afdd518fbea97c72242b16d17e7995ef54e6715`
- `journal-race.json` `4e827dfdbd6c0a67a4ee68834c97499d20db70197cc54c5553449194bef501a5`
- `JOURNAL_RACE_TASK.md` `28717fb904ebf1c918ab03eb6b00f19d50940b2b338afd236668807f092f87a9`
- `journal_race_contract.py` `ffa9cfded9f8540de7b907a2bfd1b5b15887fbc307d4a29e0a6beebab9c1ce6b`
- qualification report `5fedccd352617e0ae46d8eb8f19ca9e75ffb54e90c3293c605b63dbb5732d583`
- `docs/memory-benchmark.md` `7f291702b48663f7ec31c6c21d412a657f5f229da313195f4c533801617bfaf3`
- `docs/continuation-comparison-protocol.md` `b3999644a853378a3f7614dd632ceea5191091faf0030db4bb9629e24285ee3f`
- `development-pool.json` `e13cee8a31281101f04a8f23ea4348fc5fbe999d273b48e4d1c5b96bf10cdbe5`


## Coordinator acceptance

Independent read-only reviews accepted the fixed Python recipe and, after both classification
corrections, the grader. The coordinator replayed the actual seven-check pair with Python 3.9.6
and SQLite 3.54.0 on Darwin arm64: exactly the three registered baseline failures plus four
preservation passes, followed by seven reference passes. The worker-input hash remained
`124c56ce54a10c7e0cfcc018239b31d46e733e30c063e7b77a3ccb06a6c9fd8e`.

The complete benchmark suite passed 167 tests on Python 3.9. The coordinator also replayed all
four earlier Java fixture pairs offline on Java 21; their expected failures/preservation checks,
reference passes and worker-input hashes remained unchanged. Diff checks passed. Only disposable
private fixture exports/databases were used; no hook, delivery, provider or installed-service
behavior changed. This qualifies a fifth familiar development fixture and fourth inventory
member, with the existing 17-candidate/12-cluster inventory and NAT7 gates unchanged.


### Main integration

Integrated the merged compact-corpus adapter from PR121 without changing any of this fixture's
source, grader, manifest, task or report bytes. The sole protocol conflict was resolved to retain
both the qualified adapter status and the journal-race qualification. A fresh independent review
accepted the integrated documentation and pins. The combined Python 3.9 benchmark suite passed
202 tests; `git diff --check` passed. The actual five fixture-pair replays above remain applicable
because their owned executable inputs are unchanged.
