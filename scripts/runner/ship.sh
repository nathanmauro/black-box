#!/usr/bin/env bash
# Usage: ship.sh <taskId> <repo> <branch> <worktreeDir> <push:true|false> \
#   <autoMerge:true|false> <danger or empty string> <title> <summary>
#
# The repo argument is the absolute repository root, not the worktree. The script
# prints exactly one JSON result line to stdout; all diagnostics go to stderr.
set -uo pipefail

usage() {
  echo 'usage: ship.sh <taskId> <repo> <branch> <worktreeDir> <push:true|false> <autoMerge:true|false> <danger or empty string> <title> <summary>' >&2
}

emit_result() {
  local status="$1"
  local reason="$2"
  local pr_url="$3"
  local merge_status="$4"
  shift 4

  local manual_json='[]'
  if [[ "$#" -gt 0 ]]; then
    manual_json="$(printf '%s\n' "$@" | jq -Rsc 'split("\n")[:-1]')"
  fi

  jq -cn \
    --arg status "$status" \
    --arg reason "$reason" \
    --arg prUrl "$pr_url" \
    --arg mergeStatus "$merge_status" \
    --argjson manualCommands "$manual_json" \
    '{
      status: $status,
      reason: $reason,
      prUrl: (if $prUrl == "" then null else $prUrl end),
      mergeStatus: (if $mergeStatus == "" then null else $mergeStatus end),
      manualCommands: $manualCommands
    }'
  exit 0
}

stderr_tail() {
  local file="$1"
  local fallback="$2"
  local detail
  detail="$(tail -n 20 "$file" 2>/dev/null || true)"
  if [[ -z "$detail" ]]; then
    detail="$fallback"
  fi
  printf '%s' "$detail"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  emit_result "local-only" "help requested" "" ""
fi

if [[ "$#" -ne 9 ]]; then
  usage
  emit_result "local-only" "invalid arguments" "" ""
fi

TASK_ID="$1"
REPO="$2"
BRANCH="$3"
WORKTREE_DIR="$4"
PUSH="$5"
AUTO_MERGE="$6"
DANGER="$7"
TITLE="$8"
SUMMARY="$9"

if [[ -z "$TASK_ID" || -z "$REPO" || -z "$BRANCH" || -z "$WORKTREE_DIR" ]]; then
  usage
  emit_result "local-only" "taskId, repo, branch, and worktreeDir must not be empty" "" ""
fi

if ! command -v jq >/dev/null 2>&1; then
  echo 'ship.sh: jq is required to produce the structured result' >&2
  exit 1
fi

printf -v repo_q '%q' "$REPO"
printf -v branch_q '%q' "$BRANCH"
printf -v title_q '%q' "${TITLE:-$TASK_ID}"
pr_body="${SUMMARY}"$'\n\n''Spec: docs/superpowers/specs/2026-07-15-full-auto-board-runner.md'$'\n'"Task: ${TASK_ID}"
printf -v body_q '%q' "$pr_body"
push_command="git -C ${repo_q} push -u origin ${branch_q}"
pr_command="(cd ${repo_q} && gh pr create --head ${branch_q} --title ${title_q} --body ${body_q})"

if [[ "$PUSH" != "true" ]]; then
  emit_result \
    "local-only" \
    "repo config push is not true" \
    "" \
    "" \
    "$push_command" \
    "$pr_command"
fi

if printf '%s\n' "$DANGER" | grep -Eiq 'PUBLIC|never push|do not push'; then
  emit_result \
    "local-only" \
    "repo config danger flag: ${DANGER}" \
    "" \
    "" \
    "$push_command" \
    "$pr_command"
fi

if ! command -v gh >/dev/null 2>&1; then
  emit_result \
    "local-only" \
    "gh CLI not found on PATH" \
    "" \
    "" \
    "$push_command" \
    "# install gh, then: ${pr_command}"
fi

if ! git -C "$REPO" remote get-url origin >/dev/null 2>&1; then
  emit_result \
    "local-only" \
    "no origin remote configured" \
    "" \
    "" \
    "git -C ${repo_q} remote add origin <url>" \
    "$push_command"
fi

error_file="$(mktemp -t blackbox-ship-error.XXXXXX)"
checks_file="$(mktemp -t blackbox-ship-checks.XXXXXX)"
cleanup() {
  rm -f "$error_file" "$checks_file"
}
trap cleanup EXIT

echo "Pushing ${BRANCH} to origin." >&2
if ! git -C "$REPO" push -u origin "$BRANCH" >/dev/null 2>"$error_file"; then
  emit_result \
    "local-only" \
    "git push failed: $(stderr_tail "$error_file" "see git push output")" \
    "" \
    "" \
    "$push_command"
fi

pr_url="$(cd "$REPO" && gh pr view "$BRANCH" --json url -q .url 2>/dev/null || true)"
if [[ -n "$pr_url" ]]; then
  echo "Reusing existing PR ${pr_url}." >&2
else
  echo "Opening a ready PR for ${BRANCH}." >&2
  : >"$error_file"
  pr_output="$(
    cd "$REPO" &&
      gh pr create \
        --head "$BRANCH" \
        --title "${TITLE:-$TASK_ID}" \
        --body "$pr_body" \
        2>"$error_file"
  )"
  pr_exit="$?"
  if [[ "$pr_exit" -ne 0 ]]; then
    emit_result \
      "local-only" \
      "gh pr create failed: $(stderr_tail "$error_file" "see gh pr create output")" \
      "" \
      "" \
      "$pr_command"
  fi
  pr_url="$(printf '%s\n' "$pr_output" | awk 'NF {line=$0} END {print line}')"
  if [[ -z "$pr_url" ]]; then
    emit_result \
      "local-only" \
      "gh pr create failed: no PR URL returned" \
      "" \
      "" \
      "$pr_command"
  fi
fi

if [[ "$AUTO_MERGE" != "true" ]]; then
  emit_result "pr-open" "repo config auto_merge is not true" "$pr_url" "" \
    "gh pr merge ${pr_url} --squash --delete-branch"
fi

echo "Watching checks for ${pr_url}." >&2
: >"$checks_file"
if command -v timeout >/dev/null 2>&1; then
  (cd "$REPO" && timeout 1800 gh pr checks "$pr_url" --watch) >"$checks_file" 2>&1
  checks_exit="$?"
elif command -v gtimeout >/dev/null 2>&1; then
  (cd "$REPO" && gtimeout 1800 gh pr checks "$pr_url" --watch) >"$checks_file" 2>&1
  checks_exit="$?"
else
  # macOS does not provide timeout by default. In that environment gh's own --watch
  # remains the best available bound without adding a runtime dependency.
  (cd "$REPO" && gh pr checks "$pr_url" --watch) >"$checks_file" 2>&1
  checks_exit="$?"
fi

if [[ "$checks_exit" -ne 0 ]]; then
  emit_result \
    "blocked" \
    "checks red: $(stderr_tail "$checks_file" "see gh pr checks output")" \
    "$pr_url" \
    "checks-red"
fi

echo "Checks are green; squash-merging ${pr_url}." >&2
: >"$error_file"
if (cd "$REPO" && gh pr merge "$pr_url" --squash --delete-branch) >/dev/null 2>"$error_file"; then
  emit_result "merged" "checks green and PR merged" "$pr_url" "merged"
fi

emit_result \
  "pr-open" \
  "gh pr merge failed: $(stderr_tail "$error_file" "see gh pr merge output")" \
  "$pr_url" \
  "merge-failed" \
  "gh pr merge ${pr_url} --squash --delete-branch"
