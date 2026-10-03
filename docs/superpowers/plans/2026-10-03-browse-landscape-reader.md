# Browse at compact landscape heights

NAT-291 follows the synthetic mobile reader measurement: at 844×390, stacked Activity/session
controls left approximately 16px of visible transcript. The packaged regression with memory
controls present reproduced a stronger case: its 38px pane had zero visible height. Production CSS
was changed only after that failing assertion and screenshot.

## Contract and implementation

- The 600–880px-wide, at-most-500px-tall layout spends width on the two existing disclosure
  buttons and a vertical, scrollable turn rail. Header spacing shrinks and the search label sits
  beside its input. No controls are removed; source, title, project, status and memory stay present.
- Browse uses one utility row at these dimensions. The inherited two-row utility header left
  only 107px of reading height at 667×375; keeping its non-overlapping actions together recovers
  another 36px. Portrait and larger desktop layouts keep their existing rules.
- Existing disclosure state, keyboard focus/Escape, session/project selection, transcript search,
  memory toggle, pagination and exact-source reveal remain in the existing components. No state,
  data, API, theme, typography or dependency change was needed.
- The tradeoff is 112px of landscape width for reachable session controls. Details can still be
  opened and scrolled; closing them restores reading space. The transcript owns its scrolling.

## Verification

Final verification after the fixture timing correction below:

- `npm run check`: formatting, TypeScript and lint passed (zero errors, 70 existing warnings).
- All 692 frontend unit tests across 60 files passed.
- All 60 packaged Chromium journeys passed together with one worker and zero retries. This includes
  all six reader viewports and the unchanged global header, capture/recall, source and navigation
  journeys. The normal Maven frontend package regenerated the static CSS/JavaScript and index.
- Screenshots were inspected for 844×390, 667×375, 320×700, 390×700 and desktop; the failing
  pre-change 844×390 screenshot was preserved for review. `git diff --check` and new doc links passed.
- The runner removed its synthetic project and temporary database, and port 8799 was released.
  Its protected 8766 listener check remained unchanged; production DB identity/counts were unavailable.

Measured visible transcript with details closed and a memory toggle present:

| Viewport | Visible transcript |
| --- | ---: |
| 844×390 | 158px (baseline: 0px) |
| 667×375 | 143px |
| 320×700 | 162px |
| 390×700 | 162px |
| 390×900 | 362px |
| 1440×900 | 502px |

The extended packaged journey retains default session selection, chooser filtering/selection,
keyboard disclosures and Escape, transcript search, memory visibility, older-page loading, exact
older-source focus without test-side scrolling, direct routes and responsive transitions. Short
viewports additionally verify keyboard scrolling through twelve prompt turns, full target bounds,
long project scope/selection, reachable source and command controls, and utility non-overlap.
Strict geometry waits for the application's smooth source reveal to settle; no test scrolling is
used to place that target. Mobile documents remain bounded with no horizontal overflow.

An independent Linux CI trace exposed an inherited fixture-clock assumption: `now + 1000` was
sampled before 52 HTTP captures, while the later Projection used server time. On a slower run the
primary session correctly became newer than the intended secondary session. The fixture now reads
primary `lastSeenAt` after Projection, places secondary one second after that persisted timestamp,
and asserts the actual sessions API returns secondary first before exercising default UI selection.
The selection assertion remains; no sleeps, retries or product ordering changes mask the failure.

## Handoff and limits

Only responsive CSS, the existing packaged reader test, frontend standards, this plan and normally
regenerated static assets are owned by this slice. Coordinator owns fresh review, integration with
current main, normal asset regeneration where necessary, commits as Nathan, PR and merge. Worker
made no Git writes, publication, provider calls or live storage/configuration changes.

Verification uses synthetic captures and owned temporary SQLite storage on the guarded loopback
fixture. No physical device or non-Chromium browser is claimed. Backend behavior is unchanged, so
no unrelated backend unit suite is required. The protected service listener check does not establish
production database identity or row counts when the runner cannot discover them.
