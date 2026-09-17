# Recall observability

Black Box emits one privacy-limited `blackbox.recall.completed` JSON log record for each
`ContextService.recall` invocation. Micrometer records bounded process-local counters and timers.
These signals begin when this version is activated; stored session/event totals cannot reconstruct
historical recall usage. Previous hook fire logs may provide partial evidence, but they are not
complete server invocation counts and contain private data. Do not ingest those raw logs.

## What a completion means

`outcome=success` means the service produced a recall result, including an empty result or a lexical
fallback. It does not prove a client received, read, or used it. `outcome=error` means the core recall
failed; the original error still propagates. Request validation failures before entering the service
and MCP result serialization/clamping failures after it returns are outside this completion record.

The record covers the actual service work, with a generated UUID `request_id` shared by all stage
measurements. HTTP callers receive it in `X-Blackbox-Recall-Id`. Caller-supplied IDs are ignored.
The embedding adapter sends the same ID on availability and query HTTP requests when executing
within recall. There are no separate per-stage log events or additional trace exporter: the single
record joins transport, embedding and vector timings without duplicating ingestion. No query or
recalled text is included in that record or the correlation header. The embedding request itself
continues to contain the query as required by the existing embedding operation.

`mode=hybrid` means query embedding and vector search completed, even if every vector candidate was
rejected by the relevance floor. It does not necessarily mean semantic evidence affected the returned
page. Read `semantic_attempted`, `semantic_completed`, `semantic_contributed`, and
`semantic_returned` together:

- `semantic_attempted`: an eligible topic reached the embedding availability check. An unavailable
  embedder still counts as an attempt; blank/path/id scopes do not.
- `semantic_completed`: embedding, vector retrieval, and semantic admission completed successfully.
- `semantic_contributed`: at least one admitted, resolvable semantic hit entered rank fusion. The
  final page limit may exclude that hit.
- `semantic_returned`: number of final service-page items that were admitted in the semantic arm.
  This is overlap, not a causal claim that the lexical arm would have missed those items.

The embedding availability probe measures the memory embedder (normally Ollama), independently of
`/api/status` local AI chat health. A successful probe proves reachability only; the separate
`embedding_outcome` describes the real query embedding request. An unavailable probe can mean the
embedder is disabled or unreachable; the current port does not distinguish those states.

Vector outcomes describe the `MemoryVectorStore` operation. SQLite's vector adapter may internally
fall back from sqlite-vec to brute-force retrieval and still succeed. These records do not identify
that internal backend, prove sqlite-vec availability, or measure Elasticsearch health. The optional
`fetchVectors` score-enrichment operation has a separate outcome. Its failure now preserves the
already ranked recall page, leaving unavailable scores null instead of failing the request.

## Attribution and privacy

HTTP accepts optional `X-Blackbox-Client`, `X-Blackbox-Purpose`, and `X-Blackbox-Project` headers. MCP
`recallContext` accepts optional `telemetryClient`, `telemetryPurpose`, and `telemetryProject` strings.
Existing clients can omit all of them. Initialized MCP client names beginning with the bounded
Codex/Claude family names (`codex`, `claude`, or those names followed by `-`, `_`, space, or `/`) are
classified into those enums. A recognized initialized name takes precedence over `telemetryClient`;
otherwise the explicit declaration is used. Raw initialized names and versions are discarded.
Neither initialization metadata nor declarations authenticate a person or an application.

Allowed clients are `codex`, `claude`, `manual`, `other`, and `unknown`. Allowed purposes are
`normal`, `audit`, `test`, and `unknown`. Missing/invalid values become `unknown`; unknown calls must
not be silently counted as normal usage. Even `normal` is a caller declaration, not proof of organic
adoption. Research probes should declare `audit`; fixtures and automated verification should declare
`test`. Unlabeled historical probes cannot be retrospectively separated reliably.

The session-start hook declares its existing `--client`/`SBA_RECALL_CLIENT` value, bounded to the same
client enum. It defaults `SBA_RECALL_PURPOSE` to `normal` (ordinary automated continuity) and supports
`audit`/`test` overrides. It sends `SBA_RECALL_PROJECT_ALIAS` when configured, otherwise `unknown`.
Its existing private fire log remains a separate surface and must not be forwarded as telemetry.

Project grouping is explicitly opt-in. Set `SBA_RECALL_TELEMETRY_PROJECT_ALIASES` on the server to a
comma-separated list of at most 100 approved public-safe aliases, such as `example-project`. An alias
must match `[a-z][a-z0-9_-]{0,31}` and exactly match the server allowlist. Every other value becomes
`unknown`. Clients may then declare that alias. The default allowlist is empty. No paths, query
hashes, user IDs, session IDs, event IDs, content, URLs, exception messages, tokens, or credentials
are exported. Do not use a private name as an approved alias. Project is a log attribute only.

## Schema version 1

The SLF4J logger is `dev.nathan.sbaagentic.memory.internal.application.RecallTelemetry`. With the
default Spring console format its message is a complete JSON object after the normal log prefix.
Collectors should recognize both `event=blackbox.recall.completed` and `schema_version=1`, project
only the fields below, and drop original message/file-path fields. Do not forward arbitrary JSON
keys or the surrounding source log line. Reuse the existing log ingestion path once; do not ingest
these completions both as raw logs and as separately synthesized request events.

| Fields | Meaning / allowed values |
| --- | --- |
| `event`, `schema_version` | `blackbox.recall.completed`, integer `1` |
| `occurred_at`, `request_id` | UTC ISO completion timestamp; server-generated UUID |
| `transport` | `http`, `mcp`, `internal` |
| `client`, `purpose`, `project` | Bounded declarations described above |
| `scope_category` | `blank`, `path_or_id`, `topic`; no raw scope |
| `outcome`, `mode` | `success`/`error`; `lexical`/`hybrid` |
| `duration_ms` | Core service elapsed time, excluding telemetry emission, HTTP overhead, MCP text clamp, and serialization |
| `lexical_duration_ms`, `lexical_candidates` | Lexical repository duration and number of candidates (at most 50) |
| `semantic_attempted`, `semantic_completed`, `semantic_contributed` | Boolean states described above |
| `fallback_reason` | `none`, `blank_scope`, `path_or_id_scope`, `embedding_unavailable`, `embedding_error`, `candidates_error`, `vector_error` |
| `embedding_probe_outcome` | `skipped`, `success`, `unavailable`, `error` |
| `embedding_outcome`, `vector_outcome`, `vector_fetch_outcome` | `skipped`, `success`, `error` |
| `embedding_probe_duration_ms`, `embedding_duration_ms`, `vector_duration_ms`, `vector_fetch_duration_ms` | Nonnegative stage elapsed milliseconds. Vector duration includes semantic admission. Zero is usual for skipped stages. |
| `semantic_candidates` | Eligible event metadata pool before scope/vector filtering, not the count returned by KNN |
| `gate_admitted`, `gate_rejected` | KNN candidates admitted/rejected by score; their sum is the KNN result size after a successful scan |
| `semantic_hits`, `semantic_returned` | Resolvable admitted hits entering fusion, and their overlap with the final service page |
| `relevance_floor` | Configured score floor (default 0.61); values <=0 disable it; nonfinite values serialize as null |
| `result_count`, `result_scope`, `no_results` | Final service page count; fixed `service`; true only for a successful empty result |
| `error_category` | `none`, `recall_error`; no error text or class name |

`result_count` and `semantic_returned` are measured before the MCP text-budget clamp. The wire result
may contain fewer items and set `truncated=true`. Durations use a monotonic clock; timestamps use UTC
wall time. Stage counts are nonnegative integers. HTTP and MCP calls can share the same transport
process, so count completion records rather than generic access logs to avoid double counting.

## Counters and queries

Actuator's existing `/actuator/metrics` surface exposes these process-local instruments without
adding an exporter or changing security settings:

| Instrument | Tags / interpretation |
| --- | --- |
| `blackbox.recall.requests` | Counter by `transport`, `client`, `purpose`, `mode`, `outcome` |
| `blackbox.recall.duration` | Timer with the same tags; core elapsed seconds in Micrometer |
| `blackbox.recall.results` | Result-count distribution by `mode`, successful service results only |
| `blackbox.recall.empty` | Successful empty-result counter by `mode` |
| `blackbox.recall.semantic` | One count per call, `outcome=contributed`, `completed_empty`, or its fallback reason (`none` if recall failed before the semantic branch) |
| `blackbox.recall.gate.candidates` | Candidate counter, `disposition=admitted` or `rejected` |

Request IDs, project aliases, paths, queries, timestamps, and error strings are never metric labels.
Counters reset on restart. The log's time-bucketed event count gives an observed invocation rate
across process restarts; missing/dropped logs still prevent treating it as an exact billing ledger.

Useful saved views in an existing log UI:

1. All recall completions over time, split by purpose, transport, client, and approved project alias.
2. `purpose=normal`, separately showing `purpose=unknown`; exclude `audit` and `test` for an ordinary
   workflow estimate while keeping the attribution limitation visible.
3. Error/fallback and slow-call inspection by request ID: `outcome=error`, semantic attempted but
   not completed, and `vector_fetch_outcome=error` deserve attention. Expected path/blank skips are
   not dependency failures.
4. Topic-quality proxies: empty-result rate, gate rejection counts/floor, semantic contribution and
   returned overlap. These do not measure relevance satisfaction or downstream decision quality.

## Verification and operating limits

```bash
mvn -q -Dtest=RecallTelemetryTest,MemoryMcpToolsTest,ContextServiceHybridRecallTest,OllamaTextEmbedderClientTest,McpContractSnapshotTest test
scripts/test-recall-hook.sh
mvn test
git diff --check

# Read-only probe, explicitly excluded from ordinary usage estimates.
curl -fsS -D - --get http://localhost:8766/api/recall \
  -H 'X-Blackbox-Client: manual' -H 'X-Blackbox-Purpose: audit' \
  --data-urlencode 'scope=observability probe' --data-urlencode 'limit=1'
```

Tests use fake embedders/vector stores and temporary databases. They cover eligible hybrid results,
blank/path skipping, no matches, relevance gating, dependency failures, enrichment degradation,
real controller/MCP callback entry paths, correlation, attribution normalization, raw-data exclusion,
and metric/logger failure independence. Force outage cases only in isolated fixtures, not by
stopping a live dependency used by other requests.

Emission uses one small local JSON record, bounded counters and monotonic timing, without extra
network calls or model requests. Metrics and log emission each catch runtime failures independently
so one exporter failure cannot change recall or suppress the other path. Standard logging remains
synchronous and best effort: a slow configured appender can add latency, and process termination,
collector outages, disabled INFO logging, or retention can lose records. The core duration excludes
that emission overhead; compare end-to-end HTTP latency before/after activation to assess it.
No alert delivery is implied by collecting data or creating a saved view.

Deploy through `scripts/deploy-local.sh`; never replace the executable JAR under a running JVM.
Verify the live status, representative recall, emitted completion, and the matching sanitized row
in the existing collector UI after activation. Deployment-specific endpoints and view links belong
in the operator's private handoff, not this portable repo document.
