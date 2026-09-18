# Connect an agent

Recall is on demand by design: an agent asks for prior context when it is useful, rather than being
handed history at every session start. The `SessionStart` hook below is an optional way to request a
small packet automatically; connecting MCP does not enable it.

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
| `recallContext` | Recall Decisions, Handoffs, and Observations lexically or semantically; Projections are lexical-only |
| `searchSessions` | Search captured events and sessions |
| `recentSessions` | List recent agent sessions |
| `localModelStatus` | Inspect the optional local model backend |

### Coordination tools

| Tool | Purpose |
| --- | --- |
| `createSpec` | Freeze a work definition under an exact project key; its frozen body is returned with every claimed task |
| `enqueueTask` | Add an open task to one exact lane |
| `claimNextTask` | Atomically claim the highest-priority, oldest open task in one exact lane |
| `updateTaskStatus` | Block, reset, or cancel through the allowed lifecycle |
| `completeTask` | Complete owned work and atomically link a recallable Handoff |
| `listTasks` | Query full task/spec snapshots by project, lane, or status |
| `getSpec` | Retrieve the frozen spec body and provenance |

REST mirrors the seven coordination operations. REST task listing additionally accepts `offset`
and repeatable or comma-separated `excludeStatus`; MCP `listTasks` does not expose those parameters.
Successful REST and MCP results share field names and ISO-8601 timestamps; failures use stable typed error envelopes. See
[Architecture](architecture.md) for the full contract and transaction boundaries.

## Coordination example

Coordination extends capture → handoff → recall and is optional for memory use. The syntax below
is illustrative; MCP clients render tool calls differently. `projectKey` is stored as an exact
string: the server requires only that it be non-blank and never resolves it against the project
catalog. Use the catalog's canonical scope or path so Board grouping and exact `listTasks` queries
line up.

The server never launches a worker or executes a task command. The external worker does the work
between claiming and completing it. SSE frames are wake hints; claims and task reads are authoritative.

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

## Recall scope and limits

Core lexical recall needs no model or Elasticsearch. Semantic recall covers Decisions, Handoffs,
and Observations only. Projections can be recalled lexically; session-summary vectors are stored
for future retrieval but summaries are not returned by recall. The full event corpus is not
semantically indexed. An unavailable embedder or vector store leaves lexical results available.

Recall's `scope` value can be a repo path, bare repo name, event id, or topic, and how it is read
depends on its shape:

- A **repo path or event id** is a location, not a subject. Recall stays lexical and returns the
  most recent matching intent, reporting `mode: "lexical"`. Embedding a path would rank in-repo
  events by their similarity to a path string, which would perturb that recency ordering for no
  gain.
- A **topic** engages semantic recall. Shape decides this, not meaning: a scope containing `/`, or
  consisting entirely of 32 or more hex digits and dashes, is read as a path or event id and stays
  lexical, so a genuine subject such as `auth/session handling` is treated as a location and never
  embedded. Phrase topics without slashes. If the topic also matches prior event ids, working
  directories, captured text, or repo metadata, candidates are constrained to that anchor;
  otherwise it is treated as an open topic query within the selected time window and kinds.

One `scope` cannot yet express both a location and a subject — "decisions in this repo about
retries" needs a separate query parameter, which is not implemented.

REST calls the field `scope`; MCP calls it `repoOrTopic`. A blank value requests recent intent
across repositories. A bare repo name follows topic rules unless its shape is recognized as a
path or ID. A path match is a lexical anchor, not an authorization boundary or an exact project
filter: matching can include IDs, working directories, repo metadata, and captured text.

| Layer | Defaults and bounds |
| --- | --- |
| Optional SessionStart hook | 720 hours (30 days), 3 Decisions/Handoffs, 4,000-character context block |
| MCP `recallContext` | 168 hours, Decisions/Handoffs, 10 items (maximum 50); `maxChars` defaults to 24,000, minimum 500 |

These are different layers. The MCP clamp bounds item text fields, can trim rationale/headline
with a visible suffix and drop later items, and reports `truncated`. It is not the hook's packet
budget. Use MCP for topic queries, additional kinds, or a deliberately larger slice. Telemetry
counts the service result before that clamp; see [Recall observability](recall-observability.md).

## Optional capture and recall hooks

The bundled capture hooks do not record sessions until registered. Capture and recall are
independent choices: registering a write hook does not enable SessionStart recall.

- `scripts/hooks/sba-agent-hook.sh` normalizes supported Claude Code or Codex hook payloads and posts
  them to `/api/events`. Prompt, final-response, and tool hooks receive semantic `user`, `assistant`,
  and `tool` roles so Browse can reconstruct the recorded conversation. `SubagentStart` and
  `SubagentStop` payloads are recorded as child sessions keyed `<parent session_id>:<agent_id>`, with
  the lineage carried in event metadata (`agentId`, `agentType`, `parentClientSessionId`) so Browse
  can nest subagents under their parent.
- `scripts/hooks/sba-recall-hook.sh` recalls recent Decisions and Handoffs for a Claude Code or
  Codex `SessionStart` and prints a bounded context block. The bridge emits plain stdout for the
  host to inject as session context. Check that the installed client supports the configured hook
  event. Defaults are 30 days, 3 items, and 4000 chars. The hook skips compaction re-fires and spawned
  subagents, and appends a fire log to `recall.log` in the user's Black Box state directory with TSV
  columns `ts`,
  `client`, `outcome`, `cwd`, `session_id`, `items`, and `chars`.

Register recall globally in each client, alongside any existing hooks:

Claude Code's user-level `settings.json`:

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

Codex's user-level `hooks.json`:

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
both events to Claude Code's user-level `settings.json` with a wildcard matcher, alongside any existing hook
entries, passing `claude` as the source argument:

```json
{
  "hooks": {
    "SubagentStart": [
      {
        "matcher": "*",
        "hooks": [
          { "type": "command", "command": "/path/to/black-box/scripts/hooks/sba-agent-hook.sh claude" }
        ]
      }
    ],
    "SubagentStop": [
      {
        "matcher": "*",
        "hooks": [
          { "type": "command", "command": "/path/to/black-box/scripts/hooks/sba-agent-hook.sh claude" }
        ]
      }
    ]
  }
}
```

### Hook smoke tests

These fixture tests use a fake HTTP client and do not write to a running recorder:

```bash
scripts/test-agent-hook.sh
scripts/test-recall-hook.sh
```

The following payloads **write events**. Start a disposable recorder on port 8797 first; these
commands explicitly select it. They are not a read-only probe of an existing service:

```bash
printf '{"hook_event_name":"UserPromptSubmit","session_id":"hook-test","prompt":"hello","cwd":"%s"}' "$PWD" |
  SBA_AGENTIC_URL=http://localhost:8797 SBA_AGENT_SOURCE=manual scripts/hooks/sba-agent-hook.sh

printf '{"hook_event_name":"SubagentStart","session_id":"hook-test","agent_type":"Explore","agent_id":"hook-test-agent"}' |
  SBA_AGENTIC_URL=http://localhost:8797 SBA_AGENT_SOURCE=manual scripts/hooks/sba-agent-hook.sh
```

Both bridges fail softly when the recorder is unavailable, slow, or `jq` is missing; failure does
not stop the host session. The recall bridge skips compaction re-fires and spawned subagents.
Keep its `Black Box recall:` plain-text header: output beginning with `[` or `{` can be interpreted
as JSON by the client. The fire log contains local paths and session identifiers; it is an
operator log, not content to publish or ingest into shared memory.

The examples target the default loopback service. The bundled hooks do not send an Authorization
header. An authenticated deployment needs a credential-aware client or bridge; changing
`SBA_AGENTIC_URL` alone does not add authentication. See [Authentication](authentication.md) and
[hook environment variables](operations.md#hook-environment-variables).
