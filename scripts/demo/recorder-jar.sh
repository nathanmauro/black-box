# shellcheck shell=bash
#
# recorder-jar.sh — find or build the jar for the demo launchers. Source it; do not run it.
#
# The caller provides PROJECT_DIR, say, ok, warn and die.
#
# target/ can also hold an older jar that an installed service runs from, so the jar is chosen by
# the project version, never by a glob. Builds never run `mvn clean`, because that would delete the
# older jar. A jar a process holds open is never rebuilt.

recorder_project_version() {
  grep -m1 -A1 '<artifactId>sba-agentic</artifactId>' "${PROJECT_DIR}/pom.xml" 2>/dev/null \
    | sed -n 's|.*<version>\(.*\)</version>.*|\1|p' \
    || true
}

# A jar that predates the context endpoints would boot fine and then return 404 on /api/decisions,
# so it must hold those classes and be newer than the source tree.
recorder_jar_is_current() {
  local jar="$1"

  [[ -f "$jar" ]] || return 1
  unzip -l "$jar" 2>/dev/null | grep "CaptureDecisionRequest" >/dev/null || return 1
  [[ -z "$(find "${PROJECT_DIR}/src" "${PROJECT_DIR}/pom.xml" -newer "$jar" -print -quit 2>/dev/null)" ]]
}

build_recorder_jar() {
  local jar="$1"

  command -v mvn >/dev/null 2>&1 \
    || die "mvn is required to build a demo jar. macOS: brew install maven. Debian/Ubuntu: apt install maven."
  if [[ -f "$jar" && -n "$(lsof -t -- "$jar" 2>/dev/null || true)" ]]; then
    die "A running process holds ${jar##*/}; build from an isolated checkout instead."
  fi
  say "   Building with: mvn -q -DskipTests package  (one-time, ~30-60s)"
  (cd "$PROJECT_DIR" && mvn -q -DskipTests package) || die "Maven build failed."
  [[ -f "$jar" ]] || die "The build did not produce ${jar##*/}."
}

# Sets RECORDER_JAR to a current jar, building one if needed.
ensure_recorder_jar() {
  local version

  version="$(recorder_project_version)"
  [[ -n "$version" ]] || die "Could not read the project version from pom.xml."
  RECORDER_JAR="${PROJECT_DIR}/target/sba-agentic-${version}.jar"

  if recorder_jar_is_current "$RECORDER_JAR"; then
    ok "Using existing jar: ${RECORDER_JAR##*/}"
    return 0
  fi
  if [[ -f "$RECORDER_JAR" ]]; then
    warn "${RECORDER_JAR##*/} is stale or missing the decision/recall endpoints - rebuilding."
  else
    warn "${RECORDER_JAR##*/} is not in target/ - building."
  fi
  build_recorder_jar "$RECORDER_JAR"
  ok "Built ${RECORDER_JAR##*/}."
}
