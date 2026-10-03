# Browse transcript ordering at full captured precision

## Contract

Keep an exact source answer attached to the correct preceding prompt when captured timestamps
share a millisecond. Only the frontend merge comparison changes. Preserve canonical payloads,
recorded-event duplicate precedence, semantic duplicate windows, and source/index precedence for
truly equal instants. Keep Date-parseable legacy timestamps and invalid/missing timestamps last.
No providers, live databases, service changes, or backend timestamp changes are part of this slice.

## Reproduction and change

The actual SessionsPage component regression starts with a newest-first 50-event page: a new
prompt and 49 assistant replies. An older exact-source answer outside that page is one nanosecond
before the new prompt. Previously Date.parse collapsed these times, the merge favored the separately
fetched answer, and Browse placed it last under the wrong prompt. The regression failed before the
production change (`prompt-turn--preamble` absent).

Use the shared canonical Instant comparator for merge sort values, retaining all nine fractional
digits and the full signed year range. Normalize finite legacy Date fallbacks to canonical
millisecond values before sorting so mixed values remain transitive; invalid values remain last.
Keep elapsed-time and semantic duplicate calculations unchanged. No event-ID tie replaces the
existing recorded-origin and input-order ties.

## Verification

- Focused merge and SessionsPage tests: 37 passed after the fix, including exact-target hydration,
  pagination reattachment without duplicating the target, true timestamp ties, extended years,
  invalid/legacy timestamps, and the unchanged semantic duplicate window.
- Packaged browser journey seeds 52 synthetic events through actual HTTP with all providers disabled;
  it proves the target is outside the first 50, opens the exact event, checks chronological DOM
  order and highlighting, then loads its older prompt with the keyboard and checks attachment,
  cursor request, uniqueness, canonical timestamp preservation, and viewport bounds at 1440/390px.
- Full frontend suite: 686 tests across 60 files passed. After a test-only missing-property typing
  correction, the 37 focused cases passed again. Frontend lint/format/type checks passed with
  the existing 70 lint warnings and no errors.
- Packaged Chromium journey: both widths passed with zero retries; final run took 27.0 seconds.
  The runner rebuilt generated assets through the standard Maven frontend profile. No API
  responses were mocked or reordered. All source data came from the isolated canonical database.
- Desktop and narrow screenshots reviewed. Exact-source highlighting and correct turn attachment
  are verified; the test explicitly scrolls the target into the viewport before screenshots.
  At 390px the existing stacked header/session rail leaves limited vertical reader space; this
  change makes no layout or automatic-scroll claim.
- The protected port 8766 listener PID was unchanged. The runner could not discover its database
  identity from this checkout, so production row identity was not asserted. The owned fixture
  directory was removed, and port 8799 had no listener after the run.
- Final whitespace/diff checks passed; relative documentation links resolve.

## Handoff

Source-only worker checkout; coordinator owns review, commits, PR and merge. Verification uses
owned temporary fixture storage only. No live state was changed. Generated assets must come from
the normal frontend build, and no canonical capture migration is required.
