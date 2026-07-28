# Recall score semantics: true cosine + a measured relevance floor

## Problem

`RecalledItem.score` carries the Reciprocal-Rank-Fusion score (~0.016) — an internal ranking
artifact with no meaning to a caller. Worse, recall always returns up to `limit` items even when
nothing is relevant: kNN returns the *nearest* vectors, not *relevant* ones, so a no-match query
that used to return zero rows now returns a full page of confidently-scored noise. For a tool
meant to stop agents re-deciding settled things, plausible noise is a real hazard.

Measured context (2026-07-28 overnight session): real matches score ~0.62–0.69 cosine; the
irrelevant baseline clusters at ~0.58–0.64. Too thin to threshold blind — the floor must come
from fresh measurement, not a guess.

## Design

1. **`score` = true cosine similarity between the query and the item, uniformly.**
   Fusion still decides *order*; after fusion, each returned item carries its cosine against the
   query embedding. Semantic-arm hits already have it (`ScoredKey.score()`). Lexical-arm hits get
   it via a batched vector fetch from the canonical `memory_embeddings` store + dot product
   (vectors are unit-normalized, so dot = cosine). An item with no stored vector, or any recall in
   `lexical` mode (path/id scope, embedder unavailable, semantic arm failed), carries `score = null`
   — never a fabricated number. `RecalledItem.score` changes `double` → `Double`.

2. **The relevance floor gates only semantic-only additions.**
   A lexical hit earned its place by literal match + recency and is NEVER dropped by the floor.
   A hit that arrived only via the semantic arm must clear the floor to appear at all. This
   exactly restores pre-semantic behavior for junk queries: lexical returns zero, nothing clears
   the floor, recall honestly returns zero. `count` reflects the filtered result.

3. **The floor value is measured, then configured.**
   An env-gated measurement harness (pattern: `RecallRelevanceEvaluationTest`) runs the six eval
   queries plus a bank of deliberate no-match queries against the live corpus + live embedder and
   prints the cosine distributions (real-target scores vs noise cluster, per-rank). The default
   floor is chosen from that output in-session; it ships as a config property
   (`sba.memory.recall.relevance-floor`, `0` disables). Phase B implements the floor only after
   the phase-A numbers are read.

## Slices

- **Phase A**: cosine surfacing (`Double` score, batched lexical scoring, null in lexical mode) +
  measurement harness + contract snapshot updates (additive; no field removals) + unit tests
  (fusion order preserved; lexical-mode null; missing-vector null).
- **Phase B**: floor from measured value (property + default; semantic-only gating; honest-zero
  test: junk query in hybrid mode → 0 items) + docs.

## Out of scope (deliberate)

Separate `query` parameter on recall; `ask` module prefix/knn fixes; Tier 2 full-corpus search;
returning session summaries; automatic re-embedding on model change.

## Constraints

- Module ratchet: `memory → {project, recording}`; never import `ask`.
- `Rest`/`McpContractSnapshotTest`: update snapshots deliberately, additive only.
- `mvn package` overwrites the live jar → redeploy via `scripts/deploy-local.sh` at post-flight.
- Commits by the session owner only (no AI attribution); Codex never touches git.
