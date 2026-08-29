# Handoff — 2026-08-28 (wire the read half)

**Shipped** on branch `wire-read-half` (plan: `docs/superpowers/plans/2026-08-28-wire-the-read-half.md`;
audit record: Black Box Observation `987bb5fc` on this repo). The audit that motivated it: the write
half of the loop works (1,170 intent events) but the read half never fired on its own —
`scripts/hooks/sba-recall-hook.sh` had been wired into nothing since 2026-06-10, its 168h default
returned 0 items for this repo, the server does not log recalls, and four `recallContext` results
were rejected by Claude Code's ~100k-char tool-result cap.

## What landed

- **Recall hook** (`scripts/hooks/sba-recall-hook.sh`): defaults 720h / 3 items / 4000 chars;
  `limit` passed to the server; `--client <name>` label; skips `source=compact` re-fires and spawned
  subagents (`agent_id`); appends one TSV line per fire to `~/.blackbox/recall.log`
  (`ts client outcome cwd session_id items chars`; outcomes `ok|empty|unreachable|skipped:*`);
  logging can never change exit code or stdout. Header is plain text starting `Black Box recall:` —
  Codex's hook parser treats stdout beginning with `[` or `{` as JSON and FAILS the hook, so the old
  `[Black Box recall]` header would have been silently rejected on the Codex side.
- **`recallContext` clamp** (`memory/internal/adapter/in/mcp/RecallResultClamp.java`): optional
  `maxChars` (default 24,000, floor 500); rationale-then-headline trim with `… (+N chars)`, later items
  dropped; `RecallResult.truncated` reports it (REST path always `false`).
- **README** "Optional capture and recall hooks": hook serves Claude Code **and** Codex `SessionStart`
  (Codex has had SessionStart hooks since rust-v0.114.0, 2026-03-11); both registration stanzas.
- **Tests**: `scripts/test-recall-hook.sh` (11 cases, fake curl); `MemoryMcpToolsTest` clamp cases
  (mutation-checked); contract snapshots updated.

## Verification

- `mvn -q test`: 534 run, 0 failures, 0 errors, 2 skipped (pre-existing).
- `bash scripts/test-recall-hook.sh` 11/11; `bash scripts/test-agent-hook.sh` green; `git diff --check` clean.
- Live smoke against `:8766`: startup → 3-item block (item 1 was the Codex fix-pass Handoff — the
  loop reading itself), `compact` → silent `skipped:compact`, dead URL → silent `unreachable`; all
  log lines 7 columns, every path exit 0.

## Machine config still owed (Nathan)

Register the hook in `~/.claude/settings.json` and `~/.codex/hooks.json` `SessionStart`
(matcher `startup|resume`, `--client claude` / `--client codex`, timeout 5) — stanzas in README.
Neither `cockpit-agent-hook session-start` nor `inject-seed` injects Black Box recall, so there is
no double-injection.

## Success metric (2 weeks)

From `~/.blackbox/recall.log`: share of interactive session starts that received a non-empty packet,
per client; whether handoff open loops get picked up and decisions stop being re-litigated.

## Open loops (ranked)

1. **Measure before building.** Do not add backend for the read half until the log shows uptake.
2. Recall as a first-class ledger event (`Recall` rows with item ids) + a recall edge on the
   trajectory graph/Stream — the north star's coordination edge from real data.
3. Bi-temporal supersession (`supersedes` on `captureDecision`, `invalid_at IS NULL` default) — now
   table stakes (EchoVault v0.5, engram, Dejavu, mem0 `latest_only`); the lone Todoist item.
4. `query` separate from `scope` on recall (seam at `ContextService.pathOrIdScope`); fold `ask` into
   `memory` (nomic prefix defect + foreign ES index; two embedding clients). Do NOT extract the
   runner — it is already isolated (`allowedDependencies = {}`) and costs the core nothing.
5. SubagentStop auto-Handoff (739 spawned sessions, 0 handoffs).
6. **GitHub Actions is disabled at the repo level** — no CI since 2026-07-10; PRs #19–#26 merged
   with zero checks. Re-enable, add `npm ci && npm run build && npm test` to ci.yml.
7. PR #27 (Cursor agent, consolidation slice 4 process monitor) is unverified; `/api/processes`
   404s live. Verify locally or close.
8. README positioning: "nobody stores rejected alternatives + confidence" is dead (EchoVault v0.5.0
   ships it); "flight recorder for coding agents" is Agent-Blackbox's tagline now.
9. Bookkeeping: `docs/fleet/spec.md` rounds 1–4 all merged to main 2026-08-19 (ledger still says
   "review"); ~55 dead local branches; merged worktree `.claude/worktrees/agent-aecf4fa1f5145a731`;
   AGENTS.md recall example scope `/repos/black-box` returns 0 (real scopes are paths).
10. Earlier loops still valid: runner `cleanupWorktreeAndBranch` exception path ungated
    (`RunExecutor.java:378→391`), rev-list probe without `--` (`CrashRecovery.java:346`), no
    auto re-embed on model change, live launchd lacks `SBA_SQLITE_VEC_PATH` (brute-force vectors).

## Gotchas (carried forward)

- Any `mvn package` (including the Playwright webServer) overwrites the live jar. Run
  `scripts/deploy-local.sh` afterward, then `launchctl kickstart -k gui/$UID/com.nathan.sba-agentic`.
- Never `git add -A` except scoped `git add -A src/main/resources/static` after a bundle rebuild.
- Sandboxed Codex (`--sandbox workspace-write`) cannot self-attach Mockito; run Maven outside.
- Hook stdout for Codex must not start with `[` or `{` (see above).
- Test DBs are temp **files**, never `cache=shared` memory. Never point a second app at the live DB;
  snapshot with `sqlite3 sba-agentic.db ".backup <path>"`. The event table is `agent_events`.
- Playwright against the live app uses `domcontentloaded`, never `networkidle` (SSE).
- `POST /api/events` ingest takes `toolInput` / `toolOutput` as **objects**; `*Json` names are read-side.
- Surefire counts: clear stale reports before trusting aggregates.
