#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

FAKE_BIN="$TMP_DIR/bin"
LOG="$TMP_DIR/calls.log"
HOME_DIR="$TMP_DIR/home"
mkdir -p "$FAKE_BIN" "$HOME_DIR/Library/LaunchAgents"
touch "$HOME_DIR/Library/LaunchAgents/com.test.blackbox.plist" "$TMP_DIR/app.jar" "$LOG"

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

cat >"$FAKE_BIN/ps" <<'SH'
#!/usr/bin/env bash
echo "ps $*" >>"$BB_DEPLOY_TEST_LOG"
if [[ "$*" == *"ax"* ]]; then
  echo "111 /usr/bin/java -jar $SBA_JAR_PATH"
elif [[ "$*" == *"-p"* ]]; then
  echo "  PID STARTED                      COMMAND"
  echo "111 Tue Jun 23 12:00:00 2026      /usr/bin/java -jar $SBA_JAR_PATH"
fi
SH

cat >"$FAKE_BIN/stat" <<'SH'
#!/usr/bin/env bash
last="${@: -1}"
echo "Jar modified: 2026-06-23 12:00:00 -0400 $last"
SH

cat >"$FAKE_BIN/mvn" <<'SH'
#!/usr/bin/env bash
echo "mvn $*" >>"$BB_DEPLOY_TEST_LOG"
SH

cat >"$FAKE_BIN/launchctl" <<'SH'
#!/usr/bin/env bash
echo "launchctl $*" >>"$BB_DEPLOY_TEST_LOG"
SH

cat >"$FAKE_BIN/curl" <<'SH'
#!/usr/bin/env bash
echo "curl $*" >>"$BB_DEPLOY_TEST_LOG"
printf '{"ok":true}\n'
SH

cat >"$FAKE_BIN/sleep" <<'SH'
#!/usr/bin/env bash
echo "sleep $*" >>"$BB_DEPLOY_TEST_LOG"
SH

chmod +x "$FAKE_BIN"/*

PATH="$FAKE_BIN:/usr/bin:/bin:/usr/sbin:/sbin" \
HOME="$HOME_DIR" \
BB_DEPLOY_TEST_LOG="$LOG" \
SBA_LAUNCHD_LABEL="com.test.blackbox" \
SBA_LAUNCHD_DOMAIN="gui/501" \
SBA_JAR_PATH="$TMP_DIR/app.jar" \
SBA_STATUS_URL="http://localhost:9999/api/status" \
  "$REPO_ROOT/scripts/deploy-local.sh" >"$TMP_DIR/stdout"

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

bootout_line="$(line_no '^launchctl bootout ')"
mvn_line="$(line_no '^mvn ')"
bootstrap_line="$(line_no '^launchctl bootstrap ')"
kickstart_line="$(line_no '^launchctl kickstart -k ')"
curl_line="$(line_no '^curl ')"

if (( bootout_line >= mvn_line )); then
  echo "Expected launchd bootout before Maven package" >&2
  cat "$LOG" >&2
  exit 1
fi

if (( mvn_line >= bootstrap_line )); then
  echo "Expected launchd bootstrap after Maven package" >&2
  cat "$LOG" >&2
  exit 1
fi

if (( bootstrap_line > kickstart_line )); then
  echo "Expected launchd bootstrap before kickstart" >&2
  cat "$LOG" >&2
  exit 1
fi

if (( kickstart_line >= curl_line )); then
  echo "Expected status polling after launchd restart" >&2
  cat "$LOG" >&2
  exit 1
fi

echo "deploy-local launchd ordering ok"
