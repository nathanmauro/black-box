# Native database backup and disposable restore rehearsal

Status: implemented and verified after integration. The snapshot CLI and restore contracts below
use disposable fixtures; this unit does not run a live database backup or restore.

## Safety contract

- A standalone Python CLI plans by default. Only `--execute` connects to a source and creates an
  artifact; it never starts Spring or runs startup migrations.
- SQLite uses the native backup API through a read-only source connection. Preserve the whole
  database, including legacy tables, rowids, FTS, unknown virtual modules and vector shadow state.
- PostgreSQL uses a native custom-format archive for one exact schema, with all contained objects
  selected by `pg_dump`. Credentials stay in explicit environment/passfile channels. Do not accept
  conninfo in the database argument, inherit routing overrides, or print subprocess errors.
- Require an absent output and preexisting, trusted directory without symlink components; stage
  privately and publish atomically without replacement. A failure never reports completion.
- Restore verification belongs only to disposable fixtures. This change adds no production restore
  command, no scheduled job, no live operation, and no cross-database synchronization.

## Work units

1. Add `scripts/storage/blackbox_backup.py` and actual-CLI Python tests for native SQLite snapshots,
   WAL commits, unknown/legacy/virtual objects, dry runs, corrupt sources, file collisions, symlinks,
   fake PostgreSQL tools, scope constraints, credentials and archive validation.
2. Document recovery boundaries and manual rehearsal prerequisites in `docs/database-recovery.md`.
3. Coordinate with the Java restore-contract fixture lane. It will restore artifacts into isolated
   SQLite/PostgreSQL fixtures and compare the application storage contracts. PostgreSQL requires
   compatible `pg_dump` and `pg_restore` on PATH; offline archive decoding rejects truncated output.
4. The integration owner adds CI/verify wiring and documentation links, reviews the combined diff,
   runs real native restore fixtures, and handles Git publication.

## Verification record

- `python3 -m unittest discover -s scripts/storage -p 'test_*.py' -v`: 22 tests passed.
- Actual CLI SQLite backups retained committed WAL rows, unchanged source DB/WAL bytes, rowids,
  binary values, legacy/unknown tables/views/indexes/triggers, FTS queries, and unavailable virtual
  module schema/shadow pages. Artifact checksums, private permissions, and cleanup passed.
- Fake PostgreSQL tools verified exact schema quoting, explicit connection scope, stripped ambient
  overrides, environment/passfile credential channels, no prompts, redacted failure output,
  offline archive validation, and collision-safe publication. Native PostgreSQL restore proof is
  owned by the Java fixture lane, not inferred from fake tools.
- Integrated CLI suite: 22 tests passed. Actual native SQLite/WAL and PostgreSQL restore methods:
  two tests passed, zero failures/errors/skips. The full backend suite with PostgreSQL ran 587 tests,
  zero failures/errors and four unrelated skips; all 16 backend, four authenticated/configuration
  and two restore checks ran without skips. Scoped Java formatting and diff checks passed.
- Restore fixtures compare all 11 canonical tables plus legacy and unknown objects, typed text/BLOB
  values, nanosecond timestamps and null/empty distinctions. Actual HTTP/MCP checks cover recall,
  aliases, replacement history, full payloads, lineage, saved synthesis, original receipt replay,
  fresh capture and persistence through restart. SQLite FTS event identity is checked directly.
  Native sqlite-vec querying is deliberately outside this restore fixture; canonical vector bytes
  and database pages are preserved. No leftover restore schemas remain in the fixture server.
- CI now runs CLI safety checks, installs matching PostgreSQL clients and rejects skipped database
  consumer or restore contract suites. The local verification script includes CLI checks too.

The source database and WAL data are never written or checkpointed by the tool; SQLite itself may
update shared-memory reader bookkeeping while reading a WAL database. External transcript files,
hook outboxes, credentials, and unrelated databases are outside these artifacts.

Handoff: the snapshot CLI, restore contract, CI/local verification and recovery documentation form
one integrated unit. No production restore, scheduled backup or migration is introduced. The next
acceptance step is CI on the published PR; installed-database recovery requires its own rehearsal.
