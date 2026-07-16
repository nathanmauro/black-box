## Handoff — 2026-07-16

**Last completed**: FULL_AUTO board-driven runner shipped on `full-auto-board-runner`
(5 commits, spec at `docs/superpowers/specs/2026-07-15-full-auto-board-runner.md`) and
deployed to the live :8766 service. Verified through real use: story intake on the
Board, deterministic gate (fail-closed feedback proven live), fake-engine execution in
tmux worktrees, live SSE card movement, tendril into the worker session, agent DAG,
fail-closed ship with manual-command fallback. `mvn test` green, 195/195 vitest, 19/19
Playwright e2e including the new `full-auto.spec.ts`.

**Current branch state**:
- `full-auto-board-runner` pushed; **PR #18 open** (https://github.com/nathanmauro/black-box/pull/18).
  **CI never attached** — GitHub Actions created no run for the PR despite `on: pull_request`
  (only third-party check-suite placeholders appear; last successful Actions runs are
  from 2026-07-10). Investigate Actions availability/billing before merging; merge is
  intentionally not done.
- `full-auto-continuation` (this branch) stacked on it for the next slice.

**Live state**:
- :8766 launchd service redeployed 2026-07-16 00:43 via `scripts/deploy-local.sh`;
  root/board 200, production DB intact.
- Runner daemon NOT started (auto-mode permission gate; starting it launches
  approvals-off Codex workers with auto-merge). Start manually:
  `nohup java -jar target/sba-agentic-0.1.0.jar runner > ~/.blackbox/runner.log 2>&1 &`
- `~/.blackbox/runner.json` is in production posture: codex (xhigh) enabled,
  grok-4.5-fast disabled (no xAI key on machine), fake disabled, repos allowlist =
  sba-agentic (push + auto_merge).

**Next recommended work**:
1. Runner as a launchd service (`com.nathan.blackbox-runner`), mirroring
   `scripts/black-box.plist.template` + deploy-local wiring, so FULL_AUTO survives
   reboots without a manual nohup.
2. SDLC mode — reserved lanes `sdlc:plan` / `sdlc:review` with approval annotations
   between stages (design sketch in the spec's Non-goals).
3. CI investigation for PR #18 (Actions run never created) + merge once green.
4. Optional: xAI provider config for the grok-4.5-fast fallback; revise-and-resubmit
   prefill on blocked gate cards (stretch item in the spec).

**Blockers / questions for human**:
- Merge PR #18 after CI is sorted (or instruct merge without checks).
- Start the runner daemon when ready (command above), or allowlist it.

**Notes**:
- An orphaned Codex fix process from the build workflow outlived its agent and left
  good uncommitted fixes at ~00:15; all were reviewed, verified, and committed
  (`8aae004`, `f28d818`). Lesson recorded in Black Box: re-check `git status` after
  any Codex-delegated workflow completes.
- The e2e harness independently proved production protection (8766 listener untouched,
  DB identity unchanged, zero synthetic rows).
- DAG SVG bug (`<a>` in SVG = HTML namespace = invisible nodes) found only via live
  browser use; fixed in `c9b9bd1`, memory saved.
