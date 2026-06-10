#!/usr/bin/env bash
# Black Box recall bridge. Reads a Claude Code SessionStart payload on stdin, recalls prior
# decisions and handoffs for the current repo, and prints compact context for injection.
#
# Safety contract: this hook must NEVER fail its host agent's session start. If the recorder is
# down, slow, empty, or jq is missing, the agent should carry on as if nothing happened. So we do
# not use `set -e`, we cap the request with a short timeout, we swallow any network failure, and we
# always exit 0.
set -uo pipefail

SBA_AGENTIC_URL="${SBA_AGENTIC_URL:-http://localhost:8766}"
SBA_RECALL_WITHIN_HOURS="${SBA_RECALL_WITHIN_HOURS:-168}"
SBA_RECALL_LIMIT="${SBA_RECALL_LIMIT:-5}"
SBA_RECALL_MAX_CHARS="${SBA_RECALL_MAX_CHARS:-4000}"

# If jq is unavailable there is nothing useful we can do — never fail the host agent over it.
command -v jq >/dev/null 2>&1 || exit 0

RAW="$(cat)"
# Tolerate non-JSON stdin silently. SessionStart recall should add context or add nothing.
printf '%s' "$RAW" | jq -e . >/dev/null 2>&1 || exit 0

CWD="$(jq -r '.cwd // empty' <<<"$RAW")"
if [ -z "$CWD" ]; then
  exit 0
fi

ENCODED_CWD="$(jq -nr --arg v "$CWD" '$v | @uri')"
RECALL_URL="$SBA_AGENTIC_URL/api/recall?scope=$ENCODED_CWD&withinHours=$SBA_RECALL_WITHIN_HOURS&kinds=decision,handoff"
RESPONSE="$(curl -fsS --max-time 3 "$RECALL_URL" 2>/dev/null)" || exit 0
if [ -z "$RESPONSE" ]; then
  exit 0
fi

BLOCK="$(jq -r \
  --arg limit "$SBA_RECALL_LIMIT" \
  --arg maxChars "$SBA_RECALL_MAX_CHARS" \
  --arg agenticUrl "$SBA_AGENTIC_URL" '
  def positive_int($fallback):
    (tonumber? // $fallback) as $n | if $n > 0 then ($n | floor) else $fallback end;
  def clean:
    (. // "" | tostring | gsub("[[:space:]]+"; " ") | sub("^\\s+"; "") | sub("\\s+$"; ""));
  def clean_list:
    (. // [] | map(clean) | map(select(length > 0)));
  def item_kind:
    (.kind // "item" | ascii_downcase);
  def day:
    (.observedAt | clean) as $ts | if ($ts | length) >= 10 then $ts[0:10] else "unknown date" end;
  def source_label:
    (.source | clean) as $source
    | (.toAgent | clean) as $to
    | if item_kind == "handoff" and ($source | length) > 0 and ($to | length) > 0 then "\($source) → \($to)"
      elif ($source | length) > 0 then $source
      else "unknown"
      end;
  def open_loop_text:
    (.openLoops | clean_list) as $loops
    | if ($loops | length) > 0 then " Open loops: \($loops | join("; "))." else "" end;
  def sentence:
    item_kind as $kind
    | if $kind == "decision" then
        (.headline | clean) as $headline
        | (.rationale | clean) as $rationale
        | "DECISION (\(source_label), \(day)): \(if ($headline | length) > 0 then $headline else "Untitled decision" end)"
          + (if ($rationale | length) > 0 then " — why: \($rationale)." else "." end)
          + open_loop_text
      elif $kind == "handoff" then
        (.headline | clean) as $headline
        | (.nextAction | clean) as $next
        | "HANDOFF (\(source_label), \(day)): \(if ($headline | length) > 0 then $headline else "No handoff summary" end)."
          + (if ($next | length) > 0 then " Next action: \($next)." else "" end)
          + open_loop_text
      else
        "\($kind | ascii_upcase) (\(source_label), \(day)): \((.headline | clean))."
      end;
  def window_label:
    (.withinHours // 168) as $hours
    | if ($hours % 24) == 0 then
        ($hours / 24 | floor) as $days
        | "last \($days) day\(if $days == 1 then "" else "s" end)"
      else
        "last \($hours) hours"
      end;
  def trim_block($max):
    . as $text
    | if ($text | length) <= $max then $text
      else
        ($max - 14) as $target
        | if $target < 1 then
            "… (+\($text | length) more)"
          else
            ($text[0:$target] | sub("\\s+[^\\s]*$"; "")) as $trimmed
            | "\($trimmed) … (+\(($text | length) - ($trimmed | length)) more)"
          end
      end;
  ($limit | positive_int(5)) as $limit
  | ($maxChars | positive_int(4000)) as $max
  | (.items // [] | sort_by(.observedAt // "") | reverse | .[:$limit]) as $items
  | if ($items | length) == 0 then empty
    else
      (["[Black Box recall] Prior agent decisions/handoffs for this repo (\(window_label)):"]
        + ($items | to_entries | map("\(.key + 1). \(.value | sentence)"))
        + ["Query more with the sba-agentic recallContext MCP tool, or see \($agenticUrl)."])
      | join("\n")
      | trim_block($max)
    end
' <<<"$RESPONSE" 2>/dev/null)" || BLOCK=""

if [ -n "$BLOCK" ]; then
  printf '%s\n' "$BLOCK"
fi

exit 0
