#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

FAKE_BIN="$TMP_DIR/bin"
LOG="$TMP_DIR/calls.log"
HOME_DIR="$TMP_DIR/home"
PLIST="$HOME_DIR/Library/LaunchAgents/com.test.blackbox-runner.plist"
RUNNER_LOG="$HOME_DIR/.blackbox/runner.log"
RUNNER_CONFIG="$HOME_DIR/.blackbox/runner.json"
JAR="$TMP_DIR/app.jar"
mkdir -p "$FAKE_BIN" "$HOME_DIR/Library/LaunchAgents" "$HOME_DIR/.blackbox"
touch "$JAR" "$LOG"

cat >"$FAKE_BIN/uname" <<'SH'
#!/usr/bin/env bash
echo Darwin
SH

cat >"$FAKE_BIN/id" <<'SH'
#!/usr/bin/env bash
if [[ "${1:-}" == "-u" ]]; then
  echo 501
else
  /usr/bin/id "$@"
fi
SH

cat >"$FAKE_BIN/java" <<'SH'
#!/usr/bin/env bash
echo "java $*" >>"$BB_RUNNER_DEPLOY_TEST_LOG"
SH

cat >"$FAKE_BIN/mvn" <<'SH'
#!/usr/bin/env bash
echo "mvn $*" >>"$BB_RUNNER_DEPLOY_TEST_LOG"
SH

cat >"$FAKE_BIN/launchctl" <<'SH'
#!/usr/bin/env bash
echo "launchctl $*" >>"$BB_RUNNER_DEPLOY_TEST_LOG"
if [[ "${1:-}" == "print" ]]; then
  echo "gui/501/com.test.blackbox-runner = {"
  echo "    pid = 222"
  echo "}"
elif [[ "${1:-}" == "kickstart" && "${BB_RUNNER_DEPLOY_APPEND_LOG:-0}" == "1" ]]; then
  printf 'Black Box runner orchestrator session: fixture-session\n' >>"$SBA_RUNNER_LOG_PATH"
fi
SH

cat >"$FAKE_BIN/sleep" <<'SH'
#!/usr/bin/env bash
echo "sleep $*" >>"$BB_RUNNER_DEPLOY_TEST_LOG"
SH

chmod +x "$FAKE_BIN"/*

run_deploy() {
  local append_log="$1"
  local jar="$2"
  local output="$3"
  PATH="$FAKE_BIN:/usr/bin:/bin:/usr/sbin:/sbin" \
  HOME="$HOME_DIR" \
  BB_RUNNER_DEPLOY_TEST_LOG="$LOG" \
  BB_RUNNER_DEPLOY_APPEND_LOG="$append_log" \
  SBA_RUNNER_LAUNCHD_LABEL="com.test.blackbox-runner" \
  SBA_RUNNER_LAUNCHD_DOMAIN="gui/501" \
  SBA_RUNNER_LAUNCHD_PLIST="$PLIST" \
  SBA_RUNNER_JAR="$jar" \
  SBA_RUNNER_LOG_PATH="$RUNNER_LOG" \
  SBA_RUNNER_CONFIG="$RUNNER_CONFIG" \
  SBA_BASE_URL="http://localhost:9999" \
    "$REPO_ROOT/scripts/deploy-runner-local.sh" >"$output" 2>&1
}

line_no() {
  local pattern="$1"
  local match
  match="$(grep -n "$pattern" "$LOG" | head -n 1 | cut -d: -f1 || true)"
  if [[ -z "$match" ]]; then
    echo "Missing expected call: $pattern" >&2
    echo "--- calls ---" >&2
    cat "$LOG" >&2
    exit 1
  fi
  echo "$match"
}

printf 'Existing runner log line\n' >"$RUNNER_LOG"
run_deploy 1 "$JAR" "$TMP_DIR/success.out"

bootout_line="$(line_no '^launchctl bootout ')"
bootstrap_line="$(line_no '^launchctl bootstrap ')"
kickstart_line="$(line_no '^launchctl kickstart -k ')"

if (( bootout_line >= bootstrap_line )); then
  echo "Expected launchd bootout before bootstrap" >&2
  cat "$LOG" >&2
  exit 1
fi

if (( bootstrap_line >= kickstart_line )); then
  echo "Expected launchd bootstrap before kickstart" >&2
  cat "$LOG" >&2
  exit 1
fi

if grep -q '^mvn ' "$LOG"; then
  echo "Runner deploy must not build the jar" >&2
  cat "$LOG" >&2
  exit 1
fi

grep -Fq "<string>$FAKE_BIN/java</string>" "$PLIST"
grep -Fq "<string>$JAR</string>" "$PLIST"
grep -Fq '<string>runner</string>' "$PLIST"
grep -Fq '<key>SBA_BASE_URL</key>' "$PLIST"
grep -Fq '<string>http://localhost:9999</string>' "$PLIST"
grep -Fq "<string>$RUNNER_CONFIG</string>" "$PLIST"
if [[ "$(grep -Fc "<string>$RUNNER_LOG</string>" "$PLIST")" -ne 2 ]]; then
  echo "Expected stdout and stderr to share the runner log" >&2
  cat "$PLIST" >&2
  exit 1
fi
if grep -Eq '@[A-Z_]+@' "$PLIST"; then
  echo "Expected every runner plist placeholder to be rendered" >&2
  cat "$PLIST" >&2
  exit 1
fi

: >"$LOG"
printf 'Existing runner log line\n' >"$RUNNER_LOG"
if run_deploy 0 "$JAR" "$TMP_DIR/health-failure.out"; then
  echo "Expected deploy to fail without fresh runner log activity" >&2
  exit 1
fi
if ! grep -Fq "Timed out waiting for a fresh line in $RUNNER_LOG" "$TMP_DIR/health-failure.out"; then
  echo "Expected a clear runner log timeout" >&2
  cat "$TMP_DIR/health-failure.out" >&2
  exit 1
fi

: >"$LOG"
if run_deploy 1 "$TMP_DIR/missing.jar" "$TMP_DIR/jar-missing.out"; then
  echo "Expected deploy to refuse a missing runner jar" >&2
  exit 1
fi
if grep -q '^launchctl ' "$LOG"; then
  echo "Expected missing jar refusal before launchctl" >&2
  cat "$LOG" >&2
  exit 1
fi
if ! grep -Fq "Runner jar not found at $TMP_DIR/missing.jar" "$TMP_DIR/jar-missing.out"; then
  echo "Expected a clear missing jar message" >&2
  cat "$TMP_DIR/jar-missing.out" >&2
  exit 1
fi

echo "deploy-runner-local launchd fixtures ok"
