# Projects and Durable Melds Design

Date: 2026-06-15
Status: Approved design, pending implementation plan

## Summary

Add a Projects workspace to Black Box that lets Nathan inspect cross-agent work by project,
string together outputs from Claude, Codex, manual captures, decisions, handoffs, and tool
results into one project timeline, then select sessions and generate a model-assisted
meld. Melds are previews first. A meld becomes durable only after an explicit save, and
saved melds carry provenance so they remain visibly distinct from raw observed session
events.

The selected approach is "Projects + Durable Melds": a scoped v1 that is useful now and
keeps runway for a later full project-intelligence system.

## Goals

- Add a Projects tab that groups sessions by project and shows cross-session continuity.
- Render a project-level Hybrid Storyline timeline by default, with raw events expandable.
- Let the user select sessions from one project and generate a meld preview.
- Support both direct Claude/Codex model runs and exportable context bundles.
- Save approved melds as durable synthesis artifacts with model, prompt, source session,
  and project provenance.
- Keep SQLite as the source of truth and preserve raw event fidelity.
- Leave room for first-class project records, richer aliasing, async jobs, and deeper
  project intelligence later.

## Non-Goals

- Do not build a full project-management system in the first slice.
- Do not mutate historical `cwd` values or raw captured events.
- Do not automatically stuff whole sessions into model context.
- Do not make unsaved previews recallable or searchable as durable memory.
- Do not claim semantic project intelligence beyond what is implemented and verified.

## Approved Product Decisions

- **Meld durability:** support preview first, then explicit save. Saved melds become durable
  Black Box synthesis artifacts.
- **Model execution:** support both direct Black Box-triggered Claude/Codex runs and export
  bundle mode for active agents.
- **Project identity:** derive projects from normalized `cwd` for v1, with a small
  alias/merge seam for future cleanup.
- **Timeline default:** show a Hybrid Storyline by default: decisions, handoffs, assistant
  outputs, notable tool results, summaries, and saved melds. Raw events remain expandable.
- **Meld input:** use a map-reduce bundle: per-session summaries plus selected high-signal
  evidence such as decisions, handoffs, key tool output, and pinned raw snippets.
- **Scope boundary:** implement Approach 2 now, but preserve clean seams for later Approach 3
  project intelligence.

## Architecture

Projects start as derived groups from `agent_sessions.cwd`, not a full project table.
The server computes a canonical project key from the session path and applies a lightweight
alias layer when needed. This avoids overbuilding while preserving a stable seam for future
first-class projects, git-root detection, worktree grouping, and manual project profiles.

The Projects tab uses server-side project read models:

- project summaries and counts;
- selectable sessions for a project;
- paginated timeline blocks;
- saved melds for that project.

Meld generation is a sibling to the existing session-summary flow, not a replacement.
A meld service owns bundle construction, prompt versioning, preview generation, export
bundle generation, and saved-meld persistence. Direct runs may reuse the existing external
summary backend mechanics where practical, but the meld service owns the meld-specific
prompt and input shape.

Saved melds are stored separately from raw `agent_events`. They can appear in the project
timeline, but the UI labels them as synthesis so they are never confused with observed
agent output.

## UI and Data Flow

The Projects tab has three working areas:

- **Project list:** derived project groups from `cwd`, with counts for sessions, events,
  and saved melds. Alias/merge controls appear only where path cleanup is needed.
- **Hybrid Storyline:** paginated project timeline blocks ordered by observed time. Each
  block can expand to source sessions, raw events, tool JSON, and provenance.
- **Meld tray:** selected sessions, provider/model choice, direct-run vs export-bundle
  mode, preview output, and explicit save action.

Primary flow:

1. User opens Projects and selects a project group.
2. Black Box loads project sessions and a paginated Hybrid Storyline.
3. User selects sessions in the meld tray.
4. Black Box builds a map-reduce bundle from summaries and high-signal evidence.
5. User either generates a direct model preview or exports the same bundle for an agent.
6. User saves the preview only if it is useful.
7. Saved meld appears in the project timeline with provenance and source links.

## Backend and Data Model

Add a minimal project alias layer. A table like this is sufficient for v1:

```sql
project_aliases (
  id TEXT PRIMARY KEY,
  canonical_key TEXT NOT NULL,
  alias_key TEXT NOT NULL,
  created_at TEXT NOT NULL
)
```

Add durable meld storage separately from events:

```sql
session_melds (
  id TEXT PRIMARY KEY,
  project_key TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  execution_mode TEXT NOT NULL,
  saved_from_preview INTEGER NOT NULL,
  metadata_json TEXT,
  created_at TEXT NOT NULL
)

session_meld_inputs (
  meld_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  input_order INTEGER NOT NULL,
  included_summary INTEGER NOT NULL,
  metadata_json TEXT,
  PRIMARY KEY (meld_id, session_id)
)
```

The metadata JSON can store selected event IDs, evidence pointers, bundle character counts,
degradation notes, and source prompt/bundle hashes. The schema should avoid storing full
duplicate raw session transcripts in metadata.

Initial endpoints:

- `GET /api/projects`
- `GET /api/projects/{projectKey}/sessions`
- `GET /api/projects/{projectKey}/timeline`
- `GET /api/projects/{projectKey}/melds`
- `POST /api/melds/preview`
- `POST /api/melds/export-bundle`
- `POST /api/melds`

The exact URL encoding for `projectKey` should be implementation-owned; the design only
requires the API to support paths safely and reversibly.

## Bundle Construction

The meld bundle should be deterministic and bounded:

- include session identity, source, title, `cwd`, started/last-seen timestamps, and summary
  when available;
- include structured decisions and handoffs first;
- include selected assistant outputs and notable tool results;
- include user-pinned raw snippets when present;
- include clear degradation notes when a session has no summary or evidence was clipped;
- include provenance ids for source sessions and selected events.

Raw events are supporting evidence, not the default substrate. The fallback bundle is
summaries plus decisions/handoffs only.

## Failure Handling and Safety

Meld generation fails closed. If selected sessions are missing, summaries cannot be built,
the model command fails, or the bundle exceeds configured limits, Black Box returns a clear
preview error and writes nothing. Failed previews never create saved melds.

Direct Claude/Codex runs require an explicit UI action and should display that selected
transcript text may leave the machine for that vendor. Export bundle mode remains local
until the user gives the bundle to an agent. Both modes record provider, model, execution
mode, and prompt version only when a meld is saved.

Bundle construction should enforce configurable limits:

- maximum selected sessions;
- maximum timeline blocks/events per session;
- maximum raw tool-output characters;
- maximum final bundle characters or tokens;
- maximum preview output size.

Alias/merge is conservative. Aliases affect derived project grouping only. They do not
rewrite historical sessions, raw events, or captured `cwd` values.

## Testing and Rollout

Roll out in four phases:

1. **Read-only Projects tab:** project list, session list, Hybrid Storyline timeline,
   raw event expansion, and project counts.
2. **Meld preview and export bundle:** map-reduce bundle construction, direct preview,
   export bundle, and failure states without persistence.
3. **Durable saved melds:** persistence, provenance, timeline display, and source links.
4. **Alias/merge seam:** minimal alias table and UI only for path cleanup.

Server tests should cover project grouping, alias resolution, timeline pagination and
classification, bundle construction, preview failure modes, and saved meld provenance.

UI tests should cover Projects tab rendering, project selection, timeline block display,
session selection, preview/save states, and saved meld source links. Extend the existing
static UI contract tests rather than replacing them.

Verification before claiming completion:

- targeted repository/service tests for new logic;
- relevant UI/static contract tests;
- `mvn test`;
- `git diff --check`;
- live local proof against the running app or packaged jar when UI behavior changes.

## Implementation Plan Inputs

- V1 meld preview requests are synchronous and bounded by the existing summary timeout
  pattern. The preview response includes a status field so a later job-backed preview can
  reuse the same UI contract without changing saved-meld storage.
- The model selector starts with `provider`, `model`, and `executionMode`. `provider` is
  `claude` or `codex`; `executionMode` is `direct` or `export_bundle`; default model
  values come from configuration or environment variables.
- The first timeline classifier is conservative: structured `Decision` and `Handoff`
  events are first-class blocks; saved melds are first-class synthesis blocks; assistant
  messages and tool results become storyline blocks only when they have displayable text
  or notable output after clipping.
- Saved melds appear in Projects in v1. Recall/ASK integration is deferred until melds can
  be indexed and cited as synthesis without being confused with raw observed events.
