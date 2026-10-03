# Preserve timestamp precision in the latest retrieved handoff

## Reproduction and contract

A rendered Recall fixture returned two handoffs in relevance order: the older `.123456788Z` first,
then the newer `.123456789Z`. `Date.parse` collapses both to the same millisecond; the briefing
incorrectly labeled and linked the older capture as **Latest retrieved handoff** while both cards
were visible. This is independent of backend chronological ordering: semantic recall legitimately
ranks by relevance.

Compare canonical UTC instants without losing fractional digits. Retain missing/invalid legacy
fallback behavior, exclusion of replaced items, source links and exact timestamp display. Only
actual equal instants use the event ID as a deterministic descending tie-break. At identical
effective time, canonical evidence precedes fallback-only values (including missing/invalid values
mapped to epoch); fallback-only ties retain input order. This prevents an intervening fallback value
from making equal canonical timestamp ID order inconsistent. Other timeline
comparators and elapsed-time labels are outside this slice.

## Implementation and verification

- Add a small canonical UTC comparator with calendar/year validation and nine-digit fractional
  normalization, covering the Java Instant range without `Date`'s year/millisecond limits.
- Apply only to `newestRecorded`; preserve its existing fallback for noncanonical values.
- Add helper/selection/component tests and a packaged browser journey: real synthetic HTTP captures
  and recall, fixture ordering of those same returned items, latest evidence navigation and copy.
- Run targeted tests, frontend checks/full suite/build, and desktop/narrow browser paths. The E2E
  runtime owns only its disposable SQLite database and loopback port, with providers disabled.

Initial targeted helper/selection/component tests: 33 passed. Review identified a nontransitive
mixed epoch/fallback tie; the canonical-first policy and all permutations of that case now pass.
Final full frontend suite: 680 passed in 60 files, including 34 helper/selection/component cases.
Frontend lint, formatting and TypeScript checks passed (the existing 70 lint warnings remain;
zero errors).

The packaged Playwright journey passed at both 1440px and 390px, with no retry. Each journey POSTs
two real synthetic Handoff captures, fetches their actual recall response, and intercepts only the
response order to put the older evidence first. It verifies the newer briefing link, full timestamps
and both provenance links in clipboard context, keyboard Enter navigation to the exact newer event,
unchanged event reads, and no horizontal overflow. No ranking provider is invoked or evaluated.
Screenshots were inspected at both widths. The final packaged build regenerated static assets;
Markdown file links and `git diff --check` passed. The owned SQLite fixture was removed and the
loopback 8799 listener stopped. The existing protected service listener was unchanged; its database
was not discovered by this checkout, so the runner made no production-row-content assertion.

Coordinator owns review, Git integration, publication and merge. No live data,
service configuration, provider calls, or other frontend chronological behavior is changed.
