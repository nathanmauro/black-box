# Database snapshots and recovery rehearsals

> **Audience and status:** operators. The backup CLI is shipped; the rehearsal described here was
> verified only with disposable fixtures, not against an installed service.

Black Box's selected relational database is canonical. SQLite remains the local default;
PostgreSQL is a separate optional deployment. A snapshot preserves that database's recorded state;
it does not synchronize deployments or prove that every external transcript was captured.

The standalone [backup CLI](../scripts/storage/blackbox_backup.py) never starts Spring, runs startup
migrations, or retires legacy tables. It uses SQLite's native backup API or a PostgreSQL custom-format
archive. It does not export a known table list or reconstruct vector indexes from logical records.

The verification described here uses disposable fixtures and does not establish recovery for an
installed database. A production recovery plan still needs a chosen destination, protected artifact storage, retention,
restore rehearsal, and confirmation of the external files required by that deployment.

## Plan before executing

Python 3.9 or newer is required. Commands without `--execute` print one JSON plan and return without
opening a database, invoking PostgreSQL tools, or creating an output file or directory. Plan output
checks argument syntax; it does not prove source availability, credentials, disk space, or restore
readiness.

These paths and connection settings are disposable examples, not an installed service:

```bash
python3 scripts/storage/blackbox_backup.py sqlite \
  --source ./fixtures/blackbox.db --output ./artifacts/blackbox.snapshot.db

python3 scripts/storage/blackbox_backup.py postgres \
  --host 127.0.0.1 --port 55432 --database blackbox_fixture \
  --username fixture_owner --schema rehearsal_001 \
  --output ./artifacts/blackbox.snapshot.dump
```

To create an artifact, inspect the plan, create a private destination directory separately, and
repeat the chosen command with `--execute`. The output must not exist. Existing files, directories,
and even dangling output symlinks are refused. Output directory components must be real directories,
not symlinks; use their canonical paths on systems with temporary-directory aliases. The caller
must control the destination directory and keep its ancestors stable during execution.

The tool stages data in a private directory, validates it, computes its SHA-256, and publishes the
finished file atomically without replacing any existing name. Files have mode `0600`; staging
directories have mode `0700`, independent of a permissive umask. Use a local filesystem supporting
hard links and file/directory fsync. Normal failures remove staging data and never report completion.
An abrupt process kill or machine failure can leave a hidden `.blackbox-backup-*` staging directory;
it is not a completed backup. A crash after atomic publication can leave a valid artifact even if
completion JSON was never delivered. Inspect and rehearse that artifact before relying on it.

Successful execution prints one JSON object:

```json
{
  "status": "complete",
  "backend": "sqlite",
  "format": "sqlite3",
  "scope": {"source": "/example/fixtures/blackbox.db"},
  "output": "/example/artifacts/blackbox.snapshot.db",
  "bytes": 8192,
  "sha256": "<64 lowercase hexadecimal characters>"
}
```

A plan has `status: "planned"` and omits `bytes` and `sha256`. PostgreSQL uses
`format: "postgres-custom"` and a scope containing `host`, integer `port`, `database`, `username`, and
`schema`. The artifact is exactly the requested output file; there is no metadata sidecar. Preserve
completion metadata separately with the artifact. A checksum detects later byte changes; it is not
a restore rehearsal or proof of capture completeness.

Failures exit nonzero and print only `{"status":"failed","error":"<stable_code>"}`. The tool does
not echo arguments, exception details, tool stderr, passfile paths, or credentials on failure.
Common codes include `output_exists`, `unsafe_or_missing_output_parent`, `sqlite_sidecar_destination`,
`invalid_sqlite_source`,
`sqlite_backup_failed`, `postgres_tools_missing`, `postgres_dump_failed`, and
`postgres_archive_invalid`. The operation and offline PostgreSQL archive validation each have a
five-minute limit; large deployments need a separately reviewed backup approach.

## SQLite: preserve the native database

The source is opened with SQLite URI `mode=ro`; the backup API reads committed WAL contents. The tool
never uses `immutable=1`, writes source data, or asks the source to checkpoint. SQLite can still
update shared-memory reader bookkeeping while reading a WAL database. A source symlink, missing
file, zero-byte file, or corrupt database fails closed.

Never use the source database's `-journal`, `-wal`, or `-shm` path as a snapshot
destination, even when the sidecar does not exist. SQLite owns those names: a later
source write can delete or overwrite an apparently completed artifact. The CLI
reserves case variants and canonically equivalent Unicode spellings too, even on
filesystems that distinguish them. This uses canonical normalization and casefold,
not compatibility normalization. It rejects
obvious reserved names while planning, without filesystem access. Execution
also checks parent directory identities before staging or opening SQLite, so a source
parent symlink cannot bypass the check. This does not follow source leaf symlinks or
validate a plan's source availability. Keep source and destination directory paths
stable during execution.

Only the destination is placed in rollback-journal mode so the artifact is a standalone database
file. Physical `quick_check` must pass; application-defined CHECK expressions are disabled for this
check so an unavailable custom function does not force reconstruction or extension loading.
This check does not validate application semantics or run virtual-table extension integrity hooks.

The snapshot preserves rowids, IDs, BLOB bytes, timestamps, JSON, null-versus-empty values, receipts,
project aliases, decision-replacement relations, legacy Board tables, unknown objects, FTS tables
and their shadow pages, and native vector tables and their shadow pages. No table allowlist or
logical rebuild is used. Native extensions are not loaded by the tool. Using an extension-backed
virtual table after restore still requires the matching extension in the application environment.

Do not copy just the main file from a running WAL database: recent committed rows may live only in
its WAL. A raw copy of a source is safe only after a controlled shutdown/checkpoint and verified
absence of required WAL state, or through a separately designed coherent filesystem snapshot. The
native backup API is the implemented path here. See the [SQLite backup API](https://www.sqlite.org/backup.html)
and [WAL documentation](https://www.sqlite.org/wal.html).

For a disposable restore rehearsal, retain the snapshot as a protected, read-only master. Copy its
bytes into a **new, empty destination directory**, creating the database file exclusively; refuse an
existing destination or any destination `-wal`, `-shm`, or `-journal` files. Do not open the protected
master as the application's writable database. Verify the copied file against the recorded hash
before startup. Never restore over a running application's database or combine a snapshot with old
sidecars. Keep startup's explicit legacy-retirement option off when checking retained legacy data.

After opening only the disposable copy, verify schema/row counts and typed values, then exercise
capture, exact-project recall, decision relations, search, and a restart. FTS rowid alignment and
vector queries deserve direct checks; an empty rebuilt vector table is not a successful restore.

## PostgreSQL: one exact schema

Install a `pg_dump` compatible with the source server and a `pg_restore` capable of reading its
archive on PATH. Version mismatches fail; the tool does not install clients or silently choose a
different server. The host must be a single DNS name or IP address, the port must be explicit, and
the database must be a literal name, not a URI or libpq connection string. Unix-socket directories
and multi-host failover strings are not accepted by this CLI.

`pg_dump --format=custom` captures both definitions and data for the specified schema, including
unknown and legacy objects selected by PostgreSQL. The schema argument is quoted as one literal
identifier, so wildcard characters cannot widen scope. `--strict-names` requires it to exist.
No application migrations, table filtering, or vector rebuilding occurs during the dump.

The archive excludes other schemas, cluster-global roles/tablespaces, database-wide settings, and
non-schema large objects. Dependencies outside the schema, extension installation/binaries, and
role/ownership prerequisites require separate recovery planning. This is a **schema backup**, not a
whole-cluster disaster-recovery artifact. PostgreSQL explains these selection limits in the
[`pg_dump` documentation](https://www.postgresql.org/docs/current/app-pgdump.html).

Provide a password only through `PGPASSWORD` or an explicitly selected `PGPASSFILE` supplied by your
credential workflow. Do not place passwords in CLI arguments or a connection URI. The tool sets
`--no-password`, closes child stdin, and disables implicit default passfile discovery when no
`PGPASSFILE` is given. Ambient libpq routing and behavior overrides such as `PGSERVICE`, `PGHOSTADDR`,
`PGDATABASE`, `PGUSER`, and `PGOPTIONS` are removed. Explicit host/port/database/user arguments own
the destination. Explicit TLS/GSS protection environment settings are retained; this tool does not
configure or weaken server transport security.

Before publishing, the tool checks the custom archive header and size, then uses `pg_restore` to
decode the entire archive into the null device. This performs **no database connection or SQL
execution** and catches unreadable or truncated data that a table-of-contents listing alone can
miss. It cannot prove the archive restores correctly with the destination's roles and extensions.
Tool stderr is discarded to avoid leaking connection details; a failed operation returns a stable
code, not a raw PostgreSQL error.

A real restore rehearsal must target a separately provisioned disposable database/schema with
compatible extensions and roles. Compare native typed records and application behavior after
restore and after restart. Never use a broad cleanup against a shared cluster to make a rehearsal
pass. This change intentionally provides no production restore command.

## Outside the artifact

- External transcript files and their availability/retention remain separate from recorded events.
- Hook outboxes and pending capture queues may contain input not yet committed to the canonical DB.
- Gateway receipts, publisher delivery ledgers, and other companion databases are separate stores.
- Credentials, passfiles, model configuration, extension binaries, and deployment configuration
  require their own protected recovery process.
- Elasticsearch and model summaries are supporting systems, not substitutes for canonical records.

A database snapshot alone does not prove a lossless cutover to event-backed transcripts. Preserve
original evidence until measured capture completeness and the owner's accepted loss budget support
that separate decision.

## Verification

The Python suite invokes the actual CLI against temporary SQLite databases and fake PostgreSQL
executables:

```bash
python3 -m unittest discover -s scripts/storage -p 'test_*.py' -v
```

It covers WAL commits, stable source DB/WAL bytes, rowids, FTS, legacy/unknown objects, an unavailable
virtual-module schema with retained shadow pages, private permissions, dry runs, missing/corrupt
sources, reserved SQLite sidecar destinations (including source-parent aliases), snapshots that
remain unchanged after later source commits, output symlinks/collisions including publication races, connection-override rejection,
credential-safe errors, and truncated archive rejection. The fake PostgreSQL tools check subprocess
boundaries; native PostgreSQL restore evidence is a separate integration check.

The companion `DatabaseRestoreContractTest` rehearses real SQLite/WAL and optional PostgreSQL
backup/restore journeys with exact typed comparisons across all 13 canonical tables, including stream counter/generation and position anchors, retained legacy
objects, unknown schema objects, FTS, HTTP/MCP behavior, and restart. Its PostgreSQL method requires
an explicitly configured disposable `SBA_POSTGRES_TEST_URL`, username/password test environment, and
compatible PostgreSQL tools on PATH. This contract preserves canonical embedding bytes; native
`vec0` is disabled there. Native vector query/hydration acceptance belongs to its separate fixture
lane and is not claimed by these portable tests.

See the [backup rehearsal plan](superpowers/plans/2026-10-02-database-backup-rehearsal.md) for the work
boundary and verification record.
