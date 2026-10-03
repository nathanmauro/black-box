# Companion Readiness and Retry Recovery

Scope: `companion/macos` shell only (AppDelegate navigation/readiness/retry wiring, its tests) and
`docs/companion.md`. Out of scope: the frontend page, static assets, the backend, `LoadRetryPolicy`
delays, and any live service on port 8766.

## Findings (verified against `AppDelegate.swift` at `01218233`)

1. `didFinish` called `scheduleReadinessCheck()`, which called `readiness.loadStarted()`. A page
   that sends its bridge `state` message before `didFinish` (inline script, slow image or font
   holding back the load event) had that proof erased. A quiet healthy page sends no further
   `state`, so the 10s readiness check failed it and the shell reloaded it about every 12s.
2. `didFinish` reset `retryAttempt` to 0. A 2xx main-frame response whose JS never runs (broken
   bundle, blank proxy page) finishes, fails readiness, retries at 2s, finishes again, resets,
   and so on: blank-page retries never backed off past 2s.

## Design

- Readiness resets at `didCommit` for the current navigation, not at `didFinish`. Commit is when
  the new document replaces the old one. Resetting at `didStartProvisionalNavigation` would let the
  still-live old document's `state` messages count as proof for the new load. A non-2xx retry is
  rejected before commit, so it never resets readiness.
- `didFinish` schedules the readiness check only while the current load is still stale, and no
  longer touches `retryAttempt` or a pending retry.
- `retryAttempt` resets, and any pending retry is cancelled, only on the stale-to-ready
  transition: the first bridge `state` for a committed load. Repeated `state` messages from an already-ready page (including an old page kept
  alive after a rejected retry) do not reset backoff.
- Navigation callbacks (`didCommit`, `didFinish`, `didFail`, `didFailProvisionalNavigation`) for
  a navigation other than the most recently started one are ignored, so a late callback from a
  superseded load cannot reset readiness or schedule a retry.
- Unchanged: non-2xx rejection and its duplicate-callback suppression, `NSURLErrorCancelled`
  ignoring, WebContent termination handling, the manual Reload item, and `LoadRetryPolicy`.

Test seam: `ShellEnvironment` gains `schedule` (the timer used for retries and the readiness
check, default `DispatchQueue.main.asyncAfter`) and `onRetryScheduled`. The live shell is
unchanged; the test compresses time and records requested retry delays.

## Tasks

1. Add the seam with no behavior change. Write `ShellRecoveryTests`: real `AppDelegate`, real
   `WKWebView`, non-persistent website store, in-memory size store, no panel autosave, and a
   test-owned `NWListener` on an ephemeral loopback port (never 8766 or 8799). Run it against the
   old logic to reproduce both findings.
2. Apply the design above; rerun to green.
3. Run `swift test` and `swift build`; update `docs/companion.md`; `git diff --check`.

## Observed results

- Reproduction (seam only, old logic): `testStateBeforeDidFinish…` retried `[2]` with two
  `/companion` requests; `testBlankPages…` retried `[2, 2, 2, 2]` instead of `[2, 4, 8, 2]`;
  `testManualReload…` failed because its inline-state page was reloaded every readiness cycle
  (finding 1). The termination test passed on the old logic.
- First fix pass: the termination test failed 4 of 15 stress runs. A termination landing while the
  recovered page was still loading scheduled a retry, and that load's `didFinish` then cancelled
  it, leaving the menubar disconnected with nothing pending. The old code had the same cancel but
  masked it by always re-arming (and failing) the readiness check. Fix: `didFinish` no longer
  cancels a pending retry; the first `state` proof does. The test now forces that ordering
  deterministically (slower clock, 0.3s image) and failed before the fix.
- Final: `swift test` 69 tests, 0 failures; `swift build` clean; `ShellRecoveryTests` 10/10
  repeated runs green.
- Not covered: a real WebContent kill (the test calls the delegate callback directly), the live
  `ShellEnvironment.live()` launch (it writes real UserDefaults and panel autosave), and
  `--click-through` (needs an isolated seeded Black Box). `--self-test` uses its own readiness
  wiring and does not exercise this path.

## Coordinator acceptance

Integrated current main without modifying the four companion-owned paths. Independent source review
accepted the readiness/backoff behavior and requested one test improvement: Reload now waits for the
first slow request to be in flight instead of waiting a fixed duration. All 69 Swift tests passed
after that correction; `swift build` also passed.

The real shell's `--click-through` completed against a freshly packaged Black Box with disposable
SQLite storage and a seeded fixture project on port 8799. Mini, compact and expanded modes, matching
panel/bridge sizes, live/unseen menubar state, exact source deep links and two Escape transitions all
passed. The external link was recorded without opening it; programmatic resize did not alter manual
size memory. The run used the existing throwaway store/in-memory settings path, and its screenshots
were inspected. The fixture server stopped, its owned data was removed, and the existing port 8766
listener remained unchanged. No installed companion, real settings, live database or deployment was
modified. Actual WebContent process killing remains untested; recovery uses the delegate callback.
