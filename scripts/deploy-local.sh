#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

LABEL="${SBA_LAUNCHD_LABEL:-com.nathan.sba-agentic}"
DOMAIN="${SBA_LAUNCHD_DOMAIN:-gui/$(id -u)}"
PORT="${SBA_PORT:-8766}"
STATUS_URL="${SBA_STATUS_URL:-http://localhost:${PORT}/api/status}"
JAR="${SBA_JAR_PATH:-$REPO_ROOT/target/sba-agentic-0.1.0-SNAPSHOT.jar}"

RUN_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --with-tests)
      RUN_TESTS=1
      ;;
    -h|--help)
      cat <<USAGE
Usage: scripts/deploy-local.sh [--with-tests]

Build the packaged jar, restart the local launchd service, and wait for /api/status.

Environment overrides:
  SBA_LAUNCHD_LABEL   default: com.nathan.sba-agentic
  SBA_LAUNCHD_DOMAIN  default: gui/\$(id -u)
  SBA_PORT            default: 8766
  SBA_STATUS_URL      default: http://localhost:\$SBA_PORT/api/status
  SBA_JAR_PATH        default: target/sba-agentic-0.1.0-SNAPSHOT.jar
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
  ps ax -o pid=,command= | awk -v jar="$JAR" 'index($0, "java -jar " jar) { print $1; exit }'
}

describe_state() {
  local pid
  pid="$(current_pid || true)"
  if [[ -n "$pid" ]]; then
    ps -o pid,lstart,command -p "$pid"
  else
    echo "No java -jar process found for $JAR"
  fi
  if [[ -f "$JAR" ]]; then
    stat -f 'Jar modified: %Sm %N' -t '%Y-%m-%d %H:%M:%S %z' "$JAR"
  else
    echo "Jar not found yet: $JAR"
  fi
}

cd "$REPO_ROOT"

echo "Before deploy:"
describe_state

if [[ "$RUN_TESTS" -eq 1 ]]; then
  mvn -q package
else
  mvn -q -DskipTests package
fi

echo "Restarting launchd service: $DOMAIN/$LABEL"
launchctl kickstart -k "$DOMAIN/$LABEL"

status_file="$(mktemp)"
trap 'rm -f "$status_file"' EXIT

for attempt in $(seq 1 40); do
  if curl -fsS "$STATUS_URL" >"$status_file"; then
    echo "After deploy:"
    describe_state
    echo "Status OK: $STATUS_URL"
    cat "$status_file"
    echo
    exit 0
  fi
  sleep 1
done

echo "Timed out waiting for $STATUS_URL" >&2
echo "Last service state:" >&2
launchctl print "$DOMAIN/$LABEL" >&2 || true
exit 1
