# macOS Quick Recall

Add a native menu bar search and Control–Option–B shortcut that open Structured Recall results in
the installed Black Box Chrome app. Keep the current recall defaults (one week; decisions and
handoffs), with further filters available on the results page.

## Completion contract

- A dependency-free AppKit helper presents a focused search field from either entry point.
- Enter opens an encoded `/recall?scope=…&run=1` URL; Escape dismisses the popover.
- Typing at least two characters shows up to five existing recall matches after a short debounce;
  clicking a match opens its exact event. Standard clipboard shortcuts work.
- The installed Chrome web app is preferred, with regular Chrome as a fallback.
- Explicit `run=1` links execute recall; ordinary scope links retain their current prefill behavior.
- Installation is explicit and limited to this helper and its login launch agent. No server,
  database, runner, browser-profile, or global keyboard permission changes are made by the helper.
- Preserve unrelated dirty work. Coordinator integrates isolated worker output and owns live
  verification, installation, and any Git checkpoint. No branch publication is authorized.

## Verification

1. Cover explicit autorun, prefill-only links, query-only navigation, and stale request protection.
2. Compile and test native URL encoding and installation dry run.
3. Exercise the deep link through a real browser with controlled API fixtures.
4. Install and exercise the menu item, global shortcut, Return, Escape, and visible results in the
   actual Black Box Chrome app.
5. Record exact deployment effects, checks, and remaining limitations here and in the handoff.

## Deployment constraint

The running service reads an existing JAR from `target/`. Do not package over it. Compare its
frontend with the tracked assets before deciding on a minimal frontend deployment; preserve its
backend byte-for-byte and use the existing managed prebuilt deployment/rollback procedure if a
frontend-only candidate is necessary. Do not restart the runner.

## Recorded results

- Added the native helper, explicit build/install scripts, URL fixtures, and usage documentation.
- Added opt-in `run=1` recall links, preserving ordinary prefill links and stale-request protection.
- Current checkout: 49 frontend test files / 612 tests passed; TypeScript and production build passed.
- Native URL fixtures, signed compilation, installer dry run, and shell syntax passed. The binary
  explicitly targets macOS 13; its Mach-O minimum version was checked after review.
- The deployed frontend was newer than this branch: its JS/CSS matched commit
  `4c6a9e2a144b2644b3a1ae99321f7841fc552da7`. Rebuilt that exact source in isolation and verified all
  original static entries byte-for-byte before applying only the recall-link source patch.
- The resulting isolated candidate passed the quick-search Playwright story: automatic query,
  rendered result, and navigation to the owning session/exact event. Temporary DB cleanup passed;
  the production listener remained unchanged during this test.
- Deployed this frontend-only candidate with the managed prebuilt/rollback procedure. All 827
  non-frontend archive entries were byte-identical to the prior artifact. The server was restarted
  by that deployment; its existing configuration was preserved and health/read-only recall passed.
- Installed the helper and its own login launch agent; launchd reports it running.
- After unlocking, reproduced a native handoff defect: opening the Chrome app shim discarded the
  supplied search URL. Fixed by launching Chrome with the discovered app ID, registered profile,
  and explicit launch URL. Also fixed focus/reopen, Return handling, standard clipboard shortcuts,
  and installer startup verification/manual-instance protection.
- Added five debounced preview rows from the existing recall endpoint, cancellation and stale
  response suppression, exact-event links, readable loading/empty/error states, and a narrow local
  networking transport exception. Native fixtures, signed build and independent code review pass.
- Live desktop checks now pass: Control–Option–B from Finder focuses the field; typing displays
  five real matches; clicking a preview opens its owning session/exact event in the Chrome app;
  Command–A and Unicode paste preserve accented text and punctuation; Return opens an encoded
  full-results URL; Escape/reopen works. Quit stays stopped, and reinstalling over a manually
  launched helper is refused before its binary is overwritten. The updated launch agent starts
  the installed helper successfully.
- Changes remain local on the existing branch; no commit, push, merge, or PR was performed.

## Jev follow-up explored, not implemented

The earlier proposal was retrieval-first autocomplete: retrieve real evidence candidates, then
use Jev to rank grounded suggestions. Existing Jev ingestion judgments do not implement recall
autocomplete. The dropdown now implements the grounded candidate display using current recall
ranking, without a new Jev call. A separate follow-up could rerank those candidates with Jev while
keeping normal Enter-to-recall immediate and falling back to current ranking. That still requires
a decision about sending candidate evidence to the configured provider; no new provider calls
were introduced here.
