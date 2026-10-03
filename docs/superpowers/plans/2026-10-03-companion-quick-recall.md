# Companion Quick Recall (NAT326)

## Slice

Add one recall form to the companion's expanded level. Submitting it opens the existing Recall page
in a new browsing context, scoped to the open project, with the trimmed question and `run=1`, so
RecallPage performs its one launcher-driven recall. The embedded panel stays on `/companion`.

Out of scope: suggestions or autofetch in the companion, a global hotkey, provider calls, any
backend/API/storage change, the compact and mini levels, the retired untracked `BlackBoxRecall`
helper, and changes to the shell's http/https link policy.

## Observed contracts

- RecallPage reads `project`, `query`, and `run` from the URL. `run=1` runs recall once, then the
  page rewrites its URL without `run`. Its project picker stores
  `primaryProjectScope(project).canonicalKey`, so that is the value the form sends.
- Companion project cards are keyed by `ProjectSummary.projectKey`, resolved from the catalog the
  store already loads. The store maps the open project view back through `findProjectByIdentifier`
  and `primaryProjectScope`. River and `Unassigned` views omit `project` (all projects), matching
  how `eventHref` omits the scope for unassigned rows.
- The macOS shell routes `target=_blank` and `window.open` through
  `WKUIDelegate.createWebViewWith`, which hands only http/https URLs to `openExternal` and returns
  no web view, so the panel never navigates.
- Page-level Escape is a `window` keydown listener that calls `stepDown`.

## Form and navigation path

1. `ExpandedView` renders `<form role="search">` between the header/strip and the item list: a
   visually hidden `<label>` naming the scope ("Recall in <project>" or "Recall across projects"),
   a text input, and a `Recall` submit button that is disabled until the trimmed text has at least
   two characters.
2. `onSubmit` always calls `preventDefault`; when the trimmed text is long enough it calls
   `window.open(recallHref(text, project), "_blank", "noopener,noreferrer")`.
3. `recallHref` in `lib/companion/links.ts` builds `/recall?project=…&query=…&run=1` with
   `URLSearchParams` (omitting `project` when null).
4. Escape in the input clears non-empty text, or moves focus to the Back button when empty, and
   calls `preventDefault`. CompanionPage's window listener ignores an Escape whose default was
   prevented, so editing never also steps down a level.

## Verification

1. Vitest: `recallHref` encoding (`+`, `&`, `#`, spaces, Unicode, project omission); store scope
   mapping (canonical key, river and unassigned omission); ExpandedView submit, guard, Enter, button
   label, field label, and Escape (clears, then focuses Back, never reaches the page listener);
   CompanionPage Escape still steps down outside the field.
2. Playwright on the isolated packaged fixture (port 8799, private SQLite, providers disabled):
   seed a distinctive decision, expand to the project, submit a punctuated Unicode question, catch
   the popup, assert the exact URL, one automatic `/api/recall` request with the project and query,
   and the seeded evidence; river omits `project`; Escape in the field keeps the expanded level.
3. Swift `--click-through`: type into the field and submit from inside the shell, assert
   `openedLinks` receives the exact recall URL while the panel stays on `/companion`, and that
   Escape in the field does not step down.

## Results

- Observed: fixture card keys are opaque (`L3RtcC9ibGFjay1ib3gtZTJl`) while the Recall page's project
  value is the canonical path (`/tmp/black-box-e2e`), so the store mapping is required, not cosmetic.
- Vitest: links, ExpandedView, store, and CompanionPage cover encoding, scope mapping, the
  two-character guard, form/label semantics, and Escape. Removing CompanionPage's
  `defaultPrevented` check makes the page test fail. Full suite, lint (0 errors, the existing 68
  warnings), Prettier, and `tsc --noEmit` pass.
- Playwright (`tests/e2e/companion.spec.ts`, isolated 8799 fixture): exact popup URL, one automatic
  `/api/recall` with the canonical project and Unicode question, the seeded decision in the
  popup, river omission, Escape in the field keeps the expanded level, and the embedded form fits a
  300px panel. The non-embedded route keeps its existing 320px body minimum.
- Swift `--click-through` against the same fixture: typed `" C++ & #recall café ✓ "` + Return; the
  shell recorded
  `/recall?project=%2Ftmp%2Fblack-box-e2e&query=C%2B%2B+%26+%23recall+caf%C3%A9+%E2%9C%93&run=1`
  with the panel still on `/companion`; both field Escapes held the expanded level. Key events
  must be paced: WebKit drops back-to-back synthetic keystrokes, so the probe waits for each one.
- Limits: the app has no light theme, so only the dark tokens were checked. The probe records the
  URL instead of opening a browser, so the real `NSWorkspace.open` call was not exercised.

## Independent acceptance

Root reviewed the frozen frontend and native diffs independently. The frontend suite passed all
812 tests; Swift passed all 72 tests. Lint (68 existing warnings, no errors), formatting, TypeScript
and diff checks passed. All 11 regenerated static files were byte-identical to the reviewed build
and matched the packaged JAR. Integration with main after PR #123 preserved every owned source
and generated file.

The four packaged Chromium companion journeys passed again on an isolated SQLite fixture. Root
inspected the expanded and narrow screenshots, including containment at 300px. The native panel
probe then independently typed the punctuated Unicode question, submitted with Return, and verified
the exact canonical-project Recall URL, retained panel, and both field Escape transitions. The
external-open call was recorded rather than launching the system browser; the separate Chromium
journey verified the actual popup and seeded result.

Native acceptance used prebuilt artifacts and a private driver that verified port, process, run-token
and database ownership before seeding. The native process exited normally; the owned fixture server
required the bounded SIGKILL fallback after its shutdown grace period, then was reaped before
private storage and the owned project symlink were removed. Port 8766 retained its existing listener.
The private driver did not inspect the installed database.

Closing a browser popup during a static download also reproduced an existing server-side
client-disconnect error cascade. It is tracked separately as NAT-328; these UI changes do not
change the API exception handler. No installed app, global shortcut or provider configuration changed.
