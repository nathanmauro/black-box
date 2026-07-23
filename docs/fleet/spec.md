---
project: sba-agentic
tier: production
status: doing
current_round: 1
verify_cmd: ""
push_allowed: true
danger: "Black Box (github.com/nathanmauro/black-box, open source; draft PRs to main welcome). Backlog seed: NEXT.md ranked follow-ups from the 2026-07-16 handoff (adopted-runs crash recovery, pagination past 250 rows + exclude cancelled from runner queries, private tmux socket for Playwright harness, AgenticTools boxed-param NPEs) — but most recent work landed directly via Codex sessions (PR #20 modular monolith, activity event readability), so verify every backlog item against current git log before building; some may already be done. Verify before commit: mvn -q test for Java; cd frontend && npx vitest run for frontend; Playwright e2e only when runner/board flows change. CRITICAL: any mvn package overwrites the jar the live launchd service com.nathan.sba-agentic (:8766) runs from — after any packaging, run scripts/deploy-local.sh so the live service stays healthy. Frontend is SolidJS+Vite in frontend/ compiled into committed static/ — rebuild and commit static/ with any frontend change. Respect the enforced modular-monolith module boundaries. Follow AGENTS.md; keep docs honest; exclude .mcp.json, local DBs, IDE files from commits."
branch_lineage:
  []
---

# sba-agentic — fleet spec

## Intent

<running scope conversation>

## Stakes

production

## Acceptance bar

- verify: `` green
- On RunnerDaemon startup reconcile, each in_progress task claimed by this actor whose tmux session is alive is ADOPTED: registered in ActiveRunRegistry and watched asynchronously via CompletionDetector.awaitCompletion (off the daemon startup path, non-blocking; occupying workerPool concurrency slots is acceptable since adopted runs are real running work).
- Adoption passes a `since` timestamp that PRE-DATES the restart (task claim/update time, or epoch) so a completion report posted while the daemon was down is still honored — not Instant.now().
- When an adopted run posts a completion report, the runner applies the same outcome handling as a normal run (done/blocked annotations + task status transitions); when the tmux session dies without a report, the task is reset to open with a crash-recovery annotation.
- Existing behavior preserved: dead-session tasks still reset to open; SDLC worktree preservation and orphan pruning unchanged.
- The worktree dir is reconstructed via RunnerNaming.worktreeDirName(taskId) under each config repo's .worktrees/; if no repo contains it for an alive session, fall back to reset-to-open with an annotation instead of adopting blind.
- ActiveRunRegistry.deregister is called when an adopted run finishes, on every path.
- Unit tests (extend CrashRecoveryTest or add AdoptedRunTest with the existing fake tmux/api patterns) cover: adopt-then-done, adopt-then-session-died, dead-session reset (regression), missing-worktree fallback.
- mvn -q test green; module-boundary/architecture ratchet tests still pass; no frontend changes needed.

## Decided



## Deferred



## Rounds

### Round 1 — Adopt alive runner sessions during crash recovery
why: NEXT.md (2026-07-16 handoff) ranked follow-up #2: 'Adopted runs gap: after a runner restart, a claimed in_progress task whose tmux is still alive is never watched again (no completion detection). Crash recovery needs an adopt-or-reset path for alive sessions.' Verified still open in src/main/java/dev/nathan/sbaagentic/runner/CrashRecovery.java lines 64-68 — alive sessions are skipped with a bare continue, and no adoption code exists anywhere in src/main. Ranked item #1 (Codex directory-trust prompt) targets Nathan's global ~/.codex/config.toml — ops/config work outside this repo and unsuited to an autonomous repo session — so #2 is the top in-repo item; items #3-#5 are lower-ranked or smaller.
acceptance:
- On RunnerDaemon startup reconcile, each in_progress task claimed by this actor whose tmux session is alive is ADOPTED: registered in ActiveRunRegistry and watched asynchronously via CompletionDetector.awaitCompletion (off the daemon startup path, non-blocking; occupying workerPool concurrency slots is acceptable since adopted runs are real running work).
- Adoption passes a `since` timestamp that PRE-DATES the restart (task claim/update time, or epoch) so a completion report posted while the daemon was down is still honored — not Instant.now().
- When an adopted run posts a completion report, the runner applies the same outcome handling as a normal run (done/blocked annotations + task status transitions); when the tmux session dies without a report, the task is reset to open with a crash-recovery annotation.
- Existing behavior preserved: dead-session tasks still reset to open; SDLC worktree preservation and orphan pruning unchanged.
- The worktree dir is reconstructed via RunnerNaming.worktreeDirName(taskId) under each config repo's .worktrees/; if no repo contains it for an alive session, fall back to reset-to-open with an annotation instead of adopting blind.
- ActiveRunRegistry.deregister is called when an adopted run finishes, on every path.
- Unit tests (extend CrashRecoveryTest or add AdoptedRunTest with the existing fake tmux/api patterns) cover: adopt-then-done, adopt-then-session-died, dead-session reset (regression), missing-worktree fallback.
- mvn -q test green; module-boundary/architecture ratchet tests still pass; no frontend changes needed.
key files: src/main/java/dev/nathan/sbaagentic/runner/CrashRecovery.java, src/main/java/dev/nathan/sbaagentic/runner/RunnerDaemon.java, src/main/java/dev/nathan/sbaagentic/runner/run/WorkerRunExecutor.java, src/main/java/dev/nathan/sbaagentic/runner/run/CompletionDetector.java, src/main/java/dev/nathan/sbaagentic/runner/run/ActiveRunRegistry.java, src/main/java/dev/nathan/sbaagentic/runner/RunnerNaming.java, src/main/java/dev/nathan/sbaagentic/runner/RunExecutor.java, src/test/java/dev/nathan/sbaagentic/runner/CrashRecoveryTest.java

