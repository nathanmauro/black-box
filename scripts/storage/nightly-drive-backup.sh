#!/usr/bin/env bash
# Nightly offsite snapshot of the live Black Box SQLite DB.
#
# Snapshot with blackbox_backup.py (one-step backup API, read-only source, quick_check), compress
# with zstd, copy into a Google Drive for desktop folder, keep the newest KEEP_REMOTE there and
# KEEP_LOCAL locally. Prunes only files matching its own blackbox-nightly-* pattern.
# Failure pings Telegram through `todo telegram-send` when that CLI exists.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DB="${SBA_BACKUP_SOURCE:-$HOME/.local/share/sba-agentic/sba-agentic.db}"
LOCAL_DIR="${SBA_BACKUP_LOCAL_DIR:-$HOME/.local/share/sba-agentic/backups/nightly}"
DRIVE_ROOT="${SBA_BACKUP_DRIVE_ROOT:-$HOME/Library/CloudStorage/GoogleDrive-nathan.e.mauro@gmail.com/My Drive}"
REMOTE_DIR="${SBA_BACKUP_REMOTE_DIR:-$DRIVE_ROOT/Data/Backups/black-box}"
KEEP_REMOTE="${SBA_BACKUP_KEEP_REMOTE:-3}"
KEEP_LOCAL="${SBA_BACKUP_KEEP_LOCAL:-1}"

STAMP="$(date +%Y%m%d-%H%M%S)"
NAME="blackbox-nightly-$STAMP"

log() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
    log "FAILED: $1"
    if command -v todo >/dev/null 2>&1; then
        todo telegram-send "Black Box nightly backup failed: $1" >/dev/null 2>&1 || true
    fi
    exit 1
}

# Keep the newest $2 files matching blackbox-nightly-*$3 in directory $1.
prune() {
    local dir="$1"
    local keep="$2"
    local suffix="$3"
    local old
    old="$(find "$dir" -maxdepth 1 -type f -name "blackbox-nightly-*$suffix" | sort -r | tail -n +"$((keep + 1))")"
    if [ -n "$old" ]; then
        printf '%s\n' "$old" | while IFS= read -r file; do
            rm -f -- "$file"
            log "pruned $file"
        done
    fi
}

command -v zstd >/dev/null 2>&1 || fail "zstd_missing"
[ -d "$DRIVE_ROOT" ] || fail "drive_not_mounted"
mkdir -p "$LOCAL_DIR" "$REMOTE_DIR"

log "snapshot start $SOURCE_DB"
result="$(python3 "$SCRIPT_DIR/blackbox_backup.py" sqlite \
    --source "$SOURCE_DB" \
    --output "$LOCAL_DIR/$NAME.db" \
    --execute)" || fail "$(printf '%s' "$result" | tr -d '\n')"
log "snapshot $result"

zstd -q -T0 -6 --rm "$LOCAL_DIR/$NAME.db" -o "$LOCAL_DIR/$NAME.db.zst" || fail "zstd_failed"
zstd -q -t "$LOCAL_DIR/$NAME.db.zst" || fail "zstd_verify_failed"
printf '%s\n' "$result" >"$LOCAL_DIR/$NAME.json"
log "compressed $(du -h "$LOCAL_DIR/$NAME.db.zst" | cut -f1)"

# Copy under a temp name, then rename, so Drive never uploads a half-written archive.
cp "$LOCAL_DIR/$NAME.db.zst" "$REMOTE_DIR/.$NAME.db.zst.partial" || fail "drive_copy_failed"
mv "$REMOTE_DIR/.$NAME.db.zst.partial" "$REMOTE_DIR/$NAME.db.zst"
cp "$LOCAL_DIR/$NAME.json" "$REMOTE_DIR/$NAME.json"
log "copied to $REMOTE_DIR"

prune "$REMOTE_DIR" "$KEEP_REMOTE" ".db.zst"
prune "$REMOTE_DIR" "$KEEP_REMOTE" ".json"
prune "$LOCAL_DIR" "$KEEP_LOCAL" ".db.zst"
prune "$LOCAL_DIR" "$KEEP_LOCAL" ".json"
log "done $NAME"
