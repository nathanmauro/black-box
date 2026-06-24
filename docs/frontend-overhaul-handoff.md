> Internal development handoff notes — not user documentation.

# Black Box Frontend Handoff

**Last updated:** 2026-06-19 · **Current branch:** `reproducible-e2e-seed` · **Base branch:** `graph-constellation-page`

Black Box now uses the SolidJS + Vite + TypeScript frontend under `frontend/`.
The retired vanilla-JS overhaul notes in this file are no longer current. The built UI is emitted
into `src/main/resources/static/` by `npm run build` and by the Maven `-Pfrontend` profile, so
`mvn -Pfrontend package` ships one self-contained Spring Boot jar.

## Current UI State

- Phase-1 SolidJS rewrite is in place: Overview, Sessions, Search, structured event cards, source
  chips, command palette, and live SSE activity.
- Phase-2 views are in place: Recall, Projects with storyline timeline, Stats, and Graph
  constellation.
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

## Production Service Caveat

The production app runs as launchd service `com.nathan.sba-agentic` on port `8766` against
`sba-agentic.db`, and launchd respawns it via KeepAlive. Do not plain-kill that process, and do not
point tests or seed scripts at `8766` or `sba-agentic.db`.

If a production restart is explicitly required, use the launchd flow rather than `kill`:

```bash
launchctl kickstart -k "gui/$(id -u)/${SBA_LAUNCHD_LABEL:-com.nathan.sba-agentic}"
```

The Playwright config refuses to seed `http://127.0.0.1:8766`, defaults to `8799`, and fails if that
test URL is already occupied instead of reusing an unknown server.

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
