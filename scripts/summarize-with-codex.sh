#!/usr/bin/env bash
set -euo pipefail

prompt_file="$(mktemp "${TMPDIR:-/tmp}/sba-summary-codex-prompt.XXXXXX")"
output_file="$(mktemp "${TMPDIR:-/tmp}/sba-summary-codex-output.XXXXXX")"
codex_home="$(mktemp -d "${TMPDIR:-/tmp}/sba-summary-codex-home.XXXXXX")"
trap 'rm -rf "$prompt_file" "$output_file" "$codex_home"' EXIT
codex_bin="${SBA_SUMMARY_CODEX_BIN:-/opt/homebrew/bin/codex}"
auth_file="${SBA_SUMMARY_CODEX_AUTH:-${CODEX_HOME:-$HOME/.codex}/auth.json}"

if [[ ! -r "$auth_file" ]]; then
  echo "Codex auth file not readable: $auth_file" >&2
  exit 1
fi
ln -s "$auth_file" "$codex_home/auth.json"

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
  CODEX_HOME="$codex_home" "$codex_bin" exec --disable hooks --disable plugins --disable memories --ephemeral --ignore-rules --ignore-user-config --model "$SBA_SUMMARY_CODEX_MODEL" --output-last-message "$output_file" - < "$prompt_file" >/dev/null
else
  CODEX_HOME="$codex_home" "$codex_bin" exec --disable hooks --disable plugins --disable memories --ephemeral --ignore-rules --ignore-user-config --output-last-message "$output_file" - < "$prompt_file" >/dev/null
fi

cat "$output_file"
