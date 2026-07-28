# Semantic recall — implementation plan

Spec: [`docs/superpowers/specs/2026-07-27-semantic-recall-design.md`](../specs/2026-07-27-semantic-recall-design.md)
Branch: `semantic-recall` (from `main` @ `07f4f3a`)

Each task is independently verifiable and leaves the suite green. Run `mvn test` after
every task. Never run `mvn package` casually — it overwrites the jar the live launchd
service runs from; `scripts/deploy-local.sh` must follow any packaging.

---

## Task 1 — Embedding vector value type + float32 codec

`memory/internal/domain/EmbeddingVector.java` (domain — no web/jdbc/jackson imports,
enforced by ArchUnit).

- `record EmbeddingVector(String model, float[] values)`
- `byte[] toBlob()` / `static EmbeddingVector fromBlob(String model, byte[])` —
  little-endian float32, length must be a multiple of 4
- `double cosineSimilarity(EmbeddingVector other)` — reject dimension mismatch
- `static String contentHash(String text)` — SHA-256 hex of the exact embedded text

**Tests** (`EmbeddingVectorTest`): blob round-trip preserves values exactly; cosine of
identical vectors is 1.0 and of orthogonal is 0.0 against hand-computed fixtures;
dimension mismatch throws; odd-length blob throws; hash is stable and differs on
whitespace change.

## Task 2 — `TextEmbedder` port + HTTP adapter

- Port `memory/internal/application/port/TextEmbedder.java`:
  `EmbeddingVector embed(String text)`, `boolean available()`, `String model()`.
- `MemoryEmbeddingProperties` bound to `sba.memory.embedding` (enabled, base-url, path,
  model, dimensions=768, timeout=5s). Register it in
  `platform/internal/config/SbaConfiguration.java` next to the existing property beans.
- Adapter `memory/internal/adapter/out/http/OllamaTextEmbedderClient.java`. Mirror the
  shape of `ask/internal/adapter/out/http/OllamaEmbeddingClient.java`: POST
  `{"model": …, "prompt": text}`, accept `embedding` **or** `embeddings[0]`, hard-fail on
  a dimension mismatch against configured `dimensions`.
- When `enabled=false` or the server is unreachable, `available()` is false and `embed`
  throws a typed unavailability exception — callers degrade, they do not crash.

**Tests**: MockWebServer/`RestClient` stub covering happy path, both response shapes,
dimension mismatch, timeout, and `enabled=false`. No live server in tests.

## Task 2b — Task prefixes + content distillation (MEASURED REQUIREMENT)

Added after the premise was measured against the real corpus. Without this, recall@1 is
**0/6** — the feature does not work. See spec §3a for the numbers.

- **Task prefixes.** `TextEmbedder` gains two call shapes: `embedDocument(String)` prepends
  `search_document: `, `embedQuery(String)` prepends `search_query: `. nomic-embed-text is
  trained with these; omitting them is a measured retrieval cliff. Make the prefixes
  configurable (`sba.memory.embedding.document-prefix` / `.query-prefix`, defaulting to the
  nomic values, empty string disables) so a future model that does not want them is a config
  change, not a code change.
- **Distillation.** New `memory/internal/domain/EmbeddableText.java`:
  - `Decision` → `metadata_json.decision` + `rationale`
  - `Handoff` → `metadata_json.contextSummary` + `nextAction`
  - `Observation` / anything else / missing metadata → fall back to `text`
  - strip the leading structural preamble (`Handoff to <x>:`, `<slug> — Session close-out`),
    collapse whitespace, cap at 900 chars
  - `content_hash` must hash the **distilled** text, so changing distillation invalidates
    stale rows naturally

**Tests**: each event kind distills to the expected string; missing/malformed
`metadata_json` falls back to `text` without throwing; preamble stripping is exact;
the 900-char cap does not split mid-codepoint; prefixes are applied on the wire and are
configurable off.

## Task 3 — Canonical embedding store

- `schema.sql`: add `memory_embeddings` + its index exactly as specced. Additive only —
  do not touch existing DDL.
- Port `memory/internal/application/port/EmbeddingStore.java`: `upsert`, `findHash`,
  `loadAll(model, dimensions)`, `deleteFor(targetKind, targetId)`, `count`.
- `@Repository memory/internal/adapter/out/sqlite/EmbeddingSqlStore.java` (the ArchUnit
  rule forces `@Repository` into `internal.adapter.out.sqlite..`). Plain `JdbcTemplate`,
  matching `MemorySqlQueryAdapter` style.

**Tests**: upsert-then-read round-trip through a real temp-file SQLite DB (follow the
`${java.io.tmpdir}/…-${random.uuid}.db` convention — **never** `cache=shared` memory,
which is what caused the `SQLITE_LOCKED` flake fixed in `6692b35`); replace-on-conflict;
`loadAll` filters by model+dimensions.

## Task 4 — `MemoryVectorStore` port + brute-force adapter

- Port: `List<ScoredKey> knn(EmbeddingVector query, int k, Predicate<String> keyFilter)`.
- `BruteForceVectorStore` — loads canonical vectors, cosine, top-k via a bounded
  min-heap. Must apply `keyFilter` **before** scoring so scope/time filtering is not
  paid for in similarity math.

**Tests**: ranking order on a fixture corpus; `k` larger than corpus; empty corpus;
filter excludes everything; ties broken deterministically.

## Task 5 — sqlite-vec adapter (optional accelerator)

- `MemoryVectorProperties` bound to `sba.memory.vector` (`sqlite-vec-path`, default empty).
- Add `enable_load_extension: "true"` to `spring.datasource.hikari.data-source-properties`
  in `application.yml` (verified to work as a plain JDBC connection property).
- A Hikari customizer / init path that runs `SELECT load_extension(<path>)` per connection
  when the path is configured and the file exists. `PRAGMA busy_timeout` must survive —
  move it to a data-source property if `connection-init-sql` can only hold one statement.
- `SqliteVecVectorStore`: create `memory_vec` `vec0` virtual table if absent, keep it in
  sync on upsert, query `WHERE embedding MATCH ? AND k = ?`.
- **Resolution is fail-soft**: unset path, missing file, or a failed load logs once at
  INFO and falls back to `BruteForceVectorStore`. Never fail startup over it.

**Tests**: a `@Test` that is skipped via `Assumptions.assumeTrue` when no dylib is
available, so CI without the extension stays green. **Plus the parity test — the most
important test in this slice**: build a fixture corpus, run both adapters, assert
identical top-k ordering. That is what makes the fallback trustworthy.

## Task 6 — Indexing on ingest

- `memory/internal/application/EmbeddingIndexer.java`, `@EventListener` on `EventRecorded`
  — the same seam `ElasticIndexClient.indexRecordedEvent` already uses, which runs after
  the canonical SQLite commit.
- Embed only `Decision | Handoff | Observation`. Skip when `content_hash` is unchanged.
- Async and never-fail: any embedder error is logged and swallowed. A failed embed must
  never affect the recorded event — canonical write integrity outranks index freshness.
- Session summaries: embed when a summary is written/updated.

**Tests**: recording a `Decision` produces one embedding row; recording a `PostToolUse`
produces none; a failing embedder leaves the event recorded and the suite green;
re-recording identical text does not re-embed.

## Task 7 — Backfill job

- `memory/internal/application/EmbeddingBackfillService.java`: batched, resumable
  (skip rows whose `content_hash` already matches), bounded batch size, progress logging
  every N rows, cooperative cancellation.
- Surface: `POST /api/memory/embeddings/backfill` (returns counts; dry-run by default,
  `?apply=true` to write) **and** a CLI entry consistent with existing script patterns.
  Default to dry-run — per AGENTS.md, gate mutations.

**Tests**: dry-run writes nothing and reports an accurate count; apply is idempotent on a
second run; resume after simulated interruption embeds only the remainder.

## Task 8 — Hybrid recall

- `ContextService.recall`: run the existing lexical query, run kNN when available, fuse
  with `ReciprocalRankFusion`, keep `RECALL_LIMIT`.
- Semantic candidates must respect the same `scope` / `withinHours` / `kinds` filters as
  lexical — push those into the `keyFilter`.
- `RecallResult` gains `mode` (`hybrid` | `lexical`); `RecalledItem` gains `score`.
  Additive only.
- Update `RestContractSnapshotTest` and `McpContractSnapshotTest` snapshots deliberately;
  assert no existing field was removed or retyped.

**Tests**: **the money test** — capture a decision about "shared-cache SQLite locking",
recall with "why do the tests deadlock", assert it returns and that a lexical-only run
does not. Plus: embedder unavailable → `mode=lexical` with results still returned;
exact-token lookups still work; `kinds`/`scope`/`withinHours` filters still hold.

## Task 8b — Relevance evaluation harness

The offline stub-embedder test in Task 8 proves the *plumbing*. It cannot prove the
feature *works* — the naive design passed every conceivable unit test and still scored
recall@1 = 0/6 against the real model. Ship something that can tell the difference.

- `src/test/java/.../memory/RecallRelevanceEvaluationTest.java`, gated by
  `@EnabledIfEnvironmentVariable(named = "SBA_EVAL_LIVE_MODEL", matches = "true")` so the
  default suite stays offline and green.
- A small fixture set of `(paraphrase query, regex matching the expected target)` pairs
  checked into `src/test/resources/eval/recall-queries.json`, seeded from the six probes
  already run.
- Reports recall@1 and recall@5 and **fails below a floor** (recall@5 ≥ 4/6) so a future
  model or prompt-format change that silently degrades retrieval is caught.

This is the regression test for embedding quality, which is otherwise invisible.

## Task 9 — Docs

Update only to what is now true — structured intent is semantically searchable, the full
event corpus is not:

- `AGENTS.md:18`, `PLAN.md:34`, `PLAN.md:51`
- `README` — recall capability + the `SBA_SQLITE_VEC_PATH` knob
- `docs/architecture.md` — `memory` module responsibilities, new table
- `NEXT.md` — refreshed handoff

## Task 10 — Live verification

1. `mvn test` (full), `cd frontend && npm test -- --run`
2. `mvn -q -DskipTests package` then **`scripts/deploy-local.sh`** (mandatory — packaging
   overwrites the live jar), then `launchctl kickstart -k` if needed
3. `curl -fsS http://localhost:8766/api/status | jq`
4. Backfill dry-run, inspect counts, then apply
5. MCP `recallContext` with a paraphrase query — confirm a semantically-matched decision
   comes back with a score and `mode=hybrid`
6. Playwright e2e (`domcontentloaded`, never `networkidle`), then `deploy-local.sh` again
   because the Playwright webServer repackages the jar

---

## Standing constraints

- Module graph: `memory → {project, recording}`. `memory` must **not** import from `ask`.
- Every `@Repository` lives in `<module>.internal.adapter.out.sqlite..`;
  `internal.application..` may not import `internal.adapter..`.
- Test DBs: temp files, never `cache=shared` memory.
- Never `git add -A` (except scoped `git add -A src/main/resources/static` after a bundle
  rebuild).
- Commit subjects: human-readable changelog style, Title Case, no Conventional Commits,
  no model co-author or generated-by trailers.
