# Carry forward the remaining Stream control fixes

## Scope and reproduction

PR44 never reached main. Its remaining source fixes were adapted to current My turns, project
scope and exact-event navigation; its obsolete Browse toggle change and old bundles were not
copied. The current packaged app at 390px reproduced a grey native Filter button, run actions
extending to about 599px, overlapping Views/Options panels, and missing Escape/outside dismissal.
Six new component regressions also failed before the source change.

This slice owns Stream submit styling and narrow containment; Views/Options exclusivity and
keyboard/pointer dismissal; Sources dismissal; ordinary palette excerpts/spacing; and accurate
empty results. Recent mobile Browse layout is preserved. The first viewport pass exposed two
additional failures in the carried CSS: narrow dropdowns extended left of the viewport and a
320px query input's intrinsic minimum width clipped Filter. Scoped panel anchoring and input
shrinking fix those measured cases. Session headers wrap when needed, preserving access to actions.

## Behavior and limits

Escape closes the active disclosure and restores its trigger's focus; outside pointer presses
close without stealing focus. Views and Options share one slot. Sources has the same dismissal
behavior within the shell. Clicking inside a panel leaves it open. A fresh review exposed a
keyboard nesting conflict: opening a panel and tabbing back to the query allowed one Escape to
close both the suggestions and the panel, stealing query focus. The actual packaged keyboard
path and both Stream panel component cases reproduced this. Visible suggestions now consume
only the first Escape; a second plain Escape still closes the open panel and restores its trigger.

Ordinary command-palette event labels keep a single-line excerpt of at most 120 UTF-16 code units,
including the ellipsis, without splitting surrogate pairs. My turns retains its existing cleaned
human-text lead. Searching and exact-source links continue to use canonical data.

Empty results distinguish no recorded events, meaningful-only results, My turns, and active
project/query/source filters. Pending session queries show loading copy. Failed Stream requests
keep their error and do not also claim the recorder is empty. No storage/API/provider behavior,
project ownership, or installed application was changed.

## Verification

- Full frontend suite: **720 tests across 60 files passed**. Lint: zero errors and the same 68
  pre-existing warnings; Prettier and TypeScript checks passed. No lint exemption was added.
- **25 packaged browser journeys passed together, zero retries**: nine new control/empty-state
  cases plus existing Stream, My turns, human-only session picker and mobile header cases.
- Escape regression: both new component cases and the packaged desktop keyboard path failed
  before the guard. Desktop and narrow browser cases now use Tab/Shift+Tab to open each of Views,
  Options and Sources, return to the query, and verify first-Escape query focus/panel retention and
  second-Escape panel dismissal/trigger focus. All 92 focused Stream/shell component tests pass.
- New populated journeys use real HTTP capture/query, keyboard submission, panel interactions,
  command-palette selection and exact-event Browse navigation. Empty/error API responses are
  explicitly intercepted so the shared synthetic seed is not deleted.
- Viewports: 1440×900, 390×844, 320×700, 844×390 and 667×375. Assertions cover primary-button
  appearance, control and open-panel horizontal containment, focus, dismissal, query URL state,
  project picker bounds and exact-source target visibility. Before/after screenshots were inspected.
- Packaged E2E builds regenerated static assets through the normal Maven frontend profile.
  `git diff --check` passed. No full backend test suite was needed for this UI-only slice.
- The isolated 8799 server, synthetic project fixture and temporary database were cleaned up;
  the protected 8766 listener was unchanged. The harness could not discover its database identity,
  so this verification makes no production-database identity assertion.

Source-only handoff on `codex/stream-control-polish`, based on main `01218233`. Root owns fresh
review, Git, PR and integration. No worker commit, push, deployment or external issue mutation.
