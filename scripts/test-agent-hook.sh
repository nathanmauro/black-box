#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK="$SCRIPT_DIR/hooks/sba-agent-hook.sh"
# The legacy smoke must not inherit an opted-in user's real outbox settings. Durable tests below
# use their own temporary directories and loopback HTTP fixtures.
export SBA_CAPTURE_DURABLE=0
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

assert_subagent_event() {
  local label="$1"
  local payload="$2"
  local expected_role="$3"
  local expected_event_type="$4"
  local expected_client_session_id="$5"
  local expected_agent_id="$6"
  local expected_agent_type="$7"
  local expected_parent="$8"
  local expected_text="$9"

  assert_event "$label" "$payload" "$expected_role" "$expected_event_type"

  local actual_client_session_id
  local actual_agent_id
  local actual_agent_type
  local actual_parent
  local actual_text
  actual_client_session_id="$(jq -r '.clientSessionId' "$CAPTURE")"
  actual_agent_id="$(jq -r '.metadata.agentId // ""' "$CAPTURE")"
  actual_agent_type="$(jq -r '.metadata.agentType // ""' "$CAPTURE")"
  actual_parent="$(jq -r '.metadata.parentClientSessionId // ""' "$CAPTURE")"
  actual_text="$(jq -r '.text // ""' "$CAPTURE")"

  if [[ "$actual_client_session_id" != "$expected_client_session_id" ]]; then
    echo "$label: expected clientSessionId=$expected_client_session_id, got clientSessionId=$actual_client_session_id" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_agent_id" != "$expected_agent_id" ]]; then
    echo "$label: expected metadata.agentId=$expected_agent_id, got metadata.agentId=$actual_agent_id" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_agent_type" != "$expected_agent_type" ]]; then
    echo "$label: expected metadata.agentType=$expected_agent_type, got metadata.agentType=$actual_agent_type" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_parent" != "$expected_parent" ]]; then
    echo "$label: expected metadata.parentClientSessionId=$expected_parent, got metadata.parentClientSessionId=$actual_parent" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
  if [[ "$actual_text" != "$expected_text" ]]; then
    echo "$label: expected text=$expected_text, got text=$actual_text" >&2
    jq . "$CAPTURE" >&2
    exit 1
  fi
}

assert_subagent_event \
  "subagent start derives child session" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStart","agent_type":"Explore","agent_id":"agent-uuid-1"}' \
  "agent" \
  "SubagentStart" \
  "parent-abc:agent-uuid-1" \
  "agent-uuid-1" \
  "Explore" \
  "parent-abc" \
  ""
assert_subagent_event \
  "subagent stop with final text" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStop","agent_type":"Explore","agent_id":"agent-uuid-1","last_assistant_message":"Explored the repository tree"}' \
  "assistant" \
  "SubagentStop" \
  "parent-abc:agent-uuid-1" \
  "agent-uuid-1" \
  "Explore" \
  "parent-abc" \
  "Explored the repository tree"
assert_subagent_event \
  "subagent stop without final text" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStop","agent_type":"Explore","agent_id":"agent-uuid-1"}' \
  "agent" \
  "SubagentStop" \
  "parent-abc:agent-uuid-1" \
  "agent-uuid-1" \
  "Explore" \
  "parent-abc" \
  ""
assert_subagent_event \
  "snake-case subagent stop" \
  '{"session_id":"parent-abc","hook_event_name":"subagent_stop","agent_type":"general-purpose","agent_id":"agent-uuid-2","last_assistant_message":"Refactor complete"}' \
  "assistant" \
  "subagent_stop" \
  "parent-abc:agent-uuid-2" \
  "agent-uuid-2" \
  "general-purpose" \
  "parent-abc" \
  "Refactor complete"

# Byte-identical contract for non-subagent events: raw session key, metadata is rawHook only.
assert_event \
  "plain stop keeps raw session key" \
  '{"hook_event_name":"Stop","session_id":"plain-session","last_assistant_message":"done"}' \
  "assistant" \
  "Stop"
if [[ "$(jq -r '.clientSessionId' "$CAPTURE")" != "plain-session" ]]; then
  echo "plain stop keeps raw session key: clientSessionId was rewritten" >&2
  jq . "$CAPTURE" >&2
  exit 1
fi
if [[ "$(jq -r '.metadata | keys | join(",")' "$CAPTURE")" != "rawHook" ]]; then
  echo "plain stop keeps raw session key: metadata gained unexpected keys" >&2
  jq . "$CAPTURE" >&2
  exit 1
fi

# A subagent event without agent_id must not derive a child key.
assert_event \
  "subagent start without agent id stays plain" \
  '{"session_id":"parent-abc","hook_event_name":"SubagentStart","agent_type":"Explore"}' \
  "agent" \
  "SubagentStart"
if [[ "$(jq -r '.clientSessionId' "$CAPTURE")" != "parent-abc" ]]; then
  echo "subagent start without agent id stays plain: clientSessionId was rewritten" >&2
  jq . "$CAPTURE" >&2
  exit 1
fi

# Both normalization paths project explicit provenance; legacy rawHook remains unchanged.
assert_transcript_locator() {
  local label="$1" payload="$2" expected="$3" client="$4"
  assert_event "$label" "$payload" "assistant" "$(printf '%s' "$payload" | jq -r '.hook_event_name')"
  if ! jq -e --arg path "$expected" --arg client "$client" --argjson raw "$payload" '
    .clientSessionId == $client and .metadata.rawHook == $raw and
    (if $path == "" then (.metadata | has("transcript_path") | not)
     else .metadata.transcript_path == $path end)' "$CAPTURE" >/dev/null; then
    echo "$label: transcript projection or raw hook changed unexpectedly" >&2
    exit 1
  fi
}
assert_transcript_locator "main transcript snake wins" \
  '{"session_id":"parent","hook_event_name":"Stop","message":"answer","transcript_path":"/fixture/snake.jsonl","transcriptPath":"/fixture/camel.jsonl"}' \
  "/fixture/snake.jsonl" "parent"
assert_transcript_locator "blank snake falls back without trimming path" \
  '{"session_id":"parent","hook_event_name":"Stop","message":"answer","transcript_path":"  ","transcriptPath":" /fixture/space name.jsonl "}' \
  " /fixture/space name.jsonl " "parent"
assert_transcript_locator "nonstring snake falls back" \
  '{"session_id":"parent","hook_event_name":"Stop","message":"answer","transcript_path":7,"transcriptPath":"/fixture/camel.jsonl"}' \
  "/fixture/camel.jsonl" "parent"
assert_transcript_locator "child uses explicit child path only" \
  '{"session_id":"parent","hook_event_name":"SubagentStop","agent_id":"child","message":"answer","transcript_path":"/fixture/parent.jsonl","agent_transcript_path":"/fixture/child.jsonl"}' \
  "/fixture/child.jsonl" "parent:child"
assert_transcript_locator "child never inherits parent locator" \
  '{"session_id":"parent","hook_event_name":"SubagentStop","agent_id":"child","message":"answer","transcript_path":"/fixture/parent.jsonl"}' \
  "" "parent:child"
assert_transcript_locator "child camel fallback" \
  '{"session_id":"parent","hook_event_name":"subagent_stop","agentId":"child","message":"answer","agent_transcript_path":false,"agentTranscriptPath":"/fixture/camel-child.jsonl"}' \
  "/fixture/camel-child.jsonl" "parent:child"
assert_transcript_locator "no child identity keeps parent path" \
  '{"session_id":"parent","hook_event_name":"SubagentStop","message":"answer","transcript_path":"/fixture/parent.jsonl","agent_transcript_path":"/fixture/child.jsonl"}' \
  "/fixture/parent.jsonl" "parent"
assert_transcript_locator "NUL locator omitted without camel retarget" \
  '{"session_id":"parent","hook_event_name":"Stop","message":"answer","transcript_path":"/fixture/nu\u0000l.jsonl","transcriptPath":"/fixture/camel.jsonl"}' \
  "" "parent"
assert_transcript_locator "legacy leaves privacy filtering to ingestion" \
  '{"session_id":"parent","hook_event_name":"Stop","message":"answer","transcript_path":"/fixture/password=fake-sentinel/session.jsonl"}' \
  "/fixture/password=fake-sentinel/session.jsonl" "parent"

# Never-fail contract: real curl on PATH, recorder unreachable -> still exit 0.
set +e
printf '%s' '{"hook_event_name":"SubagentStop","session_id":"p","agent_id":"a","agent_type":"Explore","last_assistant_message":"done"}' |
  SBA_AGENTIC_URL="http://127.0.0.1:1" SBA_AGENT_SOURCE=fixture-source "$HOOK"
HOOK_STATUS=$?
set -e
if [[ "$HOOK_STATUS" -ne 0 ]]; then
  echo "never-fail with recorder down: expected exit 0, got $HOOK_STATUS" >&2
  exit 1
fi
echo "never-fail with recorder down: exit=0"

# Never-fail contract: non-JSON stdin is wrapped as RawText and still exits 0.
set +e
printf '%s' 'not json at all' |
  SBA_AGENTIC_URL="http://127.0.0.1:1" SBA_AGENT_SOURCE=fixture-source "$HOOK"
HOOK_STATUS=$?
set -e
if [[ "$HOOK_STATUS" -ne 0 ]]; then
  echo "never-fail with non-JSON stdin: expected exit 0, got $HOOK_STATUS" >&2
  exit 1
fi
echo "never-fail with non-JSON stdin: exit=0"

python3 "$SCRIPT_DIR/hooks/test_capture_outbox.py"
