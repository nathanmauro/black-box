#!/usr/bin/env bash
#
# demo.sh — one-command, reproducible showcase for BLACK BOX.
#
# Black Box is a local "flight recorder for machine minds": AI coding agents
# (Claude Code, Codex) WRITE structured intent — decisions and handoffs — into
# it, then QUERY each other's prior reasoning back out at runtime via MCP. No
# cloud, no file mutation. This script proves the signature moment end to end:
#
#   A Codex session decides an auth strategy and hands off an open loop today.
#   A FRESH Claude session arrives and recalls that exact decision — rationale,
#   confidence, and the open loop "revoke-on-logout" — through a third thing
#   that remembers for both of them.
#
# What this script does, in order:
#   1. Spins up a DEDICATED demo database (never touches your real history).
#   2. Starts the recorder itself, with local AI + Elasticsearch disabled.
#   3. Seeds a believable cross-agent story over the REST API.
#   4. Calls /api/recall and pretty-prints the structured result — the proof.
#   5. Opens the UI and tells you how to explore, then how to stop the demo.
#
# The recorder is LEFT RUNNING on normal exit so you can explore the UI. The PID
# is printed; you stop it yourself. The background app is only killed if startup
# fails or you Ctrl-C before the demo finishes.

set -euo pipefail

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #
# Derive the repo root from this script's own location (scripts/demo.sh -> repo root),
# so the demo is portable to wherever the repo is checked out.
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DB="/tmp/black-box-demo.db"
DATASOURCE_URL="jdbc:sqlite:${DEMO_DB}"
# Override with SBA_DEMO_PORT when a real recorder already owns 8766.
PORT="${SBA_DEMO_PORT:-8766}"
BASE_URL="http://localhost:${PORT}"
LOG_FILE="/tmp/black-box-demo.log"
STARTUP_TIMEOUT_SECONDS=90

# Story constants — the two agents and the repo they share.
REPO="/tmp/acme-auth"
CODEX_SESSION="codex-auth-2026-05-28"
CLAUDE_SESSION="claude-auth-2026-05-29"

# Populated once the app is up; used by the startup-failure trap.
APP_PID=""
# Flag flipped to 1 once the demo has finished successfully. While it is 0, the
# trap treats any exit (error or interrupt) as a failed startup and cleans up.
DEMO_SUCCEEDED=0

# --------------------------------------------------------------------------- #
# Pretty output helpers
# --------------------------------------------------------------------------- #
if [[ -t 1 ]]; then
  BOLD=$'\033[1m'; DIM=$'\033[2m'; CYAN=$'\033[36m'; GREEN=$'\033[32m'
  YELLOW=$'\033[33m'; RED=$'\033[31m'; RESET=$'\033[0m'
else
  BOLD=""; DIM=""; CYAN=""; GREEN=""; YELLOW=""; RED=""; RESET=""
fi

say()   { printf '%s\n' "$*"; }
step()  { printf '\n%s==>%s %s%s%s\n' "$CYAN" "$RESET" "$BOLD" "$*" "$RESET"; }
ok()    { printf '%s  ✓%s %s\n' "$GREEN" "$RESET" "$*"; }
warn()  { printf '%s  !%s %s\n' "$YELLOW" "$RESET" "$*"; }
die()   { printf '%s  ✗ %s%s\n' "$RED" "$*" "$RESET" >&2; exit 1; }

banner() {
  # Print a framed banner so the proof is impossible to miss in the scrollback.
  local line
  printf '\n%s' "$BOLD"
  printf '┏'; printf '━%.0s' {1..71}; printf '\n'
  for line in "$@"; do
    printf '┃ %s\n' "$line"
  done
  printf '┗'; printf '━%.0s' {1..71}; printf '\n'
  printf '%s\n' "$RESET"
}

# --------------------------------------------------------------------------- #
# Cleanup trap
# --------------------------------------------------------------------------- #
# The recorder is meant to keep running so the user can explore the UI. So we
# only tear it down if we exit BEFORE the demo finishes (startup failure or an
# interrupt mid-seed). Once DEMO_SUCCEEDED=1, this trap intentionally does
# nothing and leaves the app alive.
cleanup_on_failure() {
  local exit_code=$?
  if [[ "$DEMO_SUCCEEDED" -eq 1 ]]; then
    return 0
  fi
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    warn "Startup/seed did not complete — shutting down the recorder (PID ${APP_PID})."
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ "$exit_code" -ne 0 ]]; then
    say ""
    warn "Demo aborted. See ${LOG_FILE} for the recorder log."
  fi
}
trap cleanup_on_failure EXIT INT TERM

# --------------------------------------------------------------------------- #
# Small REST helpers (curl + jq)
# --------------------------------------------------------------------------- #
# POST a JSON body to an endpoint; print the response body. Fails hard on a
# non-2xx HTTP status so the seed can't silently half-complete.
post_json() {
  local path="$1" body="$2"
  local response http_code
  response="$(curl -sS -w $'\n%{http_code}' \
    -H 'Content-Type: application/json' \
    -X POST "${BASE_URL}${path}" \
    -d "$body")"
  http_code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ "$http_code" != 2* ]]; then
    die "POST ${path} failed (HTTP ${http_code}): ${body}"
  fi
  printf '%s' "$body"
}

# --------------------------------------------------------------------------- #
# 0. Preflight — required tooling
# --------------------------------------------------------------------------- #
step "Preflight checks"
command -v curl >/dev/null 2>&1 || die "curl is required but not found on PATH."
command -v jq   >/dev/null 2>&1 || die "jq is required but not found on PATH. Install with: brew install jq"
ok "curl and jq present."

# The demo OWNS the server it starts — it must not attach to, talk to, or kill a
# recorder someone else is already running on this port (e.g. your real Black Box
# instance on the real DB). If the port is taken, stop and say so clearly rather
# than seeding a foreign server (which could also be an older build missing the
# /api/decisions endpoint).
port_listener_pids() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN -t 2>/dev/null || true
  fi
}
if existing_pids="$(port_listener_pids)" && [[ -n "$existing_pids" ]]; then
  warn "Something is already listening on port ${PORT}:"
  # shellcheck disable=SC2086
  ps -o pid,command -p $existing_pids 2>/dev/null | sed 's/^/      /' || true
  die "Port ${PORT} is in use. Stop the other process first (it is NOT mine to kill), or set SBA_PORT for it, then re-run this demo."
fi
ok "Port ${PORT} is free — the demo will own its own recorder."

# --------------------------------------------------------------------------- #
# 1. Dedicated, clean demo database
# --------------------------------------------------------------------------- #
step "Preparing a clean, dedicated demo database"
# Remove the demo DB (and any SQLite WAL/SHM siblings) so every run starts fresh
# and the demo never touches the user's real ${PROJECT_DIR}/sba-agentic.db.
rm -f "${DEMO_DB}" "${DEMO_DB}-wal" "${DEMO_DB}-shm"
ok "Using a throwaway DB at ${DEMO_DB} (deleted at the start of every run)."

# --------------------------------------------------------------------------- #
# 2. Locate or build the jar, then launch the recorder
# --------------------------------------------------------------------------- #
step "Locating the Black Box recorder jar"

# Pick the first runnable jar in target/, ignoring the non-runnable *.jar.original
# artifact. Echoes the path (empty if none found).
find_jar() {
  local candidate
  if compgen -G "${PROJECT_DIR}/target/*.jar" >/dev/null; then
    for candidate in "${PROJECT_DIR}"/target/*.jar; do
      case "$candidate" in
        *.original) continue ;;
      esac
      printf '%s' "$candidate"
      return 0
    done
  fi
  return 0
}

# Is this jar usable for the DEMO specifically? It must (a) contain the
# decision/handoff/recall classes — the whole point of Black Box — and (b) not be
# older than the source tree. A jar that predates the context endpoints would
# boot fine but return 404 on /api/decisions, silently breaking the demo.
jar_is_fresh_enough() {
  local jar="$1"
  [[ -f "$jar" ]] || return 1
  # (a) Capability: the recall/decision feature classes must be inside the jar.
  if ! unzip -l "$jar" 2>/dev/null | grep -q "CaptureDecisionRequest"; then
    return 1
  fi
  # (b) Freshness: rebuild if any source/build file is newer than the jar.
  if [[ -n "$(find "${PROJECT_DIR}/src" "${PROJECT_DIR}/pom.xml" -newer "$jar" -print -quit 2>/dev/null)" ]]; then
    return 1
  fi
  return 0
}

build_jar() {
  command -v mvn >/dev/null 2>&1 || die "mvn is required to build but was not found on PATH."
  say "${DIM}   Building with: mvn -q -DskipTests package  (one-time, ~30-60s)${RESET}"
  ( cd "${PROJECT_DIR}" && mvn -q -DskipTests package ) || die "Maven build failed."
}

# Prefer an existing jar; only build when none exists, or the one we have is
# missing the demo's endpoints / is older than the source.
JAR="$(find_jar)"
if [[ -n "$JAR" ]] && jar_is_fresh_enough "$JAR"; then
  ok "Using existing jar: ${JAR}"
elif [[ -n "$JAR" ]]; then
  warn "Existing jar is stale or missing the decision/recall endpoints — rebuilding."
  build_jar
  JAR="$(find_jar)"
else
  warn "No runnable jar in target/ — building."
  build_jar
  JAR="$(find_jar)"
fi
[[ -n "$JAR" && -f "$JAR" ]] || die "No runnable jar available in target/ after build."
ok "Jar: ${JAR}"

step "Starting the recorder on the demo database (local AI OFF, Elasticsearch OFF)"
# Launch in the background on the dedicated DB. Local AI and Elasticsearch are
# disabled so the demo needs no GPU and no external services — it is fully local
# and self-contained. stdout/stderr go to the log file for troubleshooting.
SBA_DATASOURCE_URL="${DATASOURCE_URL}" \
SBA_LOCAL_AI_ENABLED=false \
SBA_ELASTICSEARCH_ENABLED=false \
SBA_PORT="${PORT}" \
  java -jar "${JAR}" >"${LOG_FILE}" 2>&1 &
APP_PID=$!
ok "Recorder launched (PID ${APP_PID}); logs at ${LOG_FILE}."

# --------------------------------------------------------------------------- #
# Poll /api/status until the recorder is healthy
# --------------------------------------------------------------------------- #
step "Waiting for the recorder to come up (timeout ${STARTUP_TIMEOUT_SECONDS}s)"
deadline=$(( $(date +%s) + STARTUP_TIMEOUT_SECONDS ))
healthy=0
while (( $(date +%s) < deadline )); do
  # If the process died during startup, surface the log immediately.
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    say "${DIM}--- last lines of ${LOG_FILE} ---${RESET}"
    tail -n 30 "${LOG_FILE}" || true
    die "Recorder process exited during startup (PID ${APP_PID})."
  fi
  if status_json="$(curl -sS "${BASE_URL}/api/status" 2>/dev/null)" \
      && printf '%s' "$status_json" | jq -e '.storage.sessions != null' >/dev/null 2>&1; then
    healthy=1
    break
  fi
  sleep 1
done

if [[ "$healthy" -ne 1 ]]; then
  say "${DIM}--- last lines of ${LOG_FILE} ---${RESET}"
  tail -n 30 "${LOG_FILE}" || true
  die "Recorder did not become healthy within ${STARTUP_TIMEOUT_SECONDS}s."
fi
ok "Recorder is healthy at ${BASE_URL}"
printf '%s' "$status_json" | jq '{storage, localAi: {enabled: .localAi.enabled, available: .localAi.available}, elasticsearch: {enabled: .elasticsearch.enabled}}'

# --------------------------------------------------------------------------- #
# 3. Seed the cross-agent story
# --------------------------------------------------------------------------- #
step "Seeding the story — Day 1: a Codex session designs the Acme auth strategy"

# 3a. Codex kicks off with a user prompt.
post_json /api/events "$(jq -n \
  --arg s   "codex" \
  --arg cs  "${CODEX_SESSION}" \
  --arg cwd "${REPO}" \
  '{source:$s, clientSessionId:$cs, eventType:"UserPromptSubmit", role:"user",
    text:"Design the auth token strategy for the Acme API", cwd:$cwd}')" >/dev/null
ok "Codex UserPromptSubmit recorded."

# 3b. A couple of tool events for timeline texture: a Read, then an Edit.
post_json /api/events "$(jq -n \
  --arg s   "codex" \
  --arg cs  "${CODEX_SESSION}" \
  --arg cwd "${REPO}" \
  '{source:$s, clientSessionId:$cs, eventType:"PostToolUse", role:"tool",
    toolName:"Read", text:"Read the existing Acme API security middleware",
    toolInput:{file:"/tmp/acme-auth/src/security/middleware.ts"},
    toolOutput:{lines:142, summary:"no token layer yet — request passes straight through"},
    cwd:$cwd}')" >/dev/null
post_json /api/events "$(jq -n \
  --arg s   "codex" \
  --arg cs  "${CODEX_SESSION}" \
  --arg cwd "${REPO}" \
  '{source:$s, clientSessionId:$cs, eventType:"PostToolUse", role:"tool",
    toolName:"Edit", text:"Scaffold the JWT issuer and refresh-token rotation table",
    toolInput:{file:"/tmp/acme-auth/src/auth/jwt.ts", change:"add issueAccessToken + rotateRefreshToken"},
    toolOutput:{status:"written", added:88},
    cwd:$cwd}')" >/dev/null
ok "Two tool events recorded (Read, Edit)."

# 3c. The structured DECISION — the heart of what a later agent will recall.
post_json /api/decisions "$(jq -n \
  --arg s   "codex" \
  --arg cs  "${CODEX_SESSION}" \
  --arg repo "${REPO}" \
  '{source:$s, clientSessionId:$cs, repo:$repo,
    decision:"Use JWT access tokens with refresh-token rotation",
    rationale:"Stateless and horizontally scalable; avoids a shared session store",
    alternatives:["Server-side sessions in Redis","Opaque tokens with introspection"],
    confidence:0.8,
    openLoops:["revoke-on-logout is not wired yet","refresh-token reuse detection is a TODO"]}')" >/dev/null
ok "Decision committed: JWT + refresh-token rotation (confidence 0.8, 2 open loops)."

# 3d. The HANDOFF — Codex leaves an explicit open loop for whoever comes next.
post_json /api/handoffs "$(jq -n \
  --arg s   "codex" \
  --arg cs  "${CODEX_SESSION}" \
  --arg repo "${REPO}" \
  '{source:$s, clientSessionId:$cs, repo:$repo,
    toAgent:"next-session",
    contextSummary:"Auth token strategy chosen and scaffolded; login + refresh endpoints stubbed",
    openLoops:["revoke-on-logout not wired"],
    nextAction:"Wire revoke-on-logout against the rotation table"}')" >/dev/null
ok "Handoff committed for the next session."

step "Seeding the story — Day 2: a FRESH Claude session arrives at the same repo"
# 3e. A brand-new Claude session, with no memory of Codex, opens the same repo.
post_json /api/events "$(jq -n \
  --arg s   "claude" \
  --arg cs  "${CLAUDE_SESSION}" \
  --arg cwd "${REPO}" \
  '{source:$s, clientSessionId:$cs, eventType:"UserPromptSubmit", role:"user",
    text:"Wire revoke-on-logout for the Acme auth flow", cwd:$cwd}')" >/dev/null
ok "Claude UserPromptSubmit recorded — picking up exactly the open loop Codex left."

# 3f. A manual observation for texture (e.g. a human note about the demo run).
post_json /api/events "$(jq -n \
  '{source:"manual", clientSessionId:"demo-narrator", eventType:"Observation", role:"note",
    text:"Demo note: two different agents, one repo, one shared memory — no cloud, no file reads.",
    cwd:"/tmp/acme-auth",
    metadata:{kind:"observation", topic:"black-box-demo"}}')" >/dev/null
ok "Manual observation recorded."

# --------------------------------------------------------------------------- #
# Best-effort: summarize the Codex session so the UI summary panel has content.
# --------------------------------------------------------------------------- #
step "Best-effort: summarizing the Codex session for the UI summary panel"
# Look up the Codex session id from /api/sessions, then ask the recorder to
# summarize it. With local AI off the recorder falls back to a compacted
# transcript, so this works without a GPU. Any failure here is non-fatal.
CODEX_SESSION_ID="$(curl -sS "${BASE_URL}/api/sessions?limit=40" 2>/dev/null \
  | jq -r --arg cs "${CODEX_SESSION}" \
      'map(select(.clientSessionId == $cs)) | (.[0].id // empty)' 2>/dev/null || true)"

if [[ -n "${CODEX_SESSION_ID:-}" ]]; then
  if curl -sS -X POST "${BASE_URL}/api/sessions/${CODEX_SESSION_ID}/summarize" >/dev/null 2>&1; then
    ok "Summarized Codex session ${CODEX_SESSION_ID} (compacted-transcript fallback, AI off)."
  else
    warn "Summarize call failed — continuing (the summary panel is optional)."
  fi
else
  warn "Could not resolve the Codex session id — skipping summarize (non-fatal)."
fi

# --------------------------------------------------------------------------- #
# 4. PROVE THE LOOP — recall what Codex decided, as a fresh agent would
# --------------------------------------------------------------------------- #
step "Proving the loop: recalling prior agent intent for ${REPO}"
RECALL_JSON="$(curl -sS \
  --get "${BASE_URL}/api/recall" \
  --data-urlencode "scope=${REPO}" \
  --data-urlencode "kinds=decision,handoff" \
  2>/dev/null)" || die "Recall request failed."

banner \
  "BLACK BOX — the loop just closed." \
  "" \
  "A FRESH Claude session just recalled what Codex decided yesterday —" \
  "including the open loop 'revoke-on-logout' — with zero cloud and zero" \
  "file reads. Two agents shared a thought through a third thing that" \
  "remembers for both."

# Pretty-print the structured recall: the decision, its rationale, the
# alternatives that were weighed, the confidence, the open loops, and the
# handoff's next action. This is the read side of the write+query loop.
printf '%s' "$RECALL_JSON" | jq '{
  scope,
  withinHours,
  kinds,
  count,
  items: [ .items[] | {
    kind,
    source,
    repo,
    headline,
    rationale,
    alternatives,
    confidence,
    openLoops,
    nextAction,
    toAgent,
    observedAt
  } ]
}'

# A tight, human-readable callout of the load-bearing detail.
RECALLED_LOOP="$(printf '%s' "$RECALL_JSON" \
  | jq -r '[.items[].openLoops[]? ] | map(select(test("revoke-on-logout"; "i"))) | (.[0] // "")')"
if [[ -n "$RECALLED_LOOP" ]]; then
  ok "Recall surfaced the open loop verbatim: \"${RECALLED_LOOP}\""
fi

# --------------------------------------------------------------------------- #
# 5. Open the UI and print explore/stop instructions
# --------------------------------------------------------------------------- #
step "Opening the Black Box UI"
if [[ "$(uname -s)" == "Darwin" ]] && command -v open >/dev/null 2>&1; then
  open "${BASE_URL}" || warn "Could not auto-open the browser; visit ${BASE_URL} manually."
  ok "Opened ${BASE_URL} in your default browser."
else
  warn "Not macOS (or 'open' unavailable) — visit ${BASE_URL} manually."
fi

# The demo is complete: from here on the recorder should stay alive so the user
# can explore. Flip the flag so the EXIT trap leaves the process running.
DEMO_SUCCEEDED=1

banner \
  "Black Box is running and seeded." \
  "" \
  "Try the recall yourself in the UI:" \
  "  • Find the recall / context panel and enter the scope:  ${REPO}" \
  "  • You'll get back the Codex decision + handoff above —" \
  "    the same structured intent, queried live." \
  "" \
  "When you're done exploring:" \
  "  • Stop the recorder:   kill ${APP_PID}" \
  "  • Demo database:       ${DEMO_DB}  (safe to delete)" \
  "  • Recorder log:        ${LOG_FILE}"

say ""
ok "Recorder PID ${APP_PID} is LEFT RUNNING for you to explore — it was NOT killed."
say "${DIM}   (Re-running demo.sh resets the demo DB and starts a fresh recorder.)${RESET}"

exit 0
