# Durable Saved Melds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Phase 3 of Projects plus Durable Melds by saving meld previews durably, listing them, and surfacing saved synthesis blocks in the project timeline with source-session links.

**Architecture:** Keep SQLite as the source of truth with `session_melds` and ordered `session_meld_inputs`. Route creation through `ProjectMeldService` so save uses the same project-bound session validation as preview, and expose read models from `ProjectRepository` to the controller and SolidJS Projects page.

**Tech Stack:** Java 21, Spring Boot 3.5, JdbcTemplate, SQLite, MockMvc, SolidJS, Vite, Vitest, Playwright.

---

### Task 1: Backend Red Tests

**Files:**
- Modify: `src/test/java/dev/nathan/sbaagentic/web/AgenticControllerTest.java`

- [ ] Add a failing MockMvc test that saves a meld through `POST /api/melds`, then verifies the generated id, project key, provider/model/execution mode, ordered source sessions, and `GET /api/projects/{projectKey}/melds` round-trip.
- [ ] Add a failing MockMvc test that attempts a cross-project save, expects `400`, and verifies the target project's meld list remains empty.
- [ ] Add a failing MockMvc test that saves a meld and verifies `GET /api/projects/{projectKey}/timeline` returns a `sourceType=saved_meld`, `blockType=synthesis` block with source-session links.
- [ ] Run `mvn -q -Dtest=AgenticControllerTest test` and confirm these tests fail because the endpoint, schema, and repository behavior do not exist yet.

### Task 2: Durable Storage and API

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectMeldSaveRequest.java`
- Create: `src/main/java/dev/nathan/sbaagentic/project/ProjectSavedMeld.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/project/ProjectRepository.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/project/ProjectMeldService.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/project/ProjectService.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/web/AgenticController.java`

- [ ] Add `CREATE TABLE IF NOT EXISTS` definitions for `session_melds` and `session_meld_inputs` exactly from the approved design.
- [ ] Add request/response records for saving and returning persisted melds.
- [ ] Add transactional repository writes for one meld row plus ordered input rows, and repository reads for saved melds by project.
- [ ] Add `ProjectMeldService.save(...)` that rejects empty, missing, or cross-project session ids before writing.
- [ ] Add top-level `POST /api/melds` and make `GET /api/projects/{projectKey}/melds` return persisted melds.
- [ ] Run `mvn -q -Dtest=AgenticControllerTest test` and confirm backend tests pass.

### Task 3: Timeline Synthesis Blocks

**Files:**
- Modify: `src/main/java/dev/nathan/sbaagentic/project/ProjectTimelineBlock.java`
- Modify: `src/main/java/dev/nathan/sbaagentic/project/ProjectRepository.java`

- [ ] Extend `ProjectTimelineBlock` with ordered `sourceSessions`.
- [ ] Count raw storyline events plus saved melds for project timeline count.
- [ ] Merge raw event blocks and saved meld blocks ordered by observed time, keeping raw events as supporting evidence and saved melds as first-class synthesis blocks.
- [ ] Run `mvn -q -Dtest=AgenticControllerTest test` and confirm save/timeline tests pass.

### Task 4: Frontend Save and Source Links

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/lib/api.test.ts`
- Modify: `frontend/src/pages/ProjectsPage.tsx`
- Modify: `frontend/src/pages/__tests__/ProjectsPage.test.tsx`
- Modify: `frontend/src/theme.css`
- Modify: `frontend/tests/e2e/smoke.spec.ts`

- [ ] Add a typed `saveProjectMeld` API helper that posts to top-level `/api/melds`.
- [ ] Add a Save action after preview, then refresh saved melds and timeline after a successful save.
- [ ] Render saved meld timeline blocks as synthesis cards with provenance and links to `/sessions/{id}`.
- [ ] Add Vitest coverage for the API helper and Projects page save behavior.
- [ ] Extend Playwright smoke coverage to preview, save, and assert saved meld source-session links.
- [ ] Run `cd frontend && npm run test`.

### Task 5: Full Verification and Commit

**Files:**
- Rebuilt static bundle under `src/main/resources/static`

- [ ] Run `mvn -q test && (cd frontend && npm run test && npm run build) && mvn -q -Pfrontend -DskipTests package && (cd frontend && npm run e2e)`.
- [ ] Run `git diff --check`.
- [ ] Inspect `git status --short` and confirm the regenerated static bundle is included.
- [ ] Commit on `phase-3-durable-melds` with Nathan as sole author and no assistant trailers.
