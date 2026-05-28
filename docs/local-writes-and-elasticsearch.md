# Local writes and Elasticsearch

This project accepts captured agent events through HTTP, CLI, hooks, and MCP. Every write lands in SQLite first. When Elasticsearch is enabled, the ingest path also indexes each new event into Elasticsearch.

## Runtime setup applied

I added `compose.elasticsearch.yml` so Elasticsearch can run with Docker Compose on `localhost:9200`.

Use a local `.codex/hooks.json` if you want Codex to run the hook bridge for this repo on `UserPromptSubmit` and `PostToolUse`.

Codex may ask you to trust the local hook the next time a session starts here. Approve it if you want automatic capture.

I also verified that `/Users/nathan/.codex/config.toml` already has this MCP server registered:

```toml
[mcp_servers.sba-agentic]
url = "http://localhost:8766/mcp"
```

The service uses:

- Elasticsearch `8.15.3`
- single-node discovery
- disabled local security, because the current app client uses unauthenticated HTTP
- a named Docker volume, `sba-agentic-elasticsearch`, for index persistence

Start Elasticsearch:

```bash
docker compose -f compose.elasticsearch.yml up -d
```

Check Elasticsearch:

```bash
curl -fsS http://localhost:9200 | jq
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
java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar ingest \
  --source=manual \
  --session=test \
  --type=ManualCapture \
  --text='first note'
```

Hook write:

```bash
SBA_AGENT_SOURCE=codex \
SBA_AGENTIC_URL=http://localhost:8766 \
/Users/nathan/Developer/proj/sba-agentic/scripts/hooks/sba-agent-hook.sh
```

Wire that command in a local `.codex/hooks.json` for `UserPromptSubmit` and `PostToolUse`.

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

Existing SQLite rows are not backfilled by the current app. Elasticsearch indexes new events written after `SBA_ELASTICSEARCH_ENABLED=true` is active.

## Verification performed

Verified on 2026-05-21:

- Docker Desktop was started because the Docker daemon was not running.
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

Stop Elasticsearch and remove its index data:

```bash
docker compose -f compose.elasticsearch.yml down -v
```
