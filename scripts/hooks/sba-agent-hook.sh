#!/usr/bin/env bash
# Black Box capture bridge. Reads a Claude Code or Codex hook payload on stdin, normalizes the common
# fields, and posts an event to the local recorder.
#
# Safety contract: this hook must NEVER fail its host agent's turn. If the recorder is down, slow, or
# jq is missing, the agent should carry on as if nothing happened. So we do not use `set -e`, we cap
# the request with a short timeout, we swallow any network failure, and we always exit 0.
set -uo pipefail

SBA_AGENTIC_URL="${SBA_AGENTIC_URL:-http://localhost:8766}"
SOURCE="${SBA_AGENT_SOURCE:-${1:-unknown}}"

# If jq is unavailable there is nothing useful we can do — never fail the host agent over it.
command -v jq >/dev/null 2>&1 || exit 0

RAW="$(cat)"
# Tolerate non-JSON stdin: wrap it so the text is still captured rather than dropped.
if printf '%s' "$RAW" | jq -e . >/dev/null 2>&1; then
  PAYLOAD="$RAW"
else
  PAYLOAD="$(jq -n --arg t "$RAW" '{hook_event_name: "RawText", prompt: $t}')"
fi

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

# Hook names vary by client and version (for example UserPromptSubmit, user_prompt_submit, and
# pre-tool-use). Compare a separator-free, case-insensitive key while preserving the original event
# type in the recorded payload.
EVENT_KEY="$(printf '%s' "$EVENT_TYPE" | tr '[:upper:]' '[:lower:]' | tr -cd '[:alnum:]')"
ROLE="agent"
case "$EVENT_KEY" in
  userpromptsubmit|beforesubmitprompt)
    ROLE="user"
    ;;
  stop|assistantmessage)
    if [[ -n "${TEXT//[[:space:]]/}" ]]; then
      ROLE="assistant"
    fi
    ;;
  pretooluse|posttooluse)
    ROLE="tool"
    ;;
esac

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
    metadata: { rawHook: $raw },
    observedAt: now | todateiso8601
  }' |
curl -fsS --max-time 3 \
  -H "Content-Type: application/json" \
  -X POST \
  --data-binary @- \
  "$SBA_AGENTIC_URL/api/events" >/dev/null 2>&1 || true

exit 0
