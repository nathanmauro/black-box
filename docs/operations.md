# Run it

Black Box runs as one Spring Boot process, with SQLite as the default canonical store. Capture,
coordination, and lexical recall need neither a model nor Elasticsearch. An optional PostgreSQL
profile owns a separate database for shared clients; it does not synchronize local history.
Commands below assume the repository root unless a path is explicit.

## Run as a service

### macOS launchd

For a first installation, render and install
[`scripts/black-box.plist.template`](../scripts/black-box.plist.template) with the actual Java,
JAR, database, log, and service-label settings. Then use:

```bash
./scripts/deploy-local.sh
```

The script requires an installed plist, stops the service before rebuilding the frontend and JAR,
restarts it, and waits for a healthy `/api/status`. Tests are skipped unless `--with-tests` is
passed. `SBA_LAUNCHD_LABEL`, `SBA_LAUNCHD_DOMAIN`, and `SBA_LAUNCHD_PLIST` select the installation;
`SBA_JAR_PATH` and `SBA_STATUS_URL` override the artifact and readiness target. The script's `--help`
lists its defaults. Set these to match the installed service when using a custom label.

Do not replace the executable JAR underneath a running JVM. An ordinary `mvn package`, including
packaging performed for E2E tests, can overwrite `target/` while a service is using it. Use the
managed deploy sequence or build in an isolated checkout/output location. A live PID alone does
not establish health: check `/api/status`, the UI, and a representative API route after restart.

### Runner service

[`scripts/deploy-runner-local.sh`](../scripts/deploy-runner-local.sh) deploys the **already-built**
JAR as a separate macOS runner service. It does not build the artifact. If the server and runner
share the JAR, stop the runner before rebuilding: the server deploy script stops only the server.
Deploy/restart the runner after the build, or supply an isolated artifact. The runner deploy script renders/installs
[`scripts/blackbox-runner.plist.template`](../scripts/blackbox-runner.plist.template), starts or
restarts the runner, and checks process/log activity.

Starting it starts autonomous orchestration under the machine-local runner configuration.
FULL_AUTO claims `gate` and `auto`; SDLC uses `gate`, `sdlc:plan`, `auto`, and `sdlc:review` and
reacts to approval annotations. Review [Runner modes](runner.md) and
[`runner-config.example.json`](runner-config.example.json) before starting it. Only `codex` and
`fake` engines exist; the example's disabled `grok` entry is not an implementation.

`SBA_RUNNER_CONFIG` selects the JSON config. The script also accepts `SBA_RUNNER_LAUNCHD_LABEL`,
`SBA_RUNNER_LAUNCHD_DOMAIN`, `SBA_RUNNER_LAUNCHD_PLIST`, `SBA_RUNNER_JAR`, `SBA_RUNNER_LOG_PATH`,
and `SBA_BASE_URL`; see its `--help`. Process/log activity does not prove a task completed.

### Linux systemd

Copy [`scripts/black-box.service`](../scripts/black-box.service) to a user-managed unit file,
edit its JAR and database paths, and create the database's parent directory. For example, after
preparing `/path/to/black-box.service`:

```bash
systemctl --user link /path/to/black-box.service
systemctl --user daemon-reload
systemctl --user enable --now black-box
```

The template does not set `WorkingDirectory`. Configure one at the checkout, or set
`SBA_SUMMARY_EXTERNAL_COMMAND` to an absolute wrapper path, before using external summaries.
The same relative-command consideration applies to the launchd template. No runner systemd unit
is supplied.

### Docker for local development

Build from a checkout whose JAR is not serving a running process:

```bash
mvn clean -DskipTests package
docker build -t black-box .
docker run --rm -p 127.0.0.1:8766:8766 -v black-box-data:/data black-box
```

The image binds all interfaces inside the container; this command publishes only host loopback.
The named volume retains the SQLite database. The local image copies only the JAR: it does not
include the default external summary wrapper, provider CLI, or credentials. Explicitly provision
those for external summaries, or choose `SBA_SUMMARY_BACKEND=local` and configure a reachable
OpenAI-compatible local server. With local AI disabled/unavailable, that backend falls back to a
compacted transcript. The cloud build uses [`Dockerfile.cloud`](../Dockerfile.cloud).

## Configuration

Defaults are defined in [`application.yml`](../src/main/resources/application.yml),
[`application-postgres.yml`](../src/main/resources/application-postgres.yml), and
[`AuthSettings`](../src/main/java/dev/nathan/sbaagentic/platform/internal/adapter/in/web/security/AuthSettings.java).
These tables cover the application variables; hook and wrapper variables are separate below.

### Server, storage, authentication, and navigation

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_PORT` | `8766` | HTTP port |
| `SBA_BIND_ADDRESS` | `127.0.0.1` | Bind address; authentication is optional and disabled by default. Enable [authentication](authentication.md) and HTTPS before network exposure. |
| `SBA_DATASOURCE_URL` | `jdbc:sqlite:sba-agentic.db` | Default SQLite location; the PostgreSQL profile defaults to `jdbc:postgresql://localhost:5432/blackbox`. |
| `SPRING_PROFILES_ACTIVE` | No PostgreSQL profile | Set `postgres` to select the PostgreSQL driver, schema, and SQL behavior. |
| `SBA_DATASOURCE_USERNAME` | `blackbox` | PostgreSQL database role |
| `SBA_DATASOURCE_PASSWORD` | Empty | PostgreSQL password, supplied through a protected runtime environment |
| `SBA_DATASOURCE_POOL_SIZE` | `8` | PostgreSQL maximum connections; default SQLite pool size is fixed at 4 |
| `SBA_AUTH_ENABLED` | `false` | Enable the browser-session and agent-bearer authentication boundary |
| `SBA_AUTH_USERNAME` | `blackbox` | Browser login name |
| `SBA_AUTH_PASSWORD` | Empty | Independently generated browser secret; required when authentication is enabled |
| `SBA_AUTH_API_TOKEN` | Empty | Separate independently generated agent bearer secret; required when authentication is enabled |
| `SBA_AUTH_SECURE_COOKIES` | `true` | Secure browser cookies; disable only in trusted loopback HTTP fixtures |
| `SBA_REDACT_ENABLED` | `true` | Redact secret-looking text before persistence |
| `SBA_EDITOR_ENABLED` | `true` | Enable catalog-bound open-in-editor actions |
| `SBA_EDITOR_COMMAND` | Cursor application CLI on macOS | Absolute Cursor/VS Code-compatible executable |
| `SBA_EDITOR_ALLOWLIST` | Fixed Cursor and VS Code CLI locations | Comma-separated absolute executable allowlist; not automatic discovery of installed editors |
| `SBA_EDITOR_TIMEOUT` | `5s` | Maximum editor/Finder handoff time |
| `SBA_EXPORT_OBSIDIAN_DIR` | Empty | Configure the built-in Markdown summary export target; export is explicitly requested through API/UI |

Authentication represents one trusted workspace with one browser user and one agent token, not
tenant isolation or per-agent permissions. Both secrets must be independently generated, different,
at least 32 characters, and satisfy startup validation; never put secret values in command
arguments or tracked files. See [Authentication](authentication.md) for the complete boundary.

PostgreSQL support starts with one authoritative API replica. It adds neither local/cloud sync nor
multi-host runner safety. The opt-in contract test reads `SBA_POSTGRES_TEST_URL`,
`SBA_POSTGRES_TEST_USERNAME`, and `SBA_POSTGRES_TEST_PASSWORD`; these select a disposable test
database, not the service database. See [PostgreSQL](postgres-backend.md).

### Summaries and local AI

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_SUMMARY_BACKEND` | `external` | Summary backend; choose `local` explicitly for local model use |
| `SBA_SUMMARY_EXTERNAL_COMMAND` | `scripts/summarize-with-codex.sh` | External summary command, executed through `/bin/sh -c` |
| `SBA_SUMMARY_TIMEOUT` | `10m` | External summary subprocess timeout |
| `SBA_LOCAL_AI_ENABLED` | `true` | Enable OpenAI-compatible local model calls |
| `SBA_LOCAL_AI_BASE_URL` | `http://localhost:1234` | Local model base URL |
| `SBA_LOCAL_AI_CHAT_PATH` | `/v1/chat/completions` | Chat endpoint path |
| `SBA_LOCAL_AI_MODEL` | `local-model` | Configured local model identifier |
| `SBA_LOCAL_AI_API_KEY` | `lm-studio` | Local-compatible API credential; replace when the selected server requires it |
| `SBA_LOCAL_AI_MAX_INPUT_CHARS` | `8000` | Per-request input window; larger transcripts are map-reduced |

Default external summarization can send transcript text through the selected vendor. Both
[`summarize-with-codex.sh`](../scripts/summarize-with-codex.sh) and
[`summarize-with-claude.sh`](../scripts/summarize-with-claude.sh) are supplied. Select the latter
with `SBA_SUMMARY_EXTERNAL_COMMAND=/path/to/black-box/scripts/summarize-with-claude.sh`.
`SBA_SUMMARY_BACKEND=local` chooses LM Studio or another local OpenAI-compatible server; it falls
back to compacted transcript text if local summarization cannot run. This configured summary
subprocess does not execute queued work or launch worker agents.

The wrappers have their own environment settings:

| Variable | Checked-in default | Purpose |
| --- | --- | --- |
| `SBA_SUMMARY_CODEX_BIN` | Homebrew Codex executable location | Override with the installed Codex executable |
| `SBA_SUMMARY_CODEX_AUTH` | `auth.json` in the configured Codex state directory | Wrapper credential source; honors `CODEX_HOME` for its default directory |
| `SBA_SUMMARY_CODEX_MODEL` | Unset | Omit `--model` unless explicitly configured |
| `SBA_SUMMARY_CLAUDE_BIN` | `claude` | Claude executable |
| `SBA_SUMMARY_CLAUDE_MODEL` | `claude-opus-4-8` | Wrapper model argument; check support in the installed client/provider |
| `SBA_SUMMARY_CLAUDE_EFFORT` | `max` | Wrapper effort argument |

These are source defaults, not a claim that a particular client or provider currently accepts
every model argument. External wrappers require their CLI and credentials in the service context.

### Memory recall and Elasticsearch

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_MEMORY_EMBEDDING_ENABLED` | `true` | Enable structured-intent semantic recall; unavailable embeddings leave lexical recall usable |
| `SBA_MEMORY_EMBEDDING_URL` | `http://localhost:11434` | Ollama-compatible memory embedding base URL |
| `SBA_MEMORY_EMBEDDING_PATH` | `/api/embeddings` | Embedding request path |
| `SBA_MEMORY_EMBEDDING_MODEL` | `nomic-embed-text` | Model for structured intent and stored summary vectors |
| `SBA_MEMORY_EMBEDDING_DIMENSIONS` | `768` | Expected vector dimensions |
| `SBA_MEMORY_EMBEDDING_DOCUMENT_PREFIX` | `search_document: ` | Stored-text prefix, including trailing space; empty disables |
| `SBA_MEMORY_EMBEDDING_QUERY_PREFIX` | `search_query: ` | Query prefix, including trailing space; empty disables |
| `SBA_MEMORY_RECALL_RELEVANCE_FLOOR` | `0.61` | Cosine floor for semantic-only additions; values at or below zero disable it |
| `SBA_RECALL_TELEMETRY_PROJECT_ALIASES` | Empty | Comma-separated allowlist of safe declared telemetry project aliases |
| `SBA_SQLITE_VEC_PATH` | Empty | Optional SQLite native accelerator; empty uses portable Java cosine ranking. PostgreSQL skips extension loading. |
| `SBA_ELASTICSEARCH_ENABLED` | `false` | Enable optional secondary event indexing |
| `SBA_ELASTICSEARCH_URL` | `http://localhost:9200` | Elasticsearch base URL |
| `SBA_ELASTICSEARCH_INDEX` | `sba-agentic-events` | Event index name |
| `SBA_ELASTICSEARCH_REPLICAS` | `0` | Replica setting when creating the event index |

Semantic recall returns Decisions, Handoffs, and Observations. Projections are lexical-only;
summary vectors are stored but not returned by recall, and the full event corpus is not
semantically indexed. Elasticsearch is independent of memory embeddings. The floor is a dated
measurement, not a universal relevance guarantee; remeasure when the model or corpus changes.

### Ask

Ask is a separate retrieval path, and it is off under the shipped defaults. It requires
`SBA_ELASTICSEARCH_ENABLED=true` (default `false`) and an externally provisioned `agent-memory`
index: Black Box searches that index but never creates or writes to it. With either missing,
retrieval reports mode `unavailable`, every Ask query returns no citations, and the answer is the
fixed string "Answer not found in memory."

With Elasticsearch reachable and that index populated, query embeddings add hybrid retrieval and an
unavailable embedder falls back to BM25. The local chat model adds answer synthesis; when retrieval
found citations but synthesis fails, Ask returns those citations with a degraded-synthesis message.

`agent-memory` is a historical default that Black Box never writes. The only index this service
populates is `SBA_ELASTICSEARCH_INDEX` (default `sba-agentic-events`), and that is not the index Ask
reads. Point `SBA_ASK_MEMORY_INDEX` at an index you actually populate. `sba-agentic-events` is a
valid target for lexical retrieval, but it carries no vector field, so hybrid retrieval degrades to
BM25 against it.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_ASK_MEMORY_INDEX` | `agent-memory` | Ask retrieval index |
| `SBA_ASK_VECTOR_FIELD` | `vector` | Vector field in that index |
| `SBA_ASK_EMBEDDING_ENABLED` | `true` | Enable Ask query embeddings |
| `SBA_ASK_EMBEDDING_URL` | `http://localhost:11434` | Ask embedding base URL |
| `SBA_ASK_EMBEDDING_PATH` | `/api/embeddings` | Ask embedding request path |
| `SBA_ASK_EMBEDDING_MODEL` | `nomic-embed-text` | Ask embedding model |
| `SBA_ASK_EMBEDDING_DIMENSIONS` | `768` | Ask vector dimensions |
| `SBA_ASK_DEFAULT_CITATIONS` | `6` | Default answer citation count |
| `SBA_ASK_DEFAULT_RETRIEVE_RESULTS` | `10` | Default retrieval result count |
| `SBA_ASK_ANSWER_MAX_TOKENS` | `640` | Answer token budget |

### Hook environment variables

Capture and recall hooks are opt-in; see [Connect an agent](agent-integration.md) for registration.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SBA_AGENTIC_URL` | `http://localhost:8766` | Capture/recall service URL |
| `SBA_AGENT_SOURCE` | Positional source argument, otherwise `unknown` | Capture source override |
| `SBA_RECALL_WITHIN_HOURS` | `720` | Recall lookback, 30 days |
| `SBA_RECALL_LIMIT` | `3` | Maximum hook items |
| `SBA_RECALL_MAX_CHARS` | `4000` | Hook context-block budget, separate from MCP's default 24,000-character item-text clamp |
| `SBA_RECALL_CLIENT` | `unknown` | Client label; `--client` takes precedence |
| `SBA_RECALL_LOG` | `recall.log` in the user's Black Box state directory | Fire log; explicit empty value or `off` disables it |
| `SBA_RECALL_PURPOSE` | `normal` | Declared `normal`, `audit`, or `test` use; other values normalize to `unknown` |
| `SBA_RECALL_PROJECT_ALIAS` | `unknown` | Safe declared alias; must satisfy the format and server allowlist |

Hook success does not prove persistence or delivery: the bridges deliberately do not fail the
host turn. Their current HTTP requests do not attach bearer authentication. The bundled runner
has the same limitation; setting server-side `SBA_AUTH_API_TOKEN` does not wire these clients.
Use a credential-aware integration for authenticated deployments. Fire logs contain paths and
session identifiers; keep them private. Server [recall telemetry](recall-observability.md) excludes
raw query/result text and does not prove that returned context was read or useful.

## Secure file navigation

Expanded Stream, Browse, Find, and Projects event cards make file references actionable only when
the path belongs to a filesystem-verified Git scope in the project catalog. The browser sends a
`CodeReference` with a `projectKey`, a relative path, and an optional line/column to
`POST /api/open-in-editor` or `POST /api/reveal-in-finder`, so a target is addressed by key plus
relative path rather than by a client-supplied absolute path. The key is a stable, URL-safe encoding
of the catalog root, not a secret: the safety property is the server-side revalidation below, not
path hiding. Paths outside eligible roots remain visible and copyable.

The server revalidates catalog membership, exact-scope confinement, symlinks, file existence, and
line bounds on each request. It invokes only a configured, allowlisted absolute executable with
discrete argv; file contents and event data are never evaluated by a shell. Cursor is the macOS
default, and VS Code uses the same `-g file:line:column` adapter. Finder reveal uses the same
resolver and a fixed `/usr/bin/open -R` command. Typed failures render beside the path.

## Schema evolution

There is no migration framework such as Flyway or Liquibase. Spring's `sql.init.mode=always`
reapplies additive, idempotent DDL in [`schema.sql`](../src/main/resources/schema.sql) on boot.
`CREATE TABLE IF NOT EXISTS` creates missing tables; it does not update existing table columns.

For existing SQLite databases,
[`RecordingSqlStore.ensureSchema`](../src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java)
inspects `PRAGMA table_info(agent_sessions)` and performs guarded `ALTER TABLE` additions for
`title_rank` and `spawned_by`. Legacy titles receive a protective rank. The PostgreSQL profile
uses [`schema-postgres.sql`](../src/main/resources/schema-postgres.sql) and skips SQLite PRAGMAs,
FTS5 initialization, and native-extension loading. This is an additive startup scheme, not a
versioned migration history, rollback facility, or database synchronization mechanism.

SQLite FTS5 is rebuildable; its triggers maintain the event index during canonical writes.
Do not run `VACUUM` on the live SQLite database without rebuilding FTS afterward: implicit event
rowids can change. LIKE fallback uses substring matching; it is not identical to FTS5 token-prefix
matching. See [Architecture](architecture.md#local-first-and-model-boundaries).

## Related guides

[Authentication](authentication.md) covers browser sessions, bearer requests, and HTTPS deployment.
[PostgreSQL](postgres-backend.md) covers shared storage and its single-server limits.
[Local writes and Elasticsearch](local-writes-and-elasticsearch.md) covers the optional local index.
The cloud work is a single-owner managed AWS prototype, documented in
[docs/lightsail-prototype.md](lightsail-prototype.md); it is not a public service.
