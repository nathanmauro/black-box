# Board-driven runner modes

The runner is an optional extension of capture → handoff → recall. It consumes explicitly queued
work through REST and records its result back into the same continuity system.

Black Box ships an optional external runner process: `java -jar sba-agentic.jar runner`, a CLI
subcommand alongside `doctor`, `ingest`, `sessions`, and the other maintenance commands. A story
submitted through the Board's New Story form, or through `createSpec` plus `enqueueTask` in lane
`gate`, records either `full_auto` or `sdlc` in its frozen spec and begins with the same deterministic
readiness checks. Both modes use isolated worktrees, configured engines, verification, commits, live
Board updates, and tendrils into worker-session context. Only `codex` and `fake` engine
implementations exist; `fake` is for tests.

### FULL_AUTO

Story readiness is the one human gate. A passing gate enqueues an `auto` task whose worker builds,
verifies, and commits the change; the runner then owns shipping. Push, pull-request creation, and
merge occur only when the repo config permits them and the existing fail-closed gates pass.

### SDLC

SDLC adds approval annotations after planning and review: `gate → plan → approval → build → review →
approval → ship`. Plan and review workers receive read-only, no-commit contracts and post `plan` or
`review` annotations before completing their stage with a Handoff. Plan approval enqueues the build
in lane `auto`. The build uses the same execution path through a verified commit, but defers shipping,
records its preserved branch and worktree, and enqueues `sdlc:review`. Review runs against that same
worktree and posts advisory findings; review approval invokes the same ship executor and `ship.sh`
gates as FULL_AUTO, minus the red-check repair round. The build's worker session is closed before
review is enqueued, so a red check leaves the pull request open and is recorded once, with no retry.

Without the matching approval the runner makes no progress. A rejection records the feedback as a
`progress` marker on the already-`done` stage task and enqueues or ships nothing. Approval never
bypasses repo allowlists, danger settings, push and auto-merge configuration, credentials, or green
check requirements.

- The runner requires an explicit machine-local config, selected through `SBA_RUNNER_CONFIG` or
  `runner.json` in the user's Black Box state directory by default. It is never committed; start from
  [`docs/runner-config.example.json`](runner-config.example.json). The config allowlists repos
  and controls push, auto-merge, and danger settings. The example includes a disabled `grok`/`xai`
  entry, but no such engine exists. Remove that entry or leave it disabled; enabling it does not
  add an implementation. Select `codex` with a model supported by the installed client, or `fake`
  for fixtures. Treat example model strings as values to review, not capability guarantees.
  Missing, unreadable, or malformed configuration and an empty repo allowlist prevent startup.
  Repo paths match exactly; use actual absolute paths, not shell shortcuts in JSON strings.
  Execution blocks if no configured, enabled engine has an implementation.
- Downstream behavior fails closed in both modes: an unknown repo, danger flag, red check, missing
  approval, or missing credential produces local-only, waiting, or blocked work, never a risky action.
- The Black Box server still never launches a worker or executes a task command. The runner is a
  separate process and an ordinary REST client, like any other agent or orchestrator.

See the [FULL_AUTO architecture](architecture.md#optional-full_auto-runner),
[SDLC architecture](architecture.md#optional-sdlc-runner-mode),
[`FULL_AUTO board-driven runner` design spec](superpowers/specs/2026-07-15-full-auto-board-runner.md),
and [`SDLC mode` design spec](superpowers/specs/2026-07-16-sdlc-mode.md) for the full pipelines
and guardrails.

## FULL_AUTO pipeline and recovery

The FULL_AUTO runner turns an explicitly submitted story into an end-to-end run while remaining an
external REST client of Black Box:

1. **Intake.** The Board's New Story form, or `createSpec` followed by `enqueueTask`, freezes the
   story and creates a task in lane `gate`.
2. **Gate.** The runner evaluates deterministic readiness checks: the repo must exist, be a readable
   Git working tree, and appear in the runner config allowlist; story frontmatter must be valid;
   the Acceptance criteria section must
   be non-empty; a verify command must be present or derivable from the repo; and push intent is
   honored only when that repo's config permits it and carries no danger flag. The gate is
   deterministic only. A `GateAdvisor` seam exists for future advisory scoring, but the shipped
   implementation is a no-op, no configuration enables an alternative, and advisor output is excluded
   from the pass/fail decision entirely. A pass enqueues an `auto`-lane task
   and completes the gate task with a Handoff; a failure blocks the gate task with concrete repair
   guidance.
3. **Execution.** For the claimed `auto` task, the runner creates an isolated worktree and branch,
   opens a tmux session, launches the configured engine, and appends `progress` annotations at
   milestones.
4. **Completion signal.** The worker reports deterministically with
   `scripts/runner/report.sh <taskId> done|blocked "<summary>"`. A bounded pane-state, commit-probe,
   and timeout fallback records diagnostic evidence but never infers success; an exhausted timeout
   blocks the task and preserves the worktree for inspection.
5. **Ship.** The runner, never the worker, owns push, pull-request creation, and merge through
   `scripts/runner/ship.sh`. Push and merge require the repo's `push` and `auto_merge` settings,
   respectively, and no danger flag; merge also requires green checks. A config or credential gap
   records the exact manual follow-up commands and still completes already delivered local or PR
   work. A red check gets one bounded repair round in the same worker session and blocks if it
   remains red.
6. **Complete.** `completeTask` records the usual recallable Handoff with the summary, branch, pull
   request, merge state, open loops, and next action.

Fail-closed behavior is the invariant: an unknown repo, a danger flag, a red check, or a missing
credential degrades the run to local-only or blocked state, never to a risky action.

Execution-failure and requeue cleanup preserves recoverable worker output. The runner only removes
a checkout it created in that execution after verifying its repository, worktree identity, branch,
unchanged initial commit, and absence of tracked changes, untracked files, and ignored files
(including logs). An unknown or mismatched identity, failed/interrupted Git probe, or changed commit
preserves the checkout and branch. Removal uses ordinary `git worktree remove`; branch deletion
compares the current ref to the initial commit so a concurrently advanced branch survives.
Clean no-engine runs and unchanged completed plan stages can still be cleaned up.
Cleanup also requires confirmed worker-session shutdown. A failed or timed-out tmux probe is
unknown state, not evidence that the worker stopped. Startup recovery applies the same worker
presence and ignored-file checks, so retained logs survive a restart even without a unique commit.

Failures and requeues write a recovery pointer with the task, orchestrator run, worktree, branch,
last verified checkpoint, and cleanup result to the runner log and best-effort task annotation.
The log remains the fallback if the API cannot accept annotations or status changes. A worker's
`DONE` report is identified as a report; it does not imply shipping or task completion succeeded.
Inspect a preserved checkout and branch before retrying: this change does not automatically resume
failed builds or remove the existing worktree to make a retry fit. Preserve or recover its files and
commits first. No cleanup protocol can serialize arbitrary external Git/filesystem writers; worker
shutdown is verified before failure cleanup, and failed removal retains the branch.

Crash-recovery worktree pruning is fail-closed the same way. A worker commits before it reports, so
a worktree holding never-published work is *clean* by `git status --porcelain`; cleanliness alone is
therefore not licence to run `git worktree remove --force` and `git branch -D`. The runner prunes an
orphaned worktree only when its branch carries no commit that is missing from both the repo's
default branch and every remote-tracking branch — that is, only when deleting it destroys nothing
that exists solely there. A local-only ship, a blocked run, and a crash mid-ship all leave commits
in exactly that state. An unresolvable default branch or a failed probe preserves. The residual cost
is disk: a squash-merged branch whose remote ref has been pruned reads as unpublished and is kept
(the normal auto-merge path removes those at ship time, not here).

The full contract, including worker-session ingest, steering, recovery, and v1 non-goals, is in the
[`FULL_AUTO board-driven runner` design spec](superpowers/specs/2026-07-15-full-auto-board-runner.md).

## SDLC pipeline and reconciliation

SDLC reuses the same external runner and storage contracts while inserting explicit human gates
after planning and review:

1. **Intake and gate.** The frozen story records `mode: sdlc` and begins in lane `gate`. The same
   deterministic readiness checks apply, but a pass enqueues `sdlc:plan` instead of `auto`.
2. **Plan.** A plan-stage worker receives a read-only, no-commit goal, explores the repo, posts a
   full `plan` annotation, and completes the stage task with a Handoff. The `done` task then waits for
   a human decision on its Board card.
3. **Plan decision.** An `approval` annotation with `stage: plan` and `decision: approve` allows the
   runner to enqueue the `auto` build task. A rejection records its feedback once as an SDLC
   `rejection_recorded` `progress` marker on the already-`done` plan task and enqueues nothing.
4. **Build.** The `auto` task follows the FULL_AUTO execution path through a verified commit, but it
   does not ship. The runner records the branch and worktree, preserves both, completes the build
   with a Handoff, and enqueues `sdlc:review`.
5. **Review.** A review-stage worker uses the preserved worktree under a read-only, no-code-change
   goal, checks the diff, acceptance criteria, approved plan, and verification command, posts an
   advisory `review` annotation, and completes the stage with a Handoff.
6. **Review decision and ship.** Approval invokes the existing shipping executor directly against
   the preserved worktree. Repo allowlists, danger settings, push and auto-merge configuration,
   credentials, and green-check requirements remain unchanged and fail closed. Rejection records the
   same durable marker, ships nothing, and preserves the worktree for inspection.

“Awaiting approval” is a Board state, not a task lifecycle status: the plan or review task remains
`done`, and without a matching approval the runner makes no progress. Approval annotations are
append-only REST/UI facts; the server stores and broadcasts them but never consumes them or launches
work. The runner treats an approval SSE frame only as a wake hint, reconciles completed SDLC stages
at startup and every 60 seconds, and uses existing successor tasks plus SDLC progress markers to make
replays idempotent. The selected relational store remains authoritative throughout.

The complete stage contracts, approval payloads, reconciliation rules, and non-goals are in the
[`SDLC mode` design spec](superpowers/specs/2026-07-16-sdlc-mode.md).

## Starting the process

After building in a checkout that is not serving a running JAR:

```bash
SBA_RUNNER_CONFIG=/path/to/runner.json \
  java -jar target/sba-agentic-0.2.0.jar runner
```

Starting the runner starts orchestration. The macOS deploy script also starts or restarts it;
review [Run it](operations.md#runner-service) before using that script. The bundled REST client
does not attach a bearer token, so this integration assumes the default trusted loopback
service; the authenticated shared-server path needs a credential-aware client. The PostgreSQL
profile does not provide multi-host runner safety.
