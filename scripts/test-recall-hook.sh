#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK="$SCRIPT_DIR/hooks/sba-recall-hook.sh"
TMP_DIR="$(mktemp -d)"
trap 'chmod -R u+w "$TMP_DIR" 2>/dev/null || true; rm -rf "$TMP_DIR"' EXIT

FAKE_BIN="$TMP_DIR/bin"
RESPONSE_FILE="$TMP_DIR/response.json"
REQUEST_CAPTURE="$TMP_DIR/request.txt"
STDOUT_FILE="$TMP_DIR/stdout.txt"
STDERR_FILE="$TMP_DIR/stderr.txt"
LOG_FILE="$TMP_DIR/recall.log"
VALID_CWD="/tmp/black-box repo"
VALID_PAYLOAD="{\"session_id\":\"session-1\",\"cwd\":\"$VALID_CWD\"}"
RUN_HOME="$TMP_DIR/home"
RUN_LOG="$LOG_FILE"
RUN_LOG_UNSET=0
RUN_MAX_CHARS=""
RUN_CURL_EXIT=""

mkdir -p "$FAKE_BIN" "$RUN_HOME"

cat >"$FAKE_BIN/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"$SBA_RECALL_TEST_REQUEST_FILE"
if [[ "${SBA_RECALL_TEST_CURL_EXIT:-0}" != "0" ]]; then
  exit "$SBA_RECALL_TEST_CURL_EXIT"
fi
cat "$SBA_RECALL_TEST_RESPONSE_FILE"
SH
chmod +x "$FAKE_BIN/curl"

reset_run_env() {
  RUN_HOME="$TMP_DIR/home"
  RUN_LOG="$LOG_FILE"
  RUN_LOG_UNSET=0
  RUN_MAX_CHARS=""
  RUN_CURL_EXIT=""
  mkdir -p "$RUN_HOME"
}

write_empty_response() {
  cat >"$RESPONSE_FILE" <<'JSON'
{"withinHours":720,"items":[]}
JSON
}

write_five_item_response() {
  cat >"$RESPONSE_FILE" <<'JSON'
{
  "withinHours": 720,
  "items": [
    {
      "kind": "decision",
      "source": "codex",
      "observedAt": "2026-08-20T10:00:00Z",
      "headline": "Older decision",
      "rationale": "Keep the test stable.",
      "openLoops": ["This older item should be hidden by the limit"]
    },
    {
      "kind": "handoff",
      "source": "codex",
      "toAgent": "claude",
      "observedAt": "2026-08-28T12:00:00Z",
      "headline": "Newest handoff",
      "nextAction": "Pick up the wire read half and verify the shell path.",
      "openLoops": ["Run the recall hook shell test", "Confirm the request is bounded"]
    },
    {
      "kind": "decision",
      "source": "claude",
      "observedAt": "2026-08-25T09:30:00Z",
      "headline": "Middle decision",
      "rationale": "Bound recall output so the startup packet stays small."
    },
    {
      "kind": "handoff",
      "source": "codex",
      "toAgent": "codex",
      "observedAt": "2026-08-01T00:00:00Z",
      "headline": "Oldest handoff",
      "nextAction": "This item should be excluded."
    },
    {
      "kind": "decision",
      "source": "codex",
      "observedAt": "2026-08-27T08:00:00Z",
      "headline": "Second decision",
      "rationale": "Log recall fires with outcome, item count, and block size."
    }
  ]
}
JSON
}

fail_case() {
  local label="$1"
  local message="$2"
  echo "FAIL $label: $message" >&2
  exit 1
}

pass_case() {
  echo "PASS $1"
}

run_hook() {
  local payload="$1"
  shift
  local env_cmd
  local status

  rm -f "$STDOUT_FILE" "$STDERR_FILE" "$REQUEST_CAPTURE" "$LOG_FILE"
  mkdir -p "$RUN_HOME"

  env_cmd=(env -u SBA_RECALL_CLIENT)
  if [[ "$RUN_LOG_UNSET" -eq 1 ]]; then
    env_cmd+=(-u SBA_RECALL_LOG)
  else
    env_cmd+=("SBA_RECALL_LOG=$RUN_LOG")
  fi
  env_cmd+=(
    "PATH=$FAKE_BIN:$PATH"
    "HOME=$RUN_HOME"
    "SBA_AGENTIC_URL=http://fixture.invalid"
    "SBA_RECALL_TEST_RESPONSE_FILE=$RESPONSE_FILE"
    "SBA_RECALL_TEST_REQUEST_FILE=$REQUEST_CAPTURE"
  )
  if [[ -n "$RUN_MAX_CHARS" ]]; then
    env_cmd+=("SBA_RECALL_MAX_CHARS=$RUN_MAX_CHARS")
  fi
  if [[ -n "$RUN_CURL_EXIT" ]]; then
    env_cmd+=("SBA_RECALL_TEST_CURL_EXIT=$RUN_CURL_EXIT")
  fi

  set +e
  "${env_cmd[@]}" "$HOOK" "$@" >"$STDOUT_FILE" 2>"$STDERR_FILE" <<<"$payload"
  status=$?
  set -e
  RUN_STATUS="$status"
}

assert_exit_zero() {
  local label="$1"
  if [[ "$RUN_STATUS" -ne 0 ]]; then
    fail_case "$label" "expected exit 0, got $RUN_STATUS"
  fi
}

assert_stdout_empty() {
  local label="$1"
  if [[ -s "$STDOUT_FILE" ]]; then
    fail_case "$label" "expected empty stdout, got $(<"$STDOUT_FILE")"
  fi
}

assert_stderr_empty() {
  local label="$1"
  if [[ -s "$STDERR_FILE" ]]; then
    fail_case "$label" "expected empty stderr, got $(<"$STDERR_FILE")"
  fi
}

assert_log_field() {
  local label="$1"
  local field="$2"
  local expected="$3"
  local lines
  local actual

  if [[ ! -s "$LOG_FILE" ]]; then
    fail_case "$label" "expected a log line"
  fi
  lines="$(wc -l <"$LOG_FILE" | tr -d '[:space:]')"
  if [[ "$lines" != "1" ]]; then
    fail_case "$label" "expected 1 log line, got $lines"
  fi
  actual="$(awk -F '\t' -v field="$field" 'END { print $field }' "$LOG_FILE")"
  if [[ "$actual" != "$expected" ]]; then
    fail_case "$label" "expected log field $field=$expected, got $actual"
  fi
}

assert_log_outcome() {
  assert_log_field "$1" 3 "$2"
}

assert_no_request() {
  local label="$1"
  if [[ -e "$REQUEST_CAPTURE" ]]; then
    fail_case "$label" "curl should not have been called"
  fi
}

numbered_line_count() {
  awk '/^[0-9]+\. / { count++ } END { print count + 0 }' "$STDOUT_FILE"
}

assert_stdout_not_jsonish() {
  local label="$1"
  local output="$2"
  local first

  first="$(printf '%s' "$output" | sed 's/^[[:space:]]*//' | cut -c1)"
  if [[ "$first" == "[" || "$first" == "{" ]]; then
    fail_case "$label" "stdout starts with JSON-looking character $first"
  fi
}

case_bad_payload() {
  local label="non-JSON stdin"
  reset_run_env
  write_empty_response
  run_hook "not json at all"
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  assert_no_request "$label"
  assert_log_outcome "$label" "skipped:bad-payload"
  pass_case "$label"
}

case_no_cwd() {
  local label="payload without cwd"
  reset_run_env
  write_empty_response
  run_hook '{"session_id":"session-no-cwd"}'
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  assert_no_request "$label"
  assert_log_outcome "$label" "skipped:no-cwd"
  pass_case "$label"
}

case_compact() {
  local label="source compact"
  reset_run_env
  write_empty_response
  run_hook "{\"session_id\":\"session-compact\",\"cwd\":\"$VALID_CWD\",\"source\":\"compact\"}"
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  assert_no_request "$label"
  assert_log_outcome "$label" "skipped:compact"
  pass_case "$label"
}

case_subagent() {
  local label="agent_id present"
  reset_run_env
  write_empty_response
  run_hook "{\"session_id\":\"parent-session\",\"cwd\":\"$VALID_CWD\",\"agent_id\":\"agent-1\"}"
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  assert_no_request "$label"
  assert_log_outcome "$label" "skipped:subagent"
  pass_case "$label"
}

case_curl_exit() {
  local label="curl exit 7"
  reset_run_env
  write_empty_response
  RUN_CURL_EXIT=7
  run_hook "$VALID_PAYLOAD"
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  assert_log_outcome "$label" "unreachable"
  pass_case "$label"
}

case_empty_response() {
  local label="recorder empty items"
  reset_run_env
  write_empty_response
  run_hook "$VALID_PAYLOAD"
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  assert_log_outcome "$label" "empty"
  assert_log_field "$label" 6 "0"
  assert_log_field "$label" 7 "0"
  pass_case "$label"
}

case_ok_response() {
  local label="recorder returns five items"
  local output
  local count
  local expected_chars
  local request
  local encoded_cwd

  reset_run_env
  write_five_item_response
  run_hook "$VALID_PAYLOAD"
  assert_exit_zero "$label"
  assert_stderr_empty "$label"

  output="$(<"$STDOUT_FILE")"
  assert_stdout_not_jsonish "$label" "$output"
  case "$output" in
    "Black Box recall:"*) ;;
    *) fail_case "$label" "stdout did not start with recall header" ;;
  esac
  count="$(numbered_line_count)"
  if [[ "$count" != "3" ]]; then
    fail_case "$label" "expected 3 numbered items, got $count"
  fi
  grep -q '^1\. HANDOFF .*Newest handoff' "$STDOUT_FILE" || fail_case "$label" "newest item was not first"
  grep -q '^2\. DECISION .*Second decision' "$STDOUT_FILE" || fail_case "$label" "second newest item was not second"
  grep -q '^3\. DECISION .*Middle decision' "$STDOUT_FILE" || fail_case "$label" "third newest item was not third"
  if grep -q 'Older decision\|Oldest handoff' "$STDOUT_FILE"; then
    fail_case "$label" "items beyond the limit were printed"
  fi

  assert_log_outcome "$label" "ok"
  assert_log_field "$label" 2 "unknown"
  assert_log_field "$label" 6 "3"
  expected_chars="${#output}"
  assert_log_field "$label" 7 "$expected_chars"

  request="$(<"$REQUEST_CAPTURE")"
  encoded_cwd="$(jq -nr --arg v "$VALID_CWD" '$v | @uri')"
  [[ "$request" == *"withinHours=720"* ]] || fail_case "$label" "request did not include withinHours=720"
  [[ "$request" == *"limit=3"* ]] || fail_case "$label" "request did not include limit=3"
  [[ "$request" == *"scope=$encoded_cwd"* ]] || fail_case "$label" "request did not include encoded cwd"
  pass_case "$label"
}

case_client_arg() {
  local label="client codex argument"
  reset_run_env
  write_empty_response
  run_hook "$VALID_PAYLOAD" --client codex
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  assert_log_outcome "$label" "empty"
  assert_log_field "$label" 2 "codex"
  pass_case "$label"
}

case_truncation() {
  local label="max chars truncation"
  reset_run_env
  write_five_item_response
  RUN_MAX_CHARS=120
  run_hook "$VALID_PAYLOAD"
  assert_exit_zero "$label"
  assert_stderr_empty "$label"
  grep -q '… (+' "$STDOUT_FILE" || fail_case "$label" "expected trailing truncation marker"
  assert_log_outcome "$label" "ok"
  pass_case "$label"
}

case_log_off() {
  local label="logging disabled"
  reset_run_env
  write_empty_response
  RUN_LOG="off"
  run_hook "$VALID_PAYLOAD"
  assert_exit_zero "$label"
  assert_stdout_empty "$label"
  assert_stderr_empty "$label"
  if [[ -e "$LOG_FILE" ]]; then
    fail_case "$label" "log file should not have been written"
  fi
  pass_case "$label"
}

case_readonly_home() {
  local label="read-only HOME"
  local output
  local readonly_home="$TMP_DIR/readonly-home"

  reset_run_env
  write_five_item_response
  mkdir -p "$readonly_home"
  chmod 500 "$readonly_home"
  RUN_HOME="$readonly_home"
  RUN_LOG_UNSET=1
  run_hook "$VALID_PAYLOAD"
  chmod 700 "$readonly_home"
  assert_exit_zero "$label"
  assert_stderr_empty "$label"
  output="$(<"$STDOUT_FILE")"
  case "$output" in
    "Black Box recall:"*) ;;
    *) fail_case "$label" "expected recall block despite unwritable default log path" ;;
  esac
  pass_case "$label"
}

case_bad_payload
case_no_cwd
case_compact
case_subagent
case_curl_exit
case_empty_response
case_ok_response
case_client_arg
case_truncation
case_log_off
case_readonly_home
