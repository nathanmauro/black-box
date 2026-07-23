# Handoff — 2026-07-23 (night)

**Last completed**: closed open loops 2 and 4 from the evening handoff and killed the
`SQLITE_LOCKED_SHAREDCACHE` flake at its root. Live :8766 redeployed and healthy.

- **Codex worker worktrees are pre-trusted** (`CodexEngine`): the engine command now
  appends `-c 'projects={"<worktree>"={trust_level="trusted"},…}'` (absolute + canonical
  path entries) whenever a worktree dir is present, so the interactive codex TUI never
  stalls an unattended worker on the first-run directory-trust prompt. Facts learned
  live against codex-cli 0.145.0 (do not relearn):
  - codex's `-c` parser naive-splits keys on every `.` with no quoted-segment support —
    a `projects."<path>".trust_level` key can NEVER address a filesystem path; the whole
    table must ride in the TOML-parsed *value* under the single-segment `projects` key.
  - trust lookup uses the process's **physical** cwd (symlinks resolved), so the
    canonical path entry is load-bearing (`/tmp` vs `/private/tmp` bit during probing).
  - for a linked worktree codex otherwise resolves trust against the **main repo root**
    (`resolve_root_git_project_for_trust`), which is why already-trusted repos never
    prompted while fresh fleet targets stalled.
  - the CLI override is ephemeral — `~/.codex/config.toml` stays untouched (verified by
    diff after every probe).
- **Spring test DBs moved off shared-cache memory**: all 9 `@SpringBootTest` classes
  now use `jdbc:sqlite:${java.io.tmpdir}/bb-<name>-${random.uuid}.db` instead of
  `file:<name>?mode=memory&cache=shared`. Shared-cache table locks ignore
  `busy_timeout` and throw `SQLITE_LOCKED` the instant an async summary/link listener
  collides with an ingest UPSERT — by this session `EventIngestServiceTest` failed ~2/3
  runs even solo. File DBs take the production WAL + busy_timeout path (contention
  waits instead of erroring). Full suite is now flake-free: 334 green twice in a row
  with zero reruns.
- **Full-auto e2e cleanup is silent**: the shared `~/.codex/sessions/blackbox-e2e/`
  rmdir only fires when the dir is empty (a crashed run's leftover no longer warns
  ENOTEMPTY — the stale 7/16 rollout was deleted), and the runner tmux kill is guarded
  by `has-session` like sdlc's. Verified: zero cleanup warnings in the final run.
- **Leak guard reports honestly** (`runtime-safety.ts`): the synthetic-row count
  retries 3× (live-service WAL checkpoints can transiently fail a single `sqlite3
  -readonly` read → `null`), and a one-sided measurement failure now throws "could not
  verify", not "synthetic events leaked". A phantom "leak" from exactly this fired once
  this session; the live DB had 0 synthetic rows.

**Verification**: mvn 334 green ×2 full-suite runs (no flake, no reruns), vitest 238,
Playwright e2e 22/22 ×3 (final round: zero cleanup warnings, no guard error, exit 0).
`scripts/deploy-local.sh` rerun after the e2e jar overwrite; /api/status OK
(sessions 3416, events 232699, ES + local AI reachable).

**Live state**: :8766 launchd service healthy on the fresh jar. Production runner
daemon NOT running.

**Open loops (ranked)**:
1. Fleet PRs **#21** (adopt-alive crash recovery) and **#22** (task-list pagination,
   stacked on #21) still await Nathan's review/merge + the pinned
   `CODEX_GOALS_DIR=~/.codex-goals/runs/2026/07/22-194034 fleet-review.sh sba-agentic <1|2>` acks.
2. Accepted-as-is follow-ups: `link.created` SSE frame for parent-rail live refresh;
   durable agentType surfacing (only in SubagentStart event metadata today); expander
   aria-label wording for runner links; two `live-verify-*` junk sessions in live DB.
3. Next roadmap slice is unpicked — candidates from the post-launch P-list: release-jar
   install one-liner, sqlite-vec semantic recall, :8766 token auth.

**Gotchas (do not relearn)**:
- Any `mvn package` (incl. the Playwright webServer) overwrites the live jar →
  `scripts/deploy-local.sh` after.
- codex `-c` overrides cannot address dotted/quoted keys (naive `.` split) — inject
  whole tables via a single-segment key; trust lookup wants the physical path.
- macOS unix socket path cap ~104 bytes — never point `TMUX_TMPDIR` at a deep temp dir.
- Never `git add -A` (except scoped `git add -A src/main/resources/static` after a bundle
  rebuild); module ratchet: recording depends on nothing, workflow→recording only.
- Playwright against the live app: `domcontentloaded`, never `networkidle` (SSE).
- Bash `run_in_background` inside a Workflow subagent dies with the subagent — main loop
  owns long execs.
- API task listings default to 100 rows — pass `limit=250` (proper pagination lands with PR #22).
