# Project Meld Preview/Export Implementation Plan

**Goal:** Complete Phase 2 of the approved Projects plus Durable Melds design by making selected
project sessions produce a bounded meld preview/export bundle without adding durable meld
persistence.

**Scope:** Project meld bundle API, Projects tab selection tray, direct-preview execution mode, static
contract coverage, and controller tests. No schema changes, saved meld rows, project alias controls,
hook changes, or Recall/ASK indexing changes.

## Tasks

- [x] Add a typed preview request/response contract under the project package.
- [x] Add `ProjectMeldService` to validate selected sessions, reject cross-project selections, build a
  deterministic bounded bundle, and call the configured summary backend only for explicit direct mode.
- [x] Add `POST /api/projects/{projectKey}/melds/preview`.
- [x] Add Projects tab session checkboxes, mode/provider/model controls, preview action, copy action,
  status text, and bounded output panel.
- [x] Extend controller tests for export-bundle output and fail-closed cross-project selection.
- [x] Extend static UI contract tests for the meld tray.

## Verification

- `node --check src/main/resources/static/app.js`
- `mvn -Dtest=StaticUiContractTest test`
- `mvn -Dtest=AgenticControllerTest#projectsReadModelGroupsSessionsAndBuildsTimeline,AgenticControllerTest#projectMeldPreviewBuildsBoundedExportBundle,AgenticControllerTest#projectMeldPreviewRejectsCrossProjectSessionSelection,StaticUiContractTest test`
- `mvn test`
- `git diff --check`
- Live browser desktop and mobile smoke against the running app
