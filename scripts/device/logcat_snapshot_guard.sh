#!/usr/bin/env bash
set -euo pipefail

# Periodically snapshots an active live logcat file so data is preserved
# even if a foreground session is interrupted.
#
# Usage:
#   scripts/device/logcat_snapshot_guard.sh <run_id> [interval_seconds]

RUN_ID="${1:-}"
INTERVAL_SECONDS="${2:-20}"
BASE_DIR="/workspaces/FishIT-Player/logs/device/live"

if [[ -z "$RUN_ID" ]]; then
  echo "Usage: $0 <run_id> [interval_seconds]" >&2
  exit 1
fi

if ! [[ "$INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || [[ "$INTERVAL_SECONDS" -lt 5 ]]; then
  echo "interval_seconds must be an integer >= 5" >&2
  exit 1
fi

LOG_FILE="${BASE_DIR}/${RUN_ID}_logcat.txt"
SNAP_DIR="${BASE_DIR}/${RUN_ID}_snapshots"
STATE_FILE="${SNAP_DIR}/.last_size"
mkdir -p "$SNAP_DIR"

touch "$STATE_FILE"
LAST_SIZE="$(cat "$STATE_FILE" 2>/dev/null || echo 0)"
if [[ -z "$LAST_SIZE" ]]; then
  LAST_SIZE=0
fi

echo "logcat_snapshot_guard start run_id=${RUN_ID} interval=${INTERVAL_SECONDS}s log_file=${LOG_FILE}"

while true; do
  if [[ -f "$LOG_FILE" ]]; then
    SIZE="$(wc -c <"$LOG_FILE" 2>/dev/null || echo 0)"
    if [[ "$SIZE" -gt "$LAST_SIZE" ]]; then
      TS="$(date -u +%Y%m%dT%H%M%SZ)"
      SNAP_FILE="${SNAP_DIR}/logcat_${TS}.txt"
      cp "$LOG_FILE" "$SNAP_FILE"
      LAST_SIZE="$SIZE"
      echo "$LAST_SIZE" >"$STATE_FILE"
      ln -sfn "$(basename "$SNAP_FILE")" "${SNAP_DIR}/latest.txt"
      echo "snapshot_saved ts=${TS} size=${SIZE}"
    fi
  fi
  sleep "$INTERVAL_SECONDS"
done
