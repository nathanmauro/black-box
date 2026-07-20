#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK="$SCRIPT_DIR/hooks/sba-agent-hook.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

FAKE_BIN="$TMP_DIR/bin"
CAPTURE="$TMP_DIR/event.json"
mkdir -p "$FAKE_BIN"

cat >"$FAKE_BIN/curl" <<'SH'
#!/usr/bin/env bash
cat >"$SBA_AGENT_HOOK_TEST_CAPTURE"
SH
chmod +x "$FAKE_BIN/curl"

assert_event() {
  local label="$1"
  local payload="$2"
  local expected_role="$3"
  local expected_event_type="$4"

  rm -f "$CAPTURE"
  PATH="$FAKE_BIN:$PATH" \
  SBA_AGENT_HOOK_TEST_CAPTURE="$CAPTURE" \
  SBA_AGENTIC_URL="http://fixture.invalid" \
    "$HOOK" fixture-source <<<"$payload"

  if [[ ! -s "$CAPTURE" ]]; then
    echo "$label: hook did not send an event" >&2
    exit 1
  fi

  local actual_role
  local actual_event_type
  actual_role="$(jq -r '.role' "$CAPTURE")"
  actual_event_type="$(jq -r '.eventType' "$CAPTURE")"

  if [[ "$actual_role" != "$expected_role" ]]; then
    echo "$label: expected role=$expected_role, got role=$actual_role" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_event_type" != "$expected_event_type" ]]; then
    echo "$label: expected eventType=$expected_event_type, got eventType=$actual_event_type" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi

  echo "$label: role=$actual_role eventType=$actual_event_type"
}

assert_event \
  "camel-case user prompt" \
  '{"hook_event_name":"UserPromptSubmit","prompt":"Show the conversation"}' \
  "user" \
  "UserPromptSubmit"
assert_event \
  "snake-case user prompt" \
  '{"hook_event_name":"user_prompt_submit","prompt":"Show the conversation"}' \
  "user" \
  "user_prompt_submit"
assert_event \
  "legacy user prompt" \
  '{"hook_event_name":"beforeSubmitPrompt","prompt":"Show the conversation"}' \
  "user" \
  "beforeSubmitPrompt"
assert_event \
  "stop with assistant text" \
  '{"hook_event_name":"Stop","last_assistant_message":"Here is the response"}' \
  "assistant" \
  "Stop"
assert_event \
  "assistant message with text" \
  '{"hook_event_name":"AssistantMessage","message":"Here is the response"}' \
  "assistant" \
  "AssistantMessage"
assert_event \
  "stop without assistant text" \
  '{"hook_event_name":"Stop"}' \
  "agent" \
  "Stop"
assert_event \
  "stop with whitespace only" \
  '{"hook_event_name":"Stop","last_assistant_message":"   "}' \
  "agent" \
  "Stop"
assert_event \
  "camel-case pre-tool event" \
  '{"hook_event_name":"PreToolUse","tool_name":"Read"}' \
  "tool" \
  "PreToolUse"
assert_event \
  "camel-case post-tool event" \
  '{"hook_event_name":"PostToolUse","tool_name":"Read"}' \
  "tool" \
  "PostToolUse"
assert_event \
  "hyphenated pre-tool event" \
  '{"hook_event_name":"pre-tool-use","tool_name":"Read"}' \
  "tool" \
  "pre-tool-use"
assert_event \
  "upper snake-case post-tool event" \
  '{"hook_event_name":"POST_TOOL_USE","tool_name":"Read"}' \
  "tool" \
  "POST_TOOL_USE"
assert_event \
  "unmapped event" \
  '{"hook_event_name":"Notification","message":"Build finished"}' \
  "agent" \
  "Notification"
