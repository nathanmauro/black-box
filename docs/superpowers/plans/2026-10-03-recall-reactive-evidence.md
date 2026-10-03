# Reactive lists in selected Recall evidence

## Scope

Keep the selected evidence card's alternatives and open loops synchronized when a user inspects
another retrieved suggestion without editing the question or closing the card. Preserve existing
headings, source links, project scope, and canonical capture data. No retrieval or write semantics
change; no provider or live database is used.

## Reproduction and acceptance

The selected-evidence card is retained between suggestions. `RecallList` currently returns once
when its initial items are empty, so selecting a later capture with populated lists silently hides
those lists. Starting populated and selecting an empty capture leaves empty list headings.

1. Record two synthetic Decisions through the actual HTTP API: A has no alternatives/open loops;
   B has both. Confirm scoped Recall returns both with B's arrays intact.
2. In the packaged UI, enter one question, select A, refocus the unchanged question, select B, and
   select A again. Verify the title, rationale, exact source, headings and list contents each time.
3. Replace the one-time early return with reactive visibility. Exercise both initial states in
   component tests and the keyboard suggestion path at desktop and narrow widths.
4. Run scoped formatting, focused/full frontend tests and checks, the ordinary frontend build, and
   relevant packaged journeys against the owned disposable SQLite fixture.

## Results

- Before the fix, both focused initial-state regressions failed: empty-to-populated hid the lists;
  populated-to-empty retained empty headings. The actual packaged HTTP/browser journey failed on
  missing alternatives at both 1440 × 1000 and 390 × 844 despite the API returning both stored arrays.
- Replaced only the list's one-time early return with Solid's reactive `Show`. Headings, list markup,
  source links, retained card identity, and capture data are unchanged.
- After the fix, all 22 RecallPage tests and all 709 frontend tests across 60 files passed.
  Scoped Prettier, lint, formatting and TypeScript checks passed. The two warnings on this code are
  resolved; 68 existing frontend warnings remain, with no rule exemptions.
- The ordinary Maven frontend/package build regenerated the static assets. Eight packaged Chromium
  journeys passed with zero retries: the new selection path at both widths, project continuity and
  decision history at both widths, empty legacy recall, precise handoff selection at both widths,
  and cross-project replacement sessions. The new path uses actual Decision capture and scoped
  Recall APIs, keyboard suggestion activation, unchanged-query selection A → B → A, exact source
  checks, canonical event equality before/after, and no horizontal overflow. Both evidence-card
  screenshots were inspected: all text and list content fit at each width.
- The runner removed its disposable SQLite database and project fixture and released port 8799.
  Its protected port 8766 listener identity was unchanged. Production database identity was not
  discoverable by this gate, so database identity preservation is not independently claimed.

The coordinator owns final Git review, integration and publication. No live service, installed
configuration, provider, or canonical production data was changed. No full backend suite was run
for this frontend-only change. The current diff and the verification above form the handoff;
next step is the coordinator's integration and publication gate.
