# NAT-195: projectless saved braids

## Contract and ownership

Implement only the saved-artifact path. A missing/null `projectKey` is accepted only when
`metadata.kind` is exactly the string `braid`; blank keys remain invalid. Such a braid requires
2–8 normalized distinct existing sessions, retains their first-occurrence order and may span
projects. Non-null project keys retain current canonical/alias and session ownership validation.
Ordinary/project-owned saves, previews, models, embeddings, recall and Constellate are unchanged.

Persist an explicit `artifact_kind` (`meld` or `braid`, default `meld`) and permit NULL ownership
only for braids. Preserve opaque metadata without claiming that caller evidence IDs were verified.
New braid input rows store server-derived source/clientSessionId/cwd provenance; existing input bytes
remain untouched and historical missing snapshots cannot establish an original cwd.

Add GET `/api/melds/{id}` (404 when absent) and explicit GET
`/api/melds?kind=braid&scope=unassigned&limit=50&before=...`. Reject other/missing filters, unknown
parameters, invalid limits and cursors. Limit is 1–100, default 50. Return `{items,count,nextBefore}`
with nullable nextBefore, ordered by precise createdAt descending then ID descending. Use a bounded,
versioned opaque cursor and existing nanosecond-safe SqlInstant comparisons. Unassigned responses
have null projectKey/canonicalKey. Null ownership never enters project counts/timelines/graphs.

## Migration safety

Use a project-owned post-schema initialization component with a single transaction. SQLite needs
a parent table rebuild; copy existing values exactly, leave inputs untouched, recreate explicit
indexes/triggers and publish by rename within that transaction. Fail closed with an actionable
message for unexpected columns, constraints or foreign-key dependencies; do not silently discard
custom invariants. PostgreSQL uses transactional ALTER/backfill/CHECK changes. Backfill only an
exact parsed JSON string metadata.kind=braid; malformed/nonobject/other metadata stays an ordinary
meld. Original metadata and existing ownership stay unchanged. Create the new keyset index after
migration, since old tables lack artifact_kind. Startup repetition is a no-op for canonical data.

Do not run against user databases or service 8766. SQLite fixtures and explicitly provisioned
schema-isolated PostgreSQL are the only mutation targets. An older binary is unsupported after
null-owned writes; recovery is forward repair or an explicitly restored verified backup, never
artifact deletion. Root owns Git/PR/CI/merge/Linear/archive; this worker owns source/tests only.

## Verification sequence

1. Reproduce missing-project rejection through actual HTTP with two existing sessions and no providers.
2. Fresh and legacy SQLite/PostgreSQL migration, exact rows/input bytes, old ordinary/project braid,
   explicit indexes/triggers, repeat startup, unexpected schema rejection and injected rollback failure.
3. Actual HTTP save/get/list/restart; precise/tied keyset pages; null/blank/kind and min/max/duplicates/
   missing-session errors; parent/input rollback; ordered provenance; unchanged captures/project views.
4. Focused then full backend/module checks, scoped Palantir/diff, independent migration/source review.
   Record actual evidence and limitations before freezing the exact owned paths for root finishing.

## Observed evidence

- Baseline real HTTP save with two existing sessions and no project returned 400, reproducing the
  missing capability before implementation.
- Thirty new focused checks pass: eight HTTP cases per database, nine SQLite migration cases,
  three PostgreSQL migration cases and two cursor cases. They exercise an old-schema application
  startup and restart, exact legacy row/input preservation, fresh constraints, attached SQLite
  indexes/triggers, rejection of custom invariants, injected late-DDL/input-write rollback,
  nanoseconds, equal timestamps, signed extended years and Instant.MIN/MAX pagination.
- Existing meld/controller compatibility plus regenerated REST/wire checks bring the selected
  verification set to 73 passing tests. Serializer-driven `contracts.update` generated the added
  mappings/list DTO fixture; its first snapshot assertion read the prior classpath resource, then
  the normal verification passed against the regenerated file.
- Independent review found legacy input JSON literal `null` would cause a read 500. A real HTTP
  upgrade fixture reproduced it; a local absent-snapshot fallback fixes the read without changing
  historical metadata. Missing joined sessions and wrongly typed legacy snapshot fields are covered.
- A final malformed-JSON control reproduced Jackson accepting a leading braid object followed by
  another JSON value or junk. The migration now uses an immutable strict reader; all twelve
  migration checks pass with those rows retaining ordinary kind and original bytes on both databases.
- Final full native Maven suite: **1,041 tests, zero failures/errors, four unrelated skips**.
  All nine architecture/module checks pass. PostgreSQL braid HTTP (8), PostgreSQL migration (3),
  existing database consumers and native backup/restore ran with no skips. No browser/UI or
  production migration acceptance is claimed by this backend slice.
- Scoped pinned Palantir apply/check and `git diff --check` pass. The exact existing CI database
  report guard, including the two added suites, was executed against these reports and passes.
- Read-only final PostgreSQL inspection reconfirmed the authorized fixture identity and found
  zero remaining `bb_braid_` schemas. App contexts are closed; root owns fixture shutdown.

All runtime evidence uses disposable SQLite files or owned random schemas in the explicitly
provisioned PostgreSQL fixture. No user service, database, provider, hook configuration or external
publishing system was changed. Parent coordinates fixture cleanup and all Git/publication work.


## Frozen handoff

Source and contracts are ready for coordinator review/integration. Runtime evidence and the exact
26-path owned manifest are in the coordinator's temporary handoff files; no Git mutation was
performed by this worker. The migration was independently reviewed, including the legacy JSON-null
read fix. The final strict trailing-token reader was reproduced and verified on both databases and
is highlighted for coordinator review. No other feature was added during finalization.
