# Capture acknowledgement offline fixture qualification (NAT-322)

## Scope and safety contract

Add one fixed, familiar development fixture, `capture-ack`, to the existing offline repository
qualifier. Baseline `5d76086eeb0d423207e0f5560b3ae1aa1f9bebc8` and reference
`596ccf62a99416f14acf0ca24f91a928bc08ed40` are immutable reviewed commits and an immediate
parent pair. Verify parentage, the exact four-path reference change set, unchanged build
configuration and source hashes before any execution. The reference overlay is limited to
`EventIngestService.java`; the reference's HTTP/MCP test, agent-integration doc and plan are
allowed reference changes but are never overlaid. No arbitrary revision, command, overlay path,
export path or plugin support is added.

Preserve the three existing fixture manifests, tasks, graders, worker-input hashes, the Maven
recipe and the default fixture. Worker exports contain tracked baseline source and the task only;
the grader and reference source remain in private grading trees.

The grader is independent of the reference test. It constructs the real `EventIngestService`,
`RedactionService` and `IngestionProperties`, plus the real `RecordingSqlStore` and `EventFtsIndex`
on a private SQLite file under the grading tree's `target` directory. Persistence is reached only
through a Spring AOP proxy carrying the real `TransactionInterceptor` for the store's own
`@Transactional` annotations over a `DataSourceTransactionManager`. Only the optional publication
boundary (`ApplicationEventPublisher`) is a fixture; persistence and the transaction boundary are
never replaced. No Spring Boot, server, providers, live database/history/configuration,
dependency downloads, network, or model.

## Acceptance hypotheses

Eight named checks use interfaces present on both revisions:

1. An `EventRecorded` publisher that throws after commit: ingest returns the acknowledgement for
   the committed row; an independent SQLite connection saw that row before the publisher threw,
   with no active transaction.
2. Terminal capture whose `EventRecorded` publication throws still attempts `SessionStopped`.
3. Terminal capture whose `SessionStopped` publication throws is still acknowledged.
4. Terminal capture with both publications throwing is acknowledged after both attempts.
5. Preservation: ordinary capture returns the persisted acknowledgement shape (normalized source,
   client session, type, `indexed=false`), publishes exactly once, and a repeat appends a second
   event (legacy captures stay non-idempotent).
6. Preservation: ordinary terminal capture publishes `EventRecorded` then `SessionStopped`.
7. Preservation: an unserializable tool payload is rejected inside the store transaction and the
   already-written session row is rolled back; nothing is published.
8. Preservation: a database write failure (fixture SQLite trigger) still rejects, rolls back the
   session row, and publishes nothing.

Expected baseline failures (1–4) are hypotheses until actual replay. Compilation, dependency,
toolchain, timeout, SQLite setup, skipped/zero/unexpected tests and grader-integrity failures are
infrastructure failures, not behavioral reproduction.

## Claim limits

This is labelled a **potentially easy control**: the baseline already contained `publishOptional`,
so the fix is a small reuse. It is not evidence of hard recovery or recall benefit. The grader
qualifies only the service persistence/commit boundary; the inventory's broader REST/MCP
acknowledgement claim remains unverified by it. Completing it adds one distinct inventory cluster
(`capture-acknowledgement`) as a third qualified inventory member; it is not a held-out trial or a
model run. The source inventory stays at 17 candidates in 12 clusters. NAT-7 gates (20 candidates,
5 tasks, 70% useful, <10% stale/duplicate, 3 human-accepted actions) remain unchanged and
`not_cleared`.

## Verification

1. Read pinned source objects and existing APIs; write the grader, task and manifest without
   changing old fixture bytes.
2. Focused qualifier tests for the new registration and pins, then the full Python 3.9 benchmark
   suite.
3. Execute the new pair offline with the identical grader on Java 21 and the existing Maven cache;
   require exactly the named baseline failures and all reference passes.
4. Requalify the existing fixtures and confirm their worker hashes are unchanged.
5. Format only the new Java grader with the pinned formatter; `git diff --check`.
6. Freeze owned paths and hashes for coordinator review and Git publication.

## Results

Preflight: direct file writes in this checkout, Python 3.9.6, Java 21.0.12 (GraalVM) and the
existing offline Maven cache were sufficient; nothing was installed or downloaded. Read-only Git
confirmed both commits, the immediate parent relation and the exact four-path reference delta.
`pom.xml`, `schema.sql`, `application.yml` and `RecordingSqlStore.java` are byte-identical across
the pair; only `EventIngestService.java` differs among pinned sources.

The first native replay passed with the identical grader. Baseline failed exactly:

- `optionalRecordedFailureAcknowledgesCommittedCapture`
- `terminalStopIsAttemptedAfterRecordedFailure`
- `terminalStopFailureAcknowledgesCommittedCapture`
- `bothTerminalFailuresAcknowledgeAfterBothAttempts`

Each failed as `AssertionFailedError: Unexpected exception thrown: IllegalStateException: fixture
optional … failure`, i.e. the fixture publisher's exception escaped a committed capture. The four
preservation checks passed on baseline; all eight passed on reference, zero skips/errors. A second
replay wrote the committed
[report](../../evaluation-results/2026-10-03-capture-ack-qualification.json) with the same worker
input SHA-256 `fd4442df6ed29767c1a009deb600a1a30cdf20cb4388327c4e35e891e06a2375`.

A disposable scratch mutation (grader bypassing the transaction proxy, baseline source) made both
rollback checks fail with an orphan session row, confirming they exercise the real transaction
boundary rather than passing vacuously. The scratch tree was removed.

The three earlier fixtures were replayed offline with unchanged contracts and worker hashes
(`539949cf…`, `ae29350d…`, `299ec5ae…`). Their manifests, tasks and graders are byte-identical.
The new grader was formatted alone with the pinned Palantir formatter. Python 3.9 suites:
`scripts/benchmarks/blackbox_memory` 150 tests and `scripts/evaluation` 22 tests pass.

Limits and threats: this is a potentially easy control. The grader covers the service commit
boundary on SQLite only; REST/MCP acknowledgement, PostgreSQL, lost-response idempotency and
listener completion are not qualified. By construction (not separately mutation-tested), a
candidate publishing inside the store transaction would fail the visibility/active-transaction
assertions, and one swallowing persistence errors would fail the rejection assertions. The inventory remains 17 candidates in 12 clusters, now with three qualified
members; NAT-7 remains `not_cleared`, with no model runs, held-out status, difficulty or accepted
actions. No services, live data, providers or Git publication were used; the coordinator owns Git.

## Coordinator acceptance

Independent source review found no actionable defect. The coordinator replayed all four fixed
fixtures offline under Java 21: capture-ack reproduced exactly its four named baseline failures
and passed all eight reference checks; the three earlier fixtures retained their recorded
behavior and worker-input hashes. All 150 benchmark tests passed independently on Python 3.9.
The branch was integrated with the merged canonical-paging API without overlapping source edits.
No installed service, provider or model was used.
