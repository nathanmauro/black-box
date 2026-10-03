# Scope Browse request state to its selected session

## Scope and reproduction

NAT-317 fixes two reproduced Browse request-ownership races. The actual SessionsPage component
in an isolated fixture showed session A's clickable lineage beneath session B's loaded reader
while B's DAG request was pending. A second fixture held A's older-page request, selected B and
observed B's pagination disabled with “Loading older…” despite B's first page being ready.

Only SessionsPage, its component tests, a focused packaged journey, the reader behavior note and
this plan are owned. Preserve existing event order/deduplication, source filters, navigation and
pagination response handling. No API contract, broad cancellation refactor or other route changes.

## Implementation and verification

- Tag each resolved lineage DAG with its requested session ID; render only matching data.
- Invalidate pagination ownership when session, transcript query or effective human-only mode
  changes. Give each older-page request a generation so stale success, error and finally paths
  cannot mutate a newer reader, including A → B → A and out-of-order completion.
- Accept a same-session older page or error only while its captured starting cursor still matches
  the current first page. A live refresh must not lose its newer cursor and skip intervening data.
  Finally cleanup remains generation-scoped so cursor changes do not strand the loading state.
- Add focused component regressions for delayed lineage, independently usable B pagination,
  old success/error/finally and returning to A. Keep existing reset/pagination contracts green.
- Add `frontend/tests/e2e/session-reader-request-state.spec.ts` using real synthetic captures
  and delayed API responses through the packaged Browse route, at desktop and narrow widths.
- Run scoped repository formatting, targeted/full frontend tests, lint, TypeScript and diff checks.
  The coordinator owns the packaged browser run after source freeze; do not launch a server here.

## Results and handoff

- Before-fix actual component fixtures reproduced both defects: B displayed A's lineage and B's
  older-page button stayed disabled after its own first page loaded. No live data was needed.
- Review identified a same-session live-refetch variant. Both additional pre-fix component cases
  failed: the obsolete page rendered after cursor replacement, and its obsolete error surfaced.
  Cursor-matching guards now reject those results while allowing loading cleanup.
- All 53 SessionsPage tests passed, including eight new ownership cases (lineage, late success/error,
  A → B → A, query changes, human-mode changes and refreshed-cursor success/error). The complete frontend suite passed 802 tests
  across 62 files. Existing reset, exact-target, ordering and deduplication tests remain green.
- Scoped Prettier formatting, `npm run check` (lint, format check and TypeScript) and
  `git diff --check` passed. Lint retains the existing 68 warnings, with zero errors or exemptions.
- Playwright collection finds both 1440px and 390px journeys. They capture two real sessions and
  one child, fetch actual one-event transcript pages with native cursors, and deliberately delay
  DAG/page responses. They test A → B and A → B → A, stale success/error/finally, keyboard paging,
  matching lineage and no horizontal overflow. A new real capture while pagination waits exercises
  live first-page refresh, preserving the intermediate page and reaching all three recorded events.
  Gates are registered before any asynchronous
  fetch so cleanup can release every delayed handler even after a failed assertion.
- Fresh read-only review accepted cross-session ownership after the fixture cleanup correction;
  the subsequent review's refreshed-cursor finding was reproduced and fixed as described above.

Packaged acceptance has not run in this worker lane. Root reserves the browser fixture and owns
the normal frontend/static asset build plus this command from `frontend/` after source freeze:

```bash
npm run e2e -- tests/e2e/session-reader-request-state.spec.ts tests/e2e/lineage.spec.ts tests/e2e/transcript-precise-ordering.spec.ts --workers=1 --retries=0
```

No Git publication, live database/configuration/provider changes or service launches occurred.
The temporary before-fix component fixture and proof log stay outside the repository. Canonical
data and API contracts are unchanged. Superseded transport requests may still finish; only their
ownership of visible reader state is invalidated. Root owns packaged acceptance and Git finishing.

## Coordinator acceptance

The coordinator verified all five frozen source/test/doc hashes and the final Opus review
accepted the refreshed-cursor guards. The packaged application then passed all six Chromium
journeys above at 1440px and 390px, including actual capture, delayed page responses, live
first-page replacement, keyboard navigation and lineage. The normal frontend Maven build
regenerated the checked-in production bundle from this source.

The isolated server used its own temporary SQLite database on port 8799 with model providers
disabled. Its database and synthetic project fixture were removed at teardown; the production
listener on port 8766 retained its original PID. Production database identity/count inspection
was unavailable to this fixture, so no broader database verification is claimed. No deployment,
provider call or installed-service restart occurred.
