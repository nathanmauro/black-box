# Structured secret redaction before persistence

## Safety contract

Close the demonstrated default-redaction bypass for structured tool input/output and metadata.
Only disposable fixtures with obviously fake secrets are used. No existing capture, live database,
provider, deployment, or hook configuration is changed. Scalar clipping is a separate change.
Custom patterns retain their replacement semantics; disabled redaction preserves original objects.

## Reproduction

The new `StructuredRedactionHttpTest` sends `toolInput.api_key`, nested `toolOutput.password`,
`metadata.credentials.access_token`, and a synthetic provider-token member name through both
`POST /api/events` and `POST /api/events/idempotent`. Before the fix, both tests fail on the actual
SQLite `agent_events` JSON columns retaining the fake secret values. Across the new focused
fixtures, the baseline has 23 tests, 22 failures, and no errors; disabled identity already passes.
All application fixtures explicitly disable judge, local AI, embeddings, editor, and Elasticsearch,
use a temporary database and random loopback port, and close their application context.

## Implementation

- Use the durable hook's case/separator-insensitive conservative key words only with default rules.
- Replace the entire value below a secret key, regardless of scalar/container type.
- Scan string member names with active text rules; reserve original names and disambiguate changed
  collisions instead of losing another field.
- Leave ordinary structure intact; recurse through maps/lists without mutating the input.
- Keep disabled object identity and custom-pattern replacement of defaults.
- Document false positives, custom/disabled behavior, and lack of retroactive cleanup in
  [operations](../../operations.md).

## Verification

- 68 tests passed with no failures, errors, or skips: `StructuredRedactionTest`,
  `StructuredRedactionHttpTest`, `RedactionServiceTest`, `EventIngestServiceTest`,
  `CaptureIdentityTest`, `IdempotentCaptureHttpTest`, `StructuredCaptureServiceTest`, and
  `JevExportRedactionTest`; the run explicitly sets `sba.judge.enabled=false`.
- Both ordinary and idempotent HTTP capture now persist only sanitized fields. Event retrieval
  retains the same sanitized JSON plus identity; replay keeps the original event ID.
- Coverage includes case/separator variants, nested lists/maps, short and non-string secret
  values, whole secret subtrees, secret-bearing names, name collisions, input immutability,
  custom rules, disabled object identity, and existing export behavior.
- Scoped Palantir formatting and `git diff --check` pass.
- Fresh coordinator read-only review found no actionable issues in key classification,
  custom/disabled compatibility, subtree/collision handling, normalization ordering, or either
  HTTP receipt path. Documentation links resolve locally, including the durable-hook privacy
  heading. Source and tests are frozen for coordinator Git integration.

No UI change is needed: consumers receive the sanitized stored event through the existing API.
Existing captures are not rewritten. The conservative policy can hide benign secret-word fields;
custom patterns and disabling redaction intentionally retain their documented behavior.

Coordinator acceptance: fresh source/test review found no remaining issues. Integrated main
through PR65, including Unicode-safe scalar clipping and capture acknowledgements. All 57
combined structured-redaction, scalar-boundary, acknowledgement and structured-capture tests
passed with no skips. No live deployment.
