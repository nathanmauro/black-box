# Run it

Black Box runs as one Spring Boot process, with SQLite as the default canonical store. Capture,
session lineage, and lexical recall need neither a model nor Elasticsearch. An optional PostgreSQL
profile owns a separate database for shared clients; it does not synchronize local history.
Commands below assume the repository root unless a path is explicit.

## Command-line help

Discover the JAR's commands without starting the application:

```bash
java -jar target/sba-agentic-0.2.0.jar --help
java -jar target/sba-agentic-0.2.0.jar ingest --help
java -jar target/sba-agentic-0.2.0.jar help search
```

Top-level `-h` and `help`, and a known command followed by `-h`, also print help and exit
successfully. Help is handled before Spring reads configuration, opens a database, starts HTTP,
contacts a provider, or reads stdin. Command-specific help lists that command's implemented options.
Unknown or retired leading commands remain errors even when followed by `--help`; `help unknown`
is also an error, with no application startup.

Help flags must be separate arguments. Values such as `--text=--help`, `--q=--help`, and the
positional query `search help` remain normal command data. Running without a command starts the
HTTP service. Running a command without a help flag performs its normal operation: `ingest` writes
an event, summary commands use the configured summary backend and persist results, and
`embeddings-backfill --apply` generates and writes missing embeddings. The help-only guarantee
does not make those operations read-only or disable their configured providers.

A normal command closes its application context and exits after its synchronous operation finishes.
A command failure still exits nonzero. Successful `ingest` confirms canonical capture; optional
background terminal summaries and judgments can be interrupted during shutdown. Run `summarize`
or `summarize-missing` explicitly when you need to wait for the summary operation and its stored
result. No-command HTTP service mode stays running until shutdown.

## CLI capture input

Use equals-form options, for example `ingest --session=review --text='Keep the existing schema'`.
Ingest rejects extra positional arguments, including a value separated from its option by a space.
A present capture option must have a value (`--name=value`); a bare `--session` or `--tool` is an
error instead of selecting a default. Source, session and type must be nonblank when supplied.
These argument errors fail before stdin is read or a capture is persisted. Omitted options retain
their defaults, optional fields may still be explicitly empty, and Spring configuration options
remain supported. Other commands keep their existing argument syntax.

`ingest --text='note'` uses that value without reading stdin. An explicit empty or whitespace-only
value is also authoritative; use `--text=''` for a metadata-only capture. A bare `--text` without a
value is an error.

Without `--text`, piped, redirected or headless stdin is read through EOF, so a slow producer can
finish sending the capture before it is acknowledged. Input must be valid UTF-8 and at most 1 MiB
(1,048,576 bytes). Oversized input, malformed UTF-8 or a read error fails before any event is saved;
empty EOF permits a no-text capture. Accepted input still follows the configured canonical
redaction and text-length limits, so the input byte cap does not promise that all text is retained.

An attached Java console is not read, preserving nonwaiting metadata-only capture. Java can report
no console when stdout is redirected even if stdin is still a terminal; that case waits for EOF
unless `--text` is supplied. A pipe that never closes likewise waits for its producer to finish.

## Run as a service

### macOS launchd

The deployment script updates an **existing healthy installation**. For initial setup, render and install
[`scripts/black-box.plist.template`](../scripts/black-box.plist.template) with the actual Java,
JAR, database, log, and service-label settings, build the initial JAR while no service uses it,
bootstrap the service, and verify `/api/status`. For subsequent updates, use:

```bash
./scripts/deploy-local.sh
```

The script requires Python 3.9+, an existing healthy installation, and an installed plist with an
absolute `WorkingDirectory` and an explicit `java -jar /absolute/path/to/application.jar` command.
`/usr/bin/env KEY=value ... java -jar ...` is also supported. Other wrappers, JVM/application
arguments, path aliases, and hard-linked JARs are rejected. Add `WorkingDirectory` when installing
the template; the deploy script preserves the installed plist bytes. It compares the loaded
program, arguments, working directory, and environment with that plist before deployment and
after restart, rejecting unpinned inherited application/JVM settings. External configuration
files and external commands referenced by the installation are not fingerprinted or restored.

For a reviewed build made in an isolated checkout, use its absolute canonical path:

```bash
./scripts/deploy-local.sh --prebuilt-jar /path/to/isolated-build/target/sba-agentic-0.2.0.jar
```

Prebuilt mode never runs Maven. Without `--prebuilt-jar`, deployment clean-builds the frontend and
JAR in this checkout, which must exactly match the installed `WorkingDirectory` and Maven output.
Tests are skipped unless `--with-tests` is passed. Build and review a candidate separately when the
installed checkout has unrelated dirty work.

`SBA_LAUNCHD_PLIST` selects an existing plist (default
`~/Library/LaunchAgents/com.nathan.sba-agentic.plist`); `SBA_LAUNCHD_LABEL`, when supplied, must
match its label. Only the current user's `gui/<uid>` domain is supported. `SBA_JAR_PATH` and
`SBA_PORT` are optional assertions and must agree with the plist. `SBA_STATUS_URL` must address
`/api/status` on that installed local port, with no credentials or redirects. The default is
`http://127.0.0.1:<installed-port>/api/status`. The readiness port must belong to the exact new
launchd PID; a healthy response from another process does not count.

Before stopping, deployment validates the Boot archives, checks existing process health, stages
a supplied prebuilt candidate, and saves a hashed copy of the previous JAR outside `target/`. It acquires an
exclusive installation lock and refuses to build or replace a JAR while the previous PID or any
other JAR consumer remains alive. Stop errors are fatal. Installation uses an atomic rename,
then bootstraps the unchanged plist and verifies the new PID, HTTP 200, and the canonical
`storage.events`/`storage.sessions` count fields. Optional model/index availability is not required.

Build, installation, bootstrap, readiness, and handled interruption failures trigger binary
rollback and a readiness check of the restored service. The command still returns failure after
a successful rollback. `SBA_DEPLOY_STOP_TIMEOUT` defaults to 20 seconds and
`SBA_DEPLOY_READY_TIMEOUT` to 60 seconds; each phase is bounded. Recovery files are retained in a
private `.blackbox-deploy/<installation-id>/recovery-*` directory beside the installed checkout.
The command prints its exact location; it includes `previous.jar`, the original plist bytes,
checksums/identity in `recovery.json`, and a build log when applicable. These files can contain
private installation configuration: keep them local and remove obsolete recovery directories
only after verifying the deployment.

If rollback is not verified, preserve that directory and inspect the reported service identity.
Stop the service and confirm its PID and all JAR consumers are gone before restoring
`previous.jar` to the recorded destination. Verify its SHA-256 against `recovery.json`, bootstrap
the unchanged installed plist, and check the new PID, port ownership, and `/api/status`. The script
does not kill unrelated consumers or rewrite a plist that changed during deployment. An
uncatchable termination or machine crash requires this manual recovery procedure; the OS releases
the advisory deployment lock automatically.

Binary rollback does **not** reverse database migrations or writes made by the candidate. Deploy
only backward-compatible schema changes when relying on binary rollback, and maintain a separate
verified database backup/recovery procedure. Additive capture-receipt tables, for example, remain
in the database after restoring an older binary. The script does not back up or restore databases.
Use the separate [database snapshot and restore rehearsal](database-recovery.md) to verify recovery
before relying on a backup or performing an irreversible schema change.

Do not replace the executable JAR underneath a running JVM. An ordinary `mvn package`, including
packaging performed for E2E tests, can overwrite `target/` while a service is using it. Use the
managed deploy sequence or build in an isolated checkout/output location. A live PID alone does
not establish health: check `/api/status`, the UI, and a representative API route after restart.

### Retired runner state

The task board and runner are retired. The runner CLI, deployment script, launchd template, and
example configuration are no longer supplied. Repository cleanup leaves existing machine-local
runner files, services, logs, registries, and worktrees untouched. Recovering or removing them is a
separate operator action; do not start an old runner against the updated API. See
[retirement and upgrade notes](board-retirement.md).

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
The same relative-command consideration applies to the launchd template.

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
| `SBA_REDACT_ENABLED` | `true` | Redact secret-looking text and structured secret fields before persistence |
| `SBA_EDITOR_ENABLED` | `true` | Enable catalog-bound open-in-editor actions |
| `SBA_EDITOR_COMMAND` | Cursor application CLI on macOS | Absolute Cursor/VS Code-compatible executable |
| `SBA_EDITOR_ALLOWLIST` | Fixed Cursor and VS Code CLI locations | Comma-separated absolute executable allowlist; not automatic discovery of installed editors |
| `SBA_EDITOR_TIMEOUT` | `5s` | Maximum editor/Finder handoff time |
| `SBA_EXPORT_OBSIDIAN_DIR` | Empty | Configure the built-in Markdown summary export target; export is explicitly requested through API/UI |
| `SBA_PROJECTS_VOICE_CANONICAL_SCOPE` | Empty | Optional verified voice project path. When set, exact dated Codex voice-session directories (`~/Documents/Codex/YYYY-MM-DD/realtime-voice-chat[-N]` or `YYYY-MM-DD-new-realtime-voice-chat`) are grouped under it as reversible `codex-voice` aliases. Recorded session paths are preserved and captures are never classified by a project mentioned in conversation. Leave unset to disable; existing `codex-voice` aliases can be removed with `DELETE /api/project-aliases?aliasKey=...`. See [ChatGPT MCP gateway](chatgpt-mcp.md). |

Ingestion redaction applies before storage to event text, tool input/output, and metadata. With
its default patterns, nested JSON member names are matched case-insensitively after removing
non-alphanumeric separators. Names containing `apikey`, `secret`, `token`, `passwd`, `password`,
`authorization`, `credential`, or `privatekey` replace the **entire value** with `[REDACTED]`,
including short strings, numbers, nulls, lists, and objects. This conservative policy matches the
[durable hook](durable-capture.md#privacy-and-limits); it may also hide benign values such as
`tokenCount`. Ordinary identity and metadata fields keep their structure.

Default text rules also scan named assignments inside string leaves, including JSON text returned
by tools. Existing secret-name spellings (such as `password`, `api_key`, `client_secret` and
`access_token`, including their existing prefixes/suffixes) accept `=` or `:`, bare or matching
single/double quoted keys, and values of any length. Quoted values can contain whitespace and
backslash-escaped quotes; bare values end at whitespace or a JSON comma/closing bracket/brace.
Complete `[REDACTED]` markers inside a bare credential are consumed as whole spans, so repeated
markers cannot expose a trailing credential suffix. Genuine closing delimiters still end the value.
Other text is preserved. An unclosed quoted credential consumes the remainder of the scanned
scalar. Input is clipped before scanning at 50,000 UTF-16 code units with Unicode-safe clipping
and the existing truncation marker; replacement markers can expand short values. This is a small
assignment grammar, not a shell parser, JSON decoder, or general detector for encoded or unlabelled
secrets. Model export also applies these default
assignment rules independently of ingestion settings and retains its conservative fallback for
escaped quoted credential keys, which may remove the rest of a text leaf.

String member names are also scanned with the active text-redaction patterns. If redacting a name
would collide with another member, the changed name gains a ` (redacted key N)` suffix so that the
other field is preserved. Custom `sba.ingestion.redact-patterns` replace the default text patterns
**and disable the default secret-key classification**; those custom patterns still scan string
values and member names. Disabling ingestion redaction leaves those inputs unchanged. This is
best-effort sanitization, not a guarantee that arbitrary secrets are recognized, and does not
retroactively scrub existing captures. Scalar truncation limits still apply. Summary/model export
has a separate redaction boundary; configuring ingestion does not replace it.

Summary Markdown export resolves its explicitly configured root to a canonical directory; a
configured root alias is supported. Descendant directory symlinks and symbolic-link/non-regular destination
notes are rejected. Existing hard-linked notes are replaced without changing their other aliases. Ordinary repeat exports replace the completed note atomically, preserving
existing POSIX permissions where supported. Failed rendering, staging or atomic publication leaves
the previous note intact; filesystems without stable file identities or atomic replacement fail
closed. New staged files use the platform's private temporary-file permissions.

Export directories must be caller-controlled and remain stable during the operation. Identity
checks detect observed directory/file replacements, but portable Java filesystem operations do
not guarantee resistance to adversarial concurrent directory renames. This is not a crash-durability
or cross-process editing protocol. Normal failures clean up staging files. If a directory moves
mid-export, a `.blackbox-export-*.tmp` file may remain in the displaced directory: cleanup avoids
following the replacement path. Inspect that orphan only after restoring a trusted directory layout.

Authentication represents one trusted workspace with one browser user and one agent token, not
tenant isolation or per-agent permissions. Both secrets must be independently generated, different,
at least 32 characters, and satisfy startup validation; never put secret values in command
arguments or tracked files. See [Authentication](authentication.md) for the complete boundary.

PostgreSQL support starts with one authoritative API replica. It adds neither local/cloud sync nor
multi-replica coordination. The opt-in contract test reads `SBA_POSTGRES_TEST_URL`,
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

Semantic recall returns Decisions, Handoffs, Observations, and Ideas. Projections are lexical-only;
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
| `SBA_CAPTURE_DURABLE` | `0` | Set `1` to enable the sanitized local outbox; its default URL is numeric-loopback `http://127.0.0.1:8766` |
| `SBA_CAPTURE_OUTBOX_DIR` | `~/.blackbox/outbox` | Private local capture queue directory |
| `SBA_CAPTURE_HTTPS_ORIGIN` | Unset | Exact normalized HTTPS destination opt-in; must match the selected URL and uses an origin-bound macOS Keychain bearer |
| `SBA_RECALL_WITHIN_HOURS` | `720` | Recall lookback, 30 days |
| `SBA_RECALL_LIMIT` | `3` | Maximum hook items |
| `SBA_RECALL_MAX_CHARS` | `4000` | Hook context-block budget, separate from MCP's default 24,000-character item-text clamp |
| `SBA_RECALL_CLIENT` | `unknown` | Client label; `--client` takes precedence |
| `SBA_RECALL_LOG` | `recall.log` in the user's Black Box state directory | Fire log; explicit empty value or `off` disables it |
| `SBA_RECALL_PURPOSE` | `normal` | Declared `normal`, `audit`, or `test` use; other values normalize to `unknown` |
| `SBA_RECALL_PROJECT_ALIAS` | `unknown` | Safe declared alias; must satisfy the format and server allowlist |

Hook success does not prove persistence or delivery: the bridges deliberately do not fail the
host turn. Legacy direct capture and recall hooks do not attach bearer authentication; setting
server-side `SBA_AUTH_API_TOKEN` does not wire these clients. The optional
[durable capture outbox](durable-capture.md#explicit-https-delivery) supports explicit HTTPS
delivery with a destination-bound credential. Fire logs contain paths and
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
reapplies idempotent creation DDL in [`schema.sql`](../src/main/resources/schema.sql) on boot.
`CREATE TABLE IF NOT EXISTS` creates missing tables; it does not update existing table columns.

For existing SQLite databases,
[`RecordingSqlStore.ensureSchema`](../src/main/java/dev/nathan/sbaagentic/recording/internal/adapter/out/sqlite/RecordingSqlStore.java)
inspects `PRAGMA table_info(agent_sessions)` and performs guarded `ALTER TABLE` additions for
`title_rank` and `spawned_by`. Legacy titles receive a protective rank. The PostgreSQL profile
uses [`schema-postgres.sql`](../src/main/resources/schema-postgres.sql) and skips SQLite PRAGMAs,
FTS5 initialization, and native-extension loading. This is not a versioned migration history,
rollback facility, or database synchronization mechanism.

NAT-243 adds an idempotent, opt-in retirement migration for both database profiles. Fresh schemas
omit the board; existing databases preserve legacy data unless `SBA_RETIRE_WORKFLOW=true`
(`sba.storage.retire-workflow=true`) is explicitly set. With that flag, startup removes `specs`,
`tasks`, and `task_events` and nulls/drops `session_links.task_id`, while retaining session links
and recorded events, including historical completion Handoffs. Verify a database backup before
enabling retirement. Binary rollback cannot restore the removed task data or its schema;
do not run an old binary against the migrated database. See [retirement notes](board-retirement.md).

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

## Canonical event chronology indexes

Startup adds three derived expression indexes for global event chronology, session event chronology,
and the human-turn subset. They normalize the stored UTC timestamp only inside index/query keys, with
nine fractional digits and an ordered signed-year encoding; canonical event bytes and existing indexes
remain unchanged. Repeated startup reuses the indexes. The first startup after this upgrade builds them
against existing history, which adds a one-time startup/disk cost and ordinary index maintenance on
subsequent writes. SQLite remains the default.

Feed and session transcript cursors retain their existing wire format. They compare normalized time
and event ID together, so whole-second, fractional and nanosecond events do not disappear when pages
are merged with transcript-file events. A later-arriving older event can appear in a remaining page;
this is keyset navigation, not a frozen database snapshot. Newer arrivals above an already-consumed
cursor require a head refresh, as before.

A disposable 50,000-event SQLite fixture (1% intent, 99% hook events) built all three indexes in
98 ms, adding about 6.0 MiB; repeat initialization took 9 ms. The first page used the ordered index
(80 microseconds); global, session and human deep-page queries used indexed range seeks with no
temporary sort (73–89 microseconds). Retrieving 100 recent intent events through the real recall
adapter took 2.9 ms. These are measured warmed fixture results, not production latency guarantees.
Deployment time, index space and filtered-query cost depend on database size, storage and workload.
