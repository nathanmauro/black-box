#!/usr/bin/env bash
set -euo pipefail

SBA_AGENTIC_URL="${SBA_AGENTIC_URL:-http://localhost:8766}"
SOURCE="${SBA_AGENT_SOURCE:-${1:-unknown}}"
PAYLOAD="$(cat)"

EVENT_TYPE="$(jq -r '.hook_event_name // .hookEventName // .event // "HookEvent"' <<<"$PAYLOAD")"
SESSION_ID="$(jq -r '.session_id // .sessionId // .conversation_id // .conversationId // .turn_id // .turnId // "unknown-session"' <<<"$PAYLOAD")"
TURN_ID="$(jq -r '.turn_id // .turnId // empty' <<<"$PAYLOAD")"
CWD="$(jq -r '.cwd // .workspace // env.PWD' <<<"$PAYLOAD")"
TOOL_NAME="$(jq -r '.tool_name // .toolName // empty' <<<"$PAYLOAD")"
TEXT="$(jq -r '
  def stringify: if type == "string" then . else tojson end;
  (.prompt // .last_assistant_message // .lastAssistantMessage // .message // .tool_response // .toolResponse // .tool_output // .toolOutput // empty) | stringify
' <<<"$PAYLOAD")"
TOOL_INPUT="$(jq -c '.tool_input // .toolInput // null' <<<"$PAYLOAD")"
TOOL_OUTPUT="$(jq -c '.tool_response // .toolResponse // .tool_output // .toolOutput // null' <<<"$PAYLOAD")"

jq -n \
  --arg source "$SOURCE" \
  --arg clientSessionId "$SESSION_ID" \
  --arg turnId "$TURN_ID" \
  --arg eventType "$EVENT_TYPE" \
  --arg text "$TEXT" \
  --arg cwd "$CWD" \
  --arg toolName "$TOOL_NAME" \
  --argjson toolInput "$TOOL_INPUT" \
  --argjson toolOutput "$TOOL_OUTPUT" \
  --argjson raw "$PAYLOAD" \
  '{
    source: $source,
    clientSessionId: $clientSessionId,
    turnId: (if $turnId | length > 0 then $turnId else null end),
    eventType: $eventType,
    role: "agent",
    text: (if $text | length > 0 then $text else null end),
    cwd: $cwd,
    toolName: (if $toolName | length > 0 then $toolName else null end),
    toolInput: $toolInput,
    toolOutput: $toolOutput,
    metadata: { rawHook: $raw },
    observedAt: now | todateiso8601
  }' |
curl -fsS \
  -H "Content-Type: application/json" \
  -X POST \
  --data-binary @- \
  "$SBA_AGENTIC_URL/api/events" >/dev/null
