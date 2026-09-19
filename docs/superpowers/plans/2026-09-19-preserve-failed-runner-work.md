# Preserve unfinished work after runner failure

Status: implemented, independently reviewed and integrated locally. Final combined regression passed; pull-request review is separate. Tracked by NAT-5.

## Problem and evidence

`RunExecutor` and `PlanStageExecutor` failure and requeue paths call `WorktreeManager.cleanupWorktreeAndBranch`. The helper force-removes the worktree and force-deletes its branch without proving cleanliness, commit reachability, or ownership. An API or shipping failure can therefore erase the worker's only recoverable output.

## Acceptance criteria (freeze before changes)

- A controlled post-worker failure preserves tracked edits, an untracked file, a unique commit, the branch, and relevant logs in a disposable Git repository.
- Failure reporting identifies task/run, recovery worktree and branch, and last verified checkpoint without implying completion.
- Dirty, unique, unknown, or mismatched ownership state is preserved.
- Clean owned no-work cleanup remains available; successful completion behavior is retained.
- Exercise the actual runner failure path, run focused and relevant regression tests, and obtain an independent review of code and evidence before integration.

## Scope and sequence

1. Reproduce loss with a controlled disposable repository and fake external endpoints/worker.
2. Make failure/requeue cleanup conservative, with explicit recovery reporting. Prefer existing abstractions.
3. Cover dirty, unique-commit, mismatch, query-failure, interrupted, and clean no-work cases.
4. Exercise a runner failure end to end; inspect surviving bytes, refs, logs, and task state.
5. Run regression checks, review independently, and record exact results here.

Do not change live runner policies, real tasks, canonical databases, service routing, or deployed artifacts. Tests use temporary repositories and isolated state. Preserve unrelated checkout changes. This change makes no claim about recall benefit or product adoption.

## Results

### Reproduction

`mvn -q -Dtest=RunExecutorIntegrationTest#shippingCrashPreservesWorkerCommitDirtyFilesAndLog test`
failed before the implementation: `RunExecutor` caught a controlled shipping exception and the test
found that the worker worktree no longer existed. The disposable repository contained a worker
commit, modified tracked `README.md`, untracked `untracked.txt`, and `worker.log`. The harness uses
the actual runner and `RealProcessRunner`/Git with fake tmux and API boundaries; it invokes no live
engine, shipping service, application database, or task.

### Implementation

- Capture the initial commit and repository/worktree Git identity only after successful creation.
- Failure/requeue cleanup requires matching ownership, unchanged HEAD, and a clean status including
  untracked and ignored files. Failed or interrupted inspection preserves.
- Use non-forced worktree removal and compare-and-delete for the unchanged branch ref. A ref that
  advances during cleanup is retained; failed removal does not trigger branch deletion.
- Log recovery task/run/path/branch/checkpoint before best-effort API annotation. Crash status
  reasons include the same recovery context when the API accepts them.
- Keep normal local-only build, preserved SDLC build/review, and clean completed plan behavior.

### Verification

- The original reproducer now passes and asserts surviving file bytes, branch, commit, stopped
  session, absence of task completion, and failure status containing the recovery pointer.
- `WorktreeManagerTest` exercises actual disposable Git repositories: clean no-work cleanup; unique
  commit; tracked, untracked, and ignored output; missing/changed ownership; wrong common Git
  directory; failed/timed-out probes; real interrupted Git probe; new untracked output after the
  status probe; and a branch that advances after worktree removal.
- Runner integration covers shipping crash, API failure after local-only shipping with API
  annotation/status also unavailable (recovery verified in logger output), actual interrupted
  completion polling, plan-worker crash, rate-limit requeue, and no-engine clean cleanup.
- `mvn -q '-Dtest=dev.nathan.sbaagentic.runner.**' test`: 115 tests across 23 classes passed,
  zero failures/errors/skips. `git diff --check` passed.

### Independent review corrections

- Review found that best-effort tmux shutdown could fail while a clean checkout was deleted. A new
  actual executor/Git reproducer failed before the correction: the post-launch API annotation
  failed, tmux termination failed, and the checkout disappeared. Cleanup now requires verified
  session absence. The real tmux adapter distinguishes explicit absence from timeout, interruption,
  permission errors, and unknown probe failures.
- Review also reproduced restart deletion of a preserved ignored-only `worker.log`. A real Git
  restart test failed before the correction; recovery now includes ignored files in its status
  check and uses non-forced worktree removal. The clean-orphan control still verifies cleanup.
- A related actual restart reproduction showed that a reopened task's clean checkout could be
  pruned with its worker still alive. Recovery now checks the matching worker session and preserves
  on a live session or failed probe, even without an in-progress task record.
- Final runner regressions: 120 tests across 25 classes, zero failures/errors/skips. Fresh correction
  review found no remaining material issue. No acceptance criterion was lowered.

### Boundaries and next step

At the implementation handoff, no live services, canonical databases, real tasks or deployments changed.
The change intentionally preserves work when state cannot be proven, which can retain disk usage.
It does not add automatic failed-build resume or promise serialization against arbitrary external
Git/filesystem writers. Recovery details rely on the runner's configured log retention when the API
is unavailable. Coordinator owns fresh independent review, any full-suite verification, Git finish,
and integration; do not describe this checkout-only result as deployed.

Coordinator closure: the reviewed changes were committed and integrated. The final combined suite passed 637 tests with zero failures/errors and three intentional skips. The server artifact was deployed locally through the separately verified deployment procedure; no production runner task was launched.
