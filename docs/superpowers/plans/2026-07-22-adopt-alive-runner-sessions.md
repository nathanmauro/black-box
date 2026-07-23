# Adopt Alive Runner Sessions

**Goal:** Close the crash-recovery gap where a runner-owned `in_progress` task is left unwatched when its tmux session survives a daemon restart.

## Scope and safety contract

- Preserve the existing dead-session reset, SDLC worktree preservation, and clean-orphan pruning behavior.
- Adopt only sessions owned by the restarting actor and backed by the deterministic worktree path under a currently configured repo.
- Register adopted sessions before dispatching their watcher to the daemon worker pool so steering and the concurrency cap continue to work.
- Search for completion reports from the task claim/update timestamp, which predates daemon restart and therefore includes reports posted while the daemon was down.
- Use the shared worker completion-to-outcome mapping. Apply reported `done` and `blocked` outcomes; reset to `open` only when recovery cannot safely watch the run, including a missing worktree or a session that ends without a report.
- Always deregister an adopted run, including watcher, API, and executor failure paths.

## Implementation slices

1. Extend completion results with report provenance and extract WorkerRunExecutor's completion-result mapping for reuse.
2. Teach CrashRecovery to locate configured worktrees, register and asynchronously dispatch adopted watchers, apply reports, and fail safely when adoption is impossible.
3. Submit adopted watchers through RunnerDaemon's existing worker-pool accounting so they consume real concurrency without blocking startup.
4. Extend CrashRecoveryTest for adopted done, adopted session death, dead-session regression, and missing-worktree fallback.

## Verification

1. Run targeted crash-recovery tests while iterating.
2. Run `git diff --check`.
3. Run `mvn -q test` and require the full suite, including architecture ratchets, to pass before committing.
