#!/usr/bin/env bash
set -euo pipefail

prompt_file="$(mktemp "${TMPDIR:-/tmp}/sba-summary-codex-prompt.XXXXXX")"
output_file="$(mktemp "${TMPDIR:-/tmp}/sba-summary-codex-output.XXXXXX")"
trap 'rm -f "$prompt_file" "$output_file"' EXIT
codex_bin="${SBA_SUMMARY_CODEX_BIN:-/opt/homebrew/bin/codex}"

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
  cat
} > "$prompt_file"

if [[ -n "${SBA_SUMMARY_CODEX_MODEL:-}" ]]; then
  "$codex_bin" exec --disable hooks --ephemeral --model "$SBA_SUMMARY_CODEX_MODEL" --output-last-message "$output_file" - < "$prompt_file" >/dev/null
else
  "$codex_bin" exec --disable hooks --ephemeral --output-last-message "$output_file" - < "$prompt_file" >/dev/null
fi

cat "$output_file"
