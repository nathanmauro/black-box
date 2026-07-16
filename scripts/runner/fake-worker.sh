#!/usr/bin/env bash
set -euo pipefail

SBA_TASK_ID="${SBA_TASK_ID:-}"
if [[ -z "$SBA_TASK_ID" ]]; then
  echo 'fake-worker.sh: SBA_TASK_ID is required' >&2
  exit 1
fi

echo "fake worker run at $(date -u +%FT%TZ)" >> .blackbox-fake-worker.log
git add .blackbox-fake-worker.log
if ! git diff --cached --quiet; then
  git commit -m "fake worker: trivial verified commit"
else
  echo 'fake-worker.sh: marker file had no staged changes; skipping commit' >&2
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

"$(dirname "$0")/report.sh" \
  "$SBA_TASK_ID" \
  done \
  "fake worker completed a trivial verified commit"
