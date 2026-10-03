# Saved braids without a project

A saved braid combines existing sessions without assigning the result to one project. It is a
saved artifact, separate from the canonical captured events. Saving makes no model, embedding,
structured-recall or external publishing call. Project previews still require one project.

## Save and read

POST `/api/melds` accepts a missing or JSON-null `projectKey` only when `metadata.kind` is exactly
`"braid"`. Supply a nonblank `body` and 2–8 distinct existing `sessionIds`. IDs are trimmed, blank
entries removed and duplicates collapsed in first-occurrence order before checking the bounds.
A blank project key is invalid. Any non-null key retains the existing encoded-project and alias
validation, including membership of every selected session in that logical project. Ordinary
project-owned melds continue to accept 1–8 sessions.

```json
{
  "title": "Shared recovery approach",
  "body": "The saved synthesis and its supporting context.",
  "sessionIds": ["first-existing-session-id", "second-existing-session-id"],
  "metadata": {"kind": "braid", "members": ["caller-defined reference"], "evidenceIds": []}
}
```

The existing saved-meld response has `projectKey: null` and `canonicalKey: null` for an unassigned
braid. Its `sessions` retain input order. GET `/api/melds/{id}` returns that saved artifact, or 404.
There is no automatic project attachment; project counts, timelines and graphs exclude unassigned
artifacts. Metadata accepts JSON values, including ordinary nulls, and `members` and `evidenceIds`
are caller assertions, not verified canonical event references. New saves apply configured ingestion
redaction to title, body, provider, model, prompt version and metadata before persistence and the
response. Accepted sanitized metadata retains ordinary types, nulls and member order; default
secret-named members have their entire value replaced by the string `"[REDACTED]"`. Numeric
`tokenCount` or `inputTokens` therefore change type as well as value (for example, `1234` becomes
`"[REDACTED]"`); the default secret-key word list is documented in the linked redaction policy.
Enabled redaction, with either default or custom patterns, clips each string scalar before scanning
at 50,000 UTF-16 code units without splitting a surrogate pair, with a truncation marker.
Custom patterns replace the defaults;
disabling redaction also disables its scan clipping. See [redaction policy](operations.md#configuration).

Artifact kind and ownership are validated before metadata sanitization. Custom rules may change the
returned `metadata.kind` while the validated, persisted artifact remains a braid. Canonical ownership,
ordered input IDs and server-derived input provenance are not sanitized. Existing saved rows are
unchanged; this is not a historical scrub.

GET `/api/melds?kind=braid&scope=unassigned&limit=50` returns `{items,count,nextBefore}`. Both filters
are required exactly as shown; unknown, duplicate or unsupported parameters return 400. `count`
is the number on this page, not the total. Limit defaults to 50 and must be 1–100. Results descend
by precise `createdAt` then ID. Pass the returned opaque `nextBefore` unchanged as `before` for the
next page; null means this page reached the end. Invalid cursors return 400. Newer concurrent saves
appear when refreshing the first page; pagination is not a frozen database snapshot.

For newly saved braids, ordered input rows record server-derived source, client session ID and cwd
at save time. These provenance values survive later session moves. Titles, event counts and session
time bounds remain current joined session information. Legacy inputs have no original-provenance
snapshot and fall back to available session fields; missing historical sessions retain their input
ID, a zero event count and null unavailable text/time fields. This does not reconstruct deleted evidence or previously unknown
capture provenance.

## Storage upgrade and recovery

Startup upgrades existing SQLite and PostgreSQL schemas transactionally. The persisted
`artifact_kind` discriminator defaults to `meld`; only an exact parsed metadata `kind: "braid"`
backfills a legacy row as a braid. Malformed, nonobject, null and other metadata remain ordinary
melds. Existing ownership, raw metadata, input rows and their order stay intact. A database check
allows null project ownership only for a braid.

SQLite rebuilds only the parent table, preserves explicit indexes and triggers, and leaves inputs
untouched. Unsupported custom columns, table constraints or foreign-key dependencies fail startup
with an actionable migration error rather than silently removing invariants. PostgreSQL uses
transactional ALTER operations. Both profiles create the unassigned keyset index after upgrading;
repeated startup preserves canonical rows. Take and verify a backup before upgrading an existing
installation; see [database recovery](database-recovery.md).

An older binary is unsupported after null-owned braids have been written. Binary rollback does
not reverse this schema or the new writes. Repair forward or explicitly restore a verified backup
with the understood loss of later writes; do not delete braids to make an old binary start.
