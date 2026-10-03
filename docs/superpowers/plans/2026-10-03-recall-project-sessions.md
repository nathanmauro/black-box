# Keep manual replacement sessions scoped to their target repo

Status: implemented and verified in the isolated checkout; coordinator review/integration pending.

## Reproduction

Recall previously created one client session ID for its whole mounted page. Replacing an alpha
project decision and then a beta decision reused that ID. The existing backend session upsert
moved its cwd to beta, so Projects attributed both replacements to beta even though event metadata
and Recall retained alpha's repo. A disposable packaged API/SQLite fixture confirmed alpha had one
event in Projects while beta had three; alpha Recall still returned its replacement.

## Scope and contract

Keep a generated replacement session ID per exact target item's repo during a page visit. Reuse it
when returning to that repo, including with All projects selected. Keep source=manual, capture
provenance, explicit rationale, replacement/history semantics, and existing stale-response behavior.
Do not change backend session identity, rewrite existing sessions, or touch live history.

## Verification plan

The original code fails two focused UI regressions: alpha → beta → alpha replacement writes must
reuse alpha's session ID and use a different beta ID, both in All projects and while changing project
filters. Run the focused/full frontend tests, checks and build. A packaged E2E journey creates real
originals, submits all three replacements through one mounted Recall page, verifies actual session
IDs and per-project counts/session lists/history, resumes alpha and follows its replacement into
Browse. Use only the runner's disposable storage and disabled provider configuration.


## Results

- Both new tests failed before the fix because alpha and beta received the same client session ID.
  The focused Recall suite now passes all 14 tests, including unchanged rejected-write and refresh
  failure handling. Full frontend suite: 648 tests across 59 files passed.
- `npm run check` passed with no errors and 70 existing Solid warnings; `npm run build` and Maven
  frontend packaging passed. Static assets were regenerated. `git diff --check` passed.
- The new packaged journey performed alpha → beta → alpha replacements through one mounted
  All projects page. Actual API session IDs reused alpha and separated beta. Project counts were
  alpha: 2 sessions/4 events; beta: 2 sessions/2 events. Project session lists, both directions of
  replacement history, alpha Resume, and the exact replacement's Browse link all passed.
- Existing continuity journeys at 1440px and 390px plus the empty legacy-scope route also passed:
  4 packaged E2E tests total. The isolated project fixture and temporary database were removed;
  port 8799 was free after cleanup. The protected 8766 listener remained unchanged; its production
  database was not discovered, so no production database row-count claim is made.
- No live history, provider, infrastructure, backend session identity, or Git publication changed.
  Existing sessions are not rewritten; the change applies to new UI replacement captures.

## Combined coordinator acceptance

Combined this fix with alias evidence-link preservation and regenerated the frontend bundle.
All 649 frontend tests, check/build/format gates and both packaged regression journeys passed
on the combined source. The alias route retained evidence/query/fragment through reload and Back;
All projects replacements retained exact per-project counts, sessions and history. Fixture cleanup
completed and port 8799 was free afterward. No live deployment was performed.
