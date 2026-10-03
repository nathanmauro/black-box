# Preserve exact Idea source navigation

## Scope and evidence

NAT-226's Ideas view links each row to its capturing session. With the sticky **My turns** filter
on, an Idea-only session is excluded from ordinary session selection, so that session-only link
can redirect to an unrelated human session. A read-only browser fixture on main reproduced the
redirect; the existing exact-event route preserved and focused the original Idea as a control.

Use the existing exact-source href helper for the row's known session and event IDs. Keep normal
session selection, sticky human mode, source filters and the default memory layer unchanged.
Canonical captures are not modified. No backend or live data/configuration change is needed.

## Verification plan

1. Add a real packaged captureIdea → My turns → keyboard source-link regression at desktop and
   narrow widths. Reproduce the redirect before changing the production link.
2. Reuse the existing exact-event href helper; update link expectations in the focused tests.
3. Verify exact source/reload visibility with the memory layer off, meaningful source filtering,
   no horizontal overflow and unchanged canonical evidence.
4. Run focused and full frontend tests, formatting/lint/types, the ordinary generated-assets build,
   and related packaged Ideas/human-turn/session-picker journeys using the guarded disposable DB.

## Results

Both new packaged regressions failed before the link change at their exact-session/event URL
assertion. The browser showed the unrelated human session after following the Idea-only owner
link with My turns on. The fixture used real POST /api/ideas and POST /api/events requests, no
mocked product responses. Pre-fix screenshots and the failure log were saved outside the repo.

Final verification:

- The focused IdeasPage and recall-helper suites passed all 20 tests.
- All 699 frontend tests across 60 files passed.
- `npm run check` passed lint, formatting and TypeScript with zero errors and 70 existing warnings.
  Touched frontend files were formatted with the configured Prettier. `git diff --check` passed.
- Seven packaged Chromium journeys passed together with zero retries: four Ideas tests (including
  the new 1440px and 390px paths), two human-only session-picker tests and the sticky human-turn
  stream test. The normal Maven frontend profile built the source and regenerated the JavaScript
  asset and static index. No generated asset was edited manually.
- The new journeys use real Idea capture and an unrelated human session. They verify keyboard
  source navigation, exact event/session routing, sticky My turns and reload, the memory layer
  remaining off, source filtering hiding/restoring the target, no horizontal overflow and an
  unchanged canonical event. Both saved screenshots were inspected; the selected Idea is visible
  and highlighted without test-side scrolling.

The guarded app ran only on port 8799 against disposable SQLite storage with providers disabled.
Its project fixture and database were removed and the port was released. The protected 8766
listener stayed unchanged; production database identity and synthetic-row checks were unavailable
and are not claimed. No live app/configuration, provider, Linear or Git mutation occurred.

## Handoff

Source-only changes are ready for coordinator review and Git finishing. Scope is the IdeasPage
link, its focused/packaged tests, this plan, the narrow continuity note and normally generated
assets. No backend behavior changed; the full backend and full browser suite were not rerun for
this UI-only slice. Coordinator owns fresh integration acceptance, commits and publication.
