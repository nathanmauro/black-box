# Give mobile Browse a usable reader

## Observed problem and boundary

The packaged baseline, using a synthetic prompt and exact Projection source, left 38px of visible
transcript at both 390×900 and 390×700. The session rail consumed 230px/188px and the selected-session
header another 262px; content overflow also moved the global header offscreen. The same fixture had
about 502px of visible transcript on 1440×900 desktop. Baseline screenshots/measurements were saved
as local verification artifacts before changing behavior.

Use the existing restrained utilitarian theme, typography and dependencies. Only mobile Browse
selection/details layout and exact-source positioning change. Preserve project scope, session and
transcript search, memory-layer behavior, pagination, canonical data, and desktop split-pane layout.
No live app, databases, provider calls, deployment or Git publication are part of the worker scope.

## Interaction

At the existing 880px narrow-layout breakpoint, Sessions is a disclosure that switches the available
workspace between the searchable chooser and selected reader. Selection returns to reading. Session
details separately reveals the existing first-turn, path, summary and dates. Source, title,
transcript search and memory toggle remain accessible with details closed. There is one copy of the
existing content, with closed regions hidden from keyboard and accessibility navigation.

Buttons expose expanded state and controlled region IDs. Escape closes the active disclosure and
returns focus to its button. Session selection focuses the reader heading. Resizing restores the
desktop panes and metadata; returning to mobile closes disclosures and recovers focus if a previously
focused control becomes hidden. Exact-source navigation scrolls the transcript container and focuses
its target, without scrolling the enclosing app out of view or requiring a test-side scroll.

## Verification

- Packaged pre-change baseline: three viewport measurements and screenshots captured before edits.
- Focused SessionsPage suite: 31 tests passed. Full frontend suite: 692 tests across 60 files passed.
- `npm run check` passed (zero errors; 70 existing warnings), including lint, formatting and TypeScript.
  Packaged verification rebuilt the generated frontend assets through the Maven frontend profile.
- Eleven packaged Chromium journeys passed with zero retries: the new reader at 1440×900, 390×900
  and 390×700, plus desktop/mobile lineage, Projection Browse, Projection recall and precise transcript
  source navigation. Captures used the real HTTP API against disposable SQLite; providers were disabled.
- The new journey verifies chooser selection/search, transcript search, keyboard disclosure/Escape
  focus, memory visibility, older-event pagination, mobile↔desktop resize and exact source bounds.
  No test-side scrolling is used to reveal exact sources. The existing mobile lineage journey now
  opens/closes the chooser before exercising its rail controls.
- A no-match chooser filter followed by resize was reproduced and corrected: mobile filtering does
  not change the selected reader, and resizing to desktop clears only a filter excluding that reader.
- Review exposed a stale mobile `height: auto` override on direct session routes. The new upper-bound
  regression measured a 4,864px document in a 700px viewport before removal. Final direct-route
  document heights equal their 700px/900px viewports, with transcript bottoms at 689px/889px and
  outer scroll at zero. Transcript content scrolls inside the pane.

| Viewport | Visible transcript before | Visible transcript after |
| --- | ---: | ---: |
| 390×900 | 38px | 362px |
| 390×700 | 38px | 162px |
| 1440×900 | 502px | 502px |

Final screenshots were inspected for both mobile heights, direct session routes, desktop and
mobile lineage. Mobile exact sources are visibly focused within the reader, outer scroll remains
zero and no horizontal document overflow is present. Desktop keeps its previous layout and exact
source scrolling behavior, including the observed 44px outer scroll in that fixture.

The isolated server and database were cleaned up; port 8799 was released. The protected port 8766
listener remained unchanged. The runner did not discover a production database identity from this
checkout, so production row-count preservation was unavailable and is not claimed. These are
Chromium viewport tests, not physical-device or other-browser certification. `git diff --check`
passed; no live app, provider, database, deployment or Git operation was performed.

## Handoff

Coordinator owns fresh review, commits as Nathan, integration and publication. Worker leaves only
owned source/tests/docs plus normally regenerated assets. Desktop layout and other responsive pages
are not redesign targets.
