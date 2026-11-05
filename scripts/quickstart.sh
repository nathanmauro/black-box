#!/usr/bin/env bash
#
# quickstart.sh - cold-clone path to the Black Box demo.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -t 1 ]]; then
  BOLD=$'\033[1m'; CYAN=$'\033[36m'; GREEN=$'\033[32m'
  YELLOW=$'\033[33m'; RED=$'\033[31m'; RESET=$'\033[0m'
else
  BOLD=""; CYAN=""; GREEN=""; YELLOW=""; RED=""; RESET=""
fi

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==>%s %s%s%s\n' "$CYAN" "$RESET" "$BOLD" "$*" "$RESET"; }
ok()   { printf '%s  OK%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s  !!%s %s\n' "$YELLOW" "$RESET" "$*"; }
die()  { printf '%s  XX %s%s\n' "$RED" "$*" "$RESET" >&2; exit 1; }

missing=()
need() {
  missing+=("$1"$'\n'"     macOS: $2"$'\n'"     Debian/Ubuntu: $3")
}

java_major() {
  if [[ "$1" =~ ^1\.([0-9]+) ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  elif [[ "$1" =~ ^([0-9]+) ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
  fi
}

maven_39_or_newer() {
  [[ "$1" =~ ^([0-9]+)\.([0-9]+) ]] || return 0
  (( BASH_REMATCH[1] > 3 || (BASH_REMATCH[1] == 3 && BASH_REMATCH[2] >= 9) ))
}

step "Preflight checks"
if ! command -v java >/dev/null 2>&1; then
  need "java 21+" "brew install --cask temurin" "apt install openjdk-21-jdk, or install Temurin 21"
else
  java_line="$(java -version 2>&1 | sed -n '1p')"
  java_version="$(printf '%s\n' "$java_line" | sed -n 's/.*version "\([^"]*\)".*/\1/p')"
  major="$(java_major "$java_version")"
  if [[ -z "$major" || "$major" -lt 21 ]]; then
    need "java 21+ (found: ${java_line})" "brew install --cask temurin" "apt install openjdk-21-jdk, or install Temurin 21"
  fi
fi

if ! command -v mvn >/dev/null 2>&1; then
  need "mvn 3.9+" "brew install maven" "apt install maven"
else
  mvn_version="$(mvn -v 2>/dev/null | sed -n 's/^Apache Maven \([0-9][0-9.]*\).*/\1/p' | sed -n '1p')"
  if [[ -n "$mvn_version" ]] && ! maven_39_or_newer "$mvn_version"; then
    need "mvn 3.9+ (found: ${mvn_version})" "brew install maven" "install Maven 3.9+ from https://maven.apache.org/"
  fi
fi

command -v jq >/dev/null 2>&1 \
  || need "jq" "brew install jq" "apt install jq"
command -v lsof >/dev/null 2>&1 \
  || need "lsof" "included with macOS" "apt install lsof"

if (( ${#missing[@]} > 0 )); then
  say ""
  for item in "${missing[@]}"; do
    printf '%s  XX%s %s\n' "$RED" "$RESET" "$item" >&2
  done
  exit 1
fi
ok "java, mvn, jq, and lsof present."

step "Locating the Black Box recorder jar"
# shellcheck source=scripts/demo/recorder-jar.sh
source "${PROJECT_DIR}/scripts/demo/recorder-jar.sh"
ensure_recorder_jar

step "Starting the demo"
cd "$PROJECT_DIR"
exec ./scripts/demo.sh "$@"
