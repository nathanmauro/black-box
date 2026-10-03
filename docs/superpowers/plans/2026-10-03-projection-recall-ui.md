# Inspect Projection evidence in Recall

Explicit Projection recall now preserves its stored rendered evidence, but the Recall page cannot
select that kind or open a Projection-only recall link. Extend the existing kind controls and
card/clipboard presentation without changing default kinds, backend retrieval or capture semantics.

Show all stored paths through the existing bounded reader. Explain that these are recorded
possibilities, and suppress the legacy first-path confidence meter for this kind: it is not a
confidence for the whole set. Keep older servers usable with a source-link notice when body is
absent. Include the same interpretation in copied context. No selection/outcome relation is added.

Verify real capture → select kind → recall → expand → copy → exact source navigation at desktop
and narrow viewport widths, plus route restoration and source compatibility. Run affected frontend
tests, lint/format/type checks, build generated assets, and the packaged browser journeys.

All capture and browser proof uses an isolated fixture server with synthetic data. No live service,
canonical database or model provider is changed. Coordinator owns commit, PR and merge.

## Verification

The packaged desktop journey failed before implementation because no Projection kind control
existed. With the change, 48 packaged browser journeys pass, including desktop and 390px
Projection capture, filter selection, keyboard expansion, clipboard evidence, reload/deep-link
restoration, and exact source navigation. The running recorder listener remained unchanged;
the isolated synthetic test directory and database were removed by the runner.

After fresh review, the full frontend suite passed 672 tests and both Projection browser
journeys passed again on the final packaged source. Lint reports zero errors and the same 70 pre-existing warnings; formatting and
TypeScript checks pass. Generated assets come from the packaged Maven frontend build. No physical
phone or deployed application was used for this proof.

Fresh review caught a capped-body edge: Projection basis is appended after paths at capture, so
the stored rendered body can end before the metadata-backed basis. Keep the separate basis visible
and place it before paths in the bounded clipboard export; dedicated card/export regressions cover
that case. The UI does not infer that a present body contains every metadata field.
