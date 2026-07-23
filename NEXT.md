# Handoff — 2026-07-23 (evening)

**Last completed**: the final two in-repo backlog items from the 7/16 handoff, both
committed to main and pushed (`32bd5ba`, `71c5a91`), live :8766 redeployed and healthy.

- **MCP boxed params** (`32bd5ba`): `MemoryMcpTools` primitive tool params NPE'd when MCP
  clients omitted them. `limit` (recentSessions/searchSessions), `withinHours`
  (recallContext), and `confidence` (captureDecision) are now boxed, `required=false` in
  the schema, and defaulted in the adapter (limit→10, withinHours→service one-week
  default, confidence→null passthrough — downstream was already null-safe). `kinds` is
  optional too. Contract snapshot `contracts/mcp-tools.json` updated; new
  `MemoryMcpToolsTest` drives the real ToolCallback JSON path. Verified live post-deploy:
  `recallContext` with only `repoOrTopic` returns defaults over real MCP.
- **Private tmux socket for the e2e harness** (`71c5a91`): the Playwright harness (webServer
  app + spec-spawned runner daemons + spec cleanup calls) now pins `TMUX_TMPDIR` to a
  run-private dir, so harness env (SBA_BASE_URL etc.) can no longer poison the shared
  default tmux server that real runner workers inherit from. The socket dir is
  `bb-tmux-<runToken8>` directly under the system temp root because **macOS caps unix
  socket paths at ~104 bytes** — inside the long-named run temp dir tmux fails with
  "File name too long". Private server killed + dir removed in global teardown, with a
  webServer trap fallback. `full-auto.spec.ts` now asserts the worker session exists on
  the private socket and NOT on the default server.

**Verification**: mvn 330 green (known `SQLITE_LOCKED_SHAREDCACHE` flake in
`EventIngestServiceTest` under full-suite load — passes on rerun), vitest 238 green,
Playwright e2e 22/22. Default tmux server checked clean after runs (no bb-* sessions, no
SBA_* globals, no leftover bb-tmux dirs or stray runner JVMs).

**Live state**: :8766 launchd service healthy on the fresh jar (both fixes live).
Production runner daemon NOT running.

**Open loops (ranked)**:
1. Fleet PRs **#21** (adopt-alive crash recovery) and **#22** (task-list pagination,
   stacked on #21) still await Nathan's review/merge + the pinned
   `CODEX_GOALS_DIR=~/.codex-goals/runs/2026/07/22-194034 fleet-review.sh sba-agentic <1|2>` acks.
2. `~/.codex/sessions/blackbox-e2e/` holds stale rollout files from old aborted e2e runs,
   so the specs' rmdir cleanup always warns ENOTEMPTY — harmless; delete the leftovers
   (or make the cleanup tolerate them) to silence it.
3. Accepted-as-is follow-ups: `link.created` SSE frame for parent-rail live refresh;
   durable agentType surfacing (only in SubagentStart event metadata today); expander
   aria-label wording for runner links; two `live-verify-*` junk sessions in live DB.
4. 7/16 item #1 (codex first-run directory-trust pre-trust) is out-of-repo ops
   (`~/.codex/config.toml`), still unaddressed.

**Gotchas (do not relearn)**:
- Any `mvn package` (incl. the Playwright webServer) overwrites the live jar →
  `scripts/deploy-local.sh` after.
- macOS unix socket path cap ~104 bytes — never point `TMUX_TMPDIR` at a deep temp dir.
- Never `git add -A` (except scoped `git add -A src/main/resources/static` after a bundle
  rebuild); module ratchet: recording depends on nothing, workflow→recording only.
- Playwright against the live app: `domcontentloaded`, never `networkidle` (SSE).
- Bash `run_in_background` inside a Workflow subagent dies with the subagent — main loop
  owns long execs.
- API task listings default to 100 rows — pass `limit=250` (proper pagination lands with PR #22).
