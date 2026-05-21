# SBA Agentic

Local Spring Boot control plane for agent sessions, hook capture, search, MCP tools, and local AI summarization.

It gives Claude Code, Codex, and manual notes a shared local event store:

- SQLite stores canonical sessions and events.
- The web UI shows captured sessions, timelines, search, status, and summaries.
- The CLI supports quick ingest, search, session listing, and health checks.
- The hook script captures Claude Code or Codex hook payloads.
- The MCP endpoint exposes session/search/capture tools to MCP clients.
- LM Studio can summarize sessions through an OpenAI-compatible chat endpoint.
- Elasticsearch can be enabled for optional full-text indexing.

## Quick Start

Prerequisites:

- Java 21 or newer
- Maven 3.9 or newer
- `jq` for hook capture
- LM Studio or another OpenAI-compatible local model server, optional but useful

Start the app:

```bash
cd /Users/nathan/Developer/proj/sba-agentic

SBA_LOCAL_AI_MODEL=qwen3-4b-instruct-2507-mlx \
SBA_LOCAL_AI_BASE_URL=http://localhost:1234 \
mvn spring-boot:run
```

Open the UI:

```text
http://localhost:8766
```

Verify the app:

```bash
curl -fsS http://localhost:8766/api/status | jq
```

Capture a manual event:

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

Search it:

```bash
curl -fsS 'http://localhost:8766/api/search?q=first%20note' | jq
```

## Configuration

Most configuration is environment variables. Defaults live in `src/main/resources/application.yml`.

| Variable | Default | Use |
| --- | --- | --- |
| `SBA_PORT` | `8766` | HTTP server port |
| `SBA_DATASOURCE_URL` | `jdbc:sqlite:sba-agentic.db` | SQLite database location |
| `SBA_LOCAL_AI_ENABLED` | `true` | Enables local AI summaries |
| `SBA_LOCAL_AI_BASE_URL` | `http://localhost:1234` | LM Studio or OpenAI-compatible base URL |
| `SBA_LOCAL_AI_CHAT_PATH` | `/v1/chat/completions` | Chat completion path |
| `SBA_LOCAL_AI_MODEL` | `local-model` | Model id sent to the local AI server |
| `SBA_LOCAL_AI_API_KEY` | `lm-studio` | Bearer token value for local AI requests |
| `SBA_ELASTICSEARCH_ENABLED` | `false` | Enables Elasticsearch indexing/search |
| `SBA_ELASTICSEARCH_URL` | `http://localhost:9200` | Elasticsearch base URL |
| `SBA_ELASTICSEARCH_INDEX` | `sba-agentic-events` | Elasticsearch index name |

The hook script also reads:

| Variable | Default | Use |
| --- | --- | --- |
| `SBA_AGENTIC_URL` | `http://localhost:8766` | Target SBA Agentic server |
| `SBA_AGENT_SOURCE` | first script argument or `unknown` | Source label, such as `claude` or `codex` |

Use a different database file:

```bash
mkdir -p /Users/nathan/.local/share/sba-agentic

SBA_DATASOURCE_URL='jdbc:sqlite:/Users/nathan/.local/share/sba-agentic/sba-agentic.db' \
mvn spring-boot:run
```

Run without local AI:

```bash
SBA_LOCAL_AI_ENABLED=false mvn spring-boot:run
```

Enable Elasticsearch:

```bash
SBA_ELASTICSEARCH_ENABLED=true \
SBA_ELASTICSEARCH_URL=http://localhost:9200 \
SBA_ELASTICSEARCH_INDEX=sba-agentic-events \
mvn spring-boot:run
```

## CLI

Build the jar:

```bash
mvn -DskipTests package
```

Run health checks:

```bash
java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar doctor
```

Capture text:

```bash
java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar ingest \
  --source=manual \
  --session=test \
  --type=ManualCapture \
  --text='first note'
```

Pipe text into capture:

```bash
printf 'note from stdin\n' | java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar ingest \
  --source=manual \
  --session=stdin-test \
  --type=ManualCapture
```

Search:

```bash
java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar search 'first note'
```

List recent sessions:

```bash
java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar sessions --limit=10
```

Summarize a session:

```bash
java -jar target/sba-agentic-0.1.0-SNAPSHOT.jar summarize <session-id>
```

The CLI uses the same environment variables as the server. Set `SBA_DATASOURCE_URL` if you want the CLI and server to use a database outside the repo.

## Web UI

The UI is served from the Spring Boot app at `http://localhost:8766`.

Use it for:

- Checking storage, local AI, and Elasticsearch status.
- Capturing manual notes.
- Searching local and Elasticsearch results.
- Browsing sessions and event timelines.
- Running session summaries.

The UI calls the same `/api/*` endpoints listed below.

## Hook Capture

Start the app first. The hook script reads JSON from stdin, normalizes common Claude Code and Codex hook fields, then posts an event to `/api/events`.

Script path:

```text
/Users/nathan/Developer/proj/sba-agentic/scripts/hooks/sba-agent-hook.sh
```

Manual hook smoke test:

```bash
printf '{"hook_event_name":"UserPromptSubmit","session_id":"hook-test","prompt":"hello from hook","cwd":"%s"}' "$PWD" |
  SBA_AGENT_SOURCE=manual /Users/nathan/Developer/proj/sba-agentic/scripts/hooks/sba-agent-hook.sh
```

Claude Code example:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "SBA_AGENT_SOURCE=claude SBA_AGENTIC_URL=http://localhost:8766 /Users/nathan/Developer/proj/sba-agentic/scripts/hooks/sba-agent-hook.sh"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "SBA_AGENT_SOURCE=claude SBA_AGENTIC_URL=http://localhost:8766 /Users/nathan/Developer/proj/sba-agentic/scripts/hooks/sba-agent-hook.sh"
          }
        ]
      }
    ]
  }
}
```

Codex example shape:

```toml
[hooks.UserPromptSubmit]
command = "SBA_AGENT_SOURCE=codex SBA_AGENTIC_URL=http://localhost:8766 /Users/nathan/Developer/proj/sba-agentic/scripts/hooks/sba-agent-hook.sh"

[hooks.PostToolUse]
command = "SBA_AGENT_SOURCE=codex SBA_AGENTIC_URL=http://localhost:8766 /Users/nathan/Developer/proj/sba-agentic/scripts/hooks/sba-agent-hook.sh"
```

Hook file formats can differ by client version. Keep the command the same and adapt the surrounding config shape to the client.

## MCP

The app exposes a streamable HTTP MCP server at:

```text
http://localhost:8766/mcp
```

Tools:

- `recentSessions` lists recent captured sessions.
- `searchSessions` searches local and Elasticsearch-backed events.
- `captureObservation` writes a note into the event store.
- `localModelStatus` checks the LM Studio/OpenAI-compatible backend.

Register with Codex:

```bash
codex mcp add sba-agentic --url http://localhost:8766/mcp
codex mcp list
```

Register with Claude Code:

```bash
claude mcp add --transport http --scope user sba-agentic http://localhost:8766/mcp
claude mcp list
```

Restart the client after registration if the tools do not appear.

## API

Status:

```bash
curl -fsS http://localhost:8766/api/status | jq
```

List sessions:

```bash
curl -fsS 'http://localhost:8766/api/sessions?limit=25' | jq
```

List session events:

```bash
curl -fsS 'http://localhost:8766/api/sessions/<session-id>/events?limit=100' | jq
```

Search:

```bash
curl -fsS 'http://localhost:8766/api/search?q=first%20note&limit=25' | jq
```

Summarize:

```bash
curl -fsS -X POST 'http://localhost:8766/api/sessions/<session-id>/summarize' | jq
```

Health endpoints:

```bash
curl -fsS http://localhost:8766/api/health/local-ai | jq
curl -fsS http://localhost:8766/api/health/elasticsearch | jq
curl -fsS http://localhost:8766/actuator/health | jq
```

## Data Model

SQLite tables are created from `src/main/resources/schema.sql`.

- `agent_sessions` stores one row per source and client session id.
- `agent_events` stores individual captured prompts, tool events, manual notes, and observations.

The default database file is:

```text
sba-agentic.db
```

Delete that file only if you want to wipe local captured history.

## Troubleshooting

Port already in use:

```bash
lsof -nP -iTCP:8766 -sTCP:LISTEN
SBA_PORT=8767 mvn spring-boot:run
```

Local AI shows offline:

```bash
curl -fsS http://localhost:1234/v1/models | jq
SBA_LOCAL_AI_MODEL=<model-id-from-output> mvn spring-boot:run
```

Hook capture does nothing:

```bash
command -v jq
curl -fsS http://localhost:8766/api/status | jq
```

Then run the manual hook smoke test from the hook section and check the UI.

MCP tools do not show up:

```bash
curl -fsS http://localhost:8766/actuator/health | jq
codex mcp list
claude mcp list
```

Restart the MCP client after adding the server.

Elasticsearch is disabled:

That is the default. Local SQLite search still works. Enable Elasticsearch only when you want a separate search index.
