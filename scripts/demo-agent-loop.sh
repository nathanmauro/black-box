#!/usr/bin/env bash
# Demonstrate the Black Box coordination loop without touching the live service or database.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="dry-run"
PORT="${SBA_AGENT_LOOP_DEMO_PORT:-8798}"
BASE_URL="http://127.0.0.1:${PORT}"
PREFIX="black-box-saga-e2e"
PROJECT="${PREFIX}-demo-project"
BLOCK_LANE="${PREFIX}-demo-review"
DONE_LANE="${PREFIX}-demo-implementation"
APP_PID=""
TEMP_DIR=""

usage() {
  cat <<'USAGE'
Usage: scripts/demo-agent-loop.sh [--dry-run|--run-isolated]

  --dry-run       Print the isolated REST sequence without starting a server or writing data (default).
  --run-isolated  Build the packaged jar, use a unique temp SQLite DB, run the sequence, then clean up.

SBA_AGENT_LOOP_DEMO_PORT may override the isolated port (default 8798); port 8766 is always refused.
USAGE
}

for argument in "$@"; do
  case "$argument" in
    --dry-run) MODE="dry-run" ;;
    --run-isolated) MODE="run-isolated" ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$argument" >&2; usage >&2; exit 64 ;;
  esac
done

if [[ "$PORT" == "8766" ]]; then
  printf 'Refusing production Black Box port 8766. Choose an isolated port.\n' >&2
  exit 64
fi

print_dry_run() {
  cat <<EOF
Black Box agent coordination demo: DRY RUN
No server will start, no database will be created, and no REST write will execute.
No agent is executed and no external recipient or delivery is involved.

ISOLATED_SERVER=${BASE_URL}
ISOLATED_DB=<unique-temp-dir>/${PREFIX}-demo.db
OPTIONAL_INTEGRATIONS=SBA_ELASTICSEARCH_ENABLED=false SBA_LOCAL_AI_ENABLED=false SBA_ASK_EMBEDDING_ENABLED=false SBA_SUMMARY_BACKEND=local

COMMAND: POST ${BASE_URL}/api/specs
BODY: {"projectKey":"${PROJECT}","title":"${PREFIX} demo spec","body":"${PREFIX} frozen demo specification","specRef":{"fixture":"${PREFIX}"},"actor":"${PREFIX}-demo-planner"}
RESULT: not executed (dry-run); SPEC_ID would be read from the JSON response.

COMMAND: POST ${BASE_URL}/api/tasks (lane ${BLOCK_LANE})
COMMAND: POST ${BASE_URL}/api/tasks (lane ${DONE_LANE})
COMMAND: POST ${BASE_URL}/api/tasks/claim (lane ${BLOCK_LANE})
COMMAND: PATCH ${BASE_URL}/api/tasks/<blocked-task-id> status=blocked
COMMAND: PATCH ${BASE_URL}/api/tasks/<blocked-task-id> status=open
COMMAND: POST ${BASE_URL}/api/tasks/claim (lane ${DONE_LANE})
COMMAND: POST ${BASE_URL}/api/tasks/<done-task-id>/complete
COMMAND: GET ${BASE_URL}/api/recall?scope=<handoff-id>&kinds=handoff
RESULT: not executed (dry-run); isolated mode prints every exact JSON body and response.
EOF
}

if [[ "$MODE" == "dry-run" ]]; then
  print_dry_run
  exit 0
fi

cleanup() {
  local exit_code=$?
  trap - EXIT INT TERM
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ -n "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
    printf 'CLEANUP: removed isolated temp database directory %s\n' "$TEMP_DIR"
  fi
  exit "$exit_code"
}
trap cleanup EXIT INT TERM

for required in curl jq java lsof mvn; do
  command -v "$required" >/dev/null 2>&1 || {
    printf '%s is required for --run-isolated.\n' "$required" >&2
    exit 1
  }
done

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
  printf 'Refusing occupied isolated port %s; no existing process was touched.\n' "$PORT" >&2
  exit 1
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/${PREFIX}-demo.XXXXXX")"
DEMO_DB="${TEMP_DIR}/${PREFIX}-demo.db"
LOG_FILE="${TEMP_DIR}/${PREFIX}-demo.log"
JAR="${PROJECT_DIR}/target/sba-agentic-0.1.0.jar"

printf 'BUILD: (cd %s && mvn -q -Pfrontend -DskipTests package)\n' "$PROJECT_DIR"
(cd "$PROJECT_DIR" && mvn -q -Pfrontend -DskipTests package)

printf 'START: %s with SQLite %s; Elasticsearch/model/embedding integrations disabled\n' "$BASE_URL" "$DEMO_DB"
SBA_PORT="$PORT" \
SBA_BIND_ADDRESS=127.0.0.1 \
SBA_DATASOURCE_URL="jdbc:sqlite:${DEMO_DB}" \
SBA_ELASTICSEARCH_ENABLED=false \
SBA_LOCAL_AI_ENABLED=false \
SBA_ASK_EMBEDDING_ENABLED=false \
SBA_SUMMARY_BACKEND=local \
  java -jar "$JAR" >"$LOG_FILE" 2>&1 &
APP_PID=$!

healthy=0
for _ in $(seq 1 90); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    tail -n 40 "$LOG_FILE" >&2 || true
    printf 'Isolated Black Box exited during startup.\n' >&2
    exit 1
  fi
  if curl -fsS "${BASE_URL}/api/status" >/dev/null 2>&1; then
    healthy=1
    break
  fi
  sleep 1
done
[[ "$healthy" -eq 1 ]] || { printf 'Timed out waiting for %s.\n' "$BASE_URL" >&2; exit 1; }

post_json() {
  local path="$1" body="$2" response
  printf '\nCOMMAND: curl -fsS -X POST -H '\''Content-Type: application/json'\'' %s%s --data '\''%s'\''\n' "$BASE_URL" "$path" "$body" >&2
  response="$(curl -fsS -X POST -H 'Content-Type: application/json' "${BASE_URL}${path}" --data "$body")"
  printf 'RESULT: %s\n' "$(printf '%s' "$response" | jq -c .)" >&2
  printf '%s' "$response"
}

patch_json() {
  local path="$1" body="$2" response
  printf '\nCOMMAND: curl -fsS -X PATCH -H '\''Content-Type: application/json'\'' %s%s --data '\''%s'\''\n' "$BASE_URL" "$path" "$body" >&2
  response="$(curl -fsS -X PATCH -H 'Content-Type: application/json' "${BASE_URL}${path}" --data "$body")"
  printf 'RESULT: %s\n' "$(printf '%s' "$response" | jq -c .)" >&2
  printf '%s' "$response"
}

spec_body="$(jq -cn --arg prefix "$PREFIX" --arg project "$PROJECT" '{projectKey:$project,title:($prefix+" demo spec"),body:($prefix+" frozen demo specification"),specRef:{fixture:$prefix},actor:($prefix+"-demo-planner")}')"
spec="$(post_json /api/specs "$spec_body")"
spec_id="$(printf '%s' "$spec" | jq -er .id)"

block_enqueue="$(jq -cn --arg spec "$spec_id" --arg prefix "$PREFIX" --arg lane "$BLOCK_LANE" '{specId:$spec,title:($prefix+" demo blocked task"),lane:$lane,priority:20,actor:($prefix+"-demo-planner")}')"
block_task="$(post_json /api/tasks "$block_enqueue")"
block_task_id="$(printf '%s' "$block_task" | jq -er .snapshot.task.id)"

done_enqueue="$(jq -cn --arg spec "$spec_id" --arg prefix "$PREFIX" --arg lane "$DONE_LANE" '{specId:$spec,title:($prefix+" demo completed task"),lane:$lane,priority:10,actor:($prefix+"-demo-planner")}')"
done_task="$(post_json /api/tasks "$done_enqueue")"
done_task_id="$(printf '%s' "$done_task" | jq -er .snapshot.task.id)"

block_claim="$(post_json /api/tasks/claim "$(jq -cn --arg lane "$BLOCK_LANE" --arg prefix "$PREFIX" '{lane:$lane,agent:($prefix+"-demo-reviewer")}')")"
[[ "$(printf '%s' "$block_claim" | jq -er .snapshot.task.id)" == "$block_task_id" ]]
patch_json "/api/tasks/${block_task_id}" "$(jq -cn --arg prefix "$PREFIX" '{actor:($prefix+"-demo-reviewer"),status:"blocked",blockedReason:($prefix+" deterministic dependency")}')" >/dev/null
patch_json "/api/tasks/${block_task_id}" "$(jq -cn --arg prefix "$PREFIX" '{actor:($prefix+"-demo-reviewer"),status:"open"}')" >/dev/null

done_claim="$(post_json /api/tasks/claim "$(jq -cn --arg lane "$DONE_LANE" --arg prefix "$PREFIX" '{lane:$lane,agent:($prefix+"-demo-implementer")}')")"
[[ "$(printf '%s' "$done_claim" | jq -er .snapshot.task.id)" == "$done_task_id" ]]
complete_body="$(jq -cn --arg prefix "$PREFIX" '{actor:($prefix+"-demo-implementer"),source:"codex",clientSessionId:($prefix+"-demo-session"),summary:($prefix+" demo completion handoff"),openLoops:[($prefix+" demo cleanup is automatic")],nextAction:($prefix+" inspect the isolated result")}')"
completed="$(post_json "/api/tasks/${done_task_id}/complete" "$complete_body")"
handoff_id="$(printf '%s' "$completed" | jq -er .snapshot.task.resultHandoffId)"

printf '\nCOMMAND: curl -fsS %s/api/recall?scope=%s\\&kinds=handoff\n' "$BASE_URL" "$handoff_id"
recall="$(curl -fsS --get "${BASE_URL}/api/recall" --data-urlencode "scope=${handoff_id}" --data-urlencode 'kinds=handoff')"
printf 'RESULT: %s\n' "$(printf '%s' "$recall" | jq -c .)"
printf '\nPROOF: task %s is done with linked Handoff %s; task %s reset to open.\n' "$done_task_id" "$handoff_id" "$block_task_id"
printf 'No agent was executed and no external recipient or delivery was involved.\n'
