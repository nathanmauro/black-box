#!/usr/bin/env bash
# Nightly encrypted offsite backup of the Black Box and Job Scout SQLite databases.
#
# blackbox_offsite_backup.py does the work: a WAL-safe snapshot of each database, integrity_check,
# row counts, an AES-256 archive with an authenticated manifest, a copy into a Google Drive for
# desktop folder, and a restore test of that offsite copy. It keeps the newest KEEP_REMOTE sets
# there and KEEP_LOCAL locally, and prunes only files matching its own names.
# Failure posts to Slack through `todo notify --route alerts` when that CLI exists.
#
# SBA_BACKUP_DRY_RUN=1 prints the plan and touches nothing. An empty SBA_BACKUP_JOBS_SOURCE
# skips the Job Scout database.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DB="${SBA_BACKUP_SOURCE:-$HOME/.local/share/sba-agentic/sba-agentic.db}"
JOBS_DB="${SBA_BACKUP_JOBS_SOURCE-$HOME/Developer/proj/job-scout/state/jobs.sqlite3}"
LOCAL_DIR="${SBA_BACKUP_LOCAL_DIR:-$HOME/.local/share/sba-agentic/backups/nightly}"
DRIVE_ROOT="${SBA_BACKUP_DRIVE_ROOT:-$HOME/Library/CloudStorage/GoogleDrive-nathan.e.mauro@gmail.com/My Drive}"
REMOTE_DIR="${SBA_BACKUP_REMOTE_DIR:-$DRIVE_ROOT/Data/Backups/black-box}"
PASSPHRASE_FILE="${SBA_BACKUP_PASSPHRASE_FILE:-$HOME/.config/blackbox/backup-passphrase}"
KEEP_REMOTE="${SBA_BACKUP_KEEP_REMOTE:-3}"
KEEP_LOCAL="${SBA_BACKUP_KEEP_LOCAL:-1}"
DRY_RUN="${SBA_BACKUP_DRY_RUN:-0}"

log() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

fail() {
    log "FAILED: $1"
    if command -v todo >/dev/null 2>&1; then
        todo notify --route alerts "Black Box nightly backup failed: $1" >/dev/null 2>&1 || true
    fi
    exit 1
}

args=(
    backup
    --database "blackbox-nightly=$SOURCE_DB"
    --local-dir "$LOCAL_DIR"
    --offsite-dir "$REMOTE_DIR"
    --passphrase-file "$PASSPHRASE_FILE"
    --keep-local "$KEEP_LOCAL"
    --keep-offsite "$KEEP_REMOTE"
)

if [ -n "$JOBS_DB" ]; then
    args+=(--database "jobscout-nightly=$JOBS_DB")
fi

if [ "$DRY_RUN" = "1" ]; then
    result="$(python3 "$SCRIPT_DIR/blackbox_offsite_backup.py" "${args[@]}")" ||
        fail "$(printf '%s' "$result" | tr -d '\n')"
    log "dry run $result"
    exit 0
fi

[ -d "$DRIVE_ROOT" ] || fail "drive_not_mounted"
# Under set -e a bare mkdir failure would exit before fail, without an alert.
mkdir -p "$LOCAL_DIR" "$REMOTE_DIR" || fail "backup_dir_unavailable"

log "backup start"
result="$(python3 "$SCRIPT_DIR/blackbox_offsite_backup.py" "${args[@]}" --execute)" ||
    fail "$(printf '%s' "$result" | tr -d '\n')"
log "done $result"
