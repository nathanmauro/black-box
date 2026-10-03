# Event chronology offline fixture qualification (NAT-316)

## Scope and safety contract

Add one fixed, familiar development fixture to the existing offline repository
qualifier. Baseline `a2f969585dc5b780b3dc4b0611a4084ec7efd0aa` and reference
`aac7a30230687e795f844a971c72ebd5fd393e5c` are immutable reviewed commits. Verify
parentage, the exact 13-path reference change set, unchanged build/configuration,
and source hashes before any execution. The reference overlay is limited to
`RecordingSqlStore`, `MemorySqlQueryAdapter`, and the newly introduced `SqlInstant`.
No arbitrary revision, candidate code, overlay path, or plugin support is added.

Preserve both existing fixture manifests, tasks, graders, Maven recipe, default
behavior, and worker input hashes. Worker exports contain tracked baseline source
and the task only; graders and reference changes remain in private grading trees.
The same independent grader must compile on both snapshots through their shared
recording API. No reference-only helper may appear in the grader.

Use a private SQLite file under the grading tree's `target` directory, schema SQL,
JdbcTemplate, EventFtsIndex, RecordingSqlStore, and a fixed Clock. No Spring Boot,
server, providers, live database/history/configuration, dependency downloads, or
network services. Java 21 and an existing offline Maven cache are prerequisites.
Existing scrubbed environment, no-follow trusted inputs, pinned staging inventory,
owned process-group cleanup, and bounded execution remain mandatory.

## Acceptance hypotheses

Seven named checks will exercise the feed boundary: fractional first-page order;
mixed 0/3/6/9-digit and nanosecond pagination in exact chronological ID order with
bounded termination; inclusive since boundaries; stored timestamp text preservation;
same-instant ID ties; combined project/source/query bounds; and invalid cursors.
Expected baseline failures are hypotheses until actual replay. Compiler, dependency,
toolchain, timeout, SQLite setup, skipped/zero/unexpected tests, and grader integrity
failures are infrastructure failures, not behavioral bug reproduction.

The full reviewed three-source closure is overlaid, but this qualification claims
only the independent feed checks. It does not qualify all recall/search adapters,
PostgreSQL behavior, provider execution, or application startup/migration performance.

## Verification and reporting

1. Read the pinned public source objects and exact existing APIs; write the grader
   and fixed manifest without changing the old fixture bytes.
2. Run focused runner/fake failure-contract tests, including the multi-source
   overlay and newly added source inventory, then the full Python 3.9 benchmark suite.
3. Execute the new fixture baseline/reference pair offline with the identical grader;
   require exact named behavioral failures on baseline and all seven passes on reference.
4. Requalify both old fixtures, preserving worker hashes
   `539949cfb6e7d2e5de089e9dda4700b17158b37ebbd4858dc12f9f9612b07f8a` and
   `ae29350d56eeefb749215a4badac33d8208681534a1fbe60357d93480313b48a`.
   Retain contaminated-caller and missing-cache controls where execution is affected.
5. Format only the new Java grader with the repository's pinned formatter. Update
   only the selected inventory entry/count and dated result; leave NAT-315 prose
   documents to their owner. Freeze the eight owned paths and hashes for coordinator
   review and Git publication.

All results remain `infrastructure_only` and `not_cleared`: no model run, benefit,
accepted action, held-out status, or relaxed NAT-7 threshold is implied. A successful
run makes this the second qualified member of the 17-candidate inventory;
structured-redaction is an older development fixture outside that inventory.

## Results

Source-write preflight succeeded through scoped managed-worktree escalation.
Python 3.9.6, Java 21.0.12, and the existing offline Maven cache were sufficient;
no installation or dependency download was used. Read-only Git verification confirms
the exact parent relation, 13 changed paths, source/build hashes, absence of the new
helper in the baseline, and unchanged pom/schema/application configuration.

The first native attempt correctly returned `test_execution_error`: the initial
grader omitted the existing canonical append transaction. The fixture now uses
`DataSourceTransactionManager` and `TransactionTemplate` around session/event writes.
The next run demonstrated the three chronology failures plus a preservation-control
failure: resolved `projectScopes` alone is intentionally ignored without a project
group query token. The control was corrected to use the existing documented
`project_exact:/fixture/alpha` query grammar, retaining its exact expected IDs and
all exclusion assertions. No preservation assertion was removed or reclassified.

The identical final grader produced exactly these baseline failures:

- `fractionalEventsPrecedeWholeSecondOnFirstPage`
- `mixedPrecisionPagesRetainExactChronologicalOrder`
- `sinceIncludesNextNanosecondAndExcludesPreviousNanosecond`

The other four checks pass on baseline; all seven pass on reference, with zero
skips/errors. The committed qualification report is
[`2026-10-03-event-chronology-qualification.json`](../../evaluation-results/2026-10-03-event-chronology-qualification.json).
Chronology worker input SHA-256 is
`299ec5ae5255d1e4f8ea29a02cf0cd96a3c3de019c7ff4f31e166670cfbb878a`.

Focused qualifier tests pass 66 cases; the final complete Python 3.9 benchmark suite
passes 93 cases. These include per-source overlay tampering, unexpected source
presence, changed grader/manifest/build inputs, zero/skipped/unexpected tests,
compilation/dependency failures, timeout cleanup, and contaminated caller settings.
Scoped Palantir/Spotless check, Python 3.9 syntax parsing, and `git diff --check` pass.

All three native fixture pairs also passed with invalid ancestor/caller `.mvn`
configuration plus conflicting Maven/JVM environment settings. Structured-redaction
retains three expected failures and three controls on baseline, then six passes on
reference; summary-export retains three expected failures and four controls, then
seven passes. Both original worker hashes listed above match exactly. All six prior
manifest/task/grader files are byte-identical to the starting commit; the Maven
recipe and default fixture remain unchanged.

An actual empty-cache chronology run returned `offline_dependency_unavailable`,
without attempting a download or counting a behavioral failure. The four observed
private grading trees (three successful pairs and empty-cache failure) and their
contaminated caller directory were removed. Grader SQLite/JNI files live under its
owned target directory and are cleaned; the qualifier also removes the entire
private tree on error/timeout. No services, live data/configuration, models, provider
calls, or Git publication were used.

The inventory still contains 17 familiar development candidates; two are now
qualified, with historical corpus unstaged and human acceptance unobserved.
Structured-redaction remains outside that inventory. NAT-7 remains `not_cleared`;
this result is infrastructure evidence only. Coordinator owns fresh review, the
separate NAT-315 documentation reconciliation, and all Git/PR/CI/Linear finishing.

## Coordinator acceptance

The coordinator independently verified the frozen eight-path manifest, ran all 93 Python 3.9
benchmark tests and replayed each of the three native baseline/reference pairs offline.
Chronology reproduced exactly the three named baseline failures with four preservation passes,
and all seven reference checks passed. Structured-redaction and summary-export retained their
six- and seven-check contracts and the original worker hashes. Fresh read-only review accepted
the fixed source closure, independent grader, isolation controls and bounded report claims.

No live service, database, provider or model was used. Public evaluation documentation is
reconciled with the separately verified literal-search adapter; these remain preparatory
infrastructure changes with unchanged usefulness thresholds.

After integrating the merged literal-search adapter, all 121 combined benchmark tests passed on
Python 3.9. The prior fixture inputs and qualification results remain unchanged.
