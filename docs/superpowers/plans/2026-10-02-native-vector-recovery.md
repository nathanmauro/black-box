# Recover the optional SQLite vector index

## Reproduction and boundary

A real SQLite fixture with the installed sqlite-vec extension stores embeddings before enabling
native search. Current initialization creates an empty memory_vec table and returns no matches.
The failing regression expects event:one and receives an empty result. No live database is used.

Canonical memory_embeddings remain authoritative. Rebuild only the optional index at startup,
transactionally, from vectors matching the configured model/dimensions. Retain portable ranking
on failure and for queries from another model; prevent incremental updates from mixing models.
Do not call a model provider or rewrite canonical embeddings, events or receipts.

## Verification

Run the actual native-extension regression and existing vector-store contracts, then the relevant
backend suite. Exercise enabling native search with existing data, interrupted-index recovery,
model isolation and unchanged canonical bytes. Verify rollback/fallback when rebuild cannot run.
Native cases require an installed extension and must be identified when skipped. Document startup
rebuild cost and the unchanged local default. Coordinator owns review, commit, PR and merge.

## Results

- Reproduced with the installed native extension: existing canonical event returned no match.
- Five native/portable vector-store tests passed with no skips, including recovery, model isolation
  and fallback. Review strengthened failure coverage with a nonempty native sentinel; exact native
  rows and canonical bytes remain unchanged after a failed dimensional rebuild.
- Full backend suite: 586 tests, zero failures/errors, four unrelated skips. All 20 PostgreSQL
  contracts ran; scoped Palantir formatting and diff checks passed.
- Fresh independent review found no implementation blocker. Startup rebuild cost is linear in
  the configured corpus and holds a SQLite write transaction; SQL avoids materializing it in Java.
- No live database or service was changed by this repair. The deployed local release remains
  the separately verified merged revision until a later explicit update.
