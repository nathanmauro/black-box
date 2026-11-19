# Recall quality benchmark

> **Audience and status:** maintainer regression harness. It runs fully offline: a throwaway
> SQLite file, no Elasticsearch, no model server, no network. Scores come from a synthetic corpus
> and a deterministic stand-in embedder, so they track ranking regressions in Black Box's own
> pipeline. They do not measure any real embedding model or predict quality on real history.

The benchmark seeds a fixed corpus through the real capture use cases, then queries the real
recall, compact-search, and braid-discovery surfaces and scores each ranking against graded
relevance judgments. Only the embedder is substituted. SQLite, sqlite-vec, reciprocal-rank
fusion, the relevance floor, and project scoping all run unchanged.

For the separate Docker experiment that asks whether recall helps a coding agent finish tasks,
see [memory-benchmark.md](memory-benchmark.md).

## Run it

```bash
scripts/recall-benchmark.sh                      # 5 measured iterations per query
scripts/recall-benchmark.sh --iterations 20 --report-dir /tmp/bench
mvn test -Dtest=RecallQualityBenchmarkTest       # same gate, 3 iterations, part of `mvn test`
```

Each run writes `recall-benchmark.json` (every query's ranked fixture keys, metrics, and raw
latencies) and `recall-benchmark.md` (the summary table) to `target/recall-benchmark/` by
default. The command exits non-zero when a quality floor regresses.

## Corpus

`SyntheticRecallCorpus` (seed `20261119`) builds:

- 6 projects (`/bench/atlas-api` ... `/bench/fjord-infra`) and 8 engineering topics. Odd-indexed
  projects carry all 8 topics; even-indexed projects carry 4.
- One decision, handoff, and observation per project-topic pair (108 events), plus 18
  hard-negative distractor observations that share surface words with real topics.
- 18 braids, one per consecutive pair of topics a project carries.

Capture order is shuffled with the seed, and timestamps are pinned to one-second steps after
seeding, so recency (the lexical tie-breaker) is uncorrelated with relevance and never ties.
Judgments are keyed by fixture key (`project/topic/kind`), never by database id. Grades:
decision 3, handoff 2, observation 1, distractor 0.

`HashingTextEmbedder` maps stemmed tokens through a small concept lexicon, then feature-hashes
them into a 256-dimension unit vector (FNV-1a buckets, so no JVM hash seed is involved). A
paraphrase matches only where the lexicon links its words to the original. That is the honest
limit of the semantic numbers.

## Suites and metrics

| Suite                       | Query                                  | Surface                                 |
| --------------------------- | -------------------------------------- | --------------------------------------- |
| `recall.exact.hybrid`       | the topic's literal phrase, unscoped   | `recall(query=...)`, embedder on        |
| `recall.paraphrase.hybrid`  | a paraphrase with no shared phrase     | same                                    |
| `recall.paraphrase.lexical` | same paraphrases                       | embedder off: the lexical-only ablation |
| `recall.project.hybrid`     | paraphrase, one per project-topic pair | `recall(project=..., query=...)`        |
| `search.compact`            | 2-3 topic keywords                     | `CompactSearchOperations.search`        |
| `braids.find`               | the topic's literal phrase             | `BraidDiscoveryOperations.find`         |

Per query: P@1, P@5 (empty slots count as misses), R@10, reciprocal rank, and NDCG@10 with
exponential gain `2^grade - 1`. Per suite: means, MRR, zero-result rate, modes reported by
recall, nearest-rank latency percentiles (p50/p90/p99/max over every measured call, after one
warm-up per query), and two hard invariants:

- **Leaked items:** project-scoped results from another project. Must be zero.
- **Unstable queries:** rankings that change between iterations in one run. Must be zero.

## Baseline (2026-11-19)

| Suite                     |   P@1 |   P@5 |  R@10 |   MRR |     NDCG@10 |
| ------------------------- | ----: | ----: | ----: | ----: | ----------: |
| recall.exact.hybrid       | 1.000 | 1.000 | 0.708 | 1.000 | 0.883-0.894 |
| recall.paraphrase.hybrid  | 1.000 | 1.000 | 0.688 | 1.000 |       0.804 |
| recall.paraphrase.lexical | 0.000 | 0.000 | 0.000 | 0.000 |       0.000 |
| recall.project.hybrid     | 1.000 | 0.522 | 0.870 | 1.000 |       0.830 |
| search.compact            | 1.000 | 1.000 | 0.667 | 1.000 |       0.863 |
| braids.find               | 1.000 | 0.900 | 1.000 | 1.000 |       1.000 |

Latencies on a laptop are single-digit milliseconds per call. Treat them as relative, not as
service-level numbers. The test only checks that they are positive.

What the baseline shows:

- Lexical recall matches the whole query as one `LIKE` phrase, so every paraphrase returns
  nothing. All paraphrase recall comes from the semantic leg.
- The webhook-backoff paraphrase scores NDCG@10 near 0.1. Its decision and handoff fall below the
  0.61 cosine relevance floor, and only the observation is admitted. That is the floor working as
  designed on a weak lexicon link, not a pipeline bug.
- Exact-phrase NDCG moves by about 0.01 between runs. Reciprocal-rank fusion breaks score ties by
  event id, and event ids are random UUIDs, so tied items swap places between processes. Within
  one run, rankings are fully reproducible.

## Changing the gates

The floors in `RecallQualityBenchmarkTest` sit just under the baseline. Raise one when a ranking
change genuinely improves the suite. Lower one only alongside a recorded reason, such as a
deliberate trade-off. If you change the corpus, lexicon, or seed, the baseline above no longer
applies: rerun, update this table, and set new floors.
