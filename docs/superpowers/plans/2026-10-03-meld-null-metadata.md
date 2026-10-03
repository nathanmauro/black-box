# Preserve null-valued meld metadata

## Reproduced defect and scope

A valid same-project HTTP save with `metadata: {"sourceHash": null}` returned 500 from
`Map.copyOf`, before any meld or input row was inserted. Nested null, omitted metadata and a
null metadata object succeeded; nested null round-tripped through the project meld listing.
The probe used copied current compiled classes, private SQLite/home, an ephemeral loopback
server and disabled providers. The fixture process and temporary snapshot were removed.

Replace only the outer metadata copy with a null-tolerant defensive, unmodifiable copy. Preserve
JSON values and insertion order, omitted/null object normalization to `{}`, session selection,
project ownership checks and existing transaction behavior. This remains a shallow copy; do not
introduce schema changes, new synthesis behavior or a generic validation refactor.

## Acceptance and verification

Add actual HTTP save/list/SQL checks for top-level null, nested null, mixed JSON values, omitted
metadata and a null metadata object. Verify ordered input sessions and unchanged original events.
Invalid body and invalid session selections must add neither meld nor input rows. Exercise the
public application operation to prove caller outer-map mutations do not alter its result and
returned outer metadata cannot be modified. Run the regression before the production change,
then focused project/controller tests, full backend/module checks, scoped Palantir and diff checks.
No PostgreSQL claim without a configured disposable fixture. Root owns Git and publication.

## Verified result

The new ten-case baseline produced the expected two HTTP 500 failures for top-level null values
and one direct-operation null-pointer error; seven controls passed. The initial test's empty SQL
expectation was corrected to preserve the existing NULL storage representation and `{}` read
normalization before recording that baseline.

The null-tolerant outer copy passed all ten new cases and all 49 focused project/controller tests.
Actual POST, project listing and SQL checks preserve nulls, JSON values, source-session order and
original captures; invalid body/session selections add no meld/input rows. Caller outer-map
mutation cannot alter the saved result, whose outer map rejects mutation. No deep-copy guarantee
was introduced.

Full `mvn test` passed: 987 tests, zero failures/errors and 42 optional skips (38 PostgreSQL cases
without a configured fixture and four other optional checks). All nine architecture/module tests
passed. Scoped Palantir apply/check, `git diff --check` and independent read-only review passed.
No schema, frontend, live service, provider or installed configuration changed. PostgreSQL was
not separately exercised; temporary HTTP/SQLite fixtures were closed. The four-file source unit
is frozen for the coordinator's Git, publication and merge steps.
