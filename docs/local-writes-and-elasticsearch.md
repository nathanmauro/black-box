# Local writes and Elasticsearch

This project accepts captured agent events through HTTP, CLI, hooks, and MCP. Every write lands in SQLite first. When Elasticsearch is enabled, the ingest path also indexes each new event into Elasticsearch.

## Runtime setup applied

I added `compose.elasticsearch.yml` so Elasticsearch can run with Docker Compose on `localhost:9200`.

Use a local `.codex/hooks.json` if you want Codex to run the hook bridge for this repo on `UserPromptSubmit` and `PostToolUse`.

Codex may ask you to trust the local hook the next time a session starts here. Approve it if you want automatic capture.

## Hook behavior on this workstation

The current local setup uses hooks as a low-noise event capture layer, not as a blocking control plane:

- Codex has a global hook file at `~/.codex/hooks.json` that runs `/Users/nathan/.local/bin/cockpit-agent-hook --client codex` for `SessionStart`, `UserPromptSubmit`, `PostToolUse`, and `Stop`.
- Claude Code has a global user setting at `~/.claude/settings.json` that runs `/Users/nathan/.local/bin/cockpit-agent-hook --client claude` for the same capture lifecycle.
- This repo also has a Claude-local provenance hook at `.claude/settings.local.json` for edit tools only (`Write`, `Edit`, `MultiEdit`, and `NotebookEdit`). It calls `.claude/hooks/post-tool-call.py`, which posts changed file paths to the local provenance webserver when that server is available.
- The Black Box/Cockpit capture hooks use a 5 second client timeout. The repo-local provenance hook does not currently declare a Claude-level timeout, but its HTTP call uses a 0.5 second timeout and only runs after edit tools.
- The hook events are visible in Black Box as captured `SessionStart`, `UserPromptSubmit`, and `PostToolUse` rows. They can also make search results noisy because command output becomes indexed event text.

Treat the hook output channels separately:

- `statusMessage` is visible runner text such as "Loading Cockpit startup context" or "Running PostToolUse hooks". It is for humans and should be short.
- `hookSpecificOutput.additionalContext` is the intended "console.log for the LLM" style channel: hook-produced text that could be injected into a session as model-visible context.
- On the current Codex path, startup logs show that Codex does not consume `hookSpecificOutput.additionalContext`; the hook still captures/curates events, but it does not inject visible text into the model context. Use an explicit prompt wrapper, MCP recall call, or Black Box UI surface when model-visible injection is required.

As an example from one local setup, the Codex config at `/path/to/.codex/config.toml` registered this MCP server:

```toml
[mcp_servers.sba-agentic]
url = "http://localhost:8766/mcp"
```

The service uses:

- Elasticsearch `8.15.3`
- Kibana `8.15.3` when the optional `kibana` Compose profile is enabled
- single-node discovery
- disabled local security, because the current app client uses unauthenticated HTTP
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
/ABSOLUTE/PATH/TO/scripts/hooks/sba-agent-hook.sh
```

Replace `/ABSOLUTE/PATH/TO/...` with the path on your machine.

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

Stop Kibana and leave Elasticsearch running:

```bash
docker compose -f compose.elasticsearch.yml --profile kibana stop kibana
```

Stop Elasticsearch and remove its index data:

```bash
docker compose -f compose.elasticsearch.yml down -v
```
