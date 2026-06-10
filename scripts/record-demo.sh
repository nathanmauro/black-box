#!/usr/bin/env bash
#
# record-demo.sh - record the terminal demo GIF used by README assets.

set -euo pipefail

# Headless shells often have no locale set; without UTF-8 the demo's box-drawing
# characters get mangled inside the recording itself.
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CAST_FILE="/tmp/black-box-demo.cast"
GIF_FILE="${PROJECT_DIR}/docs/assets/demo.gif"

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
if (( ${#missing[@]} > 0 )); then
  for item in "${missing[@]}"; do
    printf '%s  XX%s %s\n' "$RED" "$RESET" "$item" >&2
  done
  exit 1
fi
ok "asciinema and agg present."

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
  unzip -l "$jar" 2>/dev/null | grep -q "CaptureDecisionRequest" || return 1
  [[ -z "$(find "${PROJECT_DIR}/src" "${PROJECT_DIR}/pom.xml" -newer "$jar" -print -quit 2>/dev/null)" ]]
}

build_jar() {
  command -v mvn >/dev/null 2>&1 \
    || die "mvn is required to build a demo jar. macOS: brew install maven. Debian/Ubuntu: apt install maven."
  say "${DIM}   Building with: mvn -q -DskipTests package${RESET}"
  ( cd "$PROJECT_DIR" && mvn -q -DskipTests package ) || die "Maven build failed."
}

step "Locating the Black Box recorder jar"
JAR="$(find_jar)"
if [[ -n "$JAR" ]] && jar_is_fresh_enough "$JAR"; then
  ok "Using existing jar: ${JAR}"
else
  [[ -n "$JAR" ]] && warn "Existing jar is stale or missing demo endpoints - rebuilding."
  [[ -z "$JAR" ]] && warn "No runnable jar in target/ - building."
  build_jar
fi

cleanup_demo() {
  local pid
  pid="$(sed -n 's/.*Recorder PID \([0-9][0-9]*\).*/\1/p' "$CAST_FILE" 2>/dev/null | sed -n '$p' || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
  fi
}
trap cleanup_demo EXIT

step "Recording demo cast"
cd "$PROJECT_DIR"
rm -f "$CAST_FILE"
# demo.sh has no no-open flag. It exits after printing the recall loop, then
# leaves the recorder running; this wrapper records the real demo and kills the
# printed PID from the cast after recording.
asciinema rec --overwrite --quiet --cols 100 --rows 30 \
  -c './scripts/demo.sh' \
  "$CAST_FILE"

cleanup_demo
trap - EXIT

step "Converting cast to GIF"
agg --idle-time-limit 1.5 --theme monokai --font-size 16 "$CAST_FILE" "$GIF_FILE"
ok "Wrote ${GIF_FILE}"
warn "Eyeball the GIF before committing."
