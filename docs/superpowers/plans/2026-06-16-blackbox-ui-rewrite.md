# Black Box UI Rewrite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Black Box's vanilla-JS UI with an agent-observatory-style SolidJS app focused on searching/finding sessions, backed by a new SSE live channel and a facet-aware search path.

**Architecture:** A new `frontend/` SolidJS+Vite+TS app builds (via `frontend-maven-plugin`) into `src/main/resources/static/`, so `mvn package` still ships one self-contained jar. The Spring Boot backend gains an SSE stream (`/api/stream`) fed by an `EventBroadcaster` hooked into ingest, an SPA-forwarding rule for deep links, and a facet-aware SQLite search so `field:value` filtering works without the optional Elasticsearch index.

**Tech Stack:** Java 21 / Spring Boot 3.5.14 (backend, existing); SolidJS ^1.9, @solidjs/router ^0.16, Vite ^8, TypeScript ^6, @tanstack/solid-virtual (virtualization), Vitest + @solidjs/testing-library (unit), Playwright (e2e); plain CSS with custom properties; IBM Plex (self-hosted, existing).

## Global Constraints

- Runtime artifact stays a **single self-contained jar**; node is **build-time only**. `frontend/node_modules/` and `frontend/dist/` are gitignored.
- Built UI output lives at `src/main/resources/static/` (Vite `outDir`), replacing today's `index.html`/`app.js`/`querybar.js`/`graph.js`/`styles.css`.
- **Localhost-only**, read-only UI. No schema changes to `agent_sessions`/`agent_events`. No changes to MCP tool contracts.
- Backend base path is `/api` (see `AgenticController` `@RequestMapping("/api")`). Default server `127.0.0.1:8766`.
- Brand: keep the `BLACKBOX` masthead and **IBM Plex Sans/Mono** (already at `static/fonts/`). Adopt observatory's color tokens (§Theme in the spec).
- Commits: author is Nathan only — **no Claude/AI attribution** in any commit, trailer, or PR.
- Scope is **core-first**: Overview (search-first) · Sessions · Search · ⌘K · SSE. Recall/Projects/stats/graph are phase 2 — do not build them here.
- Spec: `docs/superpowers/specs/2026-06-16-blackbox-ui-rewrite-design.md` (read it; this plan implements it).

---

## File Structure

**Backend (Java):**
- Create `src/main/java/dev/nathan/sbaagentic/stream/EventBroadcaster.java` — holds `SseEmitter`s, publish methods.
- Create `src/main/java/dev/nathan/sbaagentic/stream/StreamController.java` — `GET /api/stream`.
- Create `src/main/java/dev/nathan/sbaagentic/stream/StreamEvents.java` — payload records.
- Modify `src/main/java/dev/nathan/sbaagentic/event/EventIngestService.java` — publish after persist.
- Create `src/main/java/dev/nathan/sbaagentic/web/SpaForwardingController.java` — deep-link fallback.
- Modify `src/main/java/dev/nathan/sbaagentic/event/EventRepository.java` — facet-aware `searchEvents`.
- Create `src/main/java/dev/nathan/sbaagentic/search/QueryFacets.java` — parse `field:value` tokens.
- Modify `pom.xml` — add `frontend-maven-plugin`.
- Tests: `src/test/java/dev/nathan/sbaagentic/stream/EventStreamTest.java`, `.../search/QueryFacetsTest.java`, `.../web/SpaForwardingTest.java`; update `.../web/StaticUiContractTest.java`.

**Frontend (`frontend/`):**
- `package.json`, `vite.config.ts`, `tsconfig.json`, `vitest.config.ts`, `playwright.config.ts`, `index.html`
- `src/index.tsx` (entry + router), `src/App.tsx` (AppShell), `src/theme.css`
- `src/lib/`: `format.ts`, `query.ts`, `api.ts`, `sse.ts`, `stores.ts`
- `src/components/`: `SourceChips.tsx`, `SourceDot.tsx`, `KindBadge.tsx`, `CommandPalette.tsx`
- `src/components/events/`: `DecisionCard.tsx`, `HandoffCard.tsx`, `ObservationCard.tsx`, `EventRow.tsx`
- `src/pages/`: `OverviewPage.tsx`, `SessionsPage.tsx`, `SearchPage.tsx`
- Tests co-located `*.test.ts(x)`; e2e in `tests/e2e/smoke.spec.ts`.

---

## PHASE A — Backend (build + cross-model verify in this session)

### Task 1: SSE stream + broadcaster + ingest hook

**Files:**
- Create: `src/main/java/dev/nathan/sbaagentic/stream/StreamEvents.java`
- Create: `src/main/java/dev/nathan/sbaagentic/stream/EventBroadcaster.java`
- Create: `src/main/java/dev/nathan/sbaagentic/stream/StreamController.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/event/EventIngestService.java`
- Test: `src/test/java/dev/nathan/sbaagentic/stream/EventStreamTest.java`

**Interfaces:**
- Produces: `EventBroadcaster.publishEventAppended(AgentEvent, AgentSession)`, `publishSessionUpdated(AgentSession)`; `GET /api/stream` emitting named SSE events `event.appended`, `session.updated`.
- Consumes: existing `AgentEvent` (`event/AgentEvent.java`), `AgentSession` (`session/AgentSession.java`), and the persist call inside `EventIngestService`.

- [ ] **Step 1: Failing test** — `EventStreamTest` (Spring Boot `@SpringBootTest(webEnvironment=RANDOM_PORT)`): subscribe to `/api/stream` via `WebClient`/`RestClient` returning `text/event-stream`, ingest one event via the existing ingest path, assert an `event.appended` SSE frame arrives within 2s carrying the session id. Expect FAIL (no endpoint).

- [ ] **Step 2: `StreamEvents.java`** — payload records:
```java
package dev.nathan.sbaagentic.stream;
public final class StreamEvents {
    public record EventAppended(String sessionId, String source, String eventType,
            String toolName, String title, String observedAt) {}
    public record SessionUpdated(String sessionId, String source, String title,
            String cwd, long eventCount, String lastSeenAt) {}
    private StreamEvents() {}
}
```

- [ ] **Step 3: `EventBroadcaster.java`** — thread-safe emitter registry:
```java
package dev.nathan.sbaagentic.stream;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class EventBroadcaster {
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }
    public void publishEventAppended(StreamEvents.EventAppended payload) { send("event.appended", payload); }
    public void publishSessionUpdated(StreamEvents.SessionUpdated payload) { send("session.updated", payload); }

    private void send(String name, Object payload) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter); // client gone; native EventSource reconnects
            }
        }
    }
}
```

- [ ] **Step 4: `StreamController.java`**:
```java
package dev.nathan.sbaagentic.stream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class StreamController {
    private final EventBroadcaster broadcaster;
    public StreamController(EventBroadcaster broadcaster) { this.broadcaster = broadcaster; }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() { return broadcaster.register(); }
}
```

- [ ] **Step 5: Hook ingest** — in `EventIngestService`, inject `EventBroadcaster` (constructor) and, **after** the event+session are persisted, build `StreamEvents.EventAppended` / `SessionUpdated` from the just-saved `AgentEvent`/`AgentSession` and call the publish methods. Keep the publish in a try/catch that never breaks ingest. Match the existing constructor-injection pattern in that file.

- [ ] **Step 6: Run** `mvn -q -Dtest=EventStreamTest test` → PASS.

- [ ] **Step 7: Commit** `feat: stream new events over SSE at /api/stream`.

---

### Task 2: SPA deep-link fallback

**Files:**
- Create: `src/main/java/dev/nathan/sbaagentic/web/SpaForwardingController.java`
- Test: `src/test/java/dev/nathan/sbaagentic/web/SpaForwardingTest.java`

**Interfaces:** Produces: GET of a non-`/api`, non-asset, non-file path returns `static/index.html` (forward), so `/sessions/:id` and `/search` resolve on hard refresh.

- [ ] **Step 1: Failing test** — `@WebMvcTest`/`@SpringBootTest` + `MockMvc`: `GET /sessions/abc` returns 200 and HTML containing `BLACKBOX`. Expect FAIL (404 today).

- [ ] **Step 2: Implement** — forward client routes to `index.html`, but never intercept `/api/**` or paths with a file extension:
```java
package dev.nathan.sbaagentic.web;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardingController {
    // Matches single-segment and nested client routes, excludes anything containing a dot (assets).
    @GetMapping(value = {"/sessions/**", "/search", "/overview"})
    public String forward() { return "forward:/index.html"; }
}
```
> Note: keep the route list explicit (Overview `/`, Sessions, Search) rather than a catch-all, so `/api/**` and static assets are never shadowed. Add phase-2 routes here when those views land.

- [ ] **Step 3: Run** `mvn -q -Dtest=SpaForwardingTest test` → PASS.

- [ ] **Step 4: Commit** `feat: forward SPA client routes to index.html`.

---

### Task 3: Facet-aware SQLite search

**Files:**
- Create: `src/main/java/dev/nathan/sbaagentic/search/QueryFacets.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/event/EventRepository.java` (`searchEvents`)
- Test: `src/test/java/dev/nathan/sbaagentic/search/QueryFacetsTest.java`

**Interfaces:**
- Produces: `QueryFacets.parse(String query) -> QueryFacets` exposing `source()`, `eventType()`, `toolName()`, `cwd()`, `freeText()` (List<String> for unrecognized terms). `EventRepository.searchEvents` builds a parameterized WHERE: equality on present keyword facets (mapped from UI aliases) AND `LIKE %term%` across text columns for each free-text term, newest first.
- Consumes: existing `searchEvents(String, int)` signature (callers in `SearchService` unchanged).

UI→column alias map (apply in `QueryFacets`): `source`→`source`, `kind`/`event_type`→`event_type`, `tool`/`tool_name`→`tool_name`, `project`/`cwd`→ session `cwd` (join), `agent`→`source`.

- [ ] **Step 1: Failing tests** — `QueryFacetsTest`:
```java
@Test void parsesFacetsAndFreeText() {
    QueryFacets f = QueryFacets.parse("source:codex kind:Decision rebase main");
    assertEquals("codex", f.source());
    assertEquals("Decision", f.eventType());
    assertEquals(List.of("rebase", "main"), f.freeText());
}
@Test void quotedFreeTextStaysTogether() {
    QueryFacets f = QueryFacets.parse("tool:Edit \"ask history\"");
    assertEquals("Edit", f.toolName());
    assertEquals(List.of("ask history"), f.freeText());
}
@Test void plainQueryIsAllFreeText() {
    assertEquals(List.of("recall bug"), QueryFacets.parse("recall bug").freeText().stream()
        .reduce((a,b)->a+" "+b).map(List::of).orElse(List.of()));
}
```

- [ ] **Step 2: Implement `QueryFacets`** — tokenizer: split on whitespace respecting double-quotes; a token matching `^(source|agent|kind|event_type|tool|tool_name|project|cwd):(.+)$` sets the mapped facet (last wins); everything else is free text (quotes stripped). Pure, no DB.

- [ ] **Step 3: Run** `mvn -q -Dtest=QueryFacetsTest test` → PASS.

- [ ] **Step 4: Repository test** — add a test in the existing `EventRepository` test (or a new `EventRepositorySearchTest`) seeding two events (one `source=codex,event_type=Decision`, one `source=claude,event_type=PostToolUse`) and asserting `searchEvents("source:codex", 10)` returns only the codex row.

- [ ] **Step 5: Implement `searchEvents`** — parse with `QueryFacets`; build `WHERE` dynamically: `AND e.source = ?` etc. for present facets, `AND (e.text LIKE ? OR e.tool_name LIKE ? OR e.event_type LIKE ?)` per free-text term; `cwd`/`project` facet joins `agent_sessions s ON s.id = e.session_id AND s.cwd LIKE ?`. Keep the existing column-LIKE behavior as the free-text branch. Order `observed_at DESC LIMIT ?`. Use parameterized JDBC (no string interpolation of values).

- [ ] **Step 6: Run** the repository test → PASS; run full `mvn -q test` to confirm no regressions in `SearchService`/controller tests.

- [ ] **Step 7: Commit** `feat: facet-aware SQLite search (field:value without Elasticsearch)`.

---

## PHASE B — Frontend scaffold & libs

### Task 4: Scaffold `frontend/` (Solid + Vite + TS) + theme

**Files:** Create `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`, `frontend/src/index.tsx`, `frontend/src/App.tsx` (placeholder), `frontend/src/theme.css`; modify root `.gitignore`.

**Interfaces:** Produces a runnable dev app (`npm run dev` on 5173) proxying `/api` to `127.0.0.1:8766`, and `npm run build` emitting to `../src/main/resources/static`.

- [ ] **Step 1:** `package.json` deps: `solid-js`, `@solidjs/router`, `@tanstack/solid-virtual`; dev: `vite`, `vite-plugin-solid`, `typescript`, `vitest`, `@solidjs/testing-library`, `@testing-library/jest-dom`, `jsdom`, `@playwright/test`. Scripts: `dev`, `build`, `preview`, `test` (vitest run), `e2e` (playwright).

- [ ] **Step 2:** `vite.config.ts`:
```ts
import { defineConfig } from "vite";
import solid from "vite-plugin-solid";
export default defineConfig({
  plugins: [solid()],
  server: { port: 5173, proxy: {
    "/api": { target: "http://127.0.0.1:8766", changeOrigin: true },
  }},
  build: { outDir: "../src/main/resources/static", emptyOutDir: true },
});
```
> `/api/stream` proxies through `/api`. `EventSource` works over the proxy without special config.

- [ ] **Step 3:** `theme.css` — copy observatory's token block (spec §3.4) into `:root`, add `@font-face` for the existing IBM Plex woff2 files (served from `/fonts/...`), set body bg/text/font. Add per-source color custom properties.

- [ ] **Step 4:** `index.html` referencing `/src/index.tsx`; `index.tsx` mounts `<App/>` inside `<Router>`; `App.tsx` placeholder renders the `BLACKBOX` header + an `<Outlet/>`.

- [ ] **Step 5:** `.gitignore` — add `frontend/node_modules/`, `frontend/dist/`, `frontend/test-results/`, `frontend/playwright-report/`. (Built assets under `src/main/resources/static/` are produced by the Maven build; keep them out of manual edits.)

- [ ] **Step 6:** Run `cd frontend && npm install && npm run build` → build succeeds, emits `index.html`+assets into `src/main/resources/static/`.

- [ ] **Step 7:** Commit `chore: scaffold SolidJS frontend (vite + theme)`.

---

### Task 5: `lib/format.ts`

**Files:** Create `frontend/src/lib/format.ts`, `frontend/src/lib/format.test.ts`.

**Interfaces:** Produces `timeAgo(iso: string, now?: number): string`, `truncatePath(cwd: string): string` (`/Users/<u>/...`→`~/...`), `sourceColor(source: string): string` (maps to the per-source CSS var/hex; unknown→neutral), `sourceLabel(source): string`.

- [ ] **Step 1:** Tests — `timeAgo` for 30s→"30s", 3h→"3h", 2d→"2d"; `truncatePath('/Users/nathan/Developer/proj/x')` → `~/Developer/proj/x`; `sourceColor('codex')` non-empty and `sourceColor('???')` returns the neutral value.
- [ ] **Step 2:** Implement; pure functions, no DOM.
- [ ] **Step 3:** Run `npm run test -- format` → PASS.
- [ ] **Step 4:** Commit `feat: formatting helpers (time-ago, path, source color)`.

---

### Task 6: `lib/query.ts` (faceted query, client mirror of backend)

**Files:** Create `frontend/src/lib/query.ts`, `frontend/src/lib/query.test.ts`.

**Interfaces:** Produces `parseQuery(q: string): { facets: Record<string,string>, text: string[] }` and `serializeQuery(state): string`, plus `FACET_FIELDS: {key, label, enumerable}[]` mirroring the backend alias map (`source`, `kind`, `tool`, `project`, free text). Used by `SearchPage` to render facet chips and by the autocomplete to know which fields are enumerable.

- [ ] **Step 1:** Tests — `parseQuery('source:codex kind:Decision rebase')` → `{facets:{source:'codex',kind:'Decision'}, text:['rebase']}`; round-trip `serializeQuery(parseQuery(x)) === normalized(x)`.
- [ ] **Step 2:** Implement (same tokenizing rules as `QueryFacets`: quotes, last-wins).
- [ ] **Step 3:** Run `npm run test -- query` → PASS.
- [ ] **Step 4:** Commit `feat: client faceted-query parse/serialize`.

---

### Task 7: `lib/api.ts` (typed REST client)

**Files:** Create `frontend/src/lib/api.ts`.

**Interfaces:** Produces TS types mirroring the Java records and typed fetchers. Types: `AgentSession {id, source, clientSessionId, title, cwd?, summary?, startedAt, lastSeenAt, eventCount}`; `AgentEvent {id, sessionId, source, turnId?, eventType, role?, text?, toolName?, toolInputJson?, toolOutputJson?, metadata?, observedAt}`; `SearchResponse {query, local: AgentEvent[], elastic: AgentEvent[], elasticHealth}`; `FieldInfo {name, type, enumerable}`; `AskStatus`, `AskResponse`. Fetchers: `getSessions(limit)`, `getSessionEvents(id, limit)`, `search(q, limit)`, `searchFields()`, `searchValues(field, prefix, limit)`, `askStatus()`, `ask(question)`, `getStatus()`. All return typed promises; throw on non-2xx with a readable message.

- [ ] **Step 1:** Implement against the real endpoints (§6 of the spec). Base URL `/api`. No test file (thin wrapper; covered by e2e), but keep functions one-liners over a shared `getJson`/`postJson`.
- [ ] **Step 2:** Commit `feat: typed api client`.

---

### Task 8: `lib/sse.ts` (live store)

**Files:** Create `frontend/src/lib/sse.ts`.

**Interfaces:** Produces `createLiveStore()` returning Solid signals: `status: () => 'connecting'|'live'|'down'`, `events: () => EventAppended[]` (ring buffer, cap 50, newest first), and an `onSessionUpdated(cb)` register. Opens one `EventSource('/api/stream')`; native auto-reconnect flips status; `addEventListener('event.appended'|'session.updated')` parse JSON into the signals.

- [ ] **Step 1:** Implement with `createSignal`; cap the events array on push; clean up on `onCleanup`.
- [ ] **Step 2:** Commit `feat: SSE live store with ring buffer + status`.

---

### Task 9: `lib/stores.ts` (filter + resources)

**Files:** Create `frontend/src/lib/stores.ts`.

**Interfaces:** Produces a module-level `sourceFilter` (signal of `Set<string>` + `toggle`, `clear`, `isActive`), `createSessionsResource()` (Solid `createResource` over `getSessions`, re-fetch on filter change, client-side source filtering), and `createSearchResource(querySignal)`.

- [ ] **Step 1:** Implement; keep `sourceFilter` a singleton so the header chips and every page share it.
- [ ] **Step 2:** Commit `feat: shared source filter + session/search resources`.

---

## PHASE C — Shell & views

### Task 10: AppShell, router, header chips, live dot

**Files:** Modify `frontend/src/App.tsx`; create `frontend/src/components/SourceChips.tsx`, `SourceDot.tsx`, `KindBadge.tsx`; wire routes in `index.tsx`.

**Interfaces:** Routes: `/` → Overview, `/sessions/:id?` → Sessions, `/search` → Search. Header renders nav pills (active state from router), `<SourceChips/>` (driven by `sourceFilter`), a live dot bound to `createLiveStore().status`, and a `⌘K` affordance that toggles the palette (Task 15).

- [ ] **Step 1:** Build `App.tsx` shell (48px header, masthead, nav pills, right-side chips+dot+⌘K). `SourceDot` = an 8px circle colored via `sourceColor`. `KindBadge` = colored pill per `event_type` (Decision/Handoff/Observation emphasized; PostToolUse muted).
- [ ] **Step 2:** Manual check: `npm run dev`, header renders, nav highlights, chips toggle, live dot connects (with backend running).
- [ ] **Step 3:** Commit `feat: app shell, nav, source chips, live dot`.

---

### Task 11: Overview (search-first, minimal)

**Files:** Create `frontend/src/pages/OverviewPage.tsx`.

**Interfaces:** Renders `HeroSearch` (large input → navigates to `/search?q=...`; also opens ⌘K on focus shortcut), the store-size readout from `getStatus()`, `RecentSessions` (from `createSessionsResource`, links to `/sessions/:id`, live-updates on `session.updated`), and `LiveActivityFeed` (from `createLiveStore().events`, each row `SourceDot`+`KindBadge`+title+time-ago, links into the owning session). Empty states per spec §9.

- [ ] **Step 1:** Build the two-region layout (hero on top; recent + live side-by-side; stacks under ~900px).
- [ ] **Step 2:** Manual check against real data: recent sessions populate; trigger an ingest (or run an agent) and watch a row appear in the live feed.
- [ ] **Step 3:** Commit `feat: search-first overview (hero search, recent, live feed)`.

---

### Task 12: Sessions split-pane (virtualized)

**Files:** Create `frontend/src/pages/SessionsPage.tsx`.

**Interfaces:** Left: virtualized `SessionList` (via `@tanstack/solid-virtual`) over `createSessionsResource`; row = `SourceDot`+title+meta(`eventCount · ~cwd · timeAgo`), selectable, honors `sourceFilter`, updates URL to `/sessions/:id`. Right: `SessionDetail` — header (title/source/cwd/summary/span/count) + virtualized `EventTimeline` over `getSessionEvents(id, limit)`. Each event routes to a renderer (Task 13). A right `SessionOutline` derives files-edited/read + tools-used from loaded events (parse `toolName`/`toolInputJson` client-side).

- [ ] **Step 1:** Virtualized list (1,401 rows must window). Selecting loads the detail resource keyed by route param.
- [ ] **Step 2:** Virtualized timeline; `PostToolUse` rows collapsed/dimmed by default.
- [ ] **Step 3:** Manual check: scroll 1,401 sessions smoothly; open a large session; outline populates.
- [ ] **Step 4:** Commit `feat: sessions split-pane with virtualized list + timeline`.

---

### Task 13: Structured event renderers

**Files:** Create `frontend/src/components/events/DecisionCard.tsx`, `HandoffCard.tsx`, `ObservationCard.tsx`, `EventRow.tsx`.

**Interfaces:** Consumes `AgentEvent`; a dispatcher (in `EventTimeline`) picks by `eventType`: `Decision`→`DecisionCard` (parse `metadata`: decision, rationale, alternatives[], confidence→meter, openLoops[]), `Handoff`→`HandoffCard` (toAgent, contextSummary, openLoops[], nextAction), `Observation`→`ObservationCard` (text), else `EventRow` (kind badge + role + toolName + sequence; `toolInputJson`/`toolOutputJson` behind a `<details>`; muted for `PostToolUse`). Never render raw JSON as the primary content.

- [ ] **Step 1:** Component test (`@solidjs/testing-library`): render `DecisionCard` with a sample event, assert rationale text + confidence meter present and no raw `{` JSON leaks into the headline.
- [ ] **Step 2:** Implement the four renderers + dispatcher. `metadata` may be a parsed object or a JSON string — handle both.
- [ ] **Step 3:** Run `npm run test -- events` → PASS.
- [ ] **Step 4:** Commit `feat: structured decision/handoff/observation event cards`.

---

### Task 14: Search page (faceted find + gated ask)

**Files:** Create `frontend/src/pages/SearchPage.tsx`.

**Interfaces:** Reads `?q=` from the route. **Find mode**: `FacetedSearchBar` (input + autocomplete popover from `searchFields()`/`searchValues()`, parsing via `lib/query.ts`); `FacetRail` (source · kind · project · tool · time chips that mutate the query); results from `search(q, limit)` grouped into Sessions / Events / Decisions+Handoffs, each linking to `/sessions/:id`; a "meaningful events only" toggle (default ON) that appends/strips a `kind:!PostToolUse`-style filter client-side (or filters results client-side when Elastic absent). Merge `elastic[]` when `elasticHealth` ok. **Ask mode**: shown only if `askStatus()` ready; posts `ask`, renders answer + ranked citations linking to sources.

- [ ] **Step 1:** Build Find mode + facet rail + autocomplete; wire to backend.
- [ ] **Step 2:** Build Ask mode behind the status gate.
- [ ] **Step 3:** Manual check: `source:codex` narrows results without Elasticsearch (proves Task 3); autocomplete suggests fields/values; Ask hidden when unavailable.
- [ ] **Step 4:** Commit `feat: faceted search page with gated RAG ask`.

---

### Task 15: ⌘K command palette

**Files:** Create `frontend/src/components/CommandPalette.tsx`; mount in `App.tsx`.

**Interfaces:** Global overlay toggled by ⌘K/Ctrl-K (and by the header affordance). Input drives: fuzzy session match (client-side over the cached recent set; falls back to `search(q)` for older), navigation entries (Overview/Sessions/Search), and "Search for `<q>`" deep-linking into `/search?q=`. Keyboard: arrows move, Enter acts, Esc closes; focus trap while open.

- [ ] **Step 1:** Implement overlay + keybindings + result list.
- [ ] **Step 2:** Manual check: ⌘K opens anywhere; typing a title jumps to its session; `source:codex` offers the search deep-link.
- [ ] **Step 3:** Commit `feat: ⌘K command palette`.

---

## PHASE D — Build wiring & verification

### Task 16: Maven build integration + static contract

**Files:** Modify `pom.xml`; update `src/test/java/dev/nathan/sbaagentic/web/StaticUiContractTest.java`.

**Interfaces:** `mvn package` runs `npm ci` + `npm run build` (frontend) before packaging, so the jar contains the built UI. `StaticUiContractTest` asserts the new entry points (e.g. `index.html` served at `/` contains `BLACKBOX`; a hashed JS/CSS asset is reachable) rather than the old vanilla filenames.

- [ ] **Step 1:** Add `com.github.eirslett:frontend-maven-plugin` bound to `generate-resources`: `install-node-and-npm` (pin Node 20/22), `npm ci`, `npm run build` with `workingDirectory=frontend`. Ensure Vite `outDir` writes into `src/main/resources/static` before `process-resources`.
- [ ] **Step 2:** Update `StaticUiContractTest` to the new asset expectations (drop assertions about `app.js`/`querybar.js`/`graph.js`).
- [ ] **Step 3:** Run `mvn -q clean package` → builds frontend, tests pass, jar contains `static/index.html` + hashed assets.
- [ ] **Step 4:** Commit `build: build SolidJS frontend into the jar via frontend-maven-plugin`.

---

### Task 17: Simulated-use e2e (required verification gate)

**Files:** Create `frontend/playwright.config.ts`, `frontend/tests/e2e/smoke.spec.ts`.

**Interfaces:** Playwright drives the **real running app** (start the jar or `mvn spring-boot:run` against the existing DB; `webServer` or a documented manual start). Asserts the human path end-to-end.

- [ ] **Step 1:** Config — `baseURL` `http://127.0.0.1:8766` (built jar) or `5173` (dev) ; single chromium project.
- [ ] **Step 2:** Spec `smoke.spec.ts`:
  - Load `/` → hero search + store-size readout visible; recent sessions list non-empty.
  - Type `source:codex` in search → submit → results render and are scoped to codex.
  - Open the first session → detail header + event timeline render; expand a Decision card → rationale visible, no raw JSON in the headline.
  - Open ⌘K → type a known title → Enter → lands on that session.
  - Live: POST one event to `/api/events` (or `/api/observations`) → assert it appears in the Overview live feed within a few seconds.
- [ ] **Step 3:** Run `npm run e2e` → PASS. Capture a screenshot artifact of the Overview for the handoff.
- [ ] **Step 4:** Commit `test: playwright simulated-use smoke (overview, search, session, ⌘K, live)`.

---

## Self-Review

- **Spec coverage:** stack/build (T4,T16) · SSE (T1) · SPA fallback (T2) · theme/brand (T4) · search-first Overview (T11) · Sessions split-pane + virtualization (T12) · structured renderers (T13) · faceted search incl. SQLite facets (T3,T14) · gated Ask (T14) · ⌘K (T15) · source filter (T9,T10) · scale/virtualization (T8 cap, T12) · testing incl. simulated-use (T13,T17). Phase-2 items (Recall/Projects/stats/graph) intentionally excluded per scope. ✓
- **Placeholders:** none — load-bearing backend/lib code is spelled out; view tasks carry exact interfaces + acceptance checks (their real gate is the Playwright pass in T17). ✓
- **Type consistency:** `searchEvents(String,int)` unchanged for callers; `QueryFacets.parse` ↔ `parseQuery` mirror the same alias map; `EventAppended`/`SessionUpdated` records match the `lib/sse.ts` listeners and `lib/api.ts` types. ✓

## Execution note

Per the project's standing preference, the heavy SolidJS implementation (Phases B–C) is delegated to **Codex** via the codex-workflow-hybrid skill; Claude owns the Phase A backend, build wiring (T16), and adversarial verification + the simulated-use pass (T17). Disjoint file sets per task keep parallel waves conflict-free.
