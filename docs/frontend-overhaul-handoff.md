> Internal development handoff notes — not user documentation.

# Black Box Frontend Handoff

**Last updated:** 2026-07-15 · **Status:** SolidJS frontend, logical Projects, and coordination Board implemented

Black Box now uses the SolidJS + Vite + TypeScript frontend under `frontend/`.
The retired vanilla-JS overhaul notes in this file are no longer current. The built UI is emitted
into `src/main/resources/static/` by `npm run build` and by the Maven `-Pfrontend` profile, so
`mvn -Pfrontend package` ships one self-contained Spring Boot jar.

## Current UI State

- Phase-1 SolidJS rewrite is in place: Overview, Sessions, Search, structured event cards, source
  chips, command palette, and live SSE activity.
- Phase-2 views are in place: Recall, logical Projects with verified worktree grouping, scope
  provenance, recent sessions, storyline timeline, and saved synthesis, plus Stats and Graph.
  Project alias merges change the read model only; raw session/event paths remain untouched.
- The coordination Board is in place at `/board`: project/lane filters and selected task are
  shareable URL state; Open, In Progress, Blocked, and Done columns update from task SSE frames;
  cancelled work is disclosed separately; detail shows the frozen spec and linked Handoff.
- The Board never launches worker agents or executes task commands. Its only lifecycle write is an
  explicit reset from `blocked` or `in_progress` to `open`, followed by an authoritative REST
  refresh.
- Playwright simulated-use coverage lives in `frontend/tests/e2e/smoke.spec.ts` and drives the real
  packaged app, not the Vite dev server.

## Reproducible E2E Gate

Run the gate from a cold repo state:

```bash
cd frontend
npm run e2e
```

Playwright now owns the full setup:

1. Builds the packaged jar from the repo root with `mvn -q -Pfrontend -DskipTests package`.
2. Starts `target/sba-agentic-0.1.0.jar` on `127.0.0.1:8799` using `SBA_PORT=8799`.
3. Points SQLite at a temp DB through `SBA_DATASOURCE_URL=jdbc:sqlite:<temp path>`.
4. Seeds the deterministic smoke data through the real `POST /api/events` ingest endpoint.
5. Runs the e2e specs, then shuts the test app down.

The seed contract is tracked in `frontend/src/e2e/seedData.ts` and covered by
`frontend/src/e2e/seedData.test.ts`. It provides the asserted sessions `UI rewrite kickoff` and
`Frontend build`, the codex decision `Use SolidJS + Vite for the UI rewrite`, and the claude-only
prompt text that `source:codex` searches must exclude.

## Live-Service Safety

Never point Playwright or deterministic seed data at the normal service port or database. The
checked-in config refuses `http://127.0.0.1:8766`, defaults to `127.0.0.1:8799`, creates a temporary
SQLite file, disables optional AI/Elasticsearch paths, and refuses to reuse an unknown server.
Restart a real installed service only through the supported `scripts/deploy-local.sh` path and only
when a live deployment is explicitly in scope.

## Verification Stack

Run the full project gate from the repo root:

```bash
mvn -q test && (cd frontend && npm run test && npm run build) && mvn -q -Pfrontend -DskipTests package && (cd frontend && npm run e2e)
```

For a faster frontend-only loop while editing seed data:

```bash
cd frontend
npm run test -- src/e2e/seedData.test.ts
npm run e2e -- --grep "overview is search-first"
```

For the coordination client and Board:

```bash
cd frontend
npm run test -- src/lib/api.test.ts src/lib/tasks.test.ts src/lib/sse.test.ts src/pages/__tests__/BoardPage.test.tsx
npm run build
```

The task live store treats `/api/stream` as a wake/diff channel only. It starts from
`GET /api/tasks`, ignores duplicate or older transitions, and refreshes after a connection gap or
malformed task frame. SQLite via REST remains authoritative.
