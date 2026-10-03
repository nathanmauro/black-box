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
| `captureIdea` | Capture an idea someone proposed that is not being acted on now (a human's aside or an agent's suggestion) |
| `captureEvidence` | Capture a verifiable fact with provenance and optional support or refute links |
| `recallIdea` | Return an idea with supporting and refuting Evidence across revisions |
| `recallContext` | Recall Decisions, Handoffs, Observations, Ideas, and Evidence lexically or semantically; Projections are lexical-only |
| `searchContext` | Bounded discovery excerpts, filter diagnostics, provenance and source references |
| `searchSessions` | Legacy raw diagnostic search; row limits do not bound payload size. `humanOnly=true` matches only the human's own turns |
| `recentSessions` | List recent agent sessions, each with `firstHumanTurn`. `humanOnly=true` keeps only sessions that contain a human turn |
| `localModelStatus` | Inspect the optional local model backend |

Capture tools require nonblank `source` and `clientSessionId`. Decisions also require `decision`,
Handoffs require `contextSummary`, Observations require `text`, Projections require at least one
path with a title, Ideas require `title`, `oneLiner`, and `origin`, and Evidence requires
`claim` and `sourceRef`. Missing, null, or blank required fields return an MCP tool error naming the field and do not write an event. Correct the
named field before retrying. Optional handoff fields such as recipient, open loops, and next action
retain their existing behavior.

Decision confidence and confidence on retained Projection paths are optional. A supplied value must
be finite and between `0.0` and `1.0`, inclusive; omission and `null` remain valid. Both REST and MCP
reject invalid confidence before creating an event or session, naming `confidence` or the original
input path such as `paths[2].confidence`. Decision replacements follow the same rule. Projection
filtering/capping is unchanged: null/untitled entries are discarded, MCP retains the first five
titled paths, and REST still rejects a list longer than five. Discarded paths add no confidence
validation requirement.

A successful capture acknowledgement identifies the event committed to the canonical database.
Failures while publishing optional downstream notifications are logged without turning that commit
into an HTTP or MCP tool error. Terminal captures attempt their session-stop notification even if
publishing the event notification fails. Validation and database failures still return errors.

Structured capture endpoints and MCP capture tools remain append-only: a genuinely lost network
response does not make a retry safe, and repeating the request can create another event. Clients
that need receipt-based retries can use `POST /api/events/idempotent` with a stable `captureId` and
unchanged event body; see [Idempotent event capture](idempotent-capture.md).

### Projection evidence

Explicit `kinds=projection` on REST recall or `kinds: ["projection"]` on MCP recall returns the
canonical rendered Projection text in `body`: its plausible futures, path descriptions and
confidences, and shared basis. `headline` and `confidence` retain their historical meaning as the
first listed path's title and confidence. They do not identify a chosen decision or an aggregate
confidence across futures; read `body` for the alternatives and conditions.

`body` preserves stored text after capture redaction and length limits. An oversized capture can
already end in `[truncated]` while its event metadata retains fuller `paths` and `basis`; use
`GET /api/events/{eventId}` for that canonical evidence. MCP can additionally shorten `body` under
`maxChars`, with a visible omission suffix and result-level `truncated: true`. That result flag
reports recall presentation limits, not whether ingest previously capped the text. The source
`eventId`, `sessionId`, and `observedAt` remain available when MCP clips a body.

Projections remain lexical-only and excluded from default Decisions/Handoffs recall. Select
**Projection** in the Recall page to inspect recorded paths, or open a link with
`kinds=projection&run=1`. The reader and copied context label them as possibilities. The card does
not show the legacy first-path confidence as an overall score; each path's declared confidence
remains in the body. Older responses without a body link to the source capture for full evidence.

### Evidence and project lanes

`Evidence` records one verifiable fact with provenance. Capture it using `captureEvidence` or
`POST /api/evidence`: provide a one-sentence `claim` and a `sourceRef` such as a session id,
`path:line`, URL, or command. Optional `excerpt` holds verbatim proof, `outputDigest` holds at most
200 characters, and `observedAt` accepts an ISO-8601 instant or offset date-time and is stored as
an instant without changing the capture time. Nonblank excerpt indentation and trailing newlines
are preserved before normal capture redaction and length limits. `capturedBy` defaults to the capture source in the
read view. `notes` holds optional markdown.

`supports` and `refutes` link to `idea:<ideaKey>` or `event:<eventId>`. A bare 8–36 character
hex-and-dash event id or prefix becomes an `event:` reference. Prefixes are case-insensitive;
event ids are stored in lowercase while idea keys retain their case. References are trimmed and
deduplicated within each list. Each list accepts at most 50 entries,
and the same reference cannot be in both lists. A target need not exist yet.

Ideas and Evidence accept a `project` home lane and up to 20 `alsoIn` entries of
`{project, score}`. Each secondary project must be distinct from the home lane and from other
entries, ignoring case and surrounding spaces; scores must be finite and between 0 and 1. When
`project` is omitted, `repo` is the home lane for this check. Empty `alsoIn`, `supports`, and
`refutes` lists are treated as absent. Read views use `repo` as the home lane when `project` was
omitted, and remove any older secondary lane that becomes the home lane. Idea lanes survive a
status-only re-capture.

`GET /api/evidence` returns `{items, count}` newest first. Filter with `target` (a typed reference),
`project` or `repo` (resolved project scope), `q` (all terms, including event text), and `limit`
(default 100, max 500). A blank `target` is ignored.
`GET /api/ideas/detail?ideaKey=<key>` returns `{idea, supports, refutes}`. It joins Evidence linked
to the stable idea key or to any revision's exact event id or an event id prefix of at least eight
characters. `recallIdea(ideaKey)` returns the same detail over MCP. Unknown keys return a typed
404 `request_failed` envelope from REST and a tool error over MCP.

`recallContext` and `/api/recall` include Evidence when `kinds` includes `evidence`; the headline
is its claim and the rationale is its excerpt. Its `body` contains the canonical rendered text,
including provenance, typed references, observation time and notes when those fit within capture
limits. Evidence is also indexed for semantic recall. MCP may further clip the body under `maxChars`
while retaining the event/session/time anchor and reporting `truncated: true`. Capture-time limits
are separate: stored text may already end in `[truncated]`; fetch the event for its stored metadata.

Evidence is opt-in in Recall; default kinds remain Decisions/Handoffs. Browse classifies it as a
memory event, revealed by the existing memory toggle or an exact source link. Stream's existing
kind facet includes Evidence. There is no dedicated Evidence or project-lane view.

The ChatGPT gateway accepts text-only `kind=evidence` through its existing generic event capture,
with transport provenance. It does not infer structured `sourceRef`, excerpt, or supports/refutes
from prose. Use dedicated REST/MCP Evidence capture for those fields; generic captures retain their
full stored text in recall `body`.

### Ideas

An `Idea` records something someone proposed that nobody is acting on now: the human's aside or an
agent's suggestion that would otherwise vanish into a transcript. Capture it with `captureIdea` or
`POST /api/ideas`.

- `origin` is `human-aside`, `agent-proposed`, or `joint`; the legacy `nathan-aside` is stored as
  `human-aside`. `status` is `untouched` (default), `partially-built`, `built-unused`,
  `superseded`, or `tracked`. `legs` (how much the idea has going for it) is an integer 0–10. Any
  other value is rejected with a message listing the allowed ones.
- A nonblank `quote` preserves indentation, surrounding spaces and trailing newlines in stored
  metadata and its rendered quotation, subject to the existing redaction and capture-length limits.
  Omitted, null, empty and whitespace-only quotes are absent on the new event; the collapsed
  listing and Idea detail inherit the newest earlier nonblank quote. A new nonblank quote replaces
  that view's value without changing older events. Previously stripped whitespace is not recoverable
  from stored records.
- Captures are append-only. To change an idea's status, capture it again with the same `ideaKey`.
  The default key is a slug of the repo's last path segment plus the title (for example
  `sba-agentic-evidence-capture-kind`). Clones that share a directory name key the same way. A
  worktree or clone under a different directory name gets a different key, so pass an explicit
  `ideaKey` when an idea is captured from more than one checkout. A re-capture only needs the
  required fields: optional fields it omits keep their earlier values in the listing.
  Project attribution uses the newest revision with a nonblank captured `repo` or legacy session
  cwd, preferring captured `repo` within that revision. A newer explicit repo or cwd still wins;
  an unattributed revision inherits an older project without rewriting either event.
- `GET /api/ideas` returns `{items, count}`, newest first, collapsed to the latest event per
  `ideaKey`, with `revisions`, `firstCapturedAt`, and `migratedFrom`. Filters: `status` (repeatable
  or comma-separated), `origin`, `project` or `repo` (a project path, alias-aware like the stream's
  project group), `q` (all terms must appear in the idea's text fields), and `limit` (default 100,
  clamped to 1–500). `count` is the number of items returned.
- `recallContext` and `/api/recall` return ideas only when `kinds` includes `idea`; the headline is
  the title, the rationale is the one-liner, and `nextAction` is the resume step.
- `POST /api/ideas/migrate-observations` parses Observations whose text starts with `[Idea]`. It is
  a dry run by default and writes nothing; `?apply=true` captures one `Idea` per candidate (one
  session per repo, `idea-migration:<repo>`, so recall and project scoping file each idea under its
  own repo; `sourceRef` set to the observation's session, `notes` set to the original body,
  `migratedFrom` set to the observation id, and the capture time set to the observation's, so a
  newer native capture of the same `ideaKey` stays the latest state). Re-running skips observations
  already migrated, and the observations themselves are never changed.

### Human turns

Prompt hooks fire for far more than human input (background-task notifications, relayed subagent
reports, automation prompts), so Black Box classifies each prompt event when it is captured.
`HumanTurns` is the single rule set: it strips harness blocks such as `<system-reminder>` and
`<task-notification>`, unwraps typed slash commands, and rejects known automation prompt shapes.
The result is stored beside the event, never in place of it:

- Events carry `humanText` (`agent_events.human_text`): the cleaned text of a human turn, else
  `null`. The stored `text` is unchanged.
- Sessions carry `firstHumanTurn` (`agent_sessions.first_human_turn`): the earliest human turn.
  A human turn also titles its session (`TitleRank.HUMAN`), above fallback, tool, text and
  client-supplied titles but below an AI summary title.
- `humanOnly=true` (default `false`) narrows `GET /api/events`, `/api/events/facets`,
  `/api/sessions` (only sessions with a human turn), `/api/sessions/{id}/events`,
  `/api/sessions/{id}/transcript` (recorded human turns only; transcript-file messages are not
  classified) and `/api/search` (local index only; Elasticsearch is skipped). MCP
  `recentSessions` and `searchSessions` take the same `humanOnly` argument.
- Classification is heuristic: an unrecognized automation prompt, or a short agent-written brief
  sent to a headless session, can still classify as human. Bumping `HumanTurns.VERSION`
  reclassifies stored rows on the next start (`human_turn_state` records the applied version).
  Semantic recall does not index human turns.

### Session transcript tool output

`GET /api/sessions/{id}/transcript` omits repeated event `text` when it equals the decoded
canonical `toolOutputJson` string after trimming surrounding whitespace. This projection works
for durable captures without `metadata.rawHook` and legacy captures alike; `rawHook` is omitted
from returned transcript metadata. Tool payloads, stored event rows and timestamps are unchanged.
Distinct status text, malformed/non-string JSON outputs and truncated prefixes remain visible.
The browser already suppresses exact short duplicates separately; this API rule does not solve
ambiguous truncated-prefix duplication or require local JSONL files.

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
that date uses `until:2026-08-17`. An exact `until:` timestamp is inclusive. Canonical feed, session transcript pagination, local
legacy/compact search and recall compare UTC timestamps at nanosecond precision, including whole-second
and fractional values. Event timestamps remain unchanged in storage and responses; pagination retains
the existing timestamp-plus-event-ID cursor and event IDs break exact timestamp ties. `before:` and malformed/negated time operators are diagnosed
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

### Session lineage

The dedicated `lineage` module owns session relationships independently of task tracking.
`POST /api/session-links` creates links; `GET /api/sessions/{id}/links` reads parents and children;
`GET /api/session-links/child-counts?ids=...` supplies Browse child counts; and
`GET /api/dag?sessionId=...` returns the session-only lineage graph. Link types remain `spawned`,
`steered`, and `continued`. Hook-derived subagent links, UI navigation, and Orbit retain the same
relationships.

The task/spec REST API and seven coordination MCP tools have been retired. Reload or reconnect
cached MCP clients after upgrading to refresh their tool inventory. Capture, recall, search,
projections, ideas, recent sessions, and model-status tools remain. See
[retirement and upgrade notes](board-retirement.md) for storage and local-state boundaries.

## Recall scope and limits

Core lexical recall needs no model or Elasticsearch. Semantic recall covers Decisions, Handoffs,
Observations, Ideas, and Evidence only. Projections can be recalled lexically; session-summary vectors are stored
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

For exact project recall, pass separate `project` and `query` fields in REST or MCP:

```json
{"project":"/repos/example","query":"why did we choose retries","withinHours":168,"kinds":["decision","handoff"]}
```

`project` is the canonical project path; registered aliases are expanded automatically. Both
lexical and semantic candidate retrieval apply this exact logical project filter before ranking.
A similar directory name or a mention in another project's text cannot match it. Captured repo
metadata takes precedence over the session's current working directory. An unknown project
returns no results. Only `query` is embedded, including topics with slashes; an empty query returns
recent intent in the selected project. Omitting `project` permits a global topic query.
Do not combine nonblank legacy `scope`/`repoOrTopic` with `project` or `query`; that ambiguity returns
an error. Blank legacy scope is compatible.

Normal recall excludes Decisions explicitly replaced by a later capture, even when the replacement
is outside the requested topic or time window. Pass `includeSuperseded=true` for history. Replacement
items carry `supersedesEventId`; replaced items carry `supersededByEventId`; either field is omitted
when absent. The exact original event remains available from its source link. See
[Project continuity](project-continuity.md) for the atomic replacement capture contract.

REST calls the field `scope`; MCP calls it `repoOrTopic`. A blank value requests recent intent
across repositories. A bare repo name follows topic rules unless its shape is recognized as a
path or ID. A path match is a lexical anchor, not an authorization boundary or an exact project
filter: matching can include IDs, working directories, repo metadata, and captured text.

The Recall page offers 24 hours, one week, 30 days, **Three months (90 days)**,
**Six months (180 days)**, and **One year (365 days)**. These are rolling windows measured from now against each event's
`observedAt`, not calendar-month boundaries. The backend accepts up to 365 days. The page still
returns up to 10 items; widening the window does not export all matching history.

Run recall after changing its inputs. The project filter and question are independent; existing
scope links retain their legacy matching behavior.
An event ID still obeys the time/kind filters and uses matching rather than bypassing them;
use a result's Browse link to open its exact source event.

| Layer | Defaults and bounds |
| --- | --- |
| Optional SessionStart hook | 720 hours (30 days), 3 Decisions/Handoffs, 4,000-character context block |
| MCP `recallContext` | 168 hours, Decisions/Handoffs, 10 items (maximum 50); `maxChars` defaults to 24,000, minimum 500 |

These are different layers. The MCP clamp bounds item text fields, can trim body/rationale/headline
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

The examples target the default loopback service. Legacy direct capture and recall hooks do not send
an Authorization header. The optional [durable capture outbox](durable-capture.md#explicit-https-delivery)
supports an explicitly selected HTTPS origin and a destination-bound macOS Keychain bearer;
changing `SBA_AGENTIC_URL` alone does not add authentication. See [Authentication](authentication.md) and
[hook environment variables](operations.md#hook-environment-variables).

### Reading a handoff in the web interface

Choose **Read recalled context** to expand the context returned in a Recall result. Structured
handoffs include their context summary; older unstructured events can provide only a first-line
excerpt. **Open full handoff in Browse** links to the exact source event, where **Read full handoff**
expands its context summary or recorded text. Compact headlines, next actions, and open loops stay
visible. The disclosures support keyboard activation and display captured content as plain text.
