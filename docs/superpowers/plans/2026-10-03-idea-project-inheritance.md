# Preserve Idea project attribution across revisions

## Reproduction and scope

A successful `POST /api/ideas` or MCP `captureIdea` can append a status revision with an
explicit existing `ideaKey`, the required fields, and no optional `repo`. If its new session also
has no cwd, `GET /api/ideas` currently reports the latest revision with `repo: null` and excludes
it from its original project. This contradicts the documented optional-field inheritance rule.
A disposable HTTP/MCP audit reproduced both transports; canonical rows remained intact.

Change only the Idea list projection. Preserve event/session identity, timestamps, statuses,
revision grouping/counts, filters, captured bytes, and append-only writes. Do not change capture
validation, default keys, raw-event recall, database schema, or frontend behavior.

## Attribution contract

Visit revisions in the existing newest-first chronological order. For each row, prefer its
nonblank captured `metadata.repo`, then its nonblank legacy session cwd. Use the first row with
either value. A newer explicit repo or cwd-only attribution wins over an older captured repo;
older revisions supply attribution only when newer revisions have neither. With no attribution
anywhere, return null. Do not modify any stored event or session to implement inheritance.

## Verification

1. Add failing real HTTP/MCP regression with separate capture sessions and an explicit key;
   canonical and registered-alias project filters must retain the latest revision. Prove latest
   event/session identity and original/current rows remain unchanged by listing.
2. Add focused projection cases for omitted/blank attribution, older legacy fallback, newer
   explicit/cwd-only winners, captured-repo precedence within a row, and all-missing null.
3. Exercise newer cwd-only generic/idempotent HTTP capture as a compatibility control.
4. Apply the minimal read projection fix; run Idea, module-boundary, and backend checks. Use the
   pinned scoped Java formatter and `git diff --check`.

All fixtures use disposable SQLite and ephemeral loopback HTTP/MCP with external systems
disabled. No production database, provider, service, private configuration, or Linear mutation.
The coordinator owns Git publication/integration and issue reconciliation.

## Results

- Before the production change, the 15 new regression cases ran with eight expected failures:
  four real HTTP/MCP project-filter cases and four older-attribution unit cases. Seven precedence
  and null compatibility controls already passed; there were no errors or skips.
- After the change, 39 targeted tests passed with no failures, errors, or skips: Idea projection,
  HTTP/MCP, capture validation, migration, keyset paging, and module boundaries.
- Full backend verification: 803 tests, zero failures/errors, 37 environment-dependent skips
  (33 opt-in PostgreSQL cases and four existing optional checks). PostgreSQL was not provisioned
  or exercised for this read-projection-only change.
- Scoped Palantir formatting and `git diff --check` passed. Independent coordinator review found
  no issues with precedence, latest identity, project aliases, or canonical-row preservation.
- All new HTTP/MCP contexts and clients close after testing; their SQLite files are disposable
  JUnit temporary files. No live service/database/provider or capture configuration changed.

The default idea-key namespace and raw-event recall semantics remain unchanged. This repairs
project inheritance in the collapsed Ideas listing; it does not backfill attribution into an
unscoped canonical capture. The coordinator owns the reviewed five-path diff and Git/PR finishing.
