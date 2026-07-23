# FULL_AUTO board-driven runner — design spec

Date: 2026-07-15
Status: approved for build
Mode shipped first: FULL_AUTO. SDLC mode is a documented future variant (see Non-goals).

## Intent

Turn the Black Box Board into a control surface: a story created on the board (or via
MCP/REST) is gate-checked for agent-readiness, then executed end-to-end by an external
runner daemon driving a Codex worker at xhigh reasoning — branch, verify, commit, push,
PR, and merge-on-green for allowlisted repos — with the card moving live across the
board, a "tendril" from the card into the worker's full session context (including
mid-run steering), and a DAG view of agent/subagent relationships.

The one human gate in FULL_AUTO is story readiness. Everything downstream is
autonomous. Fail closed everywhere: an unknown repo, a danger flag, a red check, or a
missing credential degrades to local-only/blocked — never to a risky action.

## Architecture boundary (unchanged)

The Black Box server stays a substrate: storage, lifecycle, broadcast. It never
launches workers or executes task commands. The runner is a separate process and an
ordinary API client. SQLite remains the source of truth; SSE remains best-effort wake
hints. This preserves the boundary recorded in `docs/architecture.md`.

## Vocabulary

- **Story** — one unit of shippable work: a frozen spec + its lifecycle tasks.
- **Gate task** — lane `gate`; readiness evaluation of a story.
- **Auto task** — lane `auto`; the execution unit the runner claims.
- **Run** — one worker attempt at an auto task (tmux session + engine process).
- **Tendril** — the UI path from a board card to the worker session's full context.

## Story data model (no new tables for stories)

A story is a spec whose body is markdown with YAML frontmatter carrying machine fields:

```yaml
---
story: v1
repo: /Users/nathan/Developer/proj/<name>   # absolute path to target checkout
mode: full_auto                              # full_auto | sdlc (sdlc reserved)
verify: "mvn test"                           # optional; gate derives if absent
push: true                                   # story intent; runner config can veto
priority: 10
---
# <Title>

## Goal
...

## Acceptance criteria
- ...

## Constraints / dangers
- ...
```

Specs stay frozen. Revising a blocked story = new spec + new gate task; the old gate
task is cancelled. (A "revise & resubmit" prefill on blocked cards is a stretch goal.)

## Pipeline (FULL_AUTO)

1. **Intake** — Board "New story" form (or MCP `createSpec` + `enqueueTask`) creates
   the spec and enqueues a task in lane `gate` with the story's priority.
2. **Gate** — the runner claims lane `gate` and evaluates readiness:
   - Deterministic checks (all must pass):
     - `repo` exists, is a git repo, working tree state readable;
     - repo is present in runner config `repos` (fail-closed allowlist);
     - Acceptance criteria section non-empty;
     - `verify` present, or derivable from repo convention (mvn/npm/make), recorded
       back into the run context;
     - if the story sets `push: true`, the repo's runner config must not carry a
       danger flag forbidding push.
   - Optional LLM assist (local model via existing local-AI path, or codex at low
     effort) scores clarity/boundedness and generates feedback text. Advisory only in
     v1: it annotates, never solely blocks.
   - **Pass** → enqueue lane `auto` task under the same spec, complete the gate task
     (handoff summarizes the readiness findings). **Fail** → `blocked` with
     `blockedReason` = concrete feedback (what's missing, how to fix).
3. **Execution** — the runner claims lane `auto`:
   - Creates a worktree from the repo's default branch: `.worktrees/bb-<task8>` with
     branch `auto/<slug>-<task8>`.
   - Spawns a tmux session `bb-run-<task8>` running the engine (below) with a goal
     prompt containing: the frozen story body, guardrails (identity, scope, verify
     gate, fail-closed rules — see Guardrails), and the completion protocol.
   - Transitions the task `in_progress` (claim already did) and posts `progress`
     annotations at meaningful milestones.
4. **Completion protocol** (deterministic-first):
   - The goal prompt instructs the worker: after verify is green and work is
     committed, run `scripts/runner/report.sh <taskId> done "<summary>"` (POSTs a
     `progress` annotation kind=`worker_done`). The runner treats that annotation as
     the completion signal.
   - Fallback: pane-idle heuristics (fleet ACTIVE rules: `Working (`, approval
     prompts, background-terminal waits = still active) + artifact probes (git log in
     the worktree), bounded by a per-run timeout (default 45m). Timeout → `blocked`
     with reason, worktree preserved.
5. **Ship** — runner (not the worker) owns push/PR/merge via
   `scripts/runner/ship.sh` (adapted fail-closed from the fleet's open-pr gate):
   - push + PR only when runner config `repos[].push == true` and no danger match;
   - PR opens ready (not draft) in FULL_AUTO; body has no assistant attribution;
   - **merge gate**: `repos[].auto_merge == true` AND `gh pr checks` all green
     (bounded watch, default 30m) → `gh pr merge --squash`; then delete branch,
     prune worktree.
   - Any gate miss → task `done` still (work is delivered on a branch/PR), final
     annotation records what was NOT auto-performed and the exact command to finish
     manually. Red checks → one repair round-trip back to the worker (same session,
     steering injection with the failing check output), then `blocked` if still red.
6. **Complete** — `completeTask` with a handoff: summary, branch, PR URL, merge
   status, open loops. Card lands in Done with the handoff linked (existing recall).

## Runner daemon

- **Form**: Java subcommand of the existing artifact — `java -jar sba-agentic.jar runner`
  (new `runner` CLI mode beside doctor/ingest/etc.), separate JVM from the server;
  HTTP-only client of `http://127.0.0.1:8766` (never direct DB). Reuses the proven
  `ProcessBuilder` handling (timeouts, stream draining, destroy) from
  `ai/ExternalSummaryClient`.
- **Loop**: claim `gate` then `auto` lanes with jittered backoff; subscribe to
  `GET /api/stream` for wake hints and `task.note` steering frames. Concurrency cap
  configurable (default 2 concurrent runs).
- **Config**: `~/.blackbox/runner.json` (machine-local, never committed; example +
  schema shipped in `docs/runner-config.example.json`):

```json
{
  "concurrency": 2,
  "engines": [
    {"id": "codex", "model": "gpt-5.6-sol", "effort": "xhigh", "sandbox": "workspace-write"},
    {"id": "grok", "model": "grok-4.5-fast", "provider": "xai", "enabled": false}
  ],
  "notify": "terminal-notifier -title 'Black Box' -message {msg}",
  "repos": [
    {"path": "/Users/nathan/Developer/proj/sba-agentic", "push": true, "auto_merge": true,
     "verify": "mvn test", "danger": ""}
  ]
}
```

- **Engine abstraction**: engine = command template producing an interactive worker in
  the tmux pane. Codex engine: `codex -m <model> -c model_reasoning_effort="<effort>"
  -a never "<prompt>"` (the fleet's proven invocation). `fake` engine (always
  available, used by all tests/e2e): scripted worker that emits annotations, makes a
  trivial commit in the worktree, and reports done.
- **Limit fallback**: engine output matched against rate-limit/quota patterns
  (429, `rate limit`, `usage limit`, `quota`). On match: notify (configured `notify`
  command + a Black Box observation), and if a next enabled engine exists, relaunch
  the run on it; otherwise requeue the task `open` with backoff and notify that
  fallback is unconfigured. grok stays `enabled: false` until an xAI key exists.
- **Steering**: `task.note` frames with kind=`steer` for an active run → runner
  injects the text into the worker's tmux pane via `send-keys` (quoted, Enter), and
  posts a `progress` annotation confirming injection.
- **Crash recovery**: on startup the runner reconciles: tasks it owns (claimedBy =
  its actor id) whose tmux session is gone → reset to `open` with an annotation;
  surviving sessions with a worktree under a configured repo are registered and watched
  asynchronously from the task claim/update timestamp so reports posted during downtime are
  honored; an adopted session that ends without a report, or has no safe worktree, resets to
  `open` with an annotation;
  orphaned worktrees pruned only when clean, else annotated + left for inspection.

## Server additions (storage + broadcast only)

1. **Task annotations** — `POST /api/tasks/{taskId}/annotations`
   `{actor, kind: note|steer|progress|worker_session|engine, text, dataJson?}` →
   appended to `task_events` (type `task.note`, detail carries kind/payload), SSE
   frame `task.note` broadcast. `GET /api/tasks/{taskId}/events` lists lifecycle +
   annotations (the card timeline). Annotations are append-only facts, valid in any
   non-terminal-or-terminal status (a note on a done task is fine).
2. **Session links (lineage)** — table
   `session_links(id, parent_session_id, child_session_id, link_type, task_id, created_at)`
   with `UNIQUE(parent_session_id, child_session_id, link_type)`;
   `POST /api/session-links`, `GET /api/sessions/{id}/links` (both directions).
   Link types v1: `spawned` (runner→worker), `steered`, `continued`.
3. **DAG read model** — `GET /api/tasks/{taskId}/dag` and `GET /api/dag?sessionId=`:
   nodes = {spec, tasks, sessions (title/source/status)}, edges = {spec→task,
   task→session (worker_session annotations), session→session (links)}. Pure
   projection, no new state.
4. MCP parity is NOT required in v1 for annotations/links/dag (REST-only);
   note it in docs.

## Worker session ingest (the tendril's data)

The runner locates the Codex worker's rollout JSONL (newest session file for the
worktree cwd under `~/.codex/sessions/...`) and ingests it into Black Box via the
existing ingest path **periodically during the run** (default every 60s) and at
completion. Ingest must be idempotent per event — if the current CLI ingest is not
(builder must verify), add offset-tracking or content-hash dedupe keyed by
`(source, client_session_id)`. After first ingest the runner posts:
`worker_session` annotation (`{sessionId}`) + a `session_links` row
(runner orchestrator session → worker session, `spawned`, taskId).

## Frontend

1. **New story form** — on `/board`: title, project (catalog picker + free repo
   path), goal, acceptance criteria, constraints, verify command, priority, mode
   (full_auto; sdlc visible but disabled "coming soon"). Submits spec + gate task via
   the existing (currently unused) `api.ts` wrappers. Client-side mirror of the
   deterministic gate checks as inline validation hints — the same rubric, shown
   before submit.
2. **Card detail upgrades** — annotations timeline (live via `task.note`), steer
   input box (posts kind=`steer`; enabled only while `in_progress`), engine + branch
   + PR chips (from annotations), **Tendril** button → worker session.
3. **Tendril view** — deep-link to the existing session reader
   (`/?view=browse` embed pattern or `/sessions/:id`) for the worker session,
   live-updating as periodic ingest lands events (existing `session.updated` SSE
   already triggers refresh). Steering stays available beside the transcript: a slim
   task-context header (story title, status, steer box) wraps the session view when
   reached from a card.
4. **DAG view** — from card detail and session header: renders the task DAG endpoint
   as a layered SVG (spec → tasks → sessions, session→session edges), matching
   existing `GraphPage`/theme conventions; nodes link to their session/card. No new
   graph library unless `GraphPage` already uses one.
5. **Task live store** — handle `task.note` frames (update annotations without
   refetch); keep authoritative-refresh-on-reconnect behavior.

Live transport stays SSE (`/api/stream`) — it already moves cards in real time;
"websocket" in the product sense is satisfied by SSE and switching transports is a
non-goal.

## Guardrails (embedded in every goal prompt)

- Commit identity: Nathan only. No Co-Authored-By naming a model, no generated-by
  lines, in commits or PR bodies.
- Branch-only work: never touch the default branch; never `git add -A`; stage
  explicitly; exclude `.claude/`, `.idea/`, pre-existing dirty files.
- Verify before commit; only green work is committed.
- Fail closed: dangers/unknowns → stop and report via `report.sh <taskId> blocked
  "<reason>"`, never improvise around a gate.
- The worker never pushes or opens PRs — the runner's `ship.sh` owns that.

## Verification plan (fake-first, then live)

1. Unit/integration: annotation + link + DAG endpoints (Spring tests); runner gate
   logic and ship gating against fixture git repos (fake engine); frontend vitest for
   form validation, task.note store handling, DAG rendering.
2. Playwright e2e (isolated 8799 harness, fake engine, fixture repo in tmp): create
   story in UI → gate promotes → card moves Open→In Progress live → annotations
   stream in → tendril opens session view → DAG renders → card lands Done with
   handoff. Screenshot artifacts.
3. Live proof on :8766 (after `scripts/deploy-local.sh`, which handles the
   jar-swap/launchd restart): one real story with the codex engine against a
   scratch repo, through to merged PR. The jar-rebuild-degrades-live-service gotcha
   is handled by deploy-local.sh ordering.

## Non-goals (v1)

- SDLC mode (design sketch: same pipeline with added lanes `sdlc:plan`,
  `sdlc:review` and approval annotations between them; the mode field and lane
  scheme already leave room).
- MCP tools for annotations/links/DAG; auth; multi-node; priority aging; automatic
  follow-up story creation; Claude-hook subagent lineage capture (stretch);
  replacing the cockpit codex-fleet (it gains its own FULL_AUTO mode separately).

## Slices

1. Server: annotations + session_links + DAG + SSE `task.note` (+ tests).
2. Runner: CLI mode, loop, gate, tmux/engine spawn, fake engine, report/ship
   helpers, config, fallback, recovery (+ tests).
3. Frontend: story form, card detail upgrades, tendril, DAG view, store handling
   (+ tests).
4. E2E + docs (`docs/architecture.md`, README-honest additions, this spec linked).

Slices 1–3 build against the contracts in this spec and integrate in slice 4.
