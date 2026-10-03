# Recall return state

NAT-327 restores a completed Recall result when the reader follows a result into Browse and comes
back. Recall result links route to Browse, and Back remounted Recall with no result and no `run`
marker, so the reader lost the result. Backend, suggestions, launcher intent and visual design stay
unchanged.

## Contract

- Keep one completed full recall in module memory (`frontend/src/lib/recallMemory.ts`), keyed by
  the exact normalized snapshot the request used: legacy flag, project, trimmed question, window,
  kinds in order and history setting. Nothing is written to persistent, session or local storage, so
  a reload or new tab starts empty.
- When Recall mounts, or its URL changes in place, and no `run=1` is present, an exact key match
  shows the stored result without a request. A labeled status line reports when that run completed
  and that **Run recall** refreshes it. Any other snapshot stays empty.
- The entry is cleared when a run starts, so a failed or superseded run cannot leave an older result
  to come back later. Only a response whose request token is still current is stored. A filter edit
  clears it even when the edit is reverted, and a recorded replacement clears it even when the
  snapshot changed during the write or the reader left the page first. Suggestions never touch it.
- A replacement write that completes after Recall unmounts clears the stored result but does not
  start a refresh or rewrite the URL from the page the reader left.
- If an explicit refresh of a restored result fails, the restored label stays beside the error
  until a later run succeeds. That way the older result is never presented as fresh.
- The source filter still applies to the visible result.

I chose a single module-level entry over storing state in the router or history. History state
would survive reloads, and Recall does not own the router setup. A wider cache would allow growth
the contract forbids. One entry is bounded, has nothing to persist, and mirrors the last completed
result the reader saw.

## Results

- **Failing first (packaged path).** The new Playwright journey ran against a JAR packaged from the
  unchanged source on an isolated temporary database. It seeded a unique decision, ran recall once
  and followed the exact card into Browse, where the target row was highlighted. After Back it
  failed at `recall-return-state.spec.ts:42`: the URL and project were restored, the page showed the
  empty "Run a recall query" state, and the card was missing.
- **Failing first (unit).** Against the original `RecallPage.tsx`, 6 of the 9 new unit tests failed:
  exact restore, the mismatch matrix, legacy, failed refresh, replacement and the source filter. The
  three that already passed are the negative guards: launcher intent, late responses and filter
  edits.
- **Mutation checks.** Removing each clear point individually breaks exactly one test: replacement
  write, run start, filter edit, and the timing of the restored label.
- **Review finding: replacement after leaving the page.** In the first version, a replacement write
  that completed after unmount still compared the old component's unchanged snapshot, called
  `invalidate` and started `runRecall`. That sent a new request and called `setParams` from the page
  the reader had left, and the response could be stored. A regression test runs recall, starts a
  replacement, unmounts, then resolves the write. On the previous source it failed at its first
  post-unmount check: one new `getRecall` call. A disposal guard now returns after the global
  clear and before the local refresh. Removing the guard fails that check. Moving the guard ahead
  of the clear restores the replaced result on remount, failing the cache check. The earlier
  late-response test still shows that stale requests cannot be stored.
- **After the fix.** The packaged journey passes. Back shows the exact card with no new
  `/api/recall` request and the restored timestamp note. An explicit refresh sends exactly one
  request and removes the note. A reload stays empty. A kinds edit clears the result. The related
  packaged recall, continuity, evidence, observation, projection, handoff-precision and
  project-session journeys also passed: 15 of 15.
- Frontend: 812 tests in 62 files pass after the disposal guard (811 before it). Lint shows the existing 68 warnings with no exemption
  changes, and format check and TypeScript pass. The normal `npm run build` regenerated the static
  assets, and a clean packaged Maven build on Java 21 contains the same `index.html` and bundle. The
packaged browser journeys above ran before the disposal guard. The coordinator reruns them on the
final source, because the shared fixture was reserved when the guard landed.
- The isolated harness released port 8799 and removed its temporary directory and project fixture.
  Protected port 8766 kept its existing listener. The harness could not discover the
  production database identity, so this run makes no claim about it.

## Limits

The existing E2E harness accepts only port 8799; the requested alternative port was refused by its
guards, which are outside this change. A recall that is still in flight when the reader leaves is
not kept. A failed refresh still leaves the earlier result visible beside the error, as it did
before, now with its restored label. Fixture-only work: no installed service, live database,
provider or Git changes. The coordinator owns review, commit, CI, publication and integration.

## Independent acceptance after integration

The coordinator combined the final disposal guard with the merged companion recall form and
regenerated the static bundle through the normal frontend build. All 822 frontend tests passed;
lint retained the existing 68 warnings with no errors, and formatting and TypeScript checks passed.
A clean Java 21 package passed, followed by all 19 real browser journeys covering recall return
state, continuity, evidence, observations, projections, selection, handoff precision, project
sessions and the companion. The isolated harness removed its temporary storage and project and
left the installed service listener unchanged. This supersedes the earlier 15-journey run above.
