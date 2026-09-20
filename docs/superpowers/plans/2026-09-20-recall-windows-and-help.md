# Longer Recall windows and contextual guidance

## Scope and frozen acceptance

Add Three months and Six months alongside the existing Recall windows. Show explicitly that these
mean rolling 90 and 180 days. Add click/keyboard help with practical scope, window, and filter
examples while retaining the current visual design and every captured text field.

Acceptance:

1. The controls submit 2160 and 4320 hours with the chosen scope and kinds unchanged.
2. Real isolated application use finds a 60-day capture at 90 days, a 120-day capture only at
   180 days, and excludes a capture older than 180 days. Existing 30-day behavior still works.
3. Help opens with click, Enter, and Space; Escape closes it and preserves focus. Help never
   submits recall. Controls and open help remain usable at desktop and a narrow viewport.
4. Examples match the backend: lexical path/ID matching, name/text matching, optional semantic
   topics/paraphrases, time/kind limits, blank scope, and no combined repo-plus-topic syntax.
5. Frontend regressions/build and whitespace checks pass. Build assets are regenerated, never
   hand-edited. No live deployment, client configuration, or canonical data changes occur.

## Baseline and implementation

The existing packaged Recall page was opened in an isolated Java/SQLite preview before editing.
It exposed only 24h, 1w, and 30d, with no contextual help controls.

`ContextService` already clamps to 365 days; no backend change is needed. SQL recall matches event
IDs, session working directories, and event text lexically. Topic retrieval can add semantic
matches, with scope anchoring and lexical fallback. This is not exact project filtering or a
combined location/question query.

The change adds two radio options and native details/summary help disclosures with Escape support.
CSS retains the existing palette/chips and wraps the larger control set at smaller viewports.

## Verification

- Focused Recall tests: 6 passed, including both submitted hour values and help dismissal/focus.
- Full frontend suite: 610 tests in 49 files passed. Type checking, Vite build, and Maven packaging
  passed. The static bundle was regenerated through `npm run build`.
- An isolated Java server with a temporary SQLite database and disabled external models/search
  accepted synthetic captures aged 20, 60, 120, and 200 days. Actual HTTP and browser queries for
  30, 90, and 180 days returned 1, 2, and 3 items respectively; the 200-day item stayed excluded.
- Actual Chrome use verified click, Enter, and Space disclosure controls; Escape closed help and
  returned focus without submitting or changing results. Captured angle-bracket text remained
  literal in the result cards.
- Independent review found an intermediate-width media rule before the base rule. It was moved
  after the base styles and the assets were rebuilt. The corrected preview showed two columns at
  1027 CSS pixels and one column at 390 CSS pixels; controls/help stayed in bounds, with document
  scroll width equal to viewport width. Desktop width was restored after verification.
- No backend code, production data, installed client configuration, or live deployment changed.
  The coordinator owns final review, Git, publication, and disposition of the isolated preview.

## CI quota-test correction

The first CI run passed frontend checks but exposed an existing scheduling assumption in the
durable-hook quota test. Four contenders can legitimately reach the bounded SQLite busy timeout
while the final enqueue fills the last available slot; no contender must report `queue_full` in
that schedule. A real disposable CLI/SQLite reproduction reached exactly 10,000 rows with four
`operation_deferred` responses and one successful enqueue, reproducing the old assertion failure.

The test still exercises concurrent enqueue and verifies the exact capacity. It now checks the
full-queue rejection after contention, also asserting the row count remains unchanged. Product
code, queue limits and bounded timeout behavior are unchanged.
