# PostgreSQL backend compatibility

## Contract

SQLite remains the default, preserving existing storage, FTS5, vector extension and local behavior. The optional `postgres` Spring profile selects PostgreSQL with the same public API and original ISO timestamp strings. This slice supports one API replica; it does not add auth, deployment, Linear, transcript synchronization or fleet ownership. Work occurs in an isolated checkout and disposable PostgreSQL database.

## Steps

1. Add profile/schema/driver and gate SQLite-only startup/accelerators.
2. Add small module-local SQL dialect helpers without copying repositories.
3. Preserve PostgreSQL claim locking, completion rollback and exact timestamp ordering.
4. Exercise real PostgreSQL HTTP capture/recall/projects/workflow and canonical embeddings; run the existing SQLite suite.
5. Document configuration, retrieval fallbacks and verified limits.

## Verification record

Implemented in an isolated clone of baseline `87b6a30`.

- PostgreSQL 17.8: 8 real-server contracts passed.
- PostgreSQL 18.6: the same 8 contracts passed, including HTTP capture/recall, server restart,
  redaction, nanosecond project ordering, aliases, saved synthesis, lineage, concurrent claims,
  forced claim/completion rollback and canonical binary embeddings.
- Existing SQLite suite: 557 tests, 0 failures, 0 errors, 10 skips (8 opt-in PostgreSQL tests plus
  2 existing native-extension skips). No live database or target JAR was modified.
- `git diff --check` passed.

PostgreSQL full-text/vector accelerators, history import/sync, multiple API replicas, authentication,
and deployment remain separate slices. The local SQLite profile and its native accelerators remain
unchanged by default. Parent owns integration into the shared checkout.
