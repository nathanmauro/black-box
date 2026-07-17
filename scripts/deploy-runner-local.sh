#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OS_NAME="$(uname -s)"

if [[ "$OS_NAME" != "Darwin" ]]; then
  echo "deploy-runner-local.sh manages a macOS launchd service. No Linux systemd unit is provided for the runner yet." >&2
  exit 1
fi

LABEL="${SBA_RUNNER_LAUNCHD_LABEL:-com.nathan.blackbox-runner}"
DOMAIN="${SBA_RUNNER_LAUNCHD_DOMAIN:-gui/$(id -u)}"
PLIST="${SBA_RUNNER_LAUNCHD_PLIST:-$HOME/Library/LaunchAgents/${LABEL}.plist}"
JAR="${SBA_RUNNER_JAR:-$REPO_ROOT/target/sba-agentic-0.1.0.jar}"
RUNNER_LOG_PATH="${SBA_RUNNER_LOG_PATH:-$HOME/.blackbox/runner.log}"
RUNNER_CONFIG_PATH="${SBA_RUNNER_CONFIG:-$HOME/.blackbox/runner.json}"
BASE_URL="${SBA_BASE_URL:-http://localhost:8766}"
TEMPLATE="$SCRIPT_DIR/blackbox-runner.plist.template"
rendered_plist=""
restore_needed=0
log_lines_before_start=0

for arg in "$@"; do
  case "$arg" in
    -h|--help)
      cat <<USAGE
Usage: scripts/deploy-runner-local.sh

Deploy the already-built runner jar as a local launchd service and wait for process and log activity.

Environment overrides:
  SBA_RUNNER_LAUNCHD_LABEL   default: com.nathan.blackbox-runner
  SBA_RUNNER_LAUNCHD_DOMAIN  default: gui/\$(id -u)
  SBA_RUNNER_LAUNCHD_PLIST   default: \$HOME/Library/LaunchAgents/\$SBA_RUNNER_LAUNCHD_LABEL.plist
  SBA_RUNNER_JAR             default: target/sba-agentic-0.1.0.jar
  SBA_RUNNER_LOG_PATH        default: \$HOME/.blackbox/runner.log
  SBA_RUNNER_CONFIG          default: \$HOME/.blackbox/runner.json
  SBA_BASE_URL               default: http://localhost:8766
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 64
      ;;
  esac
done

current_pid() {
  launchctl print "$DOMAIN/$LABEL" 2>/dev/null |
    awk '/pid = [0-9]+/ { print $3; exit }'
}

describe_state() {
  local pid
  pid="$(current_pid || true)"
  if [[ -n "$pid" ]]; then
    echo "launchd pid: $pid ($DOMAIN/$LABEL)"
  else
    echo "No launchd pid found for $DOMAIN/$LABEL"
  fi
  echo "Runner log tail: $RUNNER_LOG_PATH"
  if [[ -f "$RUNNER_LOG_PATH" ]]; then
    tail -n 5 "$RUNNER_LOG_PATH"
  else
    echo "Runner log not found yet: $RUNNER_LOG_PATH"
  fi
}

escape_sed_replacement() {
  printf '%s' "$1" | sed 's/[\\&|]/\\&/g'
}

render_plist() {
  local label java_bin jar log_path base_url config_path
  label="$(escape_sed_replacement "$LABEL")"
  java_bin="$(escape_sed_replacement "$JAVA_BIN")"
  jar="$(escape_sed_replacement "$JAR")"
  log_path="$(escape_sed_replacement "$RUNNER_LOG_PATH")"
  base_url="$(escape_sed_replacement "$BASE_URL")"
  config_path="$(escape_sed_replacement "$RUNNER_CONFIG_PATH")"
  sed \
    -e "s|@LABEL@|$label|g" \
    -e "s|@JAVA_BIN@|$java_bin|g" \
    -e "s|@JAR_PATH@|$jar|g" \
    -e "s|@LOG_DIR@/runner.log|$log_path|g" \
    -e "s|@BASE_URL@|$base_url|g" \
    -e "s|@RUNNER_CONFIG_PATH@|$config_path|g" \
    "$TEMPLATE" >"$rendered_plist"
}

stop_service_for_deploy() {
  echo "Stopping launchd service before deploying runner: $DOMAIN/$LABEL"
  launchctl bootout "$DOMAIN" "$PLIST" 2>/dev/null || launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  restore_needed=1
}

restore_service() {
  echo "Starting launchd service: $DOMAIN/$LABEL"
  launchctl bootstrap "$DOMAIN" "$PLIST" 2>/dev/null || true
  if [[ -f "$RUNNER_LOG_PATH" ]]; then
    log_lines_before_start="$(wc -l <"$RUNNER_LOG_PATH")"
  else
    log_lines_before_start=0
  fi
  launchctl kickstart -k "$DOMAIN/$LABEL"
  restore_needed=0
}

cleanup() {
  if [[ "$restore_needed" -eq 1 ]]; then
    restore_service || true
  fi
  if [[ -n "$rendered_plist" ]]; then
    rm -f "$rendered_plist"
  fi
}

trap cleanup EXIT

if [[ ! -f "$JAR" ]]; then
  echo "Runner jar not found at $JAR; run scripts/deploy-local.sh first to build it." >&2
  exit 1
fi

JAVA_BIN="$(command -v java || true)"
if [[ -z "$JAVA_BIN" ]]; then
  echo "Java not found on PATH; install Java 21 or set PATH before deploying the runner." >&2
  exit 1
fi

mkdir -p "$(dirname "$PLIST")" "$(dirname "$RUNNER_LOG_PATH")"
rendered_plist="$(mktemp)"
render_plist

echo "Before deploy:"
describe_state

stop_service_for_deploy
mv "$rendered_plist" "$PLIST"
rendered_plist=""
restore_service

for attempt in $(seq 1 40); do
  pid="$(current_pid || true)"
  log_lines=0
  if [[ -f "$RUNNER_LOG_PATH" ]]; then
    log_lines="$(wc -l <"$RUNNER_LOG_PATH")"
  fi
  if [[ -n "$pid" && "$log_lines" -gt "$log_lines_before_start" ]]; then
    echo "After deploy:"
    describe_state
    exit 0
  fi
  sleep 1
done

echo "Timed out waiting for a fresh line in $RUNNER_LOG_PATH and a running launchd process" >&2
echo "Last service state:" >&2
launchctl print "$DOMAIN/$LABEL" >&2 || true
exit 1
