# Bounded lineage metadata reads

NAT-622 removes the per-edge session reads from the links and one-hop DAG views. These views need
session ID, title, and source, but previously loaded full session rows, including summaries and first
human turns. The DAG also hydrated duplicate peers before `putIfAbsent` discarded them.

`RecordingCatalog.sessionHeaders` now returns a recording-owned projection keyed by exact session ID.
The SQL implementation deduplicates IDs, ignores null IDs, and binds at most 500 IDs per query. It
selects only `id`, `title`, and `source`. This stays below the legacy SQLite 999-parameter limit and
uses the same SQL on PostgreSQL. No schema, cache, or persistence behavior changed.

The services still construct responses from the ordered link lists. Missing peer metadata remains
null in links; DAG labels fall back to the ID for missing sessions or null titles. Empty titles stay
empty. Multiple link types and self-links retain their previous edge behavior.

## Reproduced work reduction

`LineageMetadataQueryTest` runs the actual services, repository, and recording store against disposable
SQLite databases. Each fixture has a center and 1, 50, or 500 distinct children, each with a 32 KiB
summary. A connection wrapper counts prepared SELECT statements, including the two link-list reads.
The test first failed against the prior implementation and then passed against the batch path.

| Peers | Links reads before | Links reads after | DAG reads before | DAG reads after |
| ----: | -----------------: | ----------------: | ---------------: | --------------: |
|     1 |                  3 |                 3 |                4 |               3 |
|    50 |                 52 |                 3 |               53 |               3 |
|   500 |                502 |                 3 |              503 |               4 |

The 500-peer DAG needs two header batches because it includes the center. Query assertions also
reject summary-column reads and statements with more than 500 parameters. These are deterministic
work counts, not measured endpoint latency or a production workload benchmark.

## Verification

Run from the repository root with JDK 21 selected:

```bash
mvn -Dtest=LineageMetadataQueryTest,SessionHeadersTest,SessionLineageMetadataTest test
mvn -Dtest=LineageMetadataHttpTest test
mvn -Dtest=SessionLinkApiContractTest,SessionLinkRepositoryTest,RetiredWorkflowUpgradeApiTest,PackageArchitectureTest,LineageApplicationModuleTest,RecordingApplicationModuleTest,ApplicationModuleStructureTest test
git diff --check
```

`LineageMetadataHttpTest` starts the application on an ephemeral loopback port with a separate SQLite
database and model/search integrations disabled. Java's HTTP client reads both endpoints for 1, 50,
and 500 peers, verifies ordered IDs and metadata, and checks the center-first DAG and empty-title
behavior. It shuts down its application context after the test. The fixtures are synthetic; no live
service or user database is used.

Additional regression tests cover duplicate IDs and peers, multiple edge types, self-links, missing
rows, null versus empty titles, SQL-looking IDs, more than two batches, and empty input without any
database read. A minimal SQLite table containing only the three projected columns prevents the store
test from silently falling back to full session hydration.

`PostgresBackendContractTest.sessionHeadersUsePortableBatchesAndExactBoundIds` exercises 1,001 sessions,
large summaries, an SQL-looking ID, duplicate and missing IDs, and empty input in its disposable
PostgreSQL schema. Run it using the existing PostgreSQL test environment:

```bash
mvn -Dtest=PostgresBackendContractTest test
```

That suite requires `SBA_POSTGRES_TEST_URL` and the existing test credentials; it skips when the URL
is absent. PostgreSQL execution was not available in the local verification environment, so its
runtime result remains a CI check. Nullable-title behavior is tested separately because the current
PostgreSQL schema requires non-null titles.
