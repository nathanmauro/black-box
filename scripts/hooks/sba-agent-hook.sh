#!/usr/bin/env bash
# Black Box capture bridge. Reads a Claude Code or Codex hook payload on stdin, normalizes the common
# fields, and posts an event to the local recorder.
#
# Safety contract: this hook must NEVER fail its host agent's turn. If the recorder is down, slow, or
# jq is missing, the agent should carry on as if nothing happened. So we do not use `set -e`, we cap
# the request with a short timeout, we swallow any network failure, and we always exit 0.
set -uo pipefail

if [[ "${SBA_CAPTURE_DURABLE:-0}" == "1" ]]; then
  # An explicit URL (including an empty or unsupported one) is never silently retargeted.
  SBA_AGENTIC_URL="${SBA_AGENTIC_URL-http://127.0.0.1:8766}"
else
  SBA_AGENTIC_URL="${SBA_AGENTIC_URL:-http://localhost:8766}"
fi
SOURCE="${SBA_AGENT_SOURCE:-${1:-unknown}}"

if [[ "${SBA_CAPTURE_DURABLE:-0}" == "1" && "${2:-}" != "--outbox-supervised" ]]; then
  command -v python3 >/dev/null 2>&1 || exit 0
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  # The foreground supervisor bounds stdin reading, normalization, enqueue, and HTTP together.
  # It waits for this invocation only and cleans up its own child group on timeout or signal.
  exec python3 "$SCRIPT_DIR/capture_outbox.py" _hook "$SOURCE"
  exit 0
fi

# If jq is unavailable there is nothing useful we can do — never fail the host agent over it.
command -v jq >/dev/null 2>&1 || exit 0

if [[ "${SBA_CAPTURE_DURABLE:-0}" == "1" ]]; then
  command -v python3 >/dev/null 2>&1 || exit 0
  RAW="$(python3 -c 'import signal,sys; signal.alarm(3); data=sys.stdin.buffer.read(1048577); sys.exit(1) if len(data)>1048576 else sys.stdout.write(data.decode("utf-8"))' 2>/dev/null)" || {
    echo "Black Box outbox: invalid_capture." >&2
    exit 0
  }
else
  RAW="$(cat)"
fi
# Tolerate non-JSON stdin: wrap it so the text is still captured rather than dropped.
if printf '%s' "$RAW" | jq -e . >/dev/null 2>&1; then
  PAYLOAD="$RAW"
else
  if [[ "${SBA_CAPTURE_DURABLE:-0}" == "1" ]]; then
    PAYLOAD="$(printf '%s' "$RAW" | jq -Rs '{hook_event_name: "RawText", prompt: .}' 2>/dev/null)"
  else
    PAYLOAD="$(jq -n --arg t "$RAW" '{hook_event_name: "RawText", prompt: $t}')"
  fi
fi

# Bash can spill large here-strings to disk. Durable input must stay in a pipe until sanitized.
jq_payload() {
  if [[ "${SBA_CAPTURE_DURABLE:-0}" == "1" ]]; then
    printf '%s' "$PAYLOAD" | jq "$@"
  else
    jq "$@" <<<"$PAYLOAD"
  fi
}

EVENT_TYPE="$(jq_payload -r '.hook_event_name // .hookEventName // .event // "HookEvent"')"
SESSION_ID="$(jq_payload -r '.session_id // .sessionId // .conversation_id // .conversationId // .turn_id // .turnId // "unknown-session"')"
TURN_ID="$(jq_payload -r '.turn_id // .turnId // empty')"
CWD="$(jq_payload -r '.cwd // .workspace // env.PWD')"
TOOL_NAME="$(jq_payload -r '.tool_name // .toolName // empty')"
TEXT="$(jq_payload -r '
  def stringify: if type == "string" then . else tojson end;
  (.prompt // .last_assistant_message // .lastAssistantMessage // .message // .tool_response // .toolResponse // .tool_output // .toolOutput // empty) | stringify
')"
TOOL_INPUT="$(jq_payload -c '.tool_input // .toolInput // null')"
TOOL_OUTPUT="$(jq_payload -c '.tool_response // .toolResponse // .tool_output // .toolOutput // null')"
AGENT_ID="$(jq_payload -r '.agent_id // .agentId // empty')"
AGENT_TYPE="$(jq_payload -r '.agent_type // .agentType // empty')"

# Hook names vary by client and version (for example UserPromptSubmit, user_prompt_submit, and
# pre-tool-use). Compare a separator-free, case-insensitive key while preserving the original event
# type in the recorded payload.
EVENT_KEY="$(printf '%s' "$EVENT_TYPE" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]')"
ROLE="agent"
case "$EVENT_KEY" in
  userpromptsubmit|beforesubmitprompt)
    ROLE="user"
    ;;
  stop|assistantmessage|subagentstop)
    if [[ -n "${TEXT//[[:space:]]/}" ]]; then
      ROLE="assistant"
    fi
    ;;
  pretooluse|posttooluse)
    ROLE="tool"
    ;;
esac

# Subagent hooks fire in the PARENT session: the payload's session_id is the parent's id and
# agent_id is unique per spawn. Derive the child session key "<parent>:<agent_id>" and carry the
# lineage in metadata (SubagentStart keeps the default agent role above). Non-subagent events
# never enter this branch, so their output stays byte-identical.
SUBAGENT_METADATA="null"
if [[ "$EVENT_KEY" == "subagentstart" || "$EVENT_KEY" == "subagentstop" ]] && [[ -n "$AGENT_ID" ]]; then
  PARENT_SESSION_ID="$SESSION_ID"
  SESSION_ID="${PARENT_SESSION_ID}:${AGENT_ID}"
  SUBAGENT_METADATA="$(jq -n \
    --arg agentId "$AGENT_ID" \
    --arg agentType "$AGENT_TYPE" \
    --arg parentClientSessionId "$PARENT_SESSION_ID" \
    '{agentId: $agentId, agentType: $agentType, parentClientSessionId: $parentClientSessionId}')"
fi

if [[ "${SBA_CAPTURE_DURABLE:-0}" == "1" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  # Keep the same jq field precedence/role/lineage. Read large text and tools from stdin rather
  # than process arguments, and omit rawHook before the in-memory sanitizer opens queue storage.
  printf '%s' "$PAYLOAD" | jq \
    --arg source "$SOURCE" \
    --arg clientSessionId "$SESSION_ID" \
    --arg turnId "$TURN_ID" \
    --arg eventType "$EVENT_TYPE" \
    --arg role "$ROLE" \
    --arg cwd "$CWD" \
    --arg toolName "$TOOL_NAME" \
    --argjson subagent "$SUBAGENT_METADATA" \
    '
    def stringify: if type == "string" then . else tojson end;
    ((.prompt // .last_assistant_message // .lastAssistantMessage // .message // .tool_response // .toolResponse // .tool_output // .toolOutput // "") | stringify | sub("\n+$"; "")) as $text |
    {
      source: $source, clientSessionId: $clientSessionId,
      turnId: (if $turnId | length > 0 then $turnId else null end),
      eventType: $eventType, role: $role,
      text: (if $text | length > 0 then $text else null end), cwd: $cwd,
      toolName: (if $toolName | length > 0 then $toolName else null end),
      toolInput: (.tool_input // .toolInput // null),
      toolOutput: (.tool_response // .toolResponse // .tool_output // .toolOutput // null),
      metadata: ($subagent // {}), observedAt: now | todateiso8601
    }' 2>/dev/null |
    python3 "$SCRIPT_DIR/capture_outbox.py" enqueue --url "$SBA_AGENTIC_URL" ||
    echo "Black Box outbox: normalization_failed." >&2
  exit 0
fi

jq -n \
  --arg source "$SOURCE" \
  --arg clientSessionId "$SESSION_ID" \
  --arg turnId "$TURN_ID" \
  --arg eventType "$EVENT_TYPE" \
  --arg role "$ROLE" \
  --arg text "$TEXT" \
  --arg cwd "$CWD" \
  --arg toolName "$TOOL_NAME" \
  --argjson toolInput "$TOOL_INPUT" \
  --argjson toolOutput "$TOOL_OUTPUT" \
  --argjson raw "$PAYLOAD" \
  --argjson subagent "$SUBAGENT_METADATA" \
  '{
    source: $source,
    clientSessionId: $clientSessionId,
    turnId: (if $turnId | length > 0 then $turnId else null end),
    eventType: $eventType,
    role: $role,
    text: (if $text | length > 0 then $text else null end),
    cwd: $cwd,
    toolName: (if $toolName | length > 0 then $toolName else null end),
    toolInput: $toolInput,
    toolOutput: $toolOutput,
    metadata: ({ rawHook: $raw } + ($subagent // {})),
    observedAt: now | todateiso8601
  }' |
curl -fsS --max-time 3 \
  -H "Content-Type: application/json" \
  -X POST \
  --data-binary @- \
  "$SBA_AGENTIC_URL/api/events" >/dev/null 2>&1 || true

exit 0
