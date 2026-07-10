# Activity Project Context Design

Date: 2026-07-08
Status: Implemented on `feat/agent-task-queue`; Ask project scoping remains a follow-up
Owner: Nathan

## Summary

Make the Black Box Activity workspace project-aware without replacing the global event
firehose. Activity should still open to Stream as the default view, but it should gain a
shared project context picker above Stream, Browse, Find, and Ask. Selecting a project narrows
Stream, Browse, and Find together, writes the selected project into the URL, and is remembered
for the next visit. Ask should either inherit that project context when the backend can support
it safely, or clearly state that the project filter is not applied to Ask yet.

This direction keeps the event firehose as the primary working surface while removing the need
to repeatedly type long paths or fight collapsible project groups in the Browse rail.

## Current Context

Activity is already a combined workspace with Stream, Browse, Find, and Ask modes. Stream and
Find both use facet query inputs, and Browse currently groups sessions by project in a
collapsible left rail. Project summaries already exist on the backend through `GET /api/projects`,
with stable `projectKey`, `canonicalKey`, display `label`, counts, and timestamps.

The current rough spots are:

- Project selection is implicit and path-heavy. Users must type or know enough of a `cwd` path
  to filter effectively.
- Browse uses project grouping inside the left rail, which creates collapse/expand friction.
- Stream, Browse, and Find do not share one obvious project context.
- Faceted queries support positive facets, but not negative filters such as
  `NOT kind:PostToolUse`.
- Find result clicks can feel disorienting because the destination session/event is not clearly
  called out after navigation.

## Goals

- Add a shared Activity-level project picker that scopes Stream, Browse, and Find.
- Keep Stream as the default firehose, with an explicit "All projects" state.
- Let users autocomplete projects by short project name while still showing full path context.
- Make Browse show a flat session rail for the selected project instead of collapsible project
  groups.
- Add negative facet grammar and UI chips for excluding event kinds, tools, sources, or projects.
- Make Find result navigation visibly land on the matched event.
- Keep changes inside existing Spring Boot, SQLite, SolidJS, Vite, and CSS patterns.

## Non-Goals

- Do not revive or redesign the parked Projects workspace in this slice.
- Do not add project alias editing, project merge controls, or first-class project management.
- Do not rewrite historical `cwd` values or raw captured events.
- Do not change MCP contracts unless implementation proves a small read-only addition is needed.
- Do not claim semantic/vector search improvements as part of this work.

## Approved UX Decisions

### Activity-Level Project Picker

Place a shared project picker in the Activity header/top bar, above the mode content. It is the
single project context for Stream, Browse, and Find. Ask can participate only when implementation
proves the backend can apply the same scope safely.

The picker has an explicit "All projects" option. When a project is selected:

- Stream shows events only for that project.
- Browse shows sessions only for that project.
- Find searches within that project unless the project filter is cleared.
- Ask inherits the project context when asking across recorded memory, if the current backend Ask
  path can support that safely. If not, Ask should clearly show that the project context is not
  applied yet.

### Project Autocomplete

Autocomplete should lead with a short project name and show the full path as secondary text.
Example:

```text
sba-agentic
/Users/nathan/Developer/proj/sba-agentic
```

Fuzzy matching should search both the short name and full canonical path. The picker should rank
recent projects first, then match quality. Project counts and last activity can be shown as quiet
metadata when space allows.

The first slice uses existing derived project identity from canonical `cwd` values. User-defined
aliases and merge controls remain future work.

### Persistence

Project context should be both shareable and sticky:

- URL state carries the selected project, preferably as `project=<projectKey>`.
- Black Box remembers the last selected project locally and restores it on a fresh Activity visit.
- If the URL includes an explicit project, URL state wins over remembered state.
- If a remembered project no longer exists, Activity falls back to "All projects".

### Browse Rail

Once project context is selected globally, Browse should stop using collapsible project groups as
its primary rail. The Browse rail becomes a flat list of sessions scoped to the selected project.

The rail supports local filtering across session title, source, client session id, and path text.
It should still show enough context per row to distinguish sessions:

- source dot or label;
- session title;
- event count;
- last-seen time.

When Activity is in "All projects", Browse can either show a recent flat session list across all
projects or ask the user to choose a project. The preferred first pass is a recent flat session
list, because it preserves the current ability to browse without first choosing a project.

### Find Result Orientation

Clicking a Find result should:

1. Switch Activity to Browse.
2. Select the owning session in the flat rail.
3. Scroll the matching event into view.
4. Briefly flash/highlight the matched event with a visible target state.

Do not add a persistent "opened from Find" banner in the first pass. The highlight should be enough
to explain the landing point without adding another chrome element.

The URL should carry enough state to reopen the same destination if feasible:

```text
/?view=browse&project=<projectKey>&session=<sessionId>&event=<eventId>
```

If direct event scroll is not immediately available because only part of the session is loaded, the
UI should load or search enough surrounding events to place the target rather than silently opening
the top of the session.

### Negative Facets

The query grammar should accept both readable and terse negative forms:

```text
NOT kind:PostToolUse
-kind:PostToolUse
```

The UI should render both as an exclude chip:

```text
kind != PostToolUse
```

Positive and negative facets can coexist across fields:

```text
source:codex NOT kind:PostToolUse project:sba-agentic
```

For a single field, exact behavior should be deterministic:

- Positive facets include matching values.
- Negative facets exclude matching values.
- If the same field/value is both included and excluded, exclusion wins and the UI should avoid
  producing that contradictory state where possible.

The implementation should preserve existing positive facet behavior and free-text behavior.

## Data And API Shape

### Project Picker Source

Use the existing project summary API as the primary source:

```http
GET /api/projects
```

The picker can derive display fields from:

- `projectKey`: URL-safe project identity;
- `canonicalKey`: canonical path-like project identity;
- `label`: existing path label;
- `sessionCount`, `eventCount`, `lastSeenAt`: ranking and metadata.

If the existing `label` is path-like, the frontend can derive a short display name from
`canonicalKey` for the picker while keeping `label` or `canonicalKey` as secondary text.

### Event And Search Filtering

Activity project context should flow into existing query/filter paths instead of creating a
separate hidden filter model. The implementation can choose either:

- append a project facet into the submitted query sent to Stream and Find; or
- pass a separate project key/query parameter to new/updated endpoints.

The preferred first pass is to keep URL project state separate from the visible query text, then
translate it at the data boundary. That keeps the search input focused on the user's own terms
while still making the active project visible in the Activity picker.

Backend filtering must remain parameterized. Do not interpolate facet values into SQL.

## Frontend Architecture

Add a small Activity project context state owned by `ActivityPage`:

- selected project key;
- selected project summary;
- "All projects" state;
- local remembered project persistence;
- URL sync.

Pass that context into:

- `StreamPage`;
- `SessionsPage`;
- `SearchPage`;
- `AskPanel` or the Search/Ask wrapper, if project-scoped Ask is supported.

Extract or share project autocomplete UI only if it removes real duplication. Otherwise, keep the
first slice narrow and local to Activity.

The project picker should use existing visual language:

- compact data-dense controls;
- existing dark theme tokens;
- restrained borders and 6-8px radius;
- no decorative redesign or marketing-style layout.

## Backend Architecture

Extend `QueryFacets` to parse negative facets while preserving current positive facet behavior.
A likely shape is:

- positive fields: source, kind/event type, tool, project/cwd;
- negative fields: source, kind/event type, tool, project/cwd;
- free text terms unchanged.

Update SQLite fallback filtering in both event search and feed paths to apply negative filters.
Search and feed should share semantics so Stream and Find do not disagree.

If implementation adds project-key filtering by encoded `projectKey`, decode through the existing
project codec path rather than matching encoded values against raw `cwd`.

## Error And Empty States

- If project summaries fail to load, Activity should still work in "All projects" mode and show a
  quiet retry/error affordance in the picker.
- If a selected project has no stream events, show an empty stream state that names the project and
  offers to clear the project filter.
- If a selected project has no sessions, Browse should show the same clear-project affordance.
- If a Find result target event cannot be loaded, open the owning session and show a non-blocking
  message that the target event could not be positioned.
- Invalid negative query syntax should fail closed into normal text tokens where possible rather
  than breaking the whole search page.

## Testing

Targeted backend tests:

- `QueryFacets` parses `NOT kind:PostToolUse` and `-kind:PostToolUse`.
- Negative facets apply in `searchEvents`.
- Negative facets apply in `feed`.
- Positive and negative facets together produce deterministic results.
- Existing positive-only and free-text tests continue passing.

Targeted frontend tests:

- Activity project picker renders project summaries and supports fuzzy selection.
- Project selection updates URL state and is restored from remembered state when no URL project is
  present.
- Stream receives project context and narrows event feed requests/results.
- Browse shows a flat project-scoped session rail.
- Find result click selects Browse, selects the owning session, and marks the target event for
  highlight.
- Negative query syntax and exclude chips round-trip through the shared query parser.

Use-level verification:

- Start the app against seeded or live local data.
- Select `sba-agentic` from project autocomplete by short name.
- Confirm Stream, Browse, and Find all reflect the same project.
- Search with `NOT kind:PostToolUse` and confirm excluded rows disappear.
- Click a Find result and confirm Browse opens at the right session and flashes the matched event.
- Reload the page and confirm URL/remembered project behavior.

Required closeout checks:

```bash
mvn test
cd frontend && npm run test
cd frontend && npm run build
git diff --check
```

## Rollout Plan

1. Query grammar and backend filtering: parse negative facets and apply them consistently to search
   and feed.
2. Shared project context: load project summaries, add Activity-level picker, URL sync, and local
   remembered state.
3. Stream and Find project scoping: thread selected project context through data loads.
4. Browse rail simplification: render flat project-scoped sessions when Activity project context is
   active.
5. Find target orientation: carry event id through selection, scroll to it, and flash/highlight it.
6. Tests and live proof against the running local app.

## Open Questions

- Whether Ask can safely and accurately apply project context in the first implementation slice, or
  should show a clear "project context not applied to Ask yet" state.
- Whether the event target scroll requires a new targeted session-event endpoint, or can be handled
  with existing event/session reads plus client-side positioning.
- Whether project autocomplete should expose counts in the first slice or keep the menu text-only
  until the interaction is proven.
