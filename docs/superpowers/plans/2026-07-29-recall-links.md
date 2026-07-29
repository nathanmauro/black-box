# Recall Links (Phase 1, Slice 2) Implementation Plan

**Spec:** `docs/superpowers/specs/2026-07-28-agent-observatory-consolidation-design.md`
§4.9, §6, §8, §13 slice 2.

**Branch:** `stream-observatory-consolidation`

## Goal

Make every structured Recall result lead to the exact recorded event in its owning session, and
replace the Board's year-long Recall scan for completion Handoffs with one primary-key event lookup.

## Scope and safety contract

- Additive REST/MCP data only: `RecalledItem` gains `sessionId`; no existing field changes.
- Add `GET /api/events/{id}` returning one canonical `AgentEvent`, with `404` for an unknown id.
- No schema migration, dependency, ingest, recall-ranking, or MCP tool change.
- Keep the current independently shippable Browse URL shape,
  `/?view=browse&session=<sessionId>&event=<eventId>`. The `/browse` route belongs to slice 6.
- Preserve the Board's existing Handoff presentation by reading its structured fields from the
  returned event metadata.
- Do not push or create a PR. The branch has no upstream and remains local-only.
- Maven packaging and Playwright overwrite the jar used by launchd on `:8766`; always finish with
  `scripts/deploy-local.sh` and verify the live service.

## Task 1: Freeze the backend lookup contract

1. Add a failing `AgenticControllerTest` covering an exact event lookup and an unknown-id `404`.
2. Add `RecordingCatalog.findEventById`, implement it as a primary-key query in
   `RecordingSqlStore`, and expose it from `EventController`.
3. Update `rest-mappings.txt` and `rest-contract-matrix.json`.
4. While touching the frozen mapping snapshot, remove only the stale `/overview` and `/stats` SPA
   entries left behind by slice 1. Keep backend `GET /api/stats`.

Focused gate:

```sh
mvn -q -Dtest=AgenticControllerTest,RestContractSnapshotTest test
```

## Task 2: Carry session lineage through Recall

1. Add `sessionId` to `RecalledItem` immediately after `eventId`.
2. Populate it from `AgentEvent.sessionId` in `ContextService.toRecalledItem`.
3. Assert the owning session id in `ContextLoopTest`.
4. Update REST/MCP shape assertions and `wire-fixtures.json`.

Focused gate:

```sh
mvn -q -Dtest=ContextLoopTest,WireContractFixtureTest,RestContractSnapshotTest,McpContractSnapshotTest test
```

## Task 3: Add the frontend exact-event client

1. Mirror required `RecalledItem.sessionId` in `frontend/src/lib/api.ts`.
2. Add `getEvent(id): Promise<AgentEvent>` with encoded path input.
3. Cover the helper in `frontend/src/lib/api.test.ts`.

Focused gate:

```sh
cd frontend && npm run test -- src/lib/api.test.ts
```

## Task 4: Link Recall cards to their exact source

1. Render the Recall card head as a router link.
2. Include `view=browse`, `session`, and `event`, plus an explicit empty `project` parameter so a
   remembered Activity scope cannot hide the owning session when Recall has no trustworthy repo.
3. Add a visible hover/focus treatment without changing the card's information hierarchy.
4. Update `RecallPage.test.tsx` to prove the exact href.

Focused gate:

```sh
cd frontend && npm run test -- src/pages/__tests__/RecallPage.test.tsx src/theme.test.ts
```

## Task 5: Remove the Board recall scan

1. Replace the injected/default `getRecall` dependency with `getEvent`.
2. Resolve `resultHandoffId` with one exact event request.
3. Safely project `contextSummary`, `nextAction`, and `openLoops` from unknown metadata, with event
   text as the headline fallback.
4. Update `BoardPage.test.tsx` to assert the exact lookup and retained Handoff rendering.

Focused gate:

```sh
cd frontend && npm run test -- src/pages/__tests__/BoardPage.test.tsx
```

## Task 6: Prove the real navigation path

Extend the existing seeded Recall Playwright test:

1. Run Recall for the seeded Decision.
2. Click its linked card head.
3. Assert Browse mode, the owning session, both `session` and `event` query values, and the
   `.event-flow-row--target` highlight.
4. If the exact event falls outside Browse's 2,000-event session batch, load it through
   `GET /api/events/{id}`, verify its owning session, and merge it into the reader.

Focused gate:

```sh
cd frontend && npx playwright test tests/e2e/smoke.spec.ts --grep "recall query"
```

## Task 7: Document, bundle, deploy, and close

1. Update `README.md` and `docs/architecture.md` for Recall click-through and Board's direct event
   lookup.
2. Update `NEXT.md`: slice 2 shipped, slice 3 is next.
3. Run the full gate:

   ```sh
   mvn -q test
   (cd frontend && npm run test && npm run build)
   mvn -q -Pfrontend -DskipTests package
   (cd frontend && npm run e2e)
   git diff --check
   ```

4. Run `scripts/deploy-local.sh`.
5. Verify `/api/status`, `/api/events/{knownId}`, served hashed assets, and the live
   Recall-to-Browse navigation on `:8766`.
6. Commit exact paths only, keep the branch unpushed, and leave a Black Box Handoff/Observation
   breadcrumb with verification and live effects.

## Acceptance criteria

- REST and MCP Recall results expose the owning internal `sessionId`.
- `GET /api/events/{id}` returns the canonical event or `404`.
- A Recall card opens the correct owning session with the exact event highlighted.
- The Board performs one exact event request for a completion Handoff and no full-year Recall scan.
- Targeted tests, full backend/frontend/package/E2E gates, `git diff --check`, and live `:8766`
  verification all pass.
