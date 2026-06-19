# Reproducible E2E Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the SolidJS Playwright gate seed and boot its own isolated Black Box instance.

**Architecture:** Keep the production launchd service and `sba-agentic.db` out of the test path. Playwright builds the packaged jar, starts it on `SBA_PORT=8799` with a temp SQLite datasource, then a global setup seeds the exact `/api/events` payloads asserted by `frontend/tests/e2e/smoke.spec.ts`.

**Tech Stack:** Java 21 / Spring Boot jar, SQLite, SolidJS + Vite + TypeScript, Vitest, Playwright.

---

### Task 1: Seed Contract

**Files:**
- Create: `frontend/src/e2e/seedData.ts`
- Create: `frontend/src/e2e/seedData.test.ts`

- [x] **Step 1:** Add a failing Vitest test that asserts the seed includes:
  - session title `UI rewrite kickoff`
  - session title `Frontend build`
  - codex decision `Use SolidJS + Vite for the UI rewrite`
  - rationale `Matches agent-observatory; stays self-contained in the jar at runtime`
  - an `openLoops` metadata array
  - a `black-box-e2e` cwd
  - a claude-only event containing `Rewrite the UI to match agent-observatory`
- [x] **Step 2:** Add a failing Vitest test that rejects production-looking base URLs such as `http://127.0.0.1:8766`.
- [x] **Step 3:** Implement the minimal seed module and prove the new tests pass.

### Task 2: Self-Starting Playwright Gate

**Files:**
- Modify: `frontend/playwright.config.ts`
- Create: `frontend/tests/e2e/global-setup.ts`

- [x] **Step 1:** Add a Playwright `webServer` command that runs `mvn -q -Pfrontend -DskipTests package` from the repo root, then starts `target/sba-agentic-0.1.0.jar` with `SBA_PORT=8799` and a temp `SBA_DATASOURCE_URL`.
- [x] **Step 2:** Disable optional external systems for the test process with environment variables.
- [x] **Step 3:** Add global setup that seeds via `POST /api/events` after Playwright confirms the server is reachable.

### Task 3: Docs And Verification

**Files:**
- Modify: `docs/frontend-overhaul-handoff.md`

- [x] **Step 1:** Replace stale vanilla-JS handoff content with current SolidJS rewrite state.
- [x] **Step 2:** Document the reproducible e2e path, port `8799`, temp DB isolation, and the launchd production-service caveat.
- [x] **Step 3:** Run the full verify command:

```bash
mvn -q test && (cd frontend && npm run test && npm run build) && mvn -q -Pfrontend -DskipTests package && (cd frontend && npm run e2e)
```
