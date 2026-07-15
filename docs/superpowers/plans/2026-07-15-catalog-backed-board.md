# Catalog-Backed Coordination Board

**Status:** Implemented and verified on 2026-07-15.

## Goal

Connect the coordination Board to Black Box's existing searchable project catalog while keeping
cards limited to work explicitly queued through REST or MCP.

## Contract

- Load project identity from `GET /api/projects` and translate picker selections to the catalog
  project's canonical scope before filtering `GET /api/tasks`.
- Keep task/spec `projectKey` values and existing Board URLs as canonical or legacy raw scopes.
- Preserve task scopes that are not present in the catalog as labelled uncatalogued choices.
- Show a project-specific empty state without inferring tasks from recorded activity, sessions, or
  external task systems.
- Keep the queue usable when the catalog request fails.
- Do not migrate the schema, change REST/MCP wire contracts, seed production tasks, or delete the
  existing acceptance records.

## Implementation

- Reuse the shared searchable project picker in the Board filter bar.
- Resolve catalog labels for filter summaries, task cards, and task detail while retaining the
  canonical path as visible context.
- Add focused frontend coverage for catalog search, canonical filtering, empty/clear behavior,
  legacy scopes, and catalog failure fallback.
- Clarify the explicit-queue boundary in the README and refresh the Board screenshot using an
  isolated, machine-neutral SQLite fixture.

## Verification

- Frontend: 122 tests passed; production build passed.
- Backend/package: 170 tests passed with `mvn -B verify`.
- Isolated runtime: catalog projects `/workspace/black-box` and `/workspace/agent-workbench`; four
  lifecycle task states under the first and a named empty state under the second.
- Browser: catalog picker, canonical labels, task columns, and empty state verified with no console
  warnings or errors.
- Asset: `docs/assets/board.png` is a 1280x720 PNG captured from the isolated runtime.
- Production port `8766` and its SQLite data were not touched during fixture capture.
