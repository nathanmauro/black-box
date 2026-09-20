# Local writes and Elasticsearch

This project accepts captured agent events through HTTP, CLI, hooks, and MCP. The selected relational database is canonical (SQLite by default, or a separate PostgreSQL profile). When Elasticsearch is enabled, the ingest path also attempts to index each new event into Elasticsearch. Standalone capture commits before indexing; completion-Handoff listeners run inside the outer task transaction, as described in the [architecture transaction note](architecture.md#java-module-graph).

## Runtime setup

[`compose.elasticsearch.yml`](../compose.elasticsearch.yml) runs a loopback-only Elasticsearch
service for local development. Hook capture is independently opt-in: the bundled
[`sba-agent-hook.sh`](../scripts/hooks/sba-agent-hook.sh) accepts supported Claude Code or Codex
payloads and posts normalized events to `/api/events`. Client hook registration and subagent
lineage are described in [Connect an agent](agent-integration.md).

A client may require trust approval before running a locally registered hook. Capture hooks are a
best-effort recording layer: they use a short HTTP timeout and do not block the host turn when
recording fails. Captured prompt and tool output can make broad search results noisy.

Capture and context injection are separate. A write hook records events; an optional SessionStart
recall hook prints a bounded context packet. On-demand MCP recall is available without either hook.
There is no requirement to recall at every session start. Client hook protocols vary, so a
human-visible status message should not be treated as evidence of model-visible context injection.

An MCP client can register the same local server. For example, the Codex configuration entry is:

```toml
[mcp_servers.sba-agentic]
url = "http://localhost:8766/mcp"
```

These examples assume the default loopback deployment. Shared deployments require
[authentication](authentication.md); the bundled hook bridge does not send a bearer header.
Service configuration, summary privacy boundaries, and schema evolution are in [Run it](operations.md).

The service uses:

- Elasticsearch `8.15.3`
- Kibana `8.15.3` when the optional `kibana` Compose profile is enabled
- single-node discovery
- disabled local security and loopback-only published ports; this Compose topology is for local development
- a named Docker volume, `sba-agentic-elasticsearch`, for index persistence
- new Black Box indices default to `number_of_replicas: 0`, because the local Compose topology is single-node

Start Elasticsearch:

```bash
docker compose -f compose.elasticsearch.yml up -d
```

Check Elasticsearch:

```bash
curl -fsS http://localhost:9200 | jq
```

If Kibana shows `sba-agentic-events` as yellow with one unassigned replica on this single-node cluster, repair the existing index setting:

```bash
curl -fsS -X PUT \
  -H 'Content-Type: application/json' \
  http://localhost:9200/sba-agentic-events/_settings \
  --data '{"index":{"number_of_replicas":0}}' | jq
```

Start Kibana without changing the live app:

```bash
docker compose -f compose.elasticsearch.yml --profile kibana up -d kibana
```

Kibana uses the same Compose network as Elasticsearch and connects to `http://elasticsearch:9200`. The local UI is available at:

```text
http://localhost:5601
```

Check Kibana:

```bash
curl -fsS http://localhost:5601/api/status | jq '{overall: .status.overall, elasticsearch: .status.core.elasticsearch}'
```

Start the app with Elasticsearch indexing enabled:

```bash
SBA_ELASTICSEARCH_ENABLED=true \
SBA_ELASTICSEARCH_URL=http://localhost:9200 \
SBA_ELASTICSEARCH_INDEX=sba-agentic-events \
mvn spring-boot:run
```

Check app status:

```bash
curl -fsS http://localhost:8766/api/status | jq
```

Expected Elasticsearch status:

```json
{
  "enabled": true,
  "available": true,
  "indexName": "sba-agentic-events",
  "detail": "reachable"
}
```

## Write paths

The commands below persist example events. Use a disposable recorder/database for smoke tests.

HTTP write:

```bash
curl -fsS -H 'Content-Type: application/json' \
  -X POST http://localhost:8766/api/events \
  --data '{
    "source": "manual",
    "clientSessionId": "manual-session",
    "eventType": "ManualCapture",
    "role": "user",
    "text": "first note",
    "metadata": { "title": "ManualCapture" }
  }' | jq
```

CLI write:

```bash
java -jar target/sba-agentic-0.2.0.jar ingest \
  --source=manual \
  --session=test \
  --type=ManualCapture \
  --text='first note'
```

Hook write:

```bash
SBA_AGENT_SOURCE=codex \
SBA_AGENTIC_URL=http://localhost:8766 \
/path/to/black-box/scripts/hooks/sba-agent-hook.sh
```

The hook reads its payload from stdin; replace `/path/to/black-box` with the checkout path.

Register that command for supported capture events such as `UserPromptSubmit` and `PostToolUse`
in the client hook settings, preserving existing entries.

MCP write:

```bash
codex mcp add sba-agentic --url http://localhost:8766/mcp
```

After the MCP client restarts, use the `captureObservation` tool to write notes into the same store.

## Verify indexing

Write a test event:

```bash
curl -fsS -H 'Content-Type: application/json' \
  -X POST http://localhost:8766/api/events \
  --data '{
    "source": "manual",
    "clientSessionId": "elastic-smoke",
    "eventType": "ManualCapture",
    "role": "user",
    "text": "elastic smoke test note",
    "metadata": { "title": "Elastic smoke test" }
  }' | jq
```

The response should include:

```json
{
  "indexed": true
}
```

Search through the app:

```bash
curl -fsS 'http://localhost:8766/api/search?q=elastic%20smoke&limit=25' | jq
```

Search Elasticsearch directly:

```bash
curl -fsS 'http://localhost:9200/sba-agentic-events/_search?q=elastic%20smoke' | jq
```

Existing canonical events are not backfilled into Elasticsearch by the current app. Elasticsearch indexes new events written after `SBA_ELASTICSEARCH_ENABLED=true` is active.

## Historical verification

The original operational record reports the following on 2026-05-21. These are historical results,
not a current service-health check:

- Elasticsearch was started with `docker compose -f compose.elasticsearch.yml up -d`.
- The Spring app was restarted with `SBA_ELASTICSEARCH_ENABLED=true`, `SBA_ELASTICSEARCH_URL=http://localhost:9200`, and `SBA_ELASTICSEARCH_INDEX=sba-agentic-events`.
- `curl -fsS http://localhost:8766/api/status | jq .` returned Elasticsearch `enabled: true` and `available: true`.
- An HTTP smoke event wrote successfully with `indexed: true`.
- A manual hook smoke event wrote through `scripts/hooks/sba-agent-hook.sh` and appeared in both local app search and Elasticsearch.
- `mvn test` passed with 2 tests, 0 failures, and 0 errors.

## Stop services

Stop only Elasticsearch:

```bash
docker compose -f compose.elasticsearch.yml down
```

Stop Kibana and leave Elasticsearch running:

```bash
docker compose -f compose.elasticsearch.yml --profile kibana stop kibana
```

Stop Elasticsearch and remove its index data:

```bash
docker compose -f compose.elasticsearch.yml down -v
```
