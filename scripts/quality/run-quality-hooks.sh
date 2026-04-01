#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_DIR="$ROOT_DIR/apps/mapper-app"
GRADLEW="$APP_DIR/gradlew"

if [[ ! -x "$GRADLEW" ]]; then
  echo "gradlew missing at $GRADLEW" >&2
  exit 1
fi

run_if_task_exists() {
  local task="$1"
  if (cd "$APP_DIR" && "$GRADLEW" tasks --all | rg -q "(^|\s)$task(\s|$)"); then
    echo "[quality-hook] running $task"
    (cd "$APP_DIR" && "$GRADLEW" "$task")
  else
    echo "[quality-hook] task not present, skipped: $task"
  fi
}

run_if_task_exists detekt
run_if_task_exists buildHealth
run_if_task_exists doctor
run_if_task_exists test

python3 "$ROOT_DIR/scripts/device/toolkit/runtime_dataset_cli.py" \
  replay report \
  --runtime-dir "$ROOT_DIR/logs/device/mapper-toolkit/current" \
  --baseline-name "" \
  --ci-strict || true

