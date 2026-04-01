#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$ROOT_DIR/artifacts/wave01"
APK_PATH="$OUT_DIR/mapper-debug.apk"
REPORT_PATH="$OUT_DIR/device-smoke.json"
APP_ID="dev.fishit.mapper.wave01"
LAUNCH_ACTIVITY="info.plateaukao.einkbro.activity.BrowserActivity"
TEST_URL="${1:-https://example.org}"
ADB_BIN="${ADB_BIN:-adb}"
failure_reason=""

mkdir -p "$OUT_DIR"

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  if [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/platform-tools/adb" ]]; then
    ADB_BIN="${ANDROID_HOME}/platform-tools/adb"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]]; then
    ADB_BIN="${ANDROID_SDK_ROOT}/platform-tools/adb"
  elif [[ -x "/home/vscode/.android-sdk/platform-tools/adb" ]]; then
    ADB_BIN="/home/vscode/.android-sdk/platform-tools/adb"
  else
    failure_reason="adb_not_found"
  fi
fi

install_ok=true
launch_ok=true
url_load_ok=true
serial=""

if [[ -z "$failure_reason" && ! -f "$APK_PATH" ]]; then
  failure_reason="apk_missing"
fi

if [[ -z "$failure_reason" ]]; then
  mapfile -t devices < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#devices[@]} -eq 0 ]]; then
    failure_reason="no_online_device"
  else
    serial="${devices[0]}"
  fi
fi

if [[ -z "$failure_reason" ]]; then
  if ! "$ADB_BIN" -s "$serial" install -r "$APK_PATH" >/dev/null; then
    install_ok=false
    failure_reason="install_failed"
  fi
fi

if [[ "$install_ok" == true && -z "$failure_reason" ]]; then
  if ! "$ADB_BIN" -s "$serial" shell am start -n "$APP_ID/$LAUNCH_ACTIVITY" >/dev/null; then
    launch_ok=false
    failure_reason="launch_failed"
  fi
fi

if [[ "$install_ok" == true && "$launch_ok" == true && -z "$failure_reason" ]]; then
  if ! "$ADB_BIN" -s "$serial" shell am start -a android.intent.action.VIEW -d "$TEST_URL" "$APP_ID" >/dev/null; then
    url_load_ok=false
    failure_reason="url_load_failed"
  fi
fi

jq -n \
  --arg run_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg serial "$serial" \
  --arg app_id "$APP_ID" \
  --arg test_url "$TEST_URL" \
  --arg adb_bin "$ADB_BIN" \
  --arg failure_reason "$failure_reason" \
  --argjson install_ok "$install_ok" \
  --argjson launch_ok "$launch_ok" \
  --argjson url_load_ok "$url_load_ok" \
  '{
    wave_id: "WAVE-01",
    run_at_utc: $run_at,
    device_serial: $serial,
    adb_bin: $adb_bin,
    app_id: $app_id,
    test_url: $test_url,
    failure_reason: $failure_reason,
    install_ok: $install_ok,
    launch_ok: $launch_ok,
    url_load_ok: $url_load_ok
  }' >"$REPORT_PATH"

if [[ -n "$failure_reason" || "$install_ok" != true || "$launch_ok" != true || "$url_load_ok" != true ]]; then
  echo "Wave-01 device smoke failed. See artifacts/wave01/device-smoke.json" >&2
  exit 1
fi

echo "Wave-01 device smoke passed on $serial"
