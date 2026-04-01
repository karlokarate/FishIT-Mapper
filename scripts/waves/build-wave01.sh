#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_DIR="$ROOT_DIR/apps/mapper-app"
OUT_DIR="$ROOT_DIR/artifacts/wave01"
APK_TARGET="$OUT_DIR/mapper-debug.apk"

if [[ ! -d "$APP_DIR" ]]; then
  echo "Missing app directory: apps/mapper-app" >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" && ! -f "$APP_DIR/local.properties" ]]; then
  echo "Android SDK not configured. Set ANDROID_HOME or create apps/mapper-app/local.properties with sdk.dir=... ." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"

(
  cd "$APP_DIR"
  ./gradlew :app:assembleDebug -PbuildAbis=arm64-v8a --no-daemon
)

apk_source="$(find "$APP_DIR/app/build/outputs/apk" -type f -name '*arm64-v8a*debug*.apk' | head -n 1)"
if [[ -z "$apk_source" ]]; then
  apk_source="$(find "$APP_DIR/app/build/outputs/apk" -type f -name '*debug*.apk' | head -n 1)"
fi

if [[ -z "$apk_source" ]]; then
  echo "No debug APK produced by Wave-01 build." >&2
  exit 1
fi

cp -f "$apk_source" "$APK_TARGET"

jq -n \
  --arg built_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg source_apk "${apk_source#$ROOT_DIR/}" \
  --arg artifact_apk "${APK_TARGET#$ROOT_DIR/}" \
  '{
    wave_id: "WAVE-01",
    built_at_utc: $built_at,
    source_apk: $source_apk,
    artifact_apk: $artifact_apk
  }' >"$OUT_DIR/build-metadata.json"

echo "Wave-01 build artifact: ${APK_TARGET#$ROOT_DIR/}"
