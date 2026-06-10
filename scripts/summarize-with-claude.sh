#!/usr/bin/env bash
set -euo pipefail

prompt_file="$(mktemp "${TMPDIR:-/tmp}/sba-summary-claude.XXXXXX")"
trap 'rm -f "$prompt_file"' EXIT

cat > "$prompt_file"

claude_bin="${SBA_SUMMARY_CLAUDE_BIN:-claude}"

{
  cat <<'PROMPT'
Summarize this local agent session for later recall. Be concise, factual, and action-oriented.

Prioritize:
- user intent and final answer
- decisions made
- files, commands, services, URLs, and session handles that matter
- unresolved work and blockers

Do not mention implementation details of the summarization process.

Transcript:
PROMPT
  cat "$prompt_file"
} | "$claude_bin" -p \
  --setting-sources '' \
  --output-format text \
  --model "${SBA_SUMMARY_CLAUDE_MODEL:-claude-opus-4-8}" \
  --effort "${SBA_SUMMARY_CLAUDE_EFFORT:-max}" \
  --no-session-persistence
