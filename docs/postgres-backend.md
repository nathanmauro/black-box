# PostgreSQL backend

Black Box keeps SQLite as its default local database. The optional `postgres` Spring profile uses
PostgreSQL through the same capture, recall, project, and workflow APIs. Choose one authoritative
server for shared clients; this profile does not synchronize a local SQLite database with a cloud
PostgreSQL database.

## Configuration

Set these variables in the service's secret/configuration system before starting the existing JAR
or container:

| Variable | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE=postgres` | Select the PostgreSQL driver, schema, and SQL behavior. |
| `SBA_DATASOURCE_URL` | JDBC URL, such as `jdbc:postgresql://database:5432/blackbox`. Add the TLS options appropriate to the host. |
| `SBA_DATASOURCE_USERNAME` | Database user; defaults to `blackbox`. |
| `SBA_DATASOURCE_PASSWORD` | Database password; defaults to empty, not an embedded credential. |
| `SBA_DATASOURCE_POOL_SIZE` | Maximum connections; defaults to 8. |

The PostgreSQL profile excludes SQLite connection properties, migrations, FTS5 creation, and native
extension loading. Startup creates the canonical tables and indexes if absent. It does not import
history or change an existing SQLite database. Database credentials need permission to create the
schema's tables and indexes on first startup.

For a core deployment without model services, set `SBA_LOCAL_AI_ENABLED=false`,
`SBA_MEMORY_EMBEDDING_ENABLED=false`, `SBA_ELASTICSEARCH_ENABLED=false`, and
`SBA_SUMMARY_BACKEND=local`. The last setting prevents the default external summary wrapper from
being invoked. These are deployment choices, not changes to the local defaults.

## Behavior and limits

- Capture, structured lexical recall, project aliases, timelines, saved synthesis, session lineage,
  task lifecycle, and completion handoffs retain their public contracts.
- Timestamp and JSON columns retain their original text. PostgreSQL timestamp types are not used to
  avoid rounding the nanosecond precision in existing event history. Embeddings retain their
  float32 binary representation in a `BYTEA` column.
- PostgreSQL claims lock the chosen task with `FOR UPDATE SKIP LOCKED`. The ownership change and its
  history event commit together. Completion's Handoff and task transition also commit together.
- Free-text event feeds use the existing bounded LIKE fallback. FTS5 token-prefix matching and LIKE
  substring matching differ; free-text facet counts report unavailable without the native index.
- Optional semantic recall uses Java cosine ranking over the canonical embedding table. No pgvector
  extension is required. This is a correctness baseline, not a claim of large-corpus vector speed.
- Use one API replica initially. In-process alias coordination, SSE delivery, and runner recovery
  are unchanged. Adding PostgreSQL alone does not provide multi-replica or multi-host runner safety.
- Transcript-file hydration, editor integration, and export paths remain server-local. The profile
  does not upload transcript files or migrate machine configuration.

The JDBC repositories retain their existing package locations to keep this change narrow. Small
module-local dialect helpers handle the differing SQL; there is no duplicate PostgreSQL repository
implementation.

## Verification

The ordinary `mvn test` suite exercises SQLite and skips the opt-in PostgreSQL contract class.
To exercise PostgreSQL, first start a disposable PostgreSQL instance, then set:

```bash
export SBA_POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:5432/blackbox_test'
export SBA_POSTGRES_TEST_USERNAME='blackbox_test'
# Set SBA_POSTGRES_TEST_PASSWORD through your test environment.
mvn -Dtest=PostgresBackendContractTest test
```

The contract test creates a randomly named `bb_contract_...` schema, uses it for a real HTTP server,
closes the server, and drops only that schema. Its database role needs CREATE SCHEMA permission.
Use a disposable database. Checks cover restart persistence, capture/recall, redaction, project
queries, nanosecond ordering, aliases, saved synthesis, session lineage, concurrent claims, forced
claim/completion rollback, and canonical embeddings without native extensions.
