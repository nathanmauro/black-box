# Handoff — 2026-07-28 (overnight)

**Shipped**: semantic recall over structured intent. `recallContext` / `GET /api/recall` now
fuse the existing lexical `LIKE` path with kNN over locally-computed embeddings, so an agent
can find a decision by *meaning* instead of by guessing the vocabulary the original author used.

## The result that matters

Live, against the real corpus (3,566 embedded rows), for paraphrase queries that share no
distinctive vocabulary with their targets:

| | before | after |
|---|---|---|
| "why do the tests deadlock when they run together" | **0 results** | `SQLITE_LOCKED` decision, rank 4 |
| "stop the coding agent asking permission the first time it opens a folder" | **0 results** | directory-trust decision, rank 2 |
| "how do we show which agent spawned which other agent" | **0 results** | subagent-lineage handoff, rank 2 |

The old lexical path returns **literally zero rows** for these — `LIKE '%<phrase>%'` matches
nothing, because no document contains the phrase. This is not better ranking; it is the
difference between nothing and the right answer.

Measured relevance: **recall@1 = 1/6, recall@5 = 5/6** (pure semantic, live model, real corpus).
Honest limits below.

## Two findings that shaped the design (do not relearn)

1. **The naive implementation does not work.** Embedding raw `agent_events.text` scored
   **recall@1 = 0/6** — every query returned generic `Handoff to next-session:` boilerplate
   clustered at 0.58–0.64 similarity. Two independent causes, both measured:
   - nomic-embed-text is **trained with task prefixes**; without `search_document: ` /
     `search_query: ` retrieval falls off a cliff.
   - the corpus is **boilerplate-dominated** — captured intent shares a large structural
     preamble and runs to thousands of chars, so the blob embeds as *"this is a handoff"*
     rather than as its subject.

   Fix: embed a **distilled** representation (`Decision` → `decision` + `rationale`; `Handoff`
   → `contextSummary` + `nextAction`; strip the preamble; cap 900 chars). Prefixes + distillation
   took recall@5 from 3/6 to 5/6. Full numbers in the spec, §3a.

2. **sqlite-vec ranks by L2, not cosine.** With real vectors this reverses orderings outright
   (proven: query `[1,0]` over non-unit vectors returned the exact opposite order). And
   `distance_metric=cosine` does not exist in v0.1.9 — `vec0 constructor error: Unknown table
   option`. Fix: **unit-normalize on write**, then map `cosine = 1 − d²/2`. For unit vectors L2
   and cosine are monotonically equivalent, so both adapters now agree exactly.

## What is live

- :8766 running the fresh jar (deployed 02:04), `/api/status` OK — 3,549 sessions / 235,896 events.
- `memory_embeddings` holds **3,566** vectors (956 structured events + session summaries),
  nomic-embed-text @ 768-d via Ollama. Backfill: 3,566 embedded, **0 failed**, 93 seconds.
- Suites: **mvn 388 green** (1 skipped = the env-gated live eval), **vitest 244 green**.
- sqlite-vec is **default OFF** — brute force serves every query. See below.

## Honest limits — do not overclaim

- **Only structured intent is indexed** (`Decision`, `Handoff`, `Observation` + session
  summaries ≈ 3.5k rows). The full 208k-event corpus is **not** semantically searchable.
  Session-summary vectors are stored but no recall path returns summaries yet.
- **Symptom-shaped queries still miss.** "the API only gave back the first chunk of rows"
  lands at rank 28 — its target exists, but the query describes a *symptom* while the handoff
  describes a *fix*. Embedding models retrieve poorly across that asymmetry.
- **Top-1 is usually not the target** (recall@1 = 1/6). The win is "it is in the top 5", not
  "it is the answer".
- **A repo path or event id stays lexical on purpose.** One `scope` cannot express both a
  location and a subject; embedding a path would rank in-repo events by similarity to a path
  string and perturb the recency ordering that "what was decided here lately" depends on.
- **The eval fixture is self-contaminating.** The spec quotes its six queries verbatim and now
  lives in the corpus, so lexical matches that text and RRF can promote it over the true
  semantic winner. That is exactly why the live API missed a query the eval scored at rank 1.

## sqlite-vec: built, verified, default off

`SqliteVecVectorStore` works and is **mutation-verified** — breaking the cosine conversion makes
the parity test fail, so it genuinely compares two implementations rather than falling back to
one. It is off by default because there is **no sqlite-vec artifact on Maven Central**, so
enabling it would make a fresh install depend on a binary this repo does not publish, and it
breaks the configured GraalVM native-image path. At 3.5k vectors brute force is ~1–3 ms, so the
accelerator buys nothing measurable today; it is the path that scales to the full corpus later.

To enable: `SBA_SQLITE_VEC_PATH=~/.blackbox/lib/vec0.dylib` (already staged, with provenance;
sqlite-vec 0.1.9, MIT/Apache, sha256 `193e480c…`). **Not** set in the launchd plist — flipping
that is your call, not an unattended one.

## Open loops (ranked)

1. Fleet PRs **#21** (adopt-alive) and **#22** (pagination, stacked) — untouched tonight per
   your instruction, still awaiting review/merge + the pinned
   `CODEX_GOALS_DIR=~/.codex-goals/runs/2026/07/22-194034 fleet-review.sh sba-agentic <1|2>` acks.
2. **`ask` has the same prefix defect this slice just fixed** — its `OllamaEmbeddingClient` sends
   a bare prompt with no `search_query:` prefix, so its retrieval is degraded the same measured
   way. Its `knn` also still targets the foreign Elasticsearch `agent-memory` index this app
   never writes to. Pointing `ask` at the new SQLite vector store would fix both and delete code.
3. **Two embedding HTTP clients now exist** (`ask` and `memory`). Correct shape is one embedder
   in `memory` consumed by `ask` through a public port; `memory` could not import `ask`'s
   because `ask → memory` and the cycle trips the ratchets.
4. A separate `query` parameter on recall, so a caller can say "in THIS repo, about THAT topic".
5. Tier 2: semantic search over the full event corpus — this is where sqlite-vec earns its keep
   (208k × 768 float32 ≈ 640 MB, too large for the in-memory brute-force path).
6. Re-embedding after a model or dimension change is **not** automatic — `content_hash` covers
   the text, not the model, so a model swap silently orphans existing vectors.

## Gotchas (carried forward)

- Any `mvn package` (incl. the Playwright webServer) overwrites the live jar → run
  `scripts/deploy-local.sh` after.
- Never `git add -A` (except scoped `git add -A src/main/resources/static` after a bundle rebuild).
- Module ratchet: `memory → {project, recording}`; `memory` must never import `ask`.
  Every `@Repository` lives in `<module>.internal.adapter.out.sqlite..`.
- Test DBs are temp **files**, never `cache=shared` memory (that caused the `SQLITE_LOCKED` flake
  fixed in `6692b35`).
- codex `-c` overrides cannot address dotted/quoted keys; trust lookup wants the physical path.
- Playwright against the live app: `domcontentloaded`, never `networkidle` (SSE).
