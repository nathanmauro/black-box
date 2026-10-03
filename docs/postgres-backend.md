# PostgreSQL backend

Black Box keeps SQLite as its default local database. The optional `postgres` Spring profile uses
PostgreSQL through the same capture, recall, project, and session-lineage APIs. Choose one
authoritative server for shared clients; this profile does not synchronize a local SQLite database
with a cloud PostgreSQL database.

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

The PostgreSQL profile excludes SQLite connection properties, SQLite-specific migrations, FTS5
creation, and native extension loading. Startup creates the canonical tables and indexes if absent. It does not import
history or change an existing SQLite database. Database credentials need permission to create the
schema's tables and indexes on first startup. Opting into legacy data retirement with
`SBA_RETIRE_WORKFLOW=true` also requires permission to alter `session_links` and drop the retired task tables; see
[retirement and upgrade notes](board-retirement.md).

For a core deployment without model services, set `SBA_LOCAL_AI_ENABLED=false`,
`SBA_MEMORY_EMBEDDING_ENABLED=false`, `SBA_ELASTICSEARCH_ENABLED=false`, and
`SBA_SUMMARY_BACKEND=local`. The last setting prevents the default external summary wrapper from
being invoked. These are deployment choices, not changes to the local defaults.

## Cloud image startup contract

`Dockerfile.cloud` has a stricter startup contract than the ordinary JAR. Its entrypoint refuses to
launch Java until `SPRING_PROFILES_ACTIVE=postgres`, an explicit `jdbc:postgresql://host/database`
URL, nonempty `SBA_DATASOURCE_USERNAME` and `SBA_DATASOURCE_PASSWORD`, and `SBA_AUTH_ENABLED=true`
are present. Supply separate generated `SBA_AUTH_PASSWORD` and `SBA_AUTH_API_TOKEN` secrets as
described in [authentication](authentication.md). `SBA_AUTH_SECURE_COOKIES` may be unset (the secure
default) or `true`; the cloud image rejects `false` and empty values. The application still checks
credential quality before serving requests.

Inject secrets through the deployment's protected environment. Do not pass command arguments to
this image: its entrypoint accepts none. It rejects Java option environment overrides, alternate
Spring configuration/profile inputs and JSON configuration (including relaxed case/dot/underscore
spellings), the entire competing `SPRING_DATASOURCE_*`
namespace (including pool connection and JNDI settings, and relaxed spelling variants) and `SBA_STORAGE_BACKEND`. Use the documented `SBA_DATASOURCE_*` settings;
the PostgreSQL profile selects the backend. Startup loads only the packaged `application.yml` and
its PostgreSQL profile; mounted working-directory configuration files are excluded.
The guard prints setting names, never secret values,
and execs Java with the image's fixed memory limits. This prevents accidental configuration drift;
an operator who replaces the entrypoint or image still controls the process.

TLS termination, a private internal listener and database TLS are deployment responsibilities.
The gate does not provision them. The ordinary JAR and local launcher retain their SQLite and
loopback defaults; this cloud-only guard does not change local operation.

## Behavior and limits

- Capture, structured lexical recall, project aliases, timelines, saved synthesis, and session
  lineage retain their public contracts. Historical completion Handoffs remain normal events.
- Timestamp and JSON columns retain their original text. PostgreSQL timestamp types are not used to
  avoid rounding the nanosecond precision in existing event history. Embeddings retain their
  float32 binary representation in a `BYTEA` column.
- Free-text event feeds use the existing bounded LIKE fallback. FTS5 token-prefix matching and LIKE
  substring matching differ; free-text facet counts report unavailable without the native index.
- Optional semantic recall uses Java cosine ranking over the canonical embedding table. No pgvector
  extension is required. This is a correctness baseline, not a claim of large-corpus vector speed.
- Use one API replica initially. In-process alias coordination and SSE delivery remain unchanged.
  Adding PostgreSQL alone does not provide multi-replica safety.
- Transcript-file hydration, editor integration, and export paths remain server-local. The profile
  does not upload transcript files or migrate machine configuration.

The JDBC repositories retain their existing package locations to keep this change narrow. Small
module-local dialect helpers handle the differing SQL; there is no duplicate PostgreSQL repository
implementation.

## Verification

The ordinary `mvn test` suite exercises SQLite and skips both opt-in PostgreSQL contract classes
when `SBA_POSTGRES_TEST_URL` is absent. CI supplies a disposable PostgreSQL 16 service and rejects
a skipped PostgreSQL contract class.
To exercise PostgreSQL, first start a disposable PostgreSQL instance, then set:

```bash
export SBA_POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:5432/blackbox_test'
export SBA_POSTGRES_TEST_USERNAME='blackbox_test'
# Set SBA_POSTGRES_TEST_PASSWORD through your test environment.
mvn -Dtest=PostgresBackendContractTest,AuthenticatedPostgresConsumerContractTest test
```

Each contract class creates its own randomly named `bb_contract_...` or `bb_auth_contract_...`
schema, uses it for a real HTTP server, closes the server, and drops only that schema. Its database role needs CREATE SCHEMA permission.
Use a disposable database. Checks cover restart persistence, capture/recall, redaction, project
queries, nanosecond ordering, aliases, saved synthesis, session lineage, and canonical embeddings
without native extensions. The opt-in retirement migration also applies to PostgreSQL: see
[retirement and upgrade notes](board-retirement.md) before upgrading an existing database.

`AuthenticatedPostgresConsumerContractTest` composes the current consumer paths on one authenticated
server: anonymous and wrong-token read/write denial, bearer-only requests without session cookies,
idempotent capture/replay, full tool input/output and provenance retrieval, bounded MCP discovery
with source follow-through, MCP capture/recall, exact project isolation, Decision replacement/history,
SSE event delivery and a scheduled heartbeat, and persistence plus idempotent replay after restart.
Model services and Elasticsearch are disabled. This is a direct loopback service check; it does not
prove managed-proxy buffering/timeouts, TLS, browser or phone use, OAuth, migration, or backup/restore.

Run the cloud startup guard without a JVM or database:

```bash
python3 -B -m unittest discover -s scripts/cloud -p 'cloud_entrypoint_test.py'
```

`./scripts/verify.sh` includes that offline guard. Export the PostgreSQL fixture variables above
before running it to include the same database contracts required by CI. The cloud image's Docker
build context admits only built `target/*.jar` files and the one required entrypoint script.
