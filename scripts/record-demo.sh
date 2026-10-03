#!/usr/bin/env bash
#
# record-demo.sh - record the terminal demo GIF used by README assets.
#
# Runs the real scripts/demo.sh (decision -> handoff -> recall) against its own
# throwaway database, on its own port, with a private HOME and TMPDIR, then
# converts the recording with agg. Nothing here touches an installed recorder.
#
#   SBA_DEMO_PORT        demo listener port (default 18888; must be free)
#   SBA_DEMO_PACE        hold multiplier passed to demo.sh (default 1)
#   SBA_DEMO_GIF         output GIF (default docs/assets/demo.gif)
#   SBA_DEMO_KEEP_CAST   copy the raw .cast here for inspection (optional)

set -euo pipefail

# Headless shells often have no locale set; without UTF-8 the demo's box-drawing
# characters get mangled inside the recording itself.
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${SBA_DEMO_PORT:-18888}"
PACE="${SBA_DEMO_PACE:-1}"
GIF_FILE="${SBA_DEMO_GIF:-${PROJECT_DIR}/docs/assets/demo.gif}"
COLS=96
ROWS=34

if [[ -t 1 ]]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; CYAN=$'\033[36m'; GREEN=$'\033[32m'
  YELLOW=$'\033[33m'; RED=$'\033[31m'; RESET=$'\033[0m'
else
  BOLD=""; DIM=""; CYAN=""; GREEN=""; YELLOW=""; RED=""; RESET=""
fi

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==>%s %s%s%s\n' "$CYAN" "$RESET" "$BOLD" "$*" "$RESET"; }
ok()   { printf '%s  OK%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s  !!%s %s\n' "$YELLOW" "$RESET" "$*"; }
die()  { printf '%s  XX %s%s\n' "$RED" "$*" "$RESET" >&2; exit 1; }

missing=()
step "Preflight checks"
command -v asciinema >/dev/null 2>&1 || missing+=("asciinema"$'\n'"     macOS: brew install asciinema"$'\n'"     alternative: pipx install asciinema")
command -v agg >/dev/null 2>&1 || missing+=("agg"$'\n'"     macOS: brew install agg"$'\n'"     alternative: cargo install --git https://github.com/asciinema/agg")
command -v lsof >/dev/null 2>&1 || missing+=("lsof")
if (( ${#missing[@]} > 0 )); then
  for item in "${missing[@]}"; do
    printf '%s  XX%s %s\n' "$RED" "$RESET" "$item" >&2
  done
  exit 1
fi
[[ "$PORT" =~ ^[0-9]{1,5}$ ]] && (( 10#$PORT >= 1 && 10#$PORT <= 65535 )) \
  || die "SBA_DEMO_PORT must be an integer from 1 to 65535."
PORT=$((10#$PORT))
[[ "$PACE" =~ ^[0-9]+(\.[0-9]+)?$ ]] && awk -v p="$PACE" 'BEGIN { exit !(p <= 10) }' \
  || die "SBA_DEMO_PACE must be a number from 0 to 10."
if [[ -n "$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)" ]]; then
  die "Port ${PORT} is in use. Set SBA_DEMO_PORT to a free port; this script never stops a foreign listener."
fi
ok "asciinema, agg, and lsof present; port ${PORT} is free."

find_jar() {
  local candidate
  for candidate in "${PROJECT_DIR}"/target/*.jar; do
    [[ -e "$candidate" ]] || return 0
    case "$candidate" in *.original) continue ;; esac
    printf '%s' "$candidate"
    return 0
  done
}

jar_is_fresh_enough() {
  local jar="$1"
  [[ -f "$jar" ]] || return 1
  unzip -l "$jar" 2>/dev/null | grep "CaptureDecisionRequest" >/dev/null || return 1
  [[ -z "$(find "${PROJECT_DIR}/src" "${PROJECT_DIR}/pom.xml" -newer "$jar" -print -quit 2>/dev/null)" ]]
}

build_jar() {
  command -v mvn >/dev/null 2>&1 \
    || die "mvn is required to build a demo jar. macOS: brew install maven. Debian/Ubuntu: apt install maven."
  say "${DIM}   Building with: mvn -q clean -DskipTests package${RESET}"
  ( cd "$PROJECT_DIR" && mvn -q clean -DskipTests package ) || die "Maven build failed."
}

# Build with the caller's real HOME (Maven cache) before the recording switches to
# a private HOME, so demo.sh only ever reuses the fresh jar.
step "Locating the Black Box recorder jar"
JAR="$(find_jar)"
if [[ -n "$JAR" ]] && jar_is_fresh_enough "$JAR"; then
  ok "Using existing jar: ${JAR#"${PROJECT_DIR}"/}"
else
  [[ -n "$JAR" ]] && warn "Existing jar is stale or missing demo endpoints - rebuilding."
  [[ -z "$JAR" ]] && warn "No runnable jar in target/ - building."
  build_jar
fi

# A short private root keeps printed paths readable and free of workstation names.
umask 077
RUN_DIR="$(mktemp -d /tmp/bbx-rec.XXXXXX)"
RUN_DIR="$(cd "$RUN_DIR" && pwd -P)"
mkdir "${RUN_DIR}/home" "${RUN_DIR}/tmp"
CAST_FILE="${RUN_DIR}/demo.cast"

# Stop only the recorder this recording started: the PID demo.sh printed, and only
# while it is still the listener on our port.
cleanup_demo() {
  local status=$? pid
  trap - EXIT
  pid="$(sed -n -e 's/.*Recorder launched (PID \([0-9][0-9]*\)).*/\1/p' \
    -e 's/.*Recorder PID \([0-9][0-9]*\).*/\1/p' "$CAST_FILE" 2>/dev/null | sed -n '$p' || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    if [[ "$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null || true)" != "$pid" ]]; then
      warn "Cannot confirm ownership of live PID ${pid}; preserving ${RUN_DIR}." >&2
      exit 1
    fi
    kill "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    if kill -0 "$pid" 2>/dev/null; then
      warn "Recorder PID ${pid} has not stopped; preserving ${RUN_DIR}." >&2
      exit 1
    fi
  fi
  rm -rf "$RUN_DIR" || exit 1
  exit "$status"
}
trap cleanup_demo EXIT

step "Recording demo cast (port ${PORT}, private HOME/TMPDIR, pace ${PACE})"
cd "$PROJECT_DIR"
# The demo leaves the recorder running; this wrapper suppresses browser opening,
# records the real recall loop, and stops the printed PID after recording.
# The leading printf shows viewers the command that is actually run.
env HOME="${RUN_DIR}/home" TMPDIR="${RUN_DIR}/tmp" \
  SBA_DEMO_PORT="$PORT" SBA_DEMO_PACE="$PACE" SBA_DEMO_NO_OPEN=1 \
  asciinema rec --return --overwrite --quiet --window-size "${COLS}x${ROWS}" \
  -c "printf '\\033[1;32m\$\\033[0m ./scripts/demo.sh\\n'; sleep 1; ./scripts/demo.sh" \
  "$CAST_FILE"

grep -q "the loop just closed" "$CAST_FILE" || die "Recording does not contain the recall proof."
if grep -Fq "$HOME" "$CAST_FILE"; then
  die "Recording contains the real HOME path; refusing to publish it."
fi
[[ -z "${SBA_DEMO_KEEP_CAST:-}" ]] || cp "$CAST_FILE" "$SBA_DEMO_KEEP_CAST"

step "Converting cast to GIF"
# Holds come from SBA_DEMO_PACE inside the real run; the idle limit only trims the
# JVM startup wait, so it must stay above the longest scripted hold.
IDLE_LIMIT="$(awk -v p="$PACE" 'BEGIN { v = 5 * p + 1; printf "%.1f", (v < 2 ? 2 : v) }')"
agg --idle-time-limit "$IDLE_LIMIT" --last-frame-duration 6 --theme monokai --font-size 16 \
  "$CAST_FILE" "$GIF_FILE"
ok "Wrote ${GIF_FILE#"${PROJECT_DIR}"/}"
warn "Eyeball the GIF before committing."
