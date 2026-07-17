#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo 'usage: report.sh <taskId> done|blocked|plan|review "<summary>"' >&2
  exit 2
fi

TASK_ID="$1"
OUTCOME="$2"
SUMMARY="$3"

if [[ -z "$TASK_ID" || -z "$SUMMARY" ]]; then
  echo 'report.sh: taskId and summary must not be empty' >&2
  exit 2
fi

case "$OUTCOME" in
  done | blocked | plan | review) ;;
  *)
    echo 'report.sh: outcome must be done, blocked, plan, or review' >&2
    exit 2
    ;;
esac

BASE_URL="${SBA_BASE_URL:-http://127.0.0.1:8766}"
BASE_URL="${BASE_URL%/}"

if [[ "$OUTCOME" == "done" || "$OUTCOME" == "blocked" ]]; then
  jq -n \
    --arg summary "$SUMMARY" \
    --arg outcome "$OUTCOME" \
    '{
      actor: "blackbox-runner-worker",
      kind: "progress",
      text: $summary,
      dataJson: {event: "worker_done", outcome: $outcome}
    }'
else
  jq -n \
    --arg summary "$SUMMARY" \
    --arg kind "$OUTCOME" \
    '{
      actor: "blackbox-runner-worker",
      kind: $kind,
      text: $summary
    }'
fi |
curl -fsS --max-time 10 \
  -H "Content-Type: application/json" \
  -X POST \
  --data-binary @- \
  "$BASE_URL/api/tasks/$TASK_ID/annotations" >/dev/null
