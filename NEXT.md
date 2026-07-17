# Handoff — 2026-07-16 (evening)

**Last completed**: SDLC mode shipped and live-proven on `full-auto-continuation`.
Stories with `mode: sdlc` flow gate → plan → human approval → build → review → human
approval → fail-closed ship. Spec: `docs/superpowers/specs/2026-07-16-sdlc-mode.md`.
Built via Codex (gpt-5.6-sol, ultra effort, fast tier) with Claude adversarial verify;
suites green (mvn 283+, vitest 221, Playwright e2e 21 incl. sdlc happy+reject).
Stretch also landed: revise & resubmit prefill on blocked gate cards.

**Live proof (the payoff)**: a real sdlc story ("shout flag" on a scratch repo) driven
through the deployed :8766 board in a real browser — codex-ultra plan worker, approval
clicked on the card, codex-ultra build committing in a worktree, adversarial review
worker (caught a real gap: README docs missing from the approved plan), review approval
click, ship fail-closed to local-only (no origin remote). Screenshots in the session
job dir (`live-proof-shots/`). The proof surfaced SEVEN real bugs fake-engine e2e
could not reach — all fixed and committed:

- `90b6f18` codex rejects prompts starting with `---` (missing `--` end-of-options)
- `106bbb7` rate-limit detector false-fired on "usage limit resets available" TUI text
  (21-cycle requeue churn) + plan/review prompts now make report.sh the only completion
- `0a3c2f7` workers inherited a dead e2e harness's SBA_BASE_URL from the shared tmux
  server env — worker command now pins the runner's resolved base URL
- `7c3c143` workspace-write sandbox denies loopback → report.sh could never POST;
  engine now passes sandbox_workspace_write.network_access=true
- `c62ee30` approval reconciler re-enqueued forever when the successor was BLOCKED
  (100 junk tasks + heavy codex quota burn before caught) + linked-worktree git
  metadata now a sandbox writable root (git add/commit works in worktrees)
- `19669cd`-ish (`Request The Server's Maximum Row Cap...`) one-arg listTasks used the
  100-row server default and went blind on busy lanes

**Current branch state**: `full-auto-continuation`, stacked on merged PR #18 (main).
Commits: spec `81292c3` → SDLC `a8cba24` → engine `--` `90b6f18` → stretch `d6fa9b7` →
detector `106bbb7` → base-URL pin `0a3c2f7` → sandbox loopback `7c3c143` → reconciler+
worktree-git `c62ee30` → static bundle `2a7eb2f` → row cap fix. Not pushed.

**Live state**: :8766 launchd service healthy (deploy-local.sh after every packaging).
Live-proof runner stopped; production runner daemon NOT running. `~/.blackbox/runner.json`
unchanged (codex xhigh — consider bumping to `ultra`, confirmed supported).
Scratch repo `~/.blackbox/scratch/sdlc-live-20260716` kept for inspection
(branch `auto/sdlc-live-proof-shout-flag-160811-14bd4143`, commit `a39a8c3`).
Board carries ~110 cancelled junk tasks from the runaway (harmless, filterable).

**Follow-ups (ranked)**:
1. Codex first-run **directory-trust prompt** stalls workers on never-seen repos
   (one-time per repo root; I answered it manually). Pre-trust allowlisted repos in
   `~/.codex/config.toml` during gate, or pass a trust override in the engine command.
2. **Adopted runs gap**: after a runner restart, a claimed in_progress task whose tmux
   is still alive is never watched again (no completion detection). Crash recovery
   needs an adopt-or-reset path for alive sessions.
3. **Pagination past 250 rows** (server clamp) for task listings; busy lanes will
   exceed it. Also consider excluding `cancelled` from runner-facing queries.
4. **Playwright harness leaks env into the shared tmux server** (SBA_BASE_URL=8799 et
   al). Mitigated by base-URL pinning; proper fix: harness uses a private tmux socket.
5. MCP `AgenticTools` primitive params NPE when omitted (recallContext `withinHours`,
   captureDecision `confidence`) — box them with defaults. Callers: always pass them.
6. Optional: steer-reminder fallback when a worker idles without reporting.
7. Optional: xAI key for grok fallback; revise-resubmit e2e coverage.

**Gotchas that bit hard (do not relearn)**:
- Bash `run_in_background` inside a Workflow subagent dies with the subagent — long
  codex execs must be owned by the MAIN loop (they survive turns + notify).
- `mvn package`/e2e harness swaps the live jar → always `scripts/deploy-local.sh` after.
- Re-check `git status` after codex workflows (orphaned execs can keep editing).
- API task listings default to 100 rows — pass `limit=250` everywhere.
