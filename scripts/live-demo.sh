#!/usr/bin/env bash
#
# live-demo.sh — a scripted live walkthrough of Black Box on a throwaway database.
#
#   Act 0  Boot a private recorder and seed synthetic history through MCP.
#   Act 1  Agent A opens an MCP session and writes a decision with rejected alternatives.
#   Act 2  Agent B opens a new MCP session and recalls a bounded packet before acting.
#   Act 2b Optional: a real Claude Code session makes the same recall and records a decision.
#   Act 3  Show the MCP surface: tools, validation errors, and capture redaction.
#   Act 4  Run the Spring Modulith and ArchUnit checks, then break a boundary on purpose.
#
# Agents A and B are scripted MCP clients with fixed payloads, so the default run launches no
# model. Every call goes over the real Streamable HTTP endpoint, and every check below fails the
# run if it is false.
#
# Usage: scripts/live-demo.sh [--step] [--keep] [--skip-boundaries] [--semantic] [--real-agent]
#   --step             Wait for Enter between acts, for presenting.
#   --keep             Leave the recorder and its scratch directory for exploring.
#   --skip-boundaries  Skip Act 4, which needs Maven.
#   --semantic         Turn on hybrid recall. It needs a local Ollama with nomic-embed-text.
#   --real-agent       Run Act 2b. It needs a signed-in `claude` CLI and costs a few cents.
#
# SBA_DEMO_PORT picks the port (default 18766). Port 8766 is refused so that this script can never
# attach to an installed recorder. SBA_DEMO_CLAUDE_MODEL picks the model for Act 2b.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SEED_FILE="${PROJECT_DIR}/scripts/demo/live-demo-seed.json"
PORT="${SBA_DEMO_PORT:-18766}"
BASE_URL="http://127.0.0.1:${PORT}"
STARTUP_TIMEOUT_SECONDS=90
PROTOCOL_VERSION="2025-06-18"
LEDGER_REPO="/demo/ledger-service"
EMBEDDING_URL="http://localhost:11434"
EMBEDDING_MODEL="nomic-embed-text"
SEMANTIC_WAIT_SECONDS=30
CLAUDE_MODEL="${SBA_DEMO_CLAUDE_MODEL:-claude-sonnet-5-5}"
RECALL_BUDGET=1500
TIGHT_RECALL_BUDGET=760
REAL_AGENT_BUDGET=3000

STEP=0
KEEP=0
SKIP_BOUNDARIES=0
SEMANTIC=0
REAL_AGENT=0
REAL_AGENT_STATUS="not run"
DEMO_DIR=""
APP_PID=""
RPC_ID=0

while (($# > 0)); do
  case "$1" in
    --step) STEP=1 ;;
    --keep) KEEP=1 ;;
    --skip-boundaries) SKIP_BOUNDARIES=1 ;;
    --semantic) SEMANTIC=1 ;;
    --real-agent) REAL_AGENT=1 ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      exit 2
      ;;
  esac
  shift
done

# --------------------------------------------------------------------------- #
# Output
# --------------------------------------------------------------------------- #
if [[ -t 1 ]]; then
  BOLD=$'\033[1m'
  DIM=$'\033[2m'
  CYAN=$'\033[36m'
  GREEN=$'\033[32m'
  YELLOW=$'\033[33m'
  RED=$'\033[31m'
  RESET=$'\033[0m'
else
  BOLD=""
  DIM=""
  CYAN=""
  GREEN=""
  YELLOW=""
  RED=""
  RESET=""
fi

say() { printf '%s\n' "$*"; }
act() { printf '\n%s━━ %s%s\n' "$BOLD$CYAN" "$*" "$RESET"; }
step() { printf '\n%s==>%s %s%s%s\n' "$CYAN" "$RESET" "$BOLD" "$*" "$RESET"; }
ok() { printf '%s  ✓%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s  !%s %s\n' "$YELLOW" "$RESET" "$*"; }
note() { printf '%s    %s%s\n' "$DIM" "$*" "$RESET"; }
die() {
  printf '%s  ✗ %s%s\n' "$RED" "$*" "$RESET" >&2
  exit 1
}

# Scratch paths never reach the transcript, so a recorded run names no workstation paths.
scrub_paths() {
  if [[ -n "$DEMO_DIR" ]]; then
    sed -e "s|${DEMO_DIR}|<scratch>|g"
  else
    cat
  fi
}

pause_for_presenter() {
  if [[ "$STEP" -eq 1 ]]; then
    printf '\n%s[Enter] %s%s' "$DIM" "$1" "$RESET"
    read -r _ </dev/tty
  fi
}

# --------------------------------------------------------------------------- #
# Lifecycle
# --------------------------------------------------------------------------- #
cleanup() {
  local exit_code=$?

  if [[ "$KEEP" -eq 1 && "$exit_code" -eq 0 ]]; then
    return 0
  fi
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ "$exit_code" -ne 0 && -n "$DEMO_DIR" ]]; then
    say "Run failed. The scratch directory is kept for inspection: ${DEMO_DIR}"
  elif [[ -n "$DEMO_DIR" ]]; then
    rm -rf "$DEMO_DIR"
    say "Stopped the recorder and deleted the scratch directory."
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

port_listener_pids() {
  lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN -t 2>/dev/null || true
}

preflight() {
  local tool

  for tool in curl jq lsof java git; do
    command -v "$tool" >/dev/null 2>&1 || die "${tool} is required but is not on PATH."
  done
  [[ "$PORT" =~ ^[0-9]{1,5}$ ]] && ((10#$PORT >= 1 && 10#$PORT <= 65535)) \
    || die "SBA_DEMO_PORT must be an integer from 1 to 65535."
  PORT=$((10#$PORT))
  BASE_URL="http://127.0.0.1:${PORT}"
  [[ "$PORT" -ne 8766 ]] || die "Port 8766 belongs to an installed recorder; pick another port."
  [[ -z "$(port_listener_pids)" ]] || die "Port ${PORT} is in use. Set SBA_DEMO_PORT to a free port."
  [[ -f "$SEED_FILE" ]] || die "Seed file is missing: ${SEED_FILE}"
  [[ "$SEMANTIC" -eq 0 ]] || embedding_model_is_available \
    || die "--semantic needs ${EMBEDDING_MODEL} on a local Ollama at ${EMBEDDING_URL}."
}

embedding_model_is_available() {
  curl -fsS "${EMBEDDING_URL}/api/tags" 2>/dev/null \
    | jq -e --arg model "$EMBEDDING_MODEL" 'any(.models[].name; startswith($model))' >/dev/null
}

project_version() {
  grep -m1 -A1 '<artifactId>sba-agentic</artifactId>' "${PROJECT_DIR}/pom.xml" \
    | sed -n 's|.*<version>\(.*\)</version>.*|\1|p'
}

jar_is_current() {
  local jar="$1"

  [[ -f "$jar" ]] || return 1
  [[ -z "$(find "${PROJECT_DIR}/src" "${PROJECT_DIR}/pom.xml" -newer "$jar" -print -quit)" ]]
}

# Selects the jar by the project version, because target/ can also hold an older jar that an
# installed service runs from. Builds without `clean` for the same reason.
locate_jar() {
  JAR="${PROJECT_DIR}/target/sba-agentic-$(project_version).jar"

  if jar_is_current "$JAR"; then
    ok "Using $(basename "$JAR")."
    return 0
  fi
  if [[ -f "$JAR" && -n "$(lsof -t "$JAR" 2>/dev/null || true)" ]]; then
    die "A running process holds $(basename "$JAR"); build from an isolated checkout instead."
  fi
  command -v mvn >/dev/null 2>&1 || die "mvn is required to build the jar."
  note "Building $(basename "$JAR") with mvn -q -DskipTests package."
  (cd "$PROJECT_DIR" && mvn -q -DskipTests package) || die "Maven build failed."
  [[ -f "$JAR" ]] || die "The build did not produce $(basename "$JAR")."
  ok "Built $(basename "$JAR")."
}

# env -i drops every ambient SBA_* and Spring setting, so nothing can point this process at an
# installed database, a model, or a secrets file. HOME is the scratch directory for the same reason.
start_recorder() {
  local log_file="${DEMO_DIR}/recorder.log"
  local embeddings=false
  local -a runtime_env

  [[ "$SEMANTIC" -eq 0 ]] || embeddings=true

  runtime_env=(
    "PATH=$PATH"
    "HOME=${DEMO_DIR}"
    "LANG=${LANG:-C}"
    "SBA_DATASOURCE_URL=jdbc:sqlite:${DEMO_DIR}/demo.db"
    "SBA_PORT=${PORT}"
    "SBA_BIND_ADDRESS=127.0.0.1"
    "SBA_LOCAL_AI_ENABLED=false"
    "SBA_ELASTICSEARCH_ENABLED=false"
    "SBA_MEMORY_EMBEDDING_ENABLED=${embeddings}"
    "SBA_ASK_EMBEDDING_ENABLED=false"
    "SBA_JUDGE_ENABLED=false"
    "SBA_SUMMARY_BACKEND=local"
    "SBA_EDITOR_ENABLED=false"
  )
  [[ -z "${JAVA_HOME:-}" ]] || runtime_env+=("JAVA_HOME=$JAVA_HOME")

  (
    cd "$DEMO_DIR"
    exec env -i "${runtime_env[@]}" java -jar "$JAR" \
      --spring.config.location=classpath:/application.yml --spring.profiles.active=default
  ) >"$log_file" 2>&1 &
  APP_PID=$!
}

wait_for_recorder() {
  local deadline=$(($(date +%s) + STARTUP_TIMEOUT_SECONDS))

  while (($(date +%s) < deadline)); do
    kill -0 "$APP_PID" 2>/dev/null || die "The recorder exited during startup."
    if recorder_is_empty; then
      [[ "$(port_listener_pids)" == "$APP_PID" ]] \
        || die "The listener on ${PORT} is not this demo's recorder."
      return 0
    fi
    sleep 1
  done
  die "The recorder was not healthy within ${STARTUP_TIMEOUT_SECONDS}s."
}

# Reads the database files the recorder holds open, so isolation is observed rather than assumed.
assert_only_scratch_database() {
  local open_databases

  open_databases="$(lsof -p "$APP_PID" -Fn 2>/dev/null | sed -n 's/^n//p' | grep -E '\.db' | sort -u)"
  [[ -n "$open_databases" ]] || die "The recorder has no database file open."
  while IFS= read -r path; do
    [[ "$path" == "${DEMO_DIR}"/* ]] || die "The recorder opened a database outside scratch."
    note "open file: $(printf '%s' "$path" | scrub_paths)"
  done <<<"$open_databases"
  ok "The recorder holds only scratch database files."
}

# --------------------------------------------------------------------------- #
# REST and MCP clients
# --------------------------------------------------------------------------- #
# Local calls must not inherit an HTTP proxy or wait forever.
curl() { command curl -q --noproxy '*' --connect-timeout 2 --max-time 30 "$@"; }

rest_get() { curl -fsS "${BASE_URL}$1"; }

recorder_is_empty() {
  curl -fsS "${BASE_URL}/api/status" 2>/dev/null | jq -e '.storage.sessions == 0' >/dev/null
}

event_count() { rest_get /api/status | jq '.storage.events'; }

mcp_post() {
  local session_id="$1" body="$2"
  local -a headers

  headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
  [[ -z "$session_id" ]] || headers+=(-H "Mcp-Session-Id: ${session_id}")
  curl -sS "${headers[@]}" -X POST "${BASE_URL}/mcp" -d "$body"
}

# The server answers either as plain JSON or as one SSE frame, depending on the method.
mcp_payload() { sed -n -e 's/^data://' -e '/^{/p'; }

mcp_rpc() {
  local session_id="$1" method="$2" params="$3"
  local body payload

  RPC_ID=$((RPC_ID + 1))
  body="$(jq -nc --argjson id "$RPC_ID" --arg method "$method" --argjson params "$params" \
    '{jsonrpc: "2.0", id: $id, method: $method, params: $params}')"
  payload="$(mcp_post "$session_id" "$body" | mcp_payload)"
  [[ -n "$payload" ]] || die "MCP ${method} returned no payload."
  printf '%s' "$payload" | jq -e '.error == null' >/dev/null || die "MCP ${method} failed: ${payload}"
  printf '%s' "$payload" | jq -c '.result'
}

# Sets MCP_SESSION. The id comes from a response header, as a real client receives it.
mcp_open_session() {
  local client_name="$1"
  local body headers_file="${DEMO_DIR}/mcp-headers.txt"

  body="$(jq -nc --arg name "$client_name" --arg version "$PROTOCOL_VERSION" \
    '{jsonrpc: "2.0", id: 0, method: "initialize", params: {
        protocolVersion: $version, capabilities: {},
        clientInfo: {name: $name, version: "1.0"}}}')"
  curl -sS -D "$headers_file" -o /dev/null \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -X POST "${BASE_URL}/mcp" -d "$body"
  MCP_SESSION="$(tr -d '\r' <"$headers_file" | awk 'tolower($1) == "mcp-session-id:" { print $2 }')"
  [[ -n "$MCP_SESSION" ]] || die "The MCP server returned no session id."
  mcp_post "$MCP_SESSION" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

mcp_close_session() {
  curl -sS -o /dev/null -X DELETE -H "Mcp-Session-Id: $1" \
    -H 'Accept: application/json, text/event-stream' "${BASE_URL}/mcp"
}

# Prints {isError, text}. A tool error is a normal result for the caller to inspect.
mcp_tool() {
  local session_id="$1" tool="$2" arguments="$3"
  local params

  params="$(jq -nc --arg name "$tool" --argjson arguments "$arguments" \
    '{name: $name, arguments: $arguments}')"
  mcp_rpc "$session_id" tools/call "$params" | jq -c '{isError, text: .content[0].text}'
}

# Prints the tool's JSON text, and fails the run on a tool error.
mcp_tool_json() {
  local result

  result="$(mcp_tool "$@")"
  printf '%s' "$result" | jq -e '.isError == false' >/dev/null || die "Tool $2 failed: ${result}"
  printf '%s' "$result" | jq -r '.text'
}

expect() {
  local description="$1" filter="$2" document="$3"

  printf '%s' "$document" | jq -e "$filter" >/dev/null || die "Check failed: ${description}"
  ok "$description"
}

# --------------------------------------------------------------------------- #
# Act 0: boot and seed
# --------------------------------------------------------------------------- #
seed_history() {
  local session count index entry tool arguments response stale_id=""

  mcp_open_session "demo-seed"
  session="$MCP_SESSION"
  count="$(jq '.captures | length' "$SEED_FILE")"

  for ((index = 0; index < count; index++)); do
    entry="$(jq -c ".captures[${index}]" "$SEED_FILE")"
    tool="$(printf '%s' "$entry" | jq -r '.tool')"
    arguments="$(printf '%s' "$entry" | jq -c '.arguments')"

    if printf '%s' "$entry" | jq -e '.supersedes == "stale"' >/dev/null; then
      arguments="$(printf '%s' "$arguments" | jq -c --arg id "$stale_id" '. + {supersedes: $id}')"
    fi
    response="$(mcp_tool_json "$session" "$tool" "$arguments")"
    if printf '%s' "$entry" | jq -e '.seedAs == "stale"' >/dev/null; then
      stale_id="$(printf '%s' "$response" | jq -r '.eventId')"
    fi
  done

  mcp_close_session "$session"
  ok "Seeded ${count} synthetic captures through MCP tools/call."
  note "Projects: /demo/ledger-service, /demo/docs-portal. One seeded decision is superseded."
}

act_boot() {
  act "Act 0: a private recorder on a throwaway database"
  preflight
  ok "Port ${PORT} is free, and this script owns the recorder it starts."

  umask 077
  DEMO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/black-box-live-demo.XXXXXX")"
  DEMO_DIR="$(cd "$DEMO_DIR" && pwd -P)"

  locate_jar
  start_recorder
  wait_for_recorder
  ok "Recorder is up at ${BASE_URL}, starting from 0 sessions and 0 events."
  note "Local AI, Elasticsearch, the judge and the editor are off."
  if [[ "$SEMANTIC" -eq 1 ]]; then
    note "Embeddings are on, through ${EMBEDDING_MODEL} on local Ollama."
  else
    note "Embeddings are off, so recall is a lexical substring match."
  fi
  assert_only_scratch_database
  seed_history
}

# --------------------------------------------------------------------------- #
# Act 1: agent A writes a decision
# --------------------------------------------------------------------------- #
act_write() {
  local arguments response session

  act "Act 1: agent A decides, and records what it rejected"
  mcp_open_session "scripted-agent-a"
  session="$MCP_SESSION"
  ok "Agent A opened MCP session ${session:0:8}…"

  arguments="$(jq -nc --arg repo "$LEDGER_REPO" '{
    source: "manual",
    clientSessionId: "scripted-agent-a",
    repo: $repo,
    decision: "Deduplicate payment webhooks with a unique constraint on the provider event id",
    rationale: "The insert shares a transaction with the ledger write, so a duplicate delivery rolls back with no double post. It needs no new infrastructure.",
    alternatives: [
      "Redis SETNX with a TTL: rejected because a failover inside the retry window lets a duplicate reach the ledger",
      "Trust the provider to deliver once: rejected because its contract is at-least-once",
      "Reconcile duplicates nightly: rejected because a customer sees the double post until the job runs"
    ],
    confidence: 0.85,
    openLoops: ["Retention for processed event ids is unset; it must outlast the 72-hour retry window"]
  }')"

  step "tools/call captureDecision"
  printf '%s' "$arguments" | jq '{decision, alternatives, confidence, openLoops}'
  response="$(mcp_tool_json "$session" captureDecision "$arguments")"
  AGENT_A_EVENT="$(printf '%s' "$response" | jq -r '.eventId')"
  expect "The server committed event ${AGENT_A_EVENT:0:8}… as a Decision." \
    '.eventType == "Decision"' "$response"

  mcp_close_session "$session"
  ok "Agent A closed its MCP session. Nothing it knew survives except what it committed."
}

# --------------------------------------------------------------------------- #
# Act 2: agent B recalls a bounded packet, then acts
# --------------------------------------------------------------------------- #
render_packet() {
  jq -r '
    .items[]
    | "\(.kind | ascii_upcase)  \(.headline)",
      "  from      \(.clientSessionId) · event \(.eventId[0:8])…",
      (if .rationale then "  why       \(.rationale)" else empty end),
      ((.alternatives // [])[] | "  rejected  \(.)"),
      ((.openLoops // [])[] | "  open loop \(.)"),
      (if .nextAction then "  next      \(.nextAction)" else empty end),
      ""'
}

# Lexical recall needs a substring. Hybrid recall ranks a single word poorly, so it gets a phrase.
recall_query() {
  if [[ "$SEMANTIC" -eq 1 ]]; then
    printf 'deduplicate payment webhooks'
  else
    printf 'webhook'
  fi
}

recall_arguments() {
  jq -nc --arg project "$LEDGER_REPO" --arg query "$(recall_query)" --argjson budget "$1" '{
    project: $project,
    query: $query,
    limit: 3,
    maxChars: $budget
  }'
}

# Embedding happens after the capture commits, so the first vector can lag the write.
recall_by_meaning() {
  local session="$1"
  local question="How do we avoid charging a customer twice when an event is resent?"
  local deadline=$(($(date +%s) + SEMANTIC_WAIT_SECONDS))
  local arguments result

  step "The same need in different words"
  say "  ${question}"
  arguments="$(jq -nc --arg project "$LEDGER_REPO" --arg query "$question" \
    '{project: $project, query: $query, limit: 3, maxChars: 1500}')"

  while (($(date +%s) < deadline)); do
    result="$(mcp_tool_json "$session" recallContext "$arguments")"
    if printf '%s' "$result" | jq -e --arg event "$AGENT_A_EVENT" \
      '.mode == "hybrid" and any(.items[]; .eventId == $event)' >/dev/null; then
      printf '%s' "$result" | jq -r '.items[] | "  \(.score // 0 | . * 1000 | floor / 1000)  \(.headline)"'
      ok "Hybrid recall found agent A's decision by meaning."
      return 0
    fi
    sleep 1
  done
  die "Hybrid recall did not find agent A's decision within ${SEMANTIC_WAIT_SECONDS}s."
}

act_recall() {
  local session packet tight history response arguments

  act "Act 2: agent B recalls a bounded packet before acting"
  mcp_open_session "scripted-agent-b"
  session="$MCP_SESSION"
  ok "Agent B opened a new MCP session ${session:0:8}…, with no shared context."

  step "tools/call recallContext (project scoped, limit 3, maxChars ${RECALL_BUDGET})"
  recall_arguments "$RECALL_BUDGET" | jq -c .
  packet="$(mcp_tool_json "$session" recallContext "$(recall_arguments "$RECALL_BUDGET")")"
  printf '\n'
  printf '%s' "$packet" | render_packet
  note "mode $(printf '%s' "$packet" | jq -r '.mode'), $(printf '%s' "$packet" | jq -r '.count') items," \
    "truncated $(printf '%s' "$packet" | jq -r '.truncated')," \
    "$(printf '%s' "$packet" | wc -c | tr -d ' ') bytes of JSON"

  step "What the packet must contain, and what it must leave out"
  expect "Agent A's decision arrived with all 3 rejected alternatives." \
    "any(.items[]; .eventId == \"${AGENT_A_EVENT}\" and (.alternatives | length) == 3)" "$packet"
  expect "Agent A's open loop arrived intact." \
    'any(.items[].openLoops[]?; test("72-hour retry window"))' "$packet"
  expect "The webhook decision in /demo/docs-portal stayed out, because recall is project scoped." \
    'all(.items[]; .repo == "/demo/ledger-service")' "$packet"
  expect "The superseded synchronous decision stayed out." \
    'all(.items[]; (.headline | test("synchronously")) | not)' "$packet"

  step "A tighter budget: the same recall with maxChars ${TIGHT_RECALL_BUDGET}"
  tight="$(mcp_tool_json "$session" recallContext "$(recall_arguments "$TIGHT_RECALL_BUDGET")")"
  printf '%s' "$tight" | jq '{count, truncated, rationale: .items[0].rationale}'
  expect "The server kept one item, cut its rationale visibly, and reported truncated." \
    '.truncated == true and .count == 1 and (.items[0].rationale | test("chars\\)$"))' "$tight"
  note "The budget covers text fields plus 200 per item, not JSON framing."

  [[ "$SEMANTIC" -eq 0 ]] || recall_by_meaning "$session"

  step "Replaced decisions stay available for audit: includeSuperseded true"
  arguments="$(jq -nc --arg project "$LEDGER_REPO" \
    '{project: $project, query: "payment webhooks", limit: 10, includeSuperseded: true}')"
  history="$(mcp_tool_json "$session" recallContext "$arguments")"
  expect "The original synchronous decision is still on record." \
    'any(.items[]; .headline | test("synchronously"))' "$history"

  step "Agent B acts on the packet instead of re-deciding"
  expect "Redis is in the packet as rejected, so agent B does not reopen it." \
    'any(.items[].alternatives[]?; test("^Redis"))' "$packet"
  arguments="$(jq -nc --arg repo "$LEDGER_REPO" --arg event "$AGENT_A_EVENT" '{
    source: "manual",
    clientSessionId: "scripted-agent-b",
    repo: $repo,
    decision: "Keep processed webhook event ids for 30 days",
    rationale: ("Closes the retention open loop on decision " + $event + ". Thirty days outlasts the 72-hour retry window and covers a manual replay."),
    alternatives: [
      "Exactly 72 hours: rejected because a manual replay after an incident can arrive later",
      "Keep ids forever: rejected because the table grows with no added protection"
    ],
    confidence: 0.8,
    openLoops: []
  }')"
  response="$(mcp_tool_json "$session" captureDecision "$arguments")"
  AGENT_B_EVENT="$(printf '%s' "$response" | jq -r '.eventId')"
  ok "Agent B closed the open loop with decision ${AGENT_B_EVENT:0:8}…, citing agent A's event."

  arguments="$(jq -nc --arg repo "$LEDGER_REPO" '{
    source: "manual",
    clientSessionId: "scripted-agent-b",
    repo: $repo,
    toAgent: "next-session",
    contextSummary: "Webhook deduplication and id retention are decided. The refund handler is unblocked.",
    openLoops: ["The purge job for expired event ids is not written"],
    nextAction: "Write the purge job, then ship the refund handler"
  }')"
  mcp_tool_json "$session" captureHandoff "$arguments" >/dev/null
  ok "Agent B left a handoff with one next action."

  step "Provenance: the record behind the recalled decision, over REST"
  rest_get "/api/events/${AGENT_A_EVENT}" \
    | jq -c '{eventType, source, clientSessionId, repo: .metadata.repo, createdAt: .observedAt}'

  mcp_close_session "$session"
}

# --------------------------------------------------------------------------- #
# Act 2b: a real agent, on request
# --------------------------------------------------------------------------- #
# Without embeddings a natural question matches nothing, so the prompt has to name a keyword.
real_agent_prompt() {
  local query="query webhook"

  [[ "$SEMANTIC" -eq 0 ]] || query="a query of your choice"
  cat <<PROMPT
You are working in the repo ${LEDGER_REPO}. A teammate proposes using Redis SETNX to deduplicate
payment webhooks. Before you answer, call recallContext with project ${LEDGER_REPO}, ${query},
limit 4, and maxChars ${REAL_AGENT_BUDGET}. Then record your conclusion with captureDecision (source
claude, clientSessionId real-agent-demo, repo ${LEDGER_REPO}) and tell me in two sentences what you
decided and why.
PROMPT
}

# --restricted ignores user and project settings, so the presenter's capture hooks do not fire and
# this session stays out of any installed recorder. --strict-mcp-config offers only the scratch one.
run_real_agent() {
  local directory="$1"

  jq -n --arg url "${BASE_URL}/mcp" \
    '{mcpServers: {"black-box-demo": {type: "http", url: $url}}}' >"${directory}/mcp.json"
  (
    cd "$directory"
    claude -p --restricted --strict-mcp-config --mcp-config mcp.json \
      --model "$CLAUDE_MODEL" --tools "" --max-budget-usd 0.50 \
      --allowedTools mcp__black-box-demo__recallContext mcp__black-box-demo__captureDecision \
      --output-format stream-json --verbose "$(real_agent_prompt)" \
      </dev/null >stream.jsonl 2>stderr.txt
  )
}

# Reads the recall result the model received, not the prose it wrote about it.
real_agent_packet() {
  jq -r 'select(.type == "user") | .message.content[]?
    | select(.type? == "tool_result") | .content
    | if type == "array" then .[0].text else . end' "$1" | head -1
}

# Fails softly: a model or network problem must not end a presentation before the later acts.
act_real_agent() {
  local directory="${DEMO_DIR}/real-agent"
  local stream="${directory}/stream.jsonl"
  local tools session recorded

  act "Act 2b: a real Claude Code session, same server, same tools"
  REAL_AGENT_STATUS="failed"
  command -v claude >/dev/null 2>&1 || {
    warn "The claude CLI is not on PATH; skipping this act."
    return 0
  }
  mkdir -p "$directory"
  step "claude -p on ${CLAUDE_MODEL}, offered only recallContext and captureDecision"
  real_agent_prompt | sed 's/^/    /'
  run_real_agent "$directory" || {
    warn "The claude run failed; skipping this act."
    return 0
  }

  tools="$(jq -r 'select(.type == "assistant") | .message.content[]
    | select(.type == "tool_use") | .name | sub("^mcp__black-box-demo__"; "")' "$stream")"
  step "What the model did"
  printf '%s\n' "$tools" | sed 's/^/  tool call: /'
  printf '\n'
  jq -r 'select(.type == "result") | .result' "$stream" | sed -e 's/^/  /' -e 's/[[:space:]]*$//'
  printf '\n'

  mcp_open_session "scripted-inspector"
  session="$MCP_SESSION"
  recorded="$(mcp_tool_json "$session" recallContext "$(jq -nc --arg project "$LEDGER_REPO" \
    '{project: $project, query: "webhook", limit: 10}')")"
  mcp_close_session "$session"

  if [[ "$(printf '%s\n' "$tools" | head -1)" != "recallContext" ]]; then
    warn "The model did not recall first."
  elif ! real_agent_packet "$stream" | jq -e --arg event "$AGENT_A_EVENT" \
    'any(.items[]; .eventId == $event and (.alternatives | length) == 3)' >/dev/null; then
    warn "Agent A's decision was not in the packet the model received."
  elif ! printf '%s' "$recorded" | jq -e \
    'any(.items[]; .source == "claude" and .clientSessionId == "real-agent-demo")' >/dev/null; then
    warn "The model's decision is not on record."
  else
    REAL_AGENT_STATUS="passed"
    ok "The model recalled first, received agent A's decision, and recorded its own."
  fi
}

# --------------------------------------------------------------------------- #
# Act 3: the MCP surface
# --------------------------------------------------------------------------- #
act_surface() {
  local session tools count before after result arguments recalled

  act "Act 3: the MCP surface"
  mcp_open_session "scripted-inspector"
  session="$MCP_SESSION"

  step "tools/list over ${BASE_URL}/mcp"
  tools="$(mcp_rpc "$session" tools/list '{}')"
  count="$(printf '%s' "$tools" | jq '.tools | length')"
  printf '%s' "$tools" | jq -r '.tools | sort_by(.name)[] | "  \(.name)"'
  ok "${count} tools are registered."

  step "The recallContext input schema a client discovers"
  printf '%s' "$tools" | jq -c '.tools[] | select(.name == "recallContext")
    | .inputSchema.properties | keys'

  step "A capture with a blank required field is refused, and writes nothing"
  before="$(event_count)"
  arguments="$(jq -nc --arg repo "$LEDGER_REPO" '{
    source: "manual", clientSessionId: "scripted-inspector", repo: $repo,
    decision: " ", rationale: "r", alternatives: [], openLoops: []
  }')"
  result="$(mcp_tool "$session" captureDecision "$arguments")"
  printf '%s\n' "$result"
  after="$(event_count)"
  expect "The tool returned isError true." '.isError == true' "$result"
  [[ "$before" == "$after" ]] || die "The refused capture changed the event count."
  ok "The event count stayed at ${after}."

  step "A credential pasted into a capture is redacted before it is stored"
  arguments="$(jq -nc '{
    source: "manual", clientSessionId: "scripted-inspector", repo: "/demo/redaction-check",
    decision: "Rotate the staging database login",
    rationale: "It was pasted into a chat as password=demo-not-a-real-secret",
    alternatives: [], openLoops: []
  }')"
  mcp_tool_json "$session" captureDecision "$arguments" >/dev/null
  recalled="$(mcp_tool_json "$session" recallContext \
    '{"project":"/demo/redaction-check","query":"staging database login"}')"
  printf '%s' "$recalled" | jq -r '.items[0].rationale'
  expect "Recall returned the redacted text." \
    '.items[0].rationale | test("demo-not-a-real-secret") | not' "$recalled"

  mcp_close_session "$session"
}

# --------------------------------------------------------------------------- #
# Act 4: module boundaries
# --------------------------------------------------------------------------- #
boundary_tests() {
  local directory="$1"

  (
    cd "$directory"
    mvn -q test -Dtest='ApplicationModuleStructureTest,PackageArchitectureTest'
  ) >"${directory}/boundary.log" 2>&1
}

print_boundary_results() {
  sed -n 's/^Tests run:/  Tests run:/p' "$1"/target/surefire-reports/*.txt | sort -u
}

# Reads the generated C4 diagram, so the graph shown is the one the verifier saw.
print_module_graph() {
  sed -n 's/^Rel(.*\.\([A-Za-z]*\), .*\.\([A-Za-z]*\), ".*/\1 \2/p' "$1" \
    | sort -u \
    | awk '
        $1 != from { if (from) print line; from = $1; line = sprintf("  %-15s -> %s", $1, $2); next }
        { line = line ", " $2 }
        END { if (from) print line }'
}

# The violation goes into a copy of HEAD, so a presenter's checkout is never edited.
act_boundaries() {
  local copy="${DEMO_DIR}/boundary"
  local violation="src/main/java/dev/nathan/sbaagentic/recording/DemoBoundaryViolation.java"

  act "Act 4: boundaries the build enforces"
  [[ "$SKIP_BOUNDARIES" -eq 0 ]] || {
    note "Skipped by --skip-boundaries."
    return 0
  }
  command -v mvn >/dev/null 2>&1 || die "mvn is required for Act 4; use --skip-boundaries."

  mkdir -p "$copy"
  git -C "$PROJECT_DIR" archive HEAD | tar -x -C "$copy"

  step "Spring Modulith and ArchUnit against a clean copy of HEAD"
  boundary_tests "$copy" || {
    sed -n '1,40p' "${copy}/boundary.log" | scrub_paths
    die "The boundary tests failed on a clean copy."
  }
  print_boundary_results "$copy"
  ok "Spring Modulith verified this module graph, and generated it from the code:"
  print_module_graph "${copy}/target/spring-modulith-docs/components.puml"

  step "Break a boundary on purpose: recording imports an internal class of memory"
  mkdir -p "$(dirname "${copy}/${violation}")"
  cat >"${copy}/${violation}" <<'JAVA'
package dev.nathan.sbaagentic.recording;

import dev.nathan.sbaagentic.memory.internal.application.EvidenceService;

class DemoBoundaryViolation {

    private EvidenceService reachedAcrossTheBoundary;
}
JAVA
  sed -n '3p' "${copy}/${violation}"
  if boundary_tests "$copy"; then
    die "The boundary tests passed with a violation in place."
  fi
  sed -n '/^\[ERROR\] Failures:/,/^\[ERROR\] Tests run:/p' "${copy}/boundary.log" \
    | grep -E "Test\.|Expecting empty|Module 'recording' depends|Tests run:" \
    | sed -e 's/^\[ERROR\] *//' -e 's/^[ -]*//' -e 's/dev\.nathan\.sbaagentic\.//g' -e 's/^/  /' \
    | scrub_paths | cut -c1-120 | sed 's/[[:space:]]*$//'
  ok "The build failed on the violation."
}

# --------------------------------------------------------------------------- #
# Finish
# --------------------------------------------------------------------------- #
finish() {
  local status

  status="$(rest_get /api/status)"
  act "Done"
  printf '%s' "$status" | jq -r '.storage | "Final store: \(.sessions) sessions, \(.events) events."'
  assert_only_scratch_database
  say "Agents A and B were scripted MCP clients. Real Claude Code session: ${REAL_AGENT_STATUS}."
  say "All records are synthetic."

  if [[ "$KEEP" -eq 1 ]]; then
    say ""
    say "Recorder left running: PID ${APP_PID}, UI at ${BASE_URL}/recall"
    say "Scratch directory: ${DEMO_DIR}"
    say "Stop it with: kill ${APP_PID}"
    trap - EXIT
    return 0
  fi
  pause_for_presenter "stop the recorder and delete the scratch directory (UI: ${BASE_URL}/recall)"
  [[ "$REAL_AGENT" -eq 0 || "$REAL_AGENT_STATUS" == "passed" ]] || die "Act 2b did not pass."
}

act_boot
pause_for_presenter "Act 1: agent A writes"
act_write
pause_for_presenter "Act 2: agent B recalls"
act_recall
if [[ "$REAL_AGENT" -eq 1 ]]; then
  pause_for_presenter "Act 2b: a real Claude Code session"
  act_real_agent
fi
pause_for_presenter "Act 3: the MCP surface"
act_surface
pause_for_presenter "Act 4: boundaries"
act_boundaries
finish
