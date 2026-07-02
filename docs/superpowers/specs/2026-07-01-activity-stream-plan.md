# Global Activity Stream — default view for Black Box

## Context

Black Box's UI is session-centric today: `/` lands on the session browser (rail of sessions grouped by project → pick one → read its events). The closest thing to a cross-agent view is the per-project "combined log," scoped to one project and a few event kinds.

The change: invert the default dynamic. `/` becomes a **global activity stream** — a running, newest-first list of events from **any agent, any session**, one compact row per event, updating live — with an Elasticsearch-style filter layer on top (free text + `project:` / `source:` / `kind:` / `tool:` facets). Session browsing stays reachable as a secondary mode. The landing page goes from "pick a session" to "watch the firehose, then narrow."

Backend is Java Spring Boot (`dev.nathan.sbaagentic`, Maven, Java 21), SQLite canonical. Frontend is SolidJS+Vite in `frontend/` (Vitest + Playwright already wired: `npm run test`, `npm run e2e`). Branch: `feat/activity-stream`.

## Reused as-is

- Facet grammar + URL `?q=` state: `frontend/src/lib/query.ts` (`parseQuery`/`serializeQuery`/`setFacet`), SearchPage's chip/autocomplete pattern, `GET /api/search/values` autocomplete.
- Row primitives: `SourceDot`, `KindBadge`, `EventRenderer`/`EventRow` (`frontend/src/components/events/`), `timeAgo`/`truncatePath` (`lib/format.ts`).
- Live wiring: `lib/sse.ts` `createLiveStore()` over `GET /api/stream`; `EventBroadcaster` already fans out every ingested event globally.
- Backend patterns: `EventRepository.searchEvents` dynamic SQL, `QueryFacets.parse`, `ProjectTimelineResponse`-style envelope, existing MockMvc/repo test patterns, `safeEventLimit` clamp.

## Contract (fixes backend/frontend parallelism)

```java
// event/EventFeedItem.java — flat record: all AgentEvent fields + cwd + sessionTitle
record EventFeedItem(id, sessionId, source, clientSessionId, turnId, eventType, role, text,
    toolName, toolInputJson, toolOutputJson, metadata, observedAt, cwd, sessionTitle)
// event/EventFeedResponse.java
record EventFeedResponse(int limit, long count, List<EventFeedItem> items, String nextBefore)
```

`GET /api/events` — params: `q` (optional, facet-aware), `limit` (default 100, `safeEventLimit` clamp), `before` (keyset cursor `"<observedAt-ISO>|<id>"`), `since` (ISO, live head refetch), `meaningful` (boolean, **backend default false** so raw API consumers aren't silently filtered; the UI passes true). Order: `observed_at DESC, id DESC`. Fetch limit+1 to derive `nextBefore` (null on last page). Malformed `before`/`since` → `IllegalArgumentException` → existing 400 pathway (`ApiExceptionHandler` → `invalid_argument`).

SSE: `StreamEvents.EventAppended` gains `id` and `cwd` (appended fields; single construction site `EventBroadcaster.index`).

## Implementation steps

**Wave 1 — backend feed** (independent)
1. `src/main/resources/schema.sql`: add `CREATE INDEX IF NOT EXISTS idx_agent_events_observed ON agent_events (observed_at DESC, id DESC);` — schema.sql reruns every boot (`sql.init.mode: always`), so do **not** duplicate in `EventRepository.ensureSchema()` (that method exists only for the ADD COLUMN case).
2. New records `event/EventFeedItem.java`, `event/EventFeedResponse.java` per contract.
3. `event/EventRepository.java`: add `MEANINGFUL_EVENT_PREDICATE` — **verbatim copy** of `ProjectRepository.STORYLINE_PREDICATE`'s SQL (decision/handoff types or metadata kinds, assistant-role-with-text, tool-bearing, `%tool%`/`%error%`/`%fail%` event types; it already uses the `e.` alias). Deliberate duplication — no cross-package coupling; mirror exactly, don't "complete" it. Add `feed(q, meaningfulOnly, before, since, limit)`: `QueryFacets.parse(q)`; **unconditional** `JOIN agent_sessions s` (intentional deviation from searchEvents' conditional join — feed always needs `s.cwd AS cwd, s.title AS session_title`); copy searchEvents' facet equality clauses + cwd LIKE + free-text OR-group; append meaningful/since/keyset-cursor clauses parameterized; map via existing `mapEvent` + the two aliased columns.
4. `web/AgenticController.java`: `@GetMapping("/events")` → `repository.feed(...)` with `safeEventLimit(limit)`. No new injections.

**Wave 2 — SSE enrichment** (independent of Wave 1)
5. `stream/StreamEvents.java`: append `id`, `cwd` to `EventAppended`.
6. `stream/EventBroadcaster.java`: pass `event.id()`, `session.cwd()` at the single call site.

**Wave 3 — backend tests** (after 1+2)
7. New `event/EventFeedTest.java` (model: `EventSearchFacetTest`): seed two sessions/two cwds; assert default ordering, each facet, `meaningful=true` keeps Decision / drops noise, keyset pagination (page 2 via `nextBefore`, no overlap, null on last page), `since`, malformed cursor throws.
8. `web/AgenticControllerTest.java`: envelope shape, `q=` filter, `meaningful` narrows, malformed `before` → 400 `invalid_argument`.
9. `stream/EventStreamTest.java`: existing push test also asserts `"cwd"` and `"id"` in the SSE frame.

**Wave 4 — frontend data layer** (parallel with 1–3; contract-driven)
10. `frontend/src/lib/api.ts`: `type EventFeedItem = AgentEvent & { cwd?: string|null; sessionTitle?: string|null }` (intersection, avoids drift), `EventFeedResponse`, `getEventFeed(params)` building URLSearchParams.
11. `frontend/src/lib/sse.ts`: `EventAppended` type gains `id: string; cwd?: string|null`.

**Wave 5 — row component** (after 4)
12. New `frontend/src/components/events/StreamRow.tsx` — props `{item, expanded, onToggle}`. Collapsed: SourceDot · KindBadge · project label (`truncatePath(item.cwd)`) · headline (export/reuse `EventRow.tsx`'s headline logic — export a shared helper, don't copy) · `timeAgo` · links to `/sessions/<id>`. Expanded: inline `<EventRenderer event={item}/>` (item is a structural superset of AgentEvent via the intersection type). New `.stream-row` CSS class — do **not** reuse `.live-row`'s 3-track grid for a 5-field row.

**Wave 6 — StreamPage + routing** (after 5; the subtle-risk wave)
13. New `frontend/src/pages/StreamPage.tsx`: URL `?q=` source of truth (SearchPage pattern); facet chips + autocomplete adapted from SearchPage (extract a shared `<FacetBar>` if clean, nice-to-have not required); `meaningfulOnly` signal default **true**; items held in a local signal (not the raw resource) so load-more appends and live-refetch prepends cleanly; "Load more" via `nextBefore`, rolling cap ~500 rows; live: watch `useLiveStore()` events → debounce ~500ms → `getEventFeed({since: newest observedAt, q, meaningful})` → dedupe by id → prepend, or increment a "N new" pill when scrolled down (scrollTop heuristic is fine); apply global `sourceFilter.matches` as client-side post-filter (consistent with sessions resource; don't thread into server query).
14. `frontend/src/pages/ActivityPage.tsx`: add `stream` mode; `modeFromParams` fallback becomes `stream`; `browse` reachable via `?view=browse`; three-way `Switch`/`Match`. **Critical fix**: `openSearchResult` currently sets `view: undefined` and relied on the old browse fallback — it must now set `view: "browse"` explicitly or clicking a search result lands on Stream instead of the session reader. Update `pages/__tests__/ActivityPage.test.tsx`: default-tab assertion becomes Stream; post-click assertion becomes `params.view === "browse"`.
15. `frontend/src/App.tsx`: relabel `/` link "Stream", add "Browse" → `/?view=browse`; `/` and `/?view=browse` share a pathname so router `<A>` active-matching collides — compute active state manually from `useSearchParams`. Add a `browse` icon to `UtilityIcon`. (`index.tsx` needs **no change** — routing table untouched.)

**Wave 7 — styling** (last, single-file)
16. `frontend/src/theme.css`: `.stream-row`, `.stream-row-expanded`, `.stream-filter-bar`, `.stream-load-more`, `.stream-new-pill` on existing tokens; model on `.live-row`/`.result-row` padding/hover; kind-accent inset border like `.combined-log-entry` for expanded state.

**Wave 8 — frontend tests**
17. New `pages/__tests__/StreamPage.test.tsx` (mock `getEventFeed` + `useLiveStore` like `SearchPage.test.tsx` mocks search): rows render, facet chip narrows `?q=`, meaningful toggle refetches, Load more appends via `nextBefore`.

## Execution routing

Per standing instruction (don't burn top-tier tokens on well-specified implementation): execute via **codex-workflow-hybrid** — Codex (gpt-5.5) implements from this plan (Waves 1–3 and 4–8 as two parallel tracks; the contract section pins the seam), Claude adversarially verifies (cross-model review) and owns git. Commits show Nathan as sole author; no Claude attribution.

## Verification

```bash
mvn test                                   # includes new EventFeedTest + updated controller/SSE tests
cd frontend && npm run build               # tsc --noEmit && vite build — hard gate
npm run test                               # vitest — extend, don't skip
```

**Verify through use (required)**: `./scripts/demo.sh` to start + seed, then a Playwright pass (`npm run e2e` harness already exists) driving the real UI: load `/` → stream rows render newest-first; type `source:claude` → rows narrow; click a row → inline expansion; POST a new event via REST → appears live (or "N new" pill); click through to owning session; Browse tab still reaches the session reader. Plus curl smoke: `curl 'localhost:8766/api/events?limit=5'`, `curl 'localhost:8766/api/events?meaningful=true&q=source:codex'`, `curl -N localhost:8766/api/stream` (frame carries `id`+`cwd`).

## Deferred (explicitly out of scope this phase)

- Facets-with-counts endpoint (autocomplete via `/api/search/values` suffices for now).
- Virtualization (rolling 500-row cap instead; revisit only if it hurts).
- Canonical project keys on events (project facet = cwd LIKE, matching existing search semantics).
