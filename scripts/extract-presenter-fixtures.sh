#!/bin/sh
# Extract golden presenter fixtures from a read-only snapshot of the live Black Box DB.
# Usage: scripts/extract-presenter-fixtures.sh [db-path]   (default: sba-agentic.db)
set -eu
DB="${1:-sba-agentic.db}"
SNAP_DIR="$(mktemp -d)"
SNAP="$SNAP_DIR/fixtures-snapshot.db"
OUT="frontend/src/lib/presenters/__fixtures__"
mkdir -p "$OUT"
sqlite3 "file:$DB?mode=ro" ".backup $SNAP"
for tool in Bash Edit Write Read apply_patch; do
  file="$OUT/$(printf '%s' "$tool" | tr '[:upper:]' '[:lower:]').json"
  sqlite3 -json "$SNAP" "
    SELECT tool_name AS toolName, event_type AS eventType,
           tool_input_json AS toolInputJson, tool_output_json AS toolOutputJson
    FROM agent_events
    WHERE tool_name = '$tool'
      AND tool_input_json IS NOT NULL
      AND length(coalesce(tool_input_json, '')) BETWEEN 40 AND 4000
      AND length(coalesce(tool_output_json, '')) < 8000
    ORDER BY observed_at DESC
    LIMIT 3;" > "$file"
  [ -s "$file" ] || printf '[]\n' > "$file"   # sqlite3 -json emits nothing (not []) for empty results
  echo "wrote $file"
done
rm -rf "$SNAP_DIR"
