# Black Box UI Rewrite — Design

**Date:** 2026-06-16
**Status:** Approved direction; spec under review
**Owner:** Nathan
**Inspiration:** `~/Developer/proj/agent-observatory` frontend

## 1. Why

Black Box is a local flight recorder for AI agents: an append-only SQLite store
(currently ~1,401 sessions / ~74,041 events) behind a Spring Boot REST API. The
backend already exposes a richer surface than the current UI uses — sessions,
projects + per-project timelines, full-text `search` with field/value faceting,
structured `recall`, and optional `ask` (RAG). The existing UI is ~135 KB of
vanilla JS (`app.js`, `querybar.js`, `graph.js`) that has outgrown a no-framework
approach and does not make the store easy to search and browse at scale.

We are doing a **complete frontend rewrite**, modeled on the agent-observatory
UI, with two priorities the user named explicitly:

1. **Finding sessions and information easily** — search is the centerpiece.
2. **A "TOC to begin"** — a landing surface that orients you and launches you
   into everything.

## 2. Goals & non-goals

**Goals (this effort — "core first"):**
- A SolidJS + Vite frontend that replaces the current static UI.
- A **search-first, minimal Overview** (the TOC): prominent search launcher,
  recent sessions, live activity stream.
- A **Sessions** split-pane (virtualized list + detail with rich structured
  event cards).
- A **Search** page: faceted `field:value` find + gated RAG ask.
- A **⌘K command palette** for instant find-and-jump from anywhere.
- A **live channel (SSE)** added to the backend so new events/sessions stream in.

**Deferred (phase 2, fold in right after):**
- **Recall** view (structured decision/handoff query as its own page).
- **Projects** view (storyline timeline + meld builder).
- A **stats** dashboard surface (counts, breakdowns).
- The **graph / constellation** visualization (`graph.js`).

**Non-goals:**
- Editing or deleting sessions/events (UI stays read-only, as today).
- Auth, multi-user, or any cloud/remote surface (localhost-only stays).
- Changing the SQLite schema or the MCP tool contracts.

## 3. Architecture

### 3.1 Frontend stack
- **SolidJS** (signals/resources/stores) + **Vite** + **TypeScript**, plain CSS
  with custom-property theming — mirrors agent-observatory exactly.
- New top-level **`frontend/`** directory holds the app source.
- **State**: Solid primitives + small stores (`createSessionStore`,
  `createSearchStore`, `createSseStore`, a global `sourceFilter`). No external
  state library.

### 3.2 Build & packaging (keeps the single self-contained jar)
- `frontend/` builds via Vite into `src/main/resources/static/`, replacing the
  current `index.html` / `app.js` / `querybar.js` / `graph.js` / `styles.css`.
- **`frontend-maven-plugin`** runs `npm ci` + `vite build` during `mvn package`,
  so `mvn package` still produces one jar with built assets in it. Node is a
  **dev/build-time dependency only**; the shipped jar is as self-contained as
  today.
- `frontend/node_modules/` and `frontend/dist/` are gitignored. The built output
  under `src/main/resources/static/` is produced by the build (not hand-edited).
- **Dev loop**: `vite dev` on `5173`, proxying `/api` and the SSE endpoint to the
  Spring Boot app on `127.0.0.1:8766` (same arrangement observatory uses on
  `5173`→`3284`).
- **SPA fallback**: client-side routes (`/sessions/:id`, `/search`) must resolve
  on hard refresh. Add a Spring forwarding rule that serves `static/index.html`
  for non-`/api`, non-asset GETs so deep links and reloads work.

### 3.3 Backend addition — live channel (SSE)
- New endpoint **`GET /api/stream`** returning `text/event-stream` via Spring
  `SseEmitter` (push-only; the browser's native `EventSource` auto-reconnects).
- A small **`EventBroadcaster`** holds active emitters. `EventIngestService`
  publishes to it **after** a successful persist.
- Event payloads (named SSE events):
  - `event.appended` — `{sessionId, source, eventType, toolName?, title, observedAt}`
  - `session.updated` — `{sessionId, source, title, cwd, eventCount, lastSeenAt}`
  - `status` — periodic `{sessions, events, sources, localAi, elasticsearch}`
- Backpressure/scale: the stream carries lightweight summaries only (never full
  event bodies); the UI fetches detail on demand. Emitter list is pruned on IO
  error. Rationale for SSE over WebSocket: we only ever push, SSE is trivial in
  Spring, reconnects natively, and proxies cleanly through Vite. WebSocket is a
  drop-in swap if bidirectional is ever needed (it is not here).

### 3.4 Theme & brand
- Keep the **`BLACKBOX`** masthead identity and **IBM Plex Sans/Mono**
  (already self-hosted under `static/fonts/`, no CDN — fits the no-deps ethos).
- Adopt observatory's **layout, density, and color system**:
  - `--bg #0f1117`, `--bg-surface #1a1b23`, `--bg-hover #22232e`,
    `--bg-selected #2a2b3a`, `--border #2a2c37`
  - `--text #c9cdd3`, `--text-dim #6b7280`, `--text-bright #f0f1f3`
  - `--accent #7c5cfc` (purple), `--green #34d399`, `--yellow #fbbf24`,
    `--red #f87171`, `--blue #60a5fa`, `--orange #f59e0b`
  - `--radius 6px`, fast `0.15s` hover transitions, compact data density.
- **Per-source dot colors** (the data has 7 sources): claude=`#7c5cfc`,
  codex=`#34d399`, cursor=`#60a5fa`, raycast=`#f59e0b`, cockpit=`#e879f9`,
  cli=`#9ca3af`, manual=`#6b7280` (palette finalizable during build).

## 4. Information architecture & navigation

Thin (≈48px) fixed header, observatory-style:

```
◼ BLACKBOX   Overview · Sessions · Search        ⬤claude ⬤codex …   ● live   ⌘K
```

- **Nav** (phase 1): Overview · Sessions · Search. (Recall · Projects added in
  phase 2.)
- **Global source-filter chips** in the header apply across every view via a
  shared `sourceFilter` store; an "All" toggle clears them.
- **Live dot**: green connected / yellow connecting / red disconnected, driven by
  the SSE connection.
- **⌘K** mounts a command palette overlay available from anywhere.

## 5. View specs

### 5.1 Overview — the TOC (search-first, minimal)

Center of gravity is **search**. Three stacked regions, nothing else:

```
┌────────────────────────────────────────────────────────────┐
│            🔎  Search sessions and events…           ⌘K     │  ← hero search
│            1,401 sessions · 74,041 events                    │
├───────────────────────────────┬────────────────────────────┤
│  RECENT SESSIONS              │  LIVE ACTIVITY  ● (sse)     │
│  ⬤claude  fix ingest…   3m   │  ⬤ Decision   SolidJS…      │
│  ⬤codex   ui rewrite…  18m   │  ⬤ PostToolUse Edit app.js  │
│  ⬤claude  recall bug…   1h   │  ⬤ UserPrompt "rewrite…"    │
│  [ browse all → ]             │  … newest on top, capped 50 │
└───────────────────────────────┴────────────────────────────┘
```

- **Hero search**: a large input that submits into the Search page (and the same
  ⌘K palette opens over it). Shows the store-size readout for orientation.
- **Recent sessions**: from `GET /api/sessions?limit=…`, filtered by the global
  source filter; each row links into Sessions detail. Updates live on
  `session.updated`.
- **Live activity**: a capped (ring-buffer, ~50) feed of `event.appended` from
  the SSE channel; each row source-dotted and kind-badged, clickable into the
  owning session. Empty state before any event arrives: a quiet "listening…".

### 5.2 Sessions — split pane

- **Left: virtualized session list** (1,401 rows — windowed rendering required).
  Row = source dot + title + meta (`events · project · time-ago`). Sort by
  recency (default); optional group-by-project. Honors the global source filter.
  Backed by `GET /api/sessions`.
- **Right: session detail**.
  - Header: title, source, `cwd`, summary (bounded with expand), time span,
    event count.
  - **Virtualized event timeline** from `GET /api/sessions/{id}/events`
    (74k events total; individual sessions can be large → windowed).
  - **Structured renderers**:
    - `DecisionCard` — decision, rationale, alternatives, confidence (0–1 as a
      meter), open loops.
    - `HandoffCard` — to-agent, context summary, open loops, next action.
    - `ObservationCard` — note text.
    - `EventRow` (generic) — kind badge + role + tool name + sequence; tool
      payloads behind a `<details>` toggle; `PostToolUse` rows dimmed and
      collapsed by default to fight the 68k-event noise.
  - **Outline** (right inset, carried from current UI): files edited / files
    read / tools used, derived client-side from the loaded events.
- Selecting a session updates the URL (`/sessions/:id`) for deep-linking.

### 5.3 Search — the priority surface

Dual mode, both backed by existing endpoints:

- **Find mode** (default):
  - Faceted query input supporting `field:value` with `AND/OR/NOT`, with an
    **autocomplete popover** sourced from `GET /api/search/fields` and
    `GET /api/search/values?field=&prefix=`.
  - Left **facet rail**: source · kind (`event_type`) · project (`cwd`) · tool ·
    time range. Selecting facets narrows the query live.
  - **Noise control**: results **default to meaningful events** (prompts,
    decisions, handoffs, observations) with a toggle to fold in raw
    `PostToolUse`. `event_type` is a first-class facet.
  - Results from `GET /api/search?q=&limit=`, **grouped** into Sessions /
    Events / Decisions+Handoffs; each row links into Sessions detail. Elastic
    hits merged in when `elasticHealth` reports available.
- **Ask mode** (gated): shown only when `GET /api/ask/status` reports ready;
  posts to `/api/ask`, renders the synthesized answer with ranked citations
  (each citation links to its source event/session).

### 5.4 ⌘K command palette

- Opens over any view. Single input with live results:
  - **Sessions** — fuzzy match on title (client-side over a cached recent set;
    falls back to `/api/search` for older sessions).
  - **Navigate** — jump to Overview / Sessions / Search.
  - **Quick query** — typing `source:codex` etc. offers "Search for …" that
    deep-links into the Search page with that facet applied.
- Keyboard: ⌘K / Ctrl-K to open, arrows to move, Enter to act, Esc to close.

## 6. Data-flow & API map

| View | Reads | Live |
|------|-------|------|
| Overview | `/api/sessions`, `/api/status` | `session.updated`, `event.appended`, `status` |
| Sessions | `/api/sessions`, `/api/sessions/{id}/events` | `session.updated`, `event.appended` (for open session) |
| Search | `/api/search`, `/api/search/fields`, `/api/search/values`, `/api/ask/status`, `/api/ask` | — |
| ⌘K | cached sessions + `/api/search` | — |

All reads go through a typed `lib/api.ts` client. The SSE store
(`lib/sse.ts`) owns the single `EventSource`, exposes connection status, and
fans named events out to subscribed view stores.

## 7. Scale & performance
- **Virtualize** the session list and the event timeline (windowed rendering).
- All list queries are **bounded** (`limit`) and cursor/offset-paginated;
  "browse all" loads incrementally.
- The live feed is a **capped ring buffer** (~50 items) — never unbounded.
- `PostToolUse` event bodies are **lazy** — the timeline shows a dimmed row;
  payload loads only when expanded.
- The SSE channel carries **summaries only**, never full event bodies.

## 8. Component inventory (Solid)
- `AppShell` — header, nav, global source filter, live dot, ⌘K mount, router.
- `CommandPalette` — the ⌘K overlay.
- `OverviewPage` — `HeroSearch`, `RecentSessions`, `LiveActivityFeed`.
- `SessionsPage` — `SessionList` (virtual), `SessionDetail`
  (`EventTimeline` virtual + `SessionOutline`).
- Structured renderers — `DecisionCard`, `HandoffCard`, `ObservationCard`,
  `EventRow`.
- `SearchPage` — `FacetedSearchBar` (+ autocomplete popover), `FacetRail`,
  `ResultGroups`, `AskPanel`.
- `lib/` — `api.ts` (typed REST client), `sse.ts` (EventSource store),
  `stores.ts` (session/search stores + `sourceFilter`), `theme.css`,
  `format.ts` (time-ago, path `~`-truncation, source colors).

## 9. States: loading / empty / error
- **Loading**: skeleton rows in lists; spinner only for Ask synthesis.
- **Empty**: each surface has a quiet empty state ("no sessions match",
  "listening for activity…", "no results — try removing a facet").
- **Error**: API failures surface a non-blocking toast + inline retry; SSE drop
  flips the live dot and silently retries (native `EventSource` behavior).
- **Gated features**: Ask mode and Elastic results appear only when their health
  endpoints report available; otherwise hidden, not broken.

## 10. Testing
- **Unit/component**: Vitest + `@solidjs/testing-library` for stores, formatters,
  the faceted-query parser, and the structured renderers.
- **Backend**: a Spring test asserting the SSE endpoint emits on ingest; update
  `StaticUiContractTest` for the new built-asset entry points.
- **Simulated human use (required)**: a Playwright run that drives the real app
  against a seeded DB — load Overview, run a faceted search, open a session and
  expand a decision card, open ⌘K and jump, and assert a live event appears on
  the Overview feed after an ingest. This is the use-level verification gate, not
  optional.

## 11. Rollout
1. Scaffold `frontend/` (Vite + Solid + TS), theme, `AppShell` + routing.
2. Backend: `EventBroadcaster` + `/api/stream` SSE + ingest hook + test.
3. `lib/` api + sse + stores + formatters.
4. Overview (search-first) against real data.
5. Sessions split pane (virtualized) + structured renderers.
6. Search page (faceted + gated Ask).
7. ⌘K palette.
8. `frontend-maven-plugin` wiring so `mvn package` builds the UI into the jar.
9. Playwright simulated-use pass; fix; ship.

Phase 2 (separate effort): Recall view, Projects + storyline + meld, stats
dashboard, graph/constellation viz.
