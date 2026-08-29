<div align="center">
  <img src="frontend/public/favicon.svg" width="72" alt="Black Box logo">
  <h1>Black Box</h1>
  <p><strong>Local-first memory and coordination for coding agents.</strong></p>
  <p>
    Preserve decisions. Claim work atomically. Leave typed handoffs.<br>
    Let the next agent start with context instead of archaeology.
  </p>

  <p>
    <a href="https://github.com/nathanmauro/black-box/actions/workflows/ci.yml"><img src="https://github.com/nathanmauro/black-box/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
    <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&amp;logoColor=white" alt="Java 21"></a>
    <a href="http://localhost:8766/mcp"><img src="https://img.shields.io/badge/MCP-Streamable_HTTP-7C6CF2" alt="MCP Streamable HTTP"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-2EA44F.svg" alt="MIT License"></a>
  </p>
</div>

![Black Box Activity workspace showing agent decisions, handoffs, and tool activity.](docs/assets/hero.png)

Black Box is a writable memory bus and coordination ledger for Codex, Claude Code, and other MCP
clients. Agents commit the reasoning worth preserving, coordinate through a SQLite-backed task
queue, and recall exact or semantically related structured decisions and completion handoffs in
later sessions.

The Black Box server is deliberately not an agent runner. It records intent, arbitrates ownership,
and exposes state; your agents and orchestrators still execute the work.

| Remember | Coordinate | Observe | Stay local-first |
| --- | --- | --- | --- |
| Typed Decisions, Handoffs, Observations, alternatives, confidence, and open loops | Frozen specs, exact-lane queues, atomic claims, lifecycle rules, and completion Handoffs | Activity, logical Projects, project-aware Board, semantic structured Recall, search, SSE updates, and stats | SQLite is authoritative; Elasticsearch and model-backed features are optional |

## The loop

```mermaid
flowchart LR
    P[Planner] -->|createSpec| S[Frozen spec]
    S -->|enqueueTask| Q[Lane queue]
    Q -->|atomic claim| W[Worker]
    W -->|block or complete| T[Task lifecycle]
    T -->|completeTask| H[Recallable Handoff]
    H -->|recallContext| N[Next agent]
```

1. A planner freezes the work definition and enqueues lane-specific tasks.
2. A worker atomically claims the highest-priority, oldest task in its exact lane.
3. Every transition is validated and recorded; stalled work can be blocked and explicitly reset.
4. Completion creates a normal Black Box Handoff and links it to the task.
5. A later agent recalls the result directly instead of reconstructing it from transcripts.

The task path never launches a worker, executes a command, or mutates a checkout. SSE frames are
wake-up hints; `claimNextTask` and `listTasks` remain authoritative.

## See it

### Watch a project's trajectory

<img src="docs/assets/trajectory.png" alt="Black Box trajectory graph: a spine of burst epochs behind a glowing head node, ranked future paths fanning ahead, ghost projections on a dashed shell, and rejected alternatives as dead stubs." width="100%">

Every project opens on its trajectory. Burst epochs form the spine behind the head — the latest
Handoff — and the ranked futures fan out ahead of it: next actions, open tasks, open loops, and the
ghost projections agents left before closing their sessions. Rejected alternatives hang below as
dead stubs. The hybrid storyline stays one tab away.

### Follow a whole project storyline

<img src="docs/assets/projects.png" alt="Black Box Projects workspace with the trajectory graph in the center pane and a selected ghost projection's detail, including its confidence, in the right rail." width="100%">

Group verified worktrees under one logical identity, select any node to inspect its evidence, and
pivot into Activity, the exact-scope Board, or Recall without rewriting recorded history.

| Coordination Board | Structured Recall |
| --- | --- |
| <img src="docs/assets/board.png" alt="Black Box Coordination Board with Open, In Progress, Blocked, and Done lanes." width="100%"> | <img src="docs/assets/recall.png" alt="Black Box Recall workspace showing a typed Handoff and Decision." width="100%"> |
| Inspect frozen intent, ownership, blockers, priorities, and linked completion Handoffs. | Query Decisions and Handoffs by repo, topic, event id, or semantic paraphrase without reading raw transcripts. |

## Start in 60 seconds

Requirements: Java 21+, Maven 3.9+, `curl`, and `jq`.

```bash
git clone https://github.com/nathanmauro/black-box.git
cd black-box
./scripts/quickstart.sh
```

The quickstart builds the jar, starts an isolated demo database, seeds a cross-agent story, proves
recall, and opens the UI at [localhost:8766](http://localhost:8766).

Already built?

```bash
./scripts/demo.sh
```

Want the complete coordination loop without touching your live database?

```bash
./scripts/demo-agent-loop.sh --dry-run
./scripts/demo-agent-loop.sh --run-isolated
```

Run the app directly:

```bash
mvn spring-boot:run
curl -fsS http://localhost:8766/api/status | jq
```

## Connect an agent

Black Box exposes MCP over Streamable HTTP at `http://localhost:8766/mcp`.

Codex:

```bash
codex mcp add sba-agentic --url http://localhost:8766/mcp
codex mcp list
```

Claude Code:

```bash
claude mcp add --transport http --scope user sba-agentic http://localhost:8766/mcp
claude mcp list
```

Restart the client if the tools do not appear. The server keeps the historical MCP id
`sba-agentic`; the product name is Black Box.

### Memory tools

| Tool | Purpose |
| --- | --- |
| `captureDecision` | Preserve a choice, rationale, rejected alternatives, confidence, and open loops |
| `captureHandoff` | Leave context, open loops, and one next action for another agent |
| `captureObservation` | Record a concise fact or note |
| `captureProjection` | Capture one to five plausible future paths for the project graph |
| `recallContext` | Recall structured Decisions, Handoffs, Observations, and Projections by repo, topic, semantic paraphrase, or event id |
| `searchSessions` | Search captured events and sessions |
| `recentSessions` | List recent agent sessions |
| `localModelStatus` | Inspect the optional local model backend |

### Coordination tools

| Tool | Purpose |
| --- | --- |
| `createSpec` | Freeze a work definition under the catalog's canonical project scope or path |
| `enqueueTask` | Add an open task to one exact lane |
| `claimNextTask` | Atomically claim the highest-priority, oldest eligible task |
| `updateTaskStatus` | Block, reset, or cancel through the allowed lifecycle |
| `completeTask` | Complete owned work and atomically link a recallable Handoff |
| `listTasks` | Query full task/spec snapshots by project, lane, or status |
| `getSpec` | Retrieve the frozen spec body and provenance |

REST mirrors the seven coordination operations exactly. Successful REST and MCP results share field
names and ISO-8601 timestamps; failures use stable typed error envelopes. See
[Architecture](docs/architecture.md) for the full contract and transaction boundaries.

## A minimal agent handoff

The syntax below is illustrative; MCP clients render tool calls differently.

```text
createSpec({
  "projectKey": "/workspace/example-app",
  "title": "Add a health probe",
  "body": "Implement GET /healthz and prove a 200 response.",
  "actor": "planner"
})

enqueueTask({
  "specId": "<returned-spec-id>",
  "title": "Implement and verify the health probe",
  "lane": "codex",
  "priority": 10,
  "actor": "planner"
})

claimNextTask({"lane": "codex", "agent": "worker-1"})

completeTask({
  "taskId": "<returned-task-id>",
  "actor": "worker-1",
  "source": "codex",
  "clientSessionId": "health-probe-run",
  "summary": "Implemented and verified GET /healthz.",
  "openLoops": [],
  "nextAction": "Run the release gate."
})

recallContext({
  "repoOrTopic": "<returned-resultHandoffId>",
  "withinHours": 24,
  "kinds": ["handoff"]
})
```

## Product surfaces

- **Activity** — the filterable global event stream, session browser, and optional Ask surface.
- **Projects** — a searchable logical-project workspace that groups verified worktree scopes without
  rewriting recorded paths. The center pane defaults to the trajectory graph (epochs, ranked
  futures, ghost projections, rejected stubs); the Hybrid Storyline stays on the Timeline tab.
  Inspect constituent scopes, recent sessions, and saved synthesis, or explicitly merge and undo
  ambiguous catalog scopes.
- **Board** — searchable catalog-project and lane filters over explicitly queued Open, In Progress,
  Blocked, and Done tasks, with frozen spec and linked-Handoff detail. Selecting a project does not
  infer tasks from Activity, sessions, or external systems; use its canonical scope or path as
  `createSpec.projectKey` when enqueueing work.
- **Recall** — focused Decision, Handoff, and Observation retrieval by repo, topic, event id, or
  semantic paraphrase. Each result links back to its owning session with the exact source event
  selected.

Open them directly:

- [Activity](http://localhost:8766/)
- [Projects](http://localhost:8766/projects)
- [Board](http://localhost:8766/board)
- [Recall](http://localhost:8766/recall)

## Trust and data boundaries

SQLite is the source of truth for sessions, events, structured memory, specs, tasks, and lifecycle
events. The server binds to `127.0.0.1` by default and has no built-in authentication. Do not expose
it on a network unless you accept that trust model.

Logical project aliases affect catalog, session, storyline, and saved-meld reads only. Black Box
never rewrites historical session/event paths or task/spec project scopes when projects are grouped.
Git-common-directory worktrees and structurally unambiguous worktree paths whose owner still has
Git metadata are discovered automatically; ambiguous or unverified paths require an explicit,
reversible merge in Projects.

Automatic redaction is enabled before persistence for private-key blocks, AWS credentials, bearer
or API tokens, and password/token assignments. Set `SBA_REDACT_ENABLED=false` only when you
deliberately want unredacted local storage.

Core capture, coordination, and lexical recall require no model and no Elasticsearch. Semantic recall
uses memory embeddings for structured Decisions, Handoffs, and Observations; recall results surface
those structured intent events only today. Non-empty session summaries are also stored in the memory
embedding index for backfill and future retrieval, but no recall path returns summaries yet. The full
captured event corpus is not semantically indexed. If the embedder or vector store is unavailable,
recall still returns lexical results and reports `mode: "lexical"`.

Recall's `scope` value can be a repo path, bare repo name, event id, or topic, and how it is read
depends on its shape:

- A **repo path or event id** is a location, not a subject. Recall stays lexical and returns the
  most recent matching intent, reporting `mode: "lexical"`. Embedding a path would rank in-repo
  events by their similarity to a path string, which would perturb that recency ordering for no
  gain.
- A **topic** engages semantic recall. If the topic also matches prior event ids, working
  directories, captured text, or repo metadata, candidates are constrained to that anchor;
  otherwise it is treated as an open topic query within the selected time window and kinds.

One `scope` cannot yet express both a location and a subject — "decisions in this repo about
retries" needs a separate query parameter, which is not implemented.

Session summaries have a separate privacy boundary: the default `external` backend invokes the
bundled Codex wrapper and can send transcript text through that vendor path. Set
`SBA_SUMMARY_BACKEND=local` to use LM Studio or another local OpenAI-compatible server instead.

## Board-driven runner modes (optional, config-gated)

Black Box also ships an optional external runner process: `java -jar sba-agentic.jar runner`, a CLI
subcommand alongside `doctor`, `ingest`, `sessions`, and the other maintenance commands. A story
submitted through the Board's New Story form, or through `createSpec` plus `enqueueTask` in lane
`gate`, records either `full_auto` or `sdlc` in its frozen spec and begins with the same deterministic
readiness checks. Both modes use isolated worktrees, configured engines, verification, commits, live
Board updates, and tendrils into worker-session context. A `fake` engine is available for tests.

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
worktree and posts advisory findings; review approval invokes the same shipping path as FULL_AUTO.

Without the matching approval the runner makes no progress. A rejection records the feedback as a
`progress` marker on the already-`done` stage task and enqueues or ships nothing. Approval never
bypasses repo allowlists, danger settings, push and auto-merge configuration, credentials, or green
check requirements.

- The runner requires an explicit machine-local config, selected through `SBA_RUNNER_CONFIG` or
  `~/.blackbox/runner.json` by default. It is never committed; start from
  [`docs/runner-config.example.json`](docs/runner-config.example.json). The config allowlists repos
  and controls push, auto-merge, and danger settings.
- Downstream behavior fails closed in both modes: an unknown repo, danger flag, red check, missing
  approval, or missing credential produces local-only, waiting, or blocked work, never a risky action.
- The Black Box server still never launches a worker or executes a task command. The runner is a
  separate process and an ordinary REST client, like any other agent or orchestrator.

See the [FULL_AUTO architecture](docs/architecture.md#optional-full_auto-runner),
[SDLC architecture](docs/architecture.md#optional-sdlc-runner-mode),
[`FULL_AUTO board-driven runner` design spec](docs/superpowers/specs/2026-07-15-full-auto-board-runner.md),
and [`SDLC mode` design spec](docs/superpowers/specs/2026-07-16-sdlc-mode.md) for the full pipelines
and guardrails.

## Run as a service

macOS launchd:

```bash
./scripts/deploy-local.sh
```

The script builds the frontend and jar, restarts `com.nathan.sba-agentic` by default, and waits for a
healthy status response. Use `scripts/black-box.plist.template` for a first-time LaunchAgent install.

macOS board runner launchd:

`scripts/deploy-runner-local.sh` deploys the already-built jar as the
`com.nathan.blackbox-runner` launchd service. It does not build the jar; run
`scripts/deploy-local.sh` first. Use `scripts/blackbox-runner.plist.template` for a first-time runner
LaunchAgent install.

Running the deploy script by hand starts or restarts the runner. This is not a passive service:
starting it launches autonomous orchestration according to `~/.blackbox/runner.json`. The runner
claims `gate` and `auto` tasks for FULL_AUTO stories and `gate`, `sdlc:plan`, `auto`, and
`sdlc:review` tasks for SDLC stories; it also reacts to SDLC approval annotations. Both modes use the
configured engine, repo, push, and auto-merge settings. Review
[Board-driven runner modes](#board-driven-runner-modes-optional-config-gated) for the full behavior
and [`docs/runner-config.example.json`](docs/runner-config.example.json) for the config shape before
starting it.

Linux systemd:

```bash
cp scripts/black-box.service ~/.config/systemd/user/black-box.service
$EDITOR ~/.config/systemd/user/black-box.service
systemctl --user daemon-reload
systemctl --user enable --now black-box
```

Docker for local development:

```bash
mvn clean -DskipTests package
docker build -t black-box .
docker run --rm -p 127.0.0.1:8766:8766 -v black-box-data:/data black-box
```

## Configuration

Defaults live in `src/main/resources/application.yml`.

### Secure file navigation

Expanded Stream, Browse, Find, and Projects event cards turn presenter file references into actions
only when the path matches a filesystem-verified Git scope from the project catalog. The browser
sends a transport-neutral `CodeReference` — an opaque `projectKey`, a relative path, and optional
line/column — to `POST /api/open-in-editor` or `POST /api/reveal-in-finder`; it never sends the raw
absolute target to either action endpoint. Paths outside eligible roots stay visible and copyable
but are not openable.

The server revalidates catalog membership, exact-scope confinement, symlinks, file existence, and
line bounds on every request. It then invokes only a configured, allowlisted absolute executable
with discrete argv; file content and event data are never evaluated by a shell. Cursor is the
macOS default, and Visual Studio Code can use the same `-g file:line:column` adapter. Finder reveal
uses the same resolver and a fixed `/usr/bin/open -R` command. Failures return stable typed errors
and render beside the path instead of silently doing nothing.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_PORT` | `8766` | HTTP port |
| `SBA_BIND_ADDRESS` | `127.0.0.1` | Bind address; network exposure has no built-in auth |
| `SBA_DATASOURCE_URL` | `jdbc:sqlite:sba-agentic.db` | SQLite database location |
| `SBA_REDACT_ENABLED` | `true` | Redact secret-looking text before persistence |
| `SBA_EDITOR_ENABLED` | `true` | Enable catalog-bound open-in-editor actions |
| `SBA_EDITOR_COMMAND` | Cursor application CLI on macOS | Absolute Cursor/VS Code-compatible CLI path |
| `SBA_EDITOR_ALLOWLIST` | Installed Cursor and VS Code CLI paths | Comma-separated absolute executables the server may launch |
| `SBA_EDITOR_TIMEOUT` | `5s` | Maximum time for the editor or Finder CLI to hand off successfully |
| `SBA_SUMMARY_BACKEND` | `external` | Summary backend; set `local` for an OpenAI-compatible local model |
| `SBA_SUMMARY_EXTERNAL_COMMAND` | `scripts/summarize-with-codex.sh` | External summary command |
| `SBA_LOCAL_AI_BASE_URL` | `http://localhost:1234` | Local OpenAI-compatible server |
| `SBA_MEMORY_EMBEDDING_ENABLED` | `true` | Enable semantic recall embeddings; disabled recall reports lexical mode |
| `SBA_MEMORY_EMBEDDING_URL` | `http://localhost:11434` | Ollama-compatible memory embedding endpoint |
| `SBA_MEMORY_EMBEDDING_MODEL` | `nomic-embed-text` | Memory embedding model for structured intent and stored summary vectors |
| `SBA_MEMORY_EMBEDDING_DOCUMENT_PREFIX` | `search_document: ` | Prefix applied to stored text before embedding; empty disables it |
| `SBA_MEMORY_EMBEDDING_QUERY_PREFIX` | `search_query: ` | Prefix applied to recall queries before embedding; empty disables it |
| `SBA_SQLITE_VEC_PATH` | unset | Optional sqlite-vec extension path; empty uses the portable brute-force vector fallback |
| `SBA_ELASTICSEARCH_ENABLED` | `false` | Enable optional secondary search indexing |
| `SBA_ELASTICSEARCH_URL` | `http://localhost:9200` | Optional Elasticsearch endpoint |
| `SBA_EXPORT_OBSIDIAN_DIR` | unset | Enables the built-in Obsidian summary export target |

The hook bridges additionally read `SBA_AGENTIC_URL`, `SBA_AGENT_SOURCE`,
`SBA_RECALL_WITHIN_HOURS`, `SBA_RECALL_LIMIT`, `SBA_RECALL_MAX_CHARS`, `SBA_RECALL_CLIENT`
(log label when `--client` is not passed), and `SBA_RECALL_LOG` (fire-log path; `off` or empty
disables it). See
[Local writes and Elasticsearch](docs/local-writes-and-elasticsearch.md) for the complete operational
reference.

## Optional capture and recall hooks

Black Box does not capture agent sessions until you opt in.

- `scripts/hooks/sba-agent-hook.sh` normalizes supported Claude Code or Codex hook payloads and posts
  them to `/api/events`. Prompt, final-response, and tool hooks receive semantic `user`, `assistant`,
  and `tool` roles so Browse can reconstruct the recorded conversation. `SubagentStart` and
  `SubagentStop` payloads are recorded as child sessions keyed `<parent session_id>:<agent_id>`, with
  the lineage carried in event metadata (`agentId`, `agentType`, `parentClientSessionId`) so Browse
  can nest subagents under their parent.
- `scripts/hooks/sba-recall-hook.sh` recalls recent Decisions and Handoffs for a Claude Code or
  Codex `SessionStart` and prints a bounded context block. Codex has supported `SessionStart` hooks
  since codex-cli rust-v0.114.0 (2026-03-11), and both clients inject the hook's plain stdout as
  session context. Defaults are 30 days, 3 items, and 4000 chars. The hook skips compaction re-fires
  and spawned subagents, and appends a fire log to `~/.blackbox/recall.log` with TSV columns `ts`,
  `client`, `outcome`, `cwd`, `session_id`, `items`, and `chars`.

Register recall globally in each client, alongside any existing hooks:

`~/.claude/settings.json`:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "startup|resume",
        "hooks": [
          {
            "type": "command",
            "command": "bash /path/to/black-box/scripts/hooks/sba-recall-hook.sh --client claude",
            "timeout": 5
          }
        ]
      }
    ]
  }
}
```

`~/.codex/hooks.json`:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "startup|resume",
        "hooks": [
          {
            "type": "command",
            "command": "bash /path/to/black-box/scripts/hooks/sba-recall-hook.sh --client codex",
            "timeout": 5
          }
        ]
      }
    ]
  }
}
```

Use `recallContext` through MCP for topic queries mid-session, more kinds, or a larger recall slice.

### Subagent lineage (Claude Code)

`SubagentStart`/`SubagentStop` hooks fire in the parent session; the bridge derives the child
session identity from `session_id` + `agent_id`. Registration is user-global (not in-repo): add
both events to `~/.claude/settings.json` with a wildcard matcher, alongside any existing hook
entries, passing `claude` as the source argument:

```json
{
  "hooks": {
    "SubagentStart": [
      {
        "matcher": "*",
        "hooks": [
          { "type": "command", "command": "/ABSOLUTE/PATH/TO/sba-agentic/scripts/hooks/sba-agent-hook.sh claude" }
        ]
      }
    ],
    "SubagentStop": [
      {
        "matcher": "*",
        "hooks": [
          { "type": "command", "command": "/ABSOLUTE/PATH/TO/sba-agentic/scripts/hooks/sba-agent-hook.sh claude" }
        ]
      }
    ]
  }
}
```

Hook smoke test:

```bash
scripts/test-agent-hook.sh

printf '{"hook_event_name":"UserPromptSubmit","session_id":"hook-test","prompt":"hello","cwd":"%s"}' "$PWD" |
  SBA_AGENT_SOURCE=manual scripts/hooks/sba-agent-hook.sh

printf '{"hook_event_name":"SubagentStart","session_id":"hook-test","agent_type":"Explore","agent_id":"hook-test-agent"}' |
  SBA_AGENT_SOURCE=manual scripts/hooks/sba-agent-hook.sh
```

## Terminal proof

The demo below is generated from the real isolated decision → handoff → recall flow.

<!-- Generated by ./scripts/record-demo.sh, which records ./scripts/demo.sh. -->
![Terminal demo showing one agent committing a decision and another recalling it.](docs/assets/demo.gif)

Regenerate it with:

```bash
SBA_DEMO_PORT=8797 ./scripts/record-demo.sh
```

## Develop

```bash
mvn test
cd frontend && npm test
cd frontend && npm run build
cd frontend && npm run e2e
```

The E2E suite starts a packaged jar on an isolated temporary SQLite database and fails closed if its
paths or ownership marker are unsafe. It does not attach to port `8766` or the production database.

For deeper implementation details:

- [Architecture](docs/architecture.md)
- [Agent task queue design](docs/superpowers/specs/2026-06-28-agent-task-queue-design.md)
- [Local writes and Elasticsearch](docs/local-writes-and-elasticsearch.md)

## License

[MIT](LICENSE) © 2026 Nathan Mauro
