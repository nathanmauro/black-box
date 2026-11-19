# Storage diagnostics

`GET /api/diagnostics/storage` returns one read-only snapshot of storage health: the database
footprint, per-table row counts, full-text and embedding index parity, redaction counters, and the
state of the nightly backup manifests. It is for operators and agents checking that the recorder,
its indexes, and its backups agree with each other.

```bash
curl -fsS http://localhost:8766/api/diagnostics/storage | jq
curl -fsS 'http://localhost:8766/api/diagnostics/storage?verifyChecksums=true' | jq .backups
```

The endpoint sits behind the same [authentication](authentication.md) boundary as the rest of
`/api` when authentication is enabled. Only `GET` is routed.

## Safety contract

- **Read-only.** Counts, `PRAGMA` readings, file sizes, and a directory listing. No checkpoint,
  `VACUUM`, `ANALYZE`, repair, or rebuild, and nothing is written to the database or the backup
  directory.
- **Sanitized.** File names but never directories. Counts but never content. No hash, salt, MAC,
  configured value, redaction pattern, or setting name. Tests assert that none of those, including
  the temporary directory and home path, appear in a response.
- **No secrets held.** The backup passphrase is never read. Manifest MACs are therefore reported as
  `"authentication": "not_verified"`. Cryptographic verification stays with the offline
  `restore-test` (see [database recovery](database-recovery.md)).

## Fields

| Section              | What it reports                                                                                                                                                                                                                                                                                                                                                                                                |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `database`           | `backend`, main file name, `fileBytes`, `walBytes`, `shmBytes` (0 when absent), `journalMode`, `pageSize`, `pageCount`, `freelistPages`. File fields are null on PostgreSQL.                                                                                                                                                                                                                                   |
| `tableRows`          | Row count per ordinary table. Virtual tables are skipped and their shadow tables are counted, which is the same selection the backup manifest records, so the two can be compared directly.                                                                                                                                                                                                                    |
| `indexes.fullText`   | `agent_events` rows against `event_fts` rows. `in_sync`, `drift` (counts differ after backfill), `backfilling`, `unavailable`, or `not_applicable` on PostgreSQL, plus the resumable backfill's progress.                                                                                                                                                                                                      |
| `indexes.embeddings` | Embeddable events (decision, handoff, observation, idea, evidence) against current-model embeddings, orphaned and other-model embeddings, session summaries, and, with sqlite-vec, `memory_vec` rows against canonical current-model rows. Status is `vector_drift` first, then `in_sync`, `missing_embeddings`, or `embedding_disabled`. An event with blank text counts as embeddable but is never embedded. |
| `redaction`          | Process-lifetime counters per channel (`ingest`, `export`, `summary`): texts inspected, texts changed, characters inspected. Also whether ingestion rules are on, whether they are the defaults, and how many configured secret values are masked. Counters reset when the service restarts.                                                                                                                   |
| `backups`            | The newest manifests (at most 12) in the configured directory, each checked for the sealed envelope and fixed KDF parameters, supported format and archive cipher, a label that matches the file name, a valid `created_utc`, `integrity_check = ok`, and a sibling archive whose size matches. Archive names that look like paths are refused, not resolved. Symlinked manifests are ignored.                 |

`backups.status` is decided by the newest manifest per database label: `ok`, `stale` (older than
`backup-max-age-hours`), `failed` (any problem code), `no_manifests`, `missing_directory`, or
`not_configured`. Older sets are listed but do not affect the status.

`verifyChecksums=true` also recomputes the SHA-256 of the newest archive per label. That reads every
archive byte, so it is off by default.

## Configuration

| Variable                               | Default                                      | Purpose                                                                                              |
| -------------------------------------- | -------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `SBA_DIAGNOSTICS_BACKUP_DIR`           | `~/.local/share/sba-agentic/backups/nightly` | Directory holding the local copy of the sealed manifests and archives. Blank skips the backup check. |
| `SBA_DIAGNOSTICS_BACKUP_MAX_AGE_HOURS` | `36`                                         | Age after which the newest manifest for a label is reported `stale`.                                 |

The default matches the nightly job's `SBA_BACKUP_LOCAL_DIR`. If that job writes elsewhere, point
`SBA_DIAGNOSTICS_BACKUP_DIR` at the same directory.
