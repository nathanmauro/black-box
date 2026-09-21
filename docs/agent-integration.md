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
| `searchContext` | Bounded discovery excerpts, filter diagnostics, provenance and source references |
| `searchSessions` | Legacy raw diagnostic search; row limits do not bound payload size |
| `recentSessions` | List recent agent sessions |
| `localModelStatus` | Inspect the optional local model backend |

Capture tools require nonblank `source` and `clientSessionId`. Decisions also require `decision`,
Handoffs require `contextSummary`, Observations require `text`, and Projections require at least one
path with a title. Missing, null, or blank required fields return an MCP tool error naming the field
and do not write an event. Correct the named field before retrying. Optional handoff fields such as
recipient, open loops, and next action retain their existing behavior.

### Bounded evidence discovery

Use `recallContext` first to recover structured prior intent. For broader discovery, prefer
`searchContext` (HTTP: `GET /api/search/compact?q=...`) to raw `searchSessions`. Both compact
surfaces return the same JSON shape; they leave existing raw search, event reads and recall intact.

```json
{"query":"backend kind:Handoff until:2026-08-18","limit":10,"maxBytes":24000}
```

The global result limit defaults to 10 and clamps to 1–50. `maxBytes` defaults to 24,000 and must
be 2,048–64,000. It bounds the complete serialized application JSON in UTF-8, including escaped
strings and metadata; HTTP headers and MCP framing add bytes. This differs from recall's text-field
`maxChars`. A response can contain fewer hits to fit the budget; the first excerpt is shortened
before its source reference is dropped. Invalid requests return compact diagnostics (HTTP 400;
MCP callers inspect `status`, which is not `ok`).

Each hit has the same fields for canonical and indexed results: `eventId`, `sessionId`,
`clientSessionId`, `source`, `eventType`, `role`, `observedAt`, `excerpt`, `excerptTruncated`,
`backends`, `provenance`, `sourceReference`, and observer-similarity fields. Excerpts contain at
most 600 Unicode code points. They are captured text, not generated summaries; a match may be in
metadata absent from the excerpt. Full metadata, tool JSON and raw hooks are not returned.
The local projection does not fetch/decode those payload fields. Searching metadata can still
scan large stored values, so a smaller response is not a database latency guarantee.

Check `coverage` for each backend: `searched`, `disabled`, `unavailable`, or `skipped_filters`.
Candidate counts describe only the bounded pool, not all matches. Each backend reads at most
200 candidates; `candidateLimitReached=true` means coverage may be incomplete. Results alternate
between backend orders without treating scores as comparable. The same canonical event appearing
in both arms is listed once, using canonical fields. Indexed-only hits remain unresolved and are
never presented as verified canonical sources. `truncated` and `omittedItems` report presentation
limits separately from candidate exhaustion.

Supported query operators include `source:`, `kind:`, `tool:`, `project:`, `session:`, `since:`,
`until:` and `last:`. The compact response exposes `appliedFilters`, one request time and the server
clock timezone. All recognized filters use canonical storage only until equivalent index filtering
exists. Legacy raw search retains its older behavior, including fuzzy index treatment of some
positive facets. `until:2026-08-18` includes all of that day in the server timezone; strictly before
that date uses `until:2026-08-17`. An exact `until:` timestamp is inclusive. Compact comparisons
normalize fractional precision. `before:` and malformed/negated time operators are diagnosed
before searching. Quote the entire token, such as `"before:2026-08-18"`, to search it literally.
URLs and ordinary colon-containing text remain searchable.

`excludeSession` excludes one exact internal or client session identity **before** candidate limits.
Default `groupSimilar=true` groups identical complete text from recognized tool events only.
These are explicitly **similar observer text with unknown origin**, not proven copies. Decisions
and user messages are not grouped. `similarCount` counts members in the candidate pool;
`similarEventIds` previews up to five additional member IDs and `similarMembersTruncated` indicates
more. Set `groupSimilar=false`, increase `limit`, or narrow by time/session to inspect members;
a group is not an exhaustive corpus inventory. Long excerpts and indexed-only excerpts are not
grouped because their completeness is unknown. A source outside the 200-candidate pool may still
be missing: narrow the query instead of inferring that no source exists.

Follow `sourceReference.eventPath` for the canonical recorded event or `browsePath` for its owning
session. Exact reads can be large: project required fields before printing. A recognized Codex
client or composite voice-session identifier provides an `externalTaskId` **candidate requiring
verification** through the external app. It is not the internal Black Box session ID. Unknown or
oversized identities remain unresolved; no file scan, credential access or external app read is
performed. Neither a capture type, matching phrase, summary, nor source candidate proves origin,
user agreement or causation. Verify original statements and label remaining inference.

For external source readers, inspect the current tool schema, follow returned pagination cursors,
check errors before parsing JSON, and bound the whole displayed response. Black Box does not
control those tools' page sizes or promise that every external task has a local transcript.

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

The Recall page offers 24 hours, one week, 30 days, **Three months (90 days)**, and
**Six months (180 days)**. These are rolling windows measured from now against each event's
`observedAt`, not calendar-month boundaries. The backend accepts up to 365 days. The page still
returns up to 10 items; widening the window does not export all matching history.

The **?** controls beside Scope, Window, and Kinds open practical examples and explain matching,
kind selection, and the top-bar source filter. Open or close them by click, Enter, or Space; Escape
closes the focused help and returns focus to its control. Run recall after changing its inputs.
An event ID still obeys the time/kind filters and uses matching rather than bypassing them;
use a result's Browse link to open its exact source event.

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
  can nest subagents under their parent. Set `SBA_CAPTURE_DURABLE=1` for an opt-in sanitized local
  queue with idempotent retries; see [durable capture](durable-capture.md) for requirements,
  destination restrictions, recovery commands, and privacy limits.
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

### Reading a handoff in the web interface

Choose **Read recalled context** to expand the context returned in a Recall result. Structured
handoffs include their context summary; older unstructured events can provide only a first-line
excerpt. **Open full handoff in Browse** links to the exact source event, where **Read full handoff**
expands its context summary or recorded text. Compact headlines, next actions, and open loops stay
visible. The disclosures support keyboard activation and display captured content as plain text.
