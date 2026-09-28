# Idea capture kind

**Status:** built and verified on branch `claude/idea-kind`, stacked on `claude/human-turn-first`; not merged or deployed.
backend is implemented; its deviations from this contract are recorded under Deviations.

**Origin:** during a loose-ends hunt on 2026-09-28, Nathan asked to track "anything like tower
closeout or ideas that the agent has… as another capture to black box as Idea". Agents propose
ideas that nobody answers, and humans drop asides that nobody records. Both vanish into transcripts.
An `Idea` is a first-class structured capture next to `Decision`, `Handoff`, `Observation`, and
`Projection`, so those ideas can be listed, recalled, and resumed.

## Out of scope

- Automatic aside detection from the human-turn stream (the tangent router). It is the next
  candidate slice; this kind is its storage.
- An `Evidence` kind. It is captured as an idea, not built.
- Editing or deleting events. Captures stay append-only: a status change is a new `Idea` event
  with the same `ideaKey`.

## Contract

### Capture

`CaptureIdeaRequest` is a root API record in `recording`. Its fields:

| Field | Type | Rules |
| --- | --- | --- |
| `source` | string, required | Capturing client (`claude`, `codex`, `manual`, …), as for other kinds. |
| `clientSessionId` | string, required | As for other kinds. |
| `repo` | string | Project path, as for other kinds. |
| `title` | string, required | Short name of the idea. |
| `oneLiner` | string, required | One sentence: what it is. |
| `origin` | string, required | One of `human-aside`, `agent-proposed`, or `joint`. The legacy value `nathan-aside` normalizes to `human-aside`. Anything else is a validation error listing the allowed values. |
| `quote` | string | Verbatim words from the person or agent who had the idea. |
| `sourceRef` | string | Where the idea came from: a session id, a `path:line`, or a URL. It is named `sourceRef` so it does not collide with the capture `source`. |
| `legs` | integer 0–10 | How much the idea has going for it. Out of range is a validation error. |
| `status` | string | One of `untouched` (default), `partially-built`, `built-unused`, `superseded`, or `tracked`. |
| `connects` | list of strings | Related threads, ideas, or issue ids. |
| `resumeStep` | string | The smallest useful next step. |
| `link` | string | Optional Linear, Obsidian (`obsidian://…`), or web link. |
| `notes` | string | Optional free-form markdown body (prior art, motivating case). |
| `ideaKey` | string | Stable identity across status changes. It defaults to a slug of `repo` plus `title`. |

- Validation happens at the common `StructuredCaptureService` boundary, as for the other kinds. It
  returns actionable messages through REST (typed error envelope) and MCP.
- **Stored event:** `eventType = "Idea"`, with metadata `kind = "idea"` and every field above.
- **Event text:** a readable multi-line rendering starting with `[Idea] <title> — <oneLiner>`.
  The rendering keeps full-text search and the human stream useful.
- **REST:** `POST /api/ideas` accepts the request and returns the same ingest response as the
  other capture routes.
- **MCP:** a `captureIdea` tool with clear parameter descriptions. It says when to use it: an idea
  someone proposed that is not being acted on now, whether it is the human's aside or an agent's
  suggestion.
- Add the kind to the ChatGPT MCP gateway (`scripts/chatgpt_mcp/gateway.py`) and its tests,
  wherever that gateway lists the capture kinds.

### Read

- `recallContext` and `/api/recall` accept `kinds: ["idea"]`. A recalled idea's title is the
  idea title and its rationale is the one-liner. The default recall kinds do **not** change.
- Ideas join semantic memory embeddings wherever structured intent kinds are embedded, if that
  path embeds by kind. Update the docs honestly either way.
- `GET /api/ideas` lists ideas collapsed to the **latest** event per `ideaKey`. It accepts:
  - `status` (repeatable or comma-separated);
  - `origin`;
  - `project` / `repo` scope, using the existing project scope resolution;
  - `q` (text);
  - `limit` (default 100, max 500).
- The response is `{ items: IdeaView[], count }`, newest first. Each `IdeaView` carries every
  capture field plus:
  - `eventId`, `sessionId`, `capturedAt`, and `firstCapturedAt`;
  - `revisions` (the event count for the key);
  - `migratedFrom` (nullable).

### Migrating `[Idea]` observations

Until this kind shipped, agents captured ideas as `Observation`s whose text starts with `[Idea]`.
The body is bullets: `- origin: <origin>, … Verbatim: "<quote>".`, `- What: …`, `- legs: N`,
`- connects: a, b (x, y), c`, and `- status: <status>. …`.

- `POST /api/ideas/migrate-observations` defaults to **dry run** (`apply=false`). It returns the
  parsed candidate ideas, and for each one the source observation id and any parse warnings.
- `apply=true` captures one new `Idea` per candidate, with `migratedFrom = <observation event id>`,
  `sourceRef` falling back to the observation's session, and `notes` set to the full original body.
  It is idempotent: an observation that already has a migrated idea is skipped.
- The migration never modifies or deletes the observation.
- The parser is best effort:
  - The title is the first line without the prefix.
  - The one-liner is the first sentence of `What:`, or the title.
  - The origin is the first token after `origin:`, normalized.
  - The quote is the text inside `Verbatim: "…"`.
  - Legs is the integer after `legs:`.
  - Status is the first word after `status:`.
  - Connects splits on commas outside parentheses.
  - Unknown or missing values produce warnings, not failures.

### Frontend

- **Types and clients:** `api.ts` gets types and clients for `captureIdea`, `getIdeas`, and the
  migration dry run.
- **Ideas view:** a route `/ideas` with a header nav icon link, following the `UTILITY_LINKS`
  pattern. Each idea row shows:
  - the title and one-liner;
  - an origin badge (`human-aside`, `agent-proposed`, or `joint`);
  - a status pill, and legs as a 0–10 meter or number;
  - the quote (verbatim, pre-wrap);
  - connects chips, the resume step, the link, and relative time;
  - a link to the capturing session.
- **Filters:** chips for status and origin; unknown values are ignored. The default view
  highlights `agent-proposed` + `untouched`, the ideas nobody answered. The filter state lives in
  the URL query.
- **Stream and recall:** `Idea` events render legibly with the title and one-liner, like other
  structured kinds, instead of raw metadata.
- **Command palette:** an entry that opens the Ideas view.

## Verification

- Backend: unit and service tests for validation, origin and status normalization, legs bounds,
  and the text rendering. Also test `/api/ideas` collapsing by `ideaKey`, recall with
  `kinds:["idea"]`, and migration parsing on fixtures shaped like the real observations. The dry
  run must write nothing, `apply` must be idempotent, and the observation must be untouched.
- Also cover MCP tool registration (17 tools), the contract snapshots, and the Python gateway
  tests.
- Frontend: vitest, `npm run check`, and a Playwright spec that seeds an agent-proposed idea and a
  human-aside idea, opens `/ideas`, filters, and sees the right rows.
- Verify through use: run the packaged jar against a fresh consistent copy of the local database
  on a spare port. Dry-run and then apply the migration of the real `[Idea]` observations. Open
  `/ideas`, recall them through MCP, and capture a new idea through MCP.

## Deviations

Backend choices where the contract was silent or needed a concrete rule:

- **Default `ideaKey`:** the slug uses the repo's last path segment, not the full path. Clones
  that share a directory name key identically, but a worktree under a different directory name does
  not; pass an explicit `ideaKey` in that case. Two repos with the same basename share a key
  namespace.
- **Collapsed fields:** the listing takes each optional field from the newest revision that carried
  it, so a status-only re-capture does not blank the quote, connects, legs, or `migratedFrom`.
- **Migration origin:** `origin` is required, so a missing or unknown `origin:` in an `[Idea]`
  observation falls back to `agent-proposed` with a warning.
- **Migration session:** migrated ideas keep the observation's `source` and are written to one
  client session per repo, `idea-migration:<repo>` (plain `idea-migration` when the repo is
  unknown), so the original sessions are not reopened. A session carries a single cwd, and recall,
  project facets, and timelines scope by it, so one shared session would misfile ideas under
  whichever repo was written last.
- **Migration time:** a migrated idea is captured at its observation's time, so a newer native
  capture of the same `ideaKey` stays the latest state and `firstCapturedAt` is when it was said.
- **Full scans:** the list and the migration read every `Idea` event (keyset pages of 1,000), not a
  newest-N window, so migration stays idempotent and revision counts stay whole at any volume.
- **Default key redaction:** the default `ideaKey` is slugged from the redacted title; slugging
  would otherwise hide a secret from the ingest-time redaction patterns.
- **Recall:** besides the title and one-liner, a recalled idea's `nextAction` is its `resumeStep`.
- **List filters:** an unknown `status` or `origin` filter value returns `400 invalid_argument`
  listing the allowed values instead of silently matching nothing. `count` is the number of items
  returned.
- **Gateway:** `append_capture` accepts `kind=idea` but still writes through the generic event
  route, which keeps its idempotency receipts. Such an idea has no structured fields; the Ideas
  list derives its title from the first line, reports status `untouched`, and leaves `origin` null.
- **SPA route:** `GET /ideas` forwards to `index.html` so the Ideas view survives a hard refresh.

## Observed results (2026-09-28)

**Build process:** a multi-agent workflow built the backend and frontend in separate worktrees.
Three lens-distinct reviewers read each side's diff, and two skeptics tried to refute each finding.

- None of the 18 findings were refuted: 11 backend and 7 frontend, some of them duplicates.
- The fixers repaired most of them:
  - Migration had written every repo's ideas into one session.
  - A capped scan broke idempotency.
  - The default key was built from the unredacted title.
  - Migrated ideas carried the wrong timestamp.
  - An overflowing `legs` value aborted the migration.
  - The frontend had empty-state, stale-row, and focus bugs.
- The coordinator fixed three confirmed findings the fixers had dropped:
  - A status-only re-capture blanked optional fields in the listing. Fields now fall back to
    earlier revisions.
  - The contract matrix said `legs` is clamped; it is actually rejected when out of range.
  - The docs claimed the default key is identical on every checkout.

**Tests**
- Full backend suite: 746 tests, 0 failures, 20 environment skips.
- Frontend: 650 vitest tests pass and `npm run check` is clean.
- Playwright: 27 tests pass, including both Ideas specs.
- Gateway: 17 pytest tests pass.

**Real-data run:** the packaged jar ran on a spare port against a fresh consistent backup of the
local database, with the model, embedding, and Elasticsearch paths off.
- The dry run parsed both real `[Idea]` observations with no warnings: `nathan-aside` became
  `human-aside`, and legs 8 and 7, the quotes, and the connects all came through. The dry run
  wrote nothing.
- `apply=true` created two ideas, each in its own repo's migration session. A second apply
  created nothing, and the source observations hashed identically before and after.
- MCP:
  - `tools/list` shows 17 tools.
  - `captureIdea` stored an agent-proposed idea.
  - An invalid origin returned "origin 'hallway' is not allowed; use one of: human-aside,
    agent-proposed, joint."
  - A status-only re-capture moved it to `tracked` and kept its quote, connects, and resume step.
  - `recallContext` with `kinds: ["idea"]` returned repo-scoped ideas, with the resume step as
    `nextAction`.
- Browser:
  - `/ideas` lists the ideas.
  - The origin chip filters and persists in the URL across a reload.
  - A 390-pixel viewport has no horizontal scroll.
  - `kind:Idea` in the stream renders "title — one-liner" rows.

**Limits**
- `recallContext` is event-level, so it returns every revision of an idea. Only `GET /api/ideas`
  collapses by key.
- Ideas written by the ChatGPT gateway have no structured fields yet.
