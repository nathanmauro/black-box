#!/usr/bin/env bash
# Local verification gate: the checks a green CI run would perform, runnable without CI.
#
#   ./scripts/verify.sh          # Script checks, Java, frontend type/unit checks, whitespace
#   ./scripts/verify.sh --e2e    # additionally runs the Playwright suite (packages a jar; see note)
#
# Note: --e2e packages target/*.jar. Never run it over a JAR used by a live service.
# Use an isolated checkout and output path for verification builds.
#
# Install as a pre-push hook (runs the default set before every push):
#   git config core.hooksPath scripts/git-hooks
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RUN_E2E=false
for arg in "$@"; do
  case "$arg" in
    --e2e) RUN_E2E=true ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }

step "Offline lifecycle rehearsal"
python3 -B -m unittest discover -s scripts/lifecycle -p 'test_*.py'

step "git diff --check (whitespace errors in tracked changes)"
git diff --check
git diff --cached --check

step "Java suite: mvn -B -q test"
mvn -B -q test

step "Frontend: type check"
(cd frontend && npx tsc --noEmit)

step "Frontend: vitest"
(cd frontend && npm test --silent)

if [ "$RUN_E2E" = true ]; then
  step "Frontend: Playwright e2e (packages a jar)"
  (cd frontend && npm run e2e)
fi

step "verify.sh passed"
