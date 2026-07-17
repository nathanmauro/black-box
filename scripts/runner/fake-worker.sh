#!/usr/bin/env bash
set -euo pipefail

SBA_TASK_ID="${SBA_TASK_ID:-}"
if [[ -z "$SBA_TASK_ID" ]]; then
  echo 'fake-worker.sh: SBA_TASK_ID is required' >&2
  exit 1
fi

SBA_STAGE="${SBA_STAGE:-build}"
case "$SBA_STAGE" in
  plan | build | review) ;;
  *)
    echo 'fake-worker.sh: SBA_STAGE must be plan, build, or review' >&2
    exit 2
    ;;
esac

if [[ "$SBA_STAGE" == "build" ]]; then
  echo "fake worker run at $(date -u +%FT%TZ)" >> .blackbox-fake-worker.log
  git add .blackbox-fake-worker.log
  if ! git diff --cached --quiet; then
    git commit -m "fake worker: trivial verified commit"
  else
    echo 'fake-worker.sh: marker file had no staged changes; skipping commit' >&2
  fi
fi

SESSIONS_DIR="$HOME/.codex/sessions/blackbox-e2e"
mkdir -p "$SESSIONS_DIR"
ROLLOUT_FILE="$SESSIONS_DIR/${SBA_TASK_ID}.jsonl"
CWD_CANONICAL="$(cd "$SBA_WORKTREE" && pwd -P)"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  printf '{"timestamp":"%s","type":"session_meta","payload":{"session_id":"%s","cwd":"%s"}}\n' \
    "$NOW" "$SBA_TASK_ID" "$CWD_CANONICAL"
  printf '{"timestamp":"%s","type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"fake worker run for task %s"}]}}\n' \
    "$NOW" "$SBA_TASK_ID"
} > "$ROLLOUT_FILE"

case "$SBA_STAGE" in
  plan)
    "$(dirname "$0")/report.sh" \
      "$SBA_TASK_ID" \
      plan \
      $'## Approach\n\n- Implement the requested behavior in the smallest relevant files.\n\n## Files to touch\n\n- The files named by the story and discovered during implementation.\n\n## Risks\n\n- Preserve existing behavior outside the requested slice.\n\n## Verification plan\n\n- Run the story verify command.'
    "$(dirname "$0")/report.sh" \
      "$SBA_TASK_ID" \
      done \
      "fake worker completed the implementation plan"
    ;;
  review)
    "$(dirname "$0")/report.sh" \
      "$SBA_TASK_ID" \
      review \
      $'## Review findings\n\nNo blocking findings in the fake review. The diff, acceptance criteria, approved plan, and verify result were checked.'
    "$(dirname "$0")/report.sh" \
      "$SBA_TASK_ID" \
      done \
      "fake worker completed the adversarial review"
    ;;
  build)
    "$(dirname "$0")/report.sh" \
      "$SBA_TASK_ID" \
      done \
      "fake worker completed a trivial verified commit"
    ;;
esac
