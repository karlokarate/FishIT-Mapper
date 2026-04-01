#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/logs/device/mapper-toolkit/current"
MITM_DIR="$RUNTIME_DIR/mitmproxy"
SESSION_NAME="mapper_mitm"
LISTEN_HOST="${MITM_LISTEN_HOST:-127.0.0.1}"
LISTEN_PORT="${MITM_LISTEN_PORT:-8080}"

usage() {
  cat <<'USAGE'
Usage:
  scripts/device/toolkit/mitm_bridge.sh on
  scripts/device/toolkit/mitm_bridge.sh off
  scripts/device/toolkit/mitm_bridge.sh status
USAGE
}

on_cmd() {
  if ! command -v mitmdump >/dev/null 2>&1; then
    echo "ERROR: mitmdump is not installed. Run mapper-toolkit bootstrap first." >&2
    exit 1
  fi
  if ! command -v tmux >/dev/null 2>&1; then
    echo "ERROR: tmux is not installed." >&2
    exit 1
  fi

  mkdir -p "$MITM_DIR"
  local out_file="$MITM_DIR/traffic_$(date -u +%Y%m%dT%H%M%SZ).mitm"

  if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    echo "mitmproxy already running in tmux session '$SESSION_NAME'"
    return 0
  fi

  tmux new-session -d -s "$SESSION_NAME" \
    "mitmdump --listen-host '$LISTEN_HOST' --listen-port '$LISTEN_PORT' -w '$out_file'"

  cat > "$MITM_DIR/latest.json" <<JSON
{
  "started_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "listen_host": "$LISTEN_HOST",
  "listen_port": $LISTEN_PORT,
  "session": "$SESSION_NAME",
  "capture_file": "${out_file#$ROOT_DIR/}"
}
JSON

  echo "mitmproxy started on $LISTEN_HOST:$LISTEN_PORT (session: $SESSION_NAME)"
  echo "Tip: configure device proxy to $LISTEN_HOST:$LISTEN_PORT"
}

off_cmd() {
  if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    tmux kill-session -t "$SESSION_NAME"
    echo "mitmproxy session stopped"
  else
    echo "mitmproxy session not running"
  fi
}

status_cmd() {
  if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    echo "status: running"
    tmux list-sessions | rg "$SESSION_NAME" || true
    if [[ -f "$MITM_DIR/latest.json" ]]; then
      jq . "$MITM_DIR/latest.json"
    fi
  else
    echo "status: stopped"
  fi
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    on) on_cmd ;;
    off) off_cmd ;;
    status) status_cmd ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
