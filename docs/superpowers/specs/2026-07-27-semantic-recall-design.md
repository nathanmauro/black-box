# Semantic recall — design

Date: 2026-07-27
Status: accepted, implementation in progress

## Problem

`recallContext` / `GET /api/recall` matches intent with three unanchored `LIKE '%term%'`
predicates and orders purely by recency:

```sql
WHERE e.event_type IN (?, ?)
  AND e.observed_at >= ?
  AND (lower(e.id) LIKE ? OR lower(coalesce(s.cwd,'')) LIKE ? OR lower(coalesce(e.text,'')) LIKE ?)
ORDER BY e.observed_at DESC LIMIT 50
```

No index can serve those predicates, so every recall full-scans `agent_events`
(234k rows today). More importantly, an agent that asks "how do we stop the test
database from deadlocking" cannot find the decision titled *"Move Spring test
databases off shared-cache memory onto temp files"* — the two share almost no
literal tokens. Recall only works when the caller already knows the vocabulary
used by the agent that wrote the memory, which defeats the point of a shared
memory bus.

This slice makes recall rank by **meaning**, and says so honestly when it cannot.

## What already exists (reuse, don't rebuild)

- `MemoryRetrievalOperations.knn(float[], int)` — a public `memory` port that already
  declares the vector seam. Its only implementation targets a *foreign* Elasticsearch
  index (`agent-memory`) that this application never writes to.
- `ReciprocalRankFusion` — tested fusion of lexical + vector candidate lists.
- The `hybrid | bm25 | unavailable` degradation vocabulary in `AskService`.
- `MemoryHit` (`id, score, title, source, sessionId, timestamp, text, snippet`).
- `EventRecorded` application events, published after the canonical SQLite commit —
  the same seam `ElasticIndexClient.indexRecordedEvent` already listens on.

The gap is a **SQLite-backed `knn`**, an **indexing side that embeds Black Box's own
corpus**, and a **backfill**.

## Decisions

### 1. What gets embedded

Structured intent only:

| Target | Rows today | Source text |
|---|---|---|
| `agent_events` where `event_type IN ('Decision','Handoff','Observation')` | 956 | `text` (already rendered prose) |
| `agent_sessions.summary` where non-empty | 2,578 | `summary` |

~3.5k vectors, ~10 MB at 768-d float32.

Rejected: embedding all 208,314 non-empty-text events. That is ~640 MB of vectors,
hours of backfill, and — decisively — `PostToolUse` noise (211,831 rows) would swamp
decision and handoff hits in every recall. Recall is *about* structured intent. The
schema and backfill job are built so a full-corpus tier can be added later without
migration; that is where sqlite-vec earns its keep.

### 2. Storage: canonical BLOB table, sqlite-vec as accelerator

Two things, not one.

**Canonical** (always written, no extension required):

```sql
CREATE TABLE IF NOT EXISTS memory_embeddings (
    target_kind  TEXT NOT NULL,          -- 'event' | 'session_summary'
    target_id    TEXT NOT NULL,          -- agent_events.id | agent_sessions.id
    model        TEXT NOT NULL,
    dimensions   INTEGER NOT NULL,
    vector       BLOB NOT NULL,          -- little-endian float32, dimensions * 4 bytes
    content_hash TEXT NOT NULL,          -- re-embed only when source text changes
    embedded_at  TEXT NOT NULL,
    PRIMARY KEY (target_kind, target_id)
);
CREATE INDEX IF NOT EXISTS idx_memory_embeddings_model ON memory_embeddings (model, dimensions);
```

**Accelerator** (only when the sqlite-vec extension is loadable), rebuildable at any
time from the canonical table:

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS memory_vec USING vec0(
    key TEXT PRIMARY KEY,                -- '<target_kind>:<target_id>'
    embedding float[768]
);
```

One port, two adapters:

- `SqliteVecVectorStore` — preferred; `WHERE embedding MATCH ? AND k = ?`.
- `BruteForceVectorStore` — fallback; reads canonical BLOBs, cosine in Java.

Why both: sqlite-vec has **no Maven Central artifact**. Making it the sole store
would force vendoring a per-platform native binary into a public product repo, break
the configured GraalVM native-image path, and make a fresh-machine install depend on
a binary we do not publish. At 3.5k vectors brute force is ~1–3 ms, so the fallback
is not a degraded experience — it is simply the portable one. sqlite-vec remains the
path that scales to the full corpus.

Verified on this machine before committing to the design (xerial 3.45.3.0,
sqlite-vec v0.1.9, macOS arm64):

- xerial is **not** built with `SQLITE_OMIT_LOAD_EXTENSION` — `load_extension` reaches
  `dlopen` and fails only on a missing file.
- `vec0(id text primary key, e float[N])` works, so the `agent_events.id` UUID needs no
  integer-rowid join table.
- `enable_load_extension=true` works as a **plain JDBC connection property**, so it
  drops into the existing Hikari `data-source-properties` block next to `foreign_keys`,
  and coexists with `PRAGMA busy_timeout`.

### 3. Embedding source

`nomic-embed-text` at **768 dimensions** — already declared in
`sba.ask.embedding-dimensions: 768`, and live on both local servers:
LM Studio (`:1234` `/v1/embeddings`, `text-embedding-nomic-embed-text-v1.5`) and
Ollama (`:11434` `/api/embeddings`, `nomic-embed-text:latest`). No new model to install.

#### 3a. Measured: the naive implementation does not work

Before building anything, the premise was tested directly against the real 956-event
corpus with the live model — embed every structured event, query with six paraphrases
that deliberately avoid the target's distinctive vocabulary, and measure recall.

| variant | recall@1 | recall@5 | mean top-1 similarity |
|---|---|---|---|
| A — raw `agent_events.text`, no task prefix | 0/6 | 3/6 | 0.602 |
| B — raw text + nomic task prefixes | 1/6 | 4/6 | 0.684 |
| C — distilled text + task prefixes | **2/6** | **5/6** | 0.684 |

Variant A is what a naive reading of "embed `agent_events.text`" produces, and it
retrieves essentially nothing: every query returned generic `Handoff to next-session:`
documents clustered at 0.58–0.64 similarity. Two independent causes, both confirmed:

1. **nomic-embed-text requires task prefixes.** It is trained with `search_document: `
   on stored text and `search_query: ` on queries. Omitting them is a measurable
   retrieval cliff (A→B). Note this means the existing `ask` module's
   `OllamaEmbeddingClient` — which sends the bare query — has the same latent defect.
2. **The corpus is boilerplate-dominated.** Captured decisions and handoffs share a
   large structural preamble (`Handoff to next-session:`, open-loops and verification
   sections) and run to thousands of characters. Embedding the rendered blob makes every
   document's vector mostly *"this is an agent handoff"* rather than what it is about.

Therefore embedding input is **distilled, not raw**:

- `Decision` → `metadata_json.decision` + `rationale`
- `Handoff` → `metadata_json.contextSummary` + `nextAction`
- fallback to `text` when metadata is absent
- strip the structural preamble, collapse whitespace, cap at ~900 characters
  (long tails dilute the vector)

Distillation alone moved "splitting the java code into enforced modules" from a miss to
rank 1. This is a load-bearing part of the design, not an optimisation.

Recall@5 of 5/6 also confirms semantic search must **not** replace lexical: hybrid
fusion is doing real work, and the honest claim is "better recall", not "solved".

`ask`'s existing `QueryEmbedder` **cannot be reused from `memory`**: it is
`ask`-internal and `ask → memory`, so importing it inverts the graph and trips both
Spring Modulith `verify()` and the ArchUnit acyclicity ratchet. `memory` therefore gets
its own `TextEmbedder` port and HTTP adapter. This duplicates ~80 lines; consolidating
both onto one embedder in `memory` (consumed by `ask` through a public port) is the
correct long-term shape and is recorded as a follow-up, deliberately deferred to keep
this slice narrow.

### 4. Retrieval

`ContextService.recall` becomes hybrid:

1. lexical candidates — the existing `LIKE` path, preserved so exact-token and id
   lookups keep working;
2. semantic candidates — kNN over embeddings, filtered to the same scope, time window,
   and kinds;
3. fuse with the existing `ReciprocalRankFusion`.

When the embedder or the vector store is unavailable, recall returns pure lexical
results and **reports that it did**. Silent degradation would be worse than no feature:
an agent must be able to tell "nothing matched" from "semantic search was off".

### 5. Contract impact

`RecallResult` gains `mode` (`hybrid | lexical`); `RecalledItem` gains `score`. Both are
**additive** — no field is removed or retyped, so existing MCP clients keep working.
`RestContractSnapshotTest` and `McpContractSnapshotTest` will trip and must be updated
deliberately.

## Configuration

```yaml
sba:
  memory:
    embedding:
      enabled: true
      base-url: http://localhost:11434
      path: /api/embeddings
      model: nomic-embed-text
      dimensions: 768
      timeout: 5s
    vector:
      sqlite-vec-path: ${SBA_SQLITE_VEC_PATH:}   # empty => brute-force fallback
```

Tests set `sba.memory.embedding.enabled=false` to stay offline, matching the existing
`sba.ask.embedding-enabled=false` convention.

## Module placement

Everything lands in `memory`, which already depends on `recording` and already owns the
`knn` port:

- `memory/internal/application/port/TextEmbedder.java`, `MemoryVectorStore.java`
- `memory/internal/adapter/out/http/` — embedding client
- `memory/internal/adapter/out/sqlite/` — both vector stores (the ArchUnit rule requires
  every `@Repository` to live in `<module>.internal.adapter.out.sqlite..`)
- indexing listens on `EventRecorded`, after the canonical commit, never blocking it

## Verification

- float32 BLOB round-trip; cosine correctness against hand-computed vectors
- `content_hash` skip — unchanged text is not re-embedded
- dimension-mismatch rejection
- **adapter parity**: `SqliteVecVectorStore` and `BruteForceVectorStore` return identical
  top-k over a fixture corpus (the highest-value test in the slice — it is what makes the
  fallback trustworthy)
- degradation: embedder down → `mode=lexical`, no exception, no empty result
- updated contract snapshots
- **the money test**: capture a decision, recall it with a paraphrase sharing no
  significant tokens, assert it comes back — the property that does not exist today
- live: MCP `recallContext` and a real-browser pass after `scripts/deploy-local.sh`

## Docs that must change

`AGENTS.md:18`, `PLAN.md:34`, `PLAN.md:51`, `README`, and `docs/architecture.md` all
currently state that Black Box does **not** do semantic/vector search. Shipping this
slice means updating them — and only to the extent that is actually true (structured
intent is semantic; the full event corpus is not).

## Out of scope

- Tier 2 full-corpus semantic search over all 208k text events
- Re-pointing `ask`'s `knn` off the foreign `agent-memory` Elasticsearch index
- Consolidating the two embedding HTTP clients
- Any UI surface for semantic search beyond what recall already renders
