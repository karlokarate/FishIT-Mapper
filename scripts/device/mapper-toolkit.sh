#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DATASET_CLI="$ROOT_DIR/scripts/device/toolkit/runtime_dataset_cli.py"
BOOTSTRAP_SCRIPT="$ROOT_DIR/scripts/device/toolkit/bootstrap.sh"
PYTHON_TUI_SCRIPT="$ROOT_DIR/scripts/device/toolkit/main.py"
LEGACY_TUI_SCRIPT="$ROOT_DIR/scripts/device/toolkit/mapper_toolkit_tui.sh"
MITM_SCRIPT="$ROOT_DIR/scripts/device/toolkit/mitm_bridge.sh"
UI_SCRIPT="$ROOT_DIR/scripts/device/ui-anchor-nav.sh"

ADB_HOST_EXPLICIT=0
if [[ "${ADB_HOST+set}" == "set" ]]; then
  ADB_HOST_EXPLICIT=1
fi
ADB_PORT_EXPLICIT=0
if [[ "${ADB_PORT+set}" == "set" ]]; then
  ADB_PORT_EXPLICIT=1
fi

ADB_BIN="${ADB_BIN:-adb}"
ADB_HOST="${ADB_HOST:-127.0.0.1}"
ADB_PORT="${ADB_PORT:-5037}"
APP_ID="${APP_ID:-dev.fishit.mapper.wave01}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-dev.fishit.mapper.wave01/info.plateaukao.einkbro.activity.BrowserActivity}"
RUNTIME_TOOLKIT_ACTION="${RUNTIME_TOOLKIT_ACTION:-dev.fishit.mapper.wave01.debug.RUNTIME_TOOLKIT_COMMAND}"
RUNTIME_TOOLKIT_RECEIVER="${RUNTIME_TOOLKIT_RECEIVER:-dev.fishit.mapper.wave01/dev.fishit.mapper.wave01.debug.RuntimeToolkitCommandReceiver}"

TOOLKIT_ROOT="$ROOT_DIR/logs/device/mapper-toolkit"
CURRENT_DIR="$TOOLKIT_ROOT/current"
ARCHIVE_DIR="$TOOLKIT_ROOT/archive"
STATE_FILE="$CURRENT_DIR/runtime_state.json"
SESSION_LOGCAT="mapper_toolkit_logcat"

export ADB_BIN ADB_HOST ADB_PORT APP_ID RUNTIME_TOOLKIT_ACTION RUNTIME_TOOLKIT_RECEIVER

usage() {
  cat <<'USAGE'
Mapper-Toolkit (portable Linux CLI)

Usage:
  scripts/device/mapper-toolkit.sh bootstrap [--yes]
  scripts/device/mapper-toolkit.sh tui [--legacy]
  scripts/device/mapper-toolkit.sh connect --device <serial|host:port>
  scripts/device/mapper-toolkit.sh doctor

  scripts/device/mapper-toolkit.sh session <start|stop|status|doctor|resume> [args...]
  scripts/device/mapper-toolkit.sh wizard <start|status|set-target-url|next-step|retry-step|skip-optional-step|finish> [args...]
  scripts/device/mapper-toolkit.sh scope <set|show> [args...]
  scripts/device/mapper-toolkit.sh probe <start-phase|stop-phase|status> [args...]
  scripts/device/mapper-toolkit.sh ui <open-screen|open-detail|tap|flow-run|anchor-scan|anchor-create|anchor-label|anchor-remove|open-url|reset-site-state> [args...]
  scripts/device/mapper-toolkit.sh capture <start|stop|snapshot|lanes|mitm> [args...]
  scripts/device/mapper-toolkit.sh trace <query|tail|export|correlate|diff> [filters...]
  scripts/device/mapper-toolkit.sh triage <start|tail|focus|anomalies|bookmark|incident-pack|stop> [filters...]
  scripts/device/mapper-toolkit.sh replay <seed|baseline-create|baseline-list|run|diff|report|native-request> [filters...]
  scripts/device/mapper-toolkit.sh provenance <mark|query|graph|export> [filters...]
  scripts/device/mapper-toolkit.sh cookies <timeline|set-events|refresh-events|domain-view> [filters...]
  scripts/device/mapper-toolkit.sh headers <infer-required|infer-required-active|token-deps|auth-chain> [filters...]
  scripts/device/mapper-toolkit.sh responses <raw|filter|grep|sample> [filters...]
  scripts/device/mapper-toolkit.sh mapping <candidate-endpoints|field-matrix|profile-draft|provider-draft-export|replay-seed>
  scripts/device/mapper-toolkit.sh housekeeping <pack|compress|purge|reindex|validate|mission-summary>
USAGE
}

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "ERROR: missing required file: $path" >&2
    exit 1
  fi
}

require_cmd() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "ERROR: missing required command '$name'" >&2
    exit 1
  fi
}

timestamp_utc() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

timestamp_compact_utc() {
  date -u +%Y%m%dT%H%M%SZ
}

ensure_layout() {
  mkdir -p \
    "$CURRENT_DIR/events" \
    "$CURRENT_DIR/lanes" \
    "$CURRENT_DIR/response_store" \
    "$CURRENT_DIR/exports" \
    "$CURRENT_DIR/rollups" \
    "$CURRENT_DIR/tmp" \
    "$ARCHIVE_DIR"
}

read_state_field() {
  local field="$1"
  [[ -f "$STATE_FILE" ]] || return 1
  jq -r "$field // empty" "$STATE_FILE"
}

maybe_restore_adb_endpoint_from_state() {
  [[ -f "$STATE_FILE" ]] || return 0

  local state_host=""
  local state_port=""
  state_host="$(read_state_field '.adb.host' || true)"
  state_port="$(read_state_field '.adb.port' || true)"

  if [[ "$ADB_HOST_EXPLICIT" -eq 0 && -n "$state_host" ]]; then
    ADB_HOST="$state_host"
  fi
  if [[ "$ADB_PORT_EXPLICIT" -eq 0 && -n "$state_port" ]]; then
    ADB_PORT="$state_port"
  fi

  export ADB_HOST ADB_PORT
}

resolve_device_serial() {
  local requested="${1:-}"
  if [[ -n "$requested" ]]; then
    printf '%s' "$requested"
    return 0
  fi

  local from_state=""
  from_state="$(read_state_field '.adb.serial' || true)"
  if [[ -n "$from_state" ]]; then
    printf '%s' "$from_state"
    return 0
  fi

  local detected
  detected="$($ADB_BIN -H "$ADB_HOST" -P "$ADB_PORT" devices | awk '/\tdevice$/{print $1; exit}')"
  if [[ -z "$detected" ]]; then
    echo "ERROR: no online adb device found on $ADB_HOST:$ADB_PORT" >&2
    exit 1
  fi
  printf '%s' "$detected"
}

adb_device() {
  local serial="$1"
  shift
  "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" "$@"
}

archive_current_if_present() {
  if [[ ! -d "$CURRENT_DIR" ]]; then
    return 0
  fi
  if [[ -z "$(find "$CURRENT_DIR" -mindepth 1 -maxdepth 1 2>/dev/null)" ]]; then
    return 0
  fi

  local run_id
  run_id="$(read_state_field '.runId' || true)"
  if [[ -z "$run_id" ]]; then
    run_id="orphan_$(timestamp_compact_utc)"
  fi

  local target="$ARCHIVE_DIR/$run_id"
  if [[ -e "$target" ]]; then
    target="$ARCHIVE_DIR/${run_id}_$(timestamp_compact_utc)"
  fi

  mv "$CURRENT_DIR" "$target"
}

write_state_file() {
  local run_id="$1"
  local profile="$2"
  local serial="$3"
  local scope_mode="$4"
  local target_host_family="$5"

  local manufacturer model sdk release fingerprint
  manufacturer="$(adb_device "$serial" shell getprop ro.product.manufacturer | tr -d '\r' | xargs || true)"
  model="$(adb_device "$serial" shell getprop ro.product.model | tr -d '\r' | xargs || true)"
  sdk="$(adb_device "$serial" shell getprop ro.build.version.sdk | tr -d '\r' | xargs || true)"
  release="$(adb_device "$serial" shell getprop ro.build.version.release | tr -d '\r' | xargs || true)"
  fingerprint="$(adb_device "$serial" shell getprop ro.build.fingerprint | tr -d '\r' | xargs || true)"

  jq -n \
    --arg run_id "$run_id" \
    --arg profile "$profile" \
    --arg started_at "$(timestamp_utc)" \
    --arg adb_host "$ADB_HOST" \
    --arg adb_port "$ADB_PORT" \
    --arg serial "$serial" \
    --arg app_id "$APP_ID" \
    --arg main_activity "$MAIN_ACTIVITY" \
    --arg manufacturer "$manufacturer" \
    --arg model "$model" \
    --arg sdk "$sdk" \
    --arg release "$release" \
    --arg fingerprint "$fingerprint" \
    --arg action "$RUNTIME_TOOLKIT_ACTION" \
    --arg receiver "$RUNTIME_TOOLKIT_RECEIVER" \
    --arg scope_mode "$scope_mode" \
    --arg target_host_family "$target_host_family" \
    '{
      schemaVersion: "1.0",
      runId: $run_id,
      profile: $profile,
      startedAtUtc: $started_at,
      adb: {
        host: $adb_host,
        port: $adb_port,
        serial: $serial
      },
      app: {
        appId: $app_id,
        mainActivity: $main_activity,
        receiverAction: $action,
        receiverComponent: $receiver
      },
      device: {
        manufacturer: $manufacturer,
        model: $model,
        sdk: $sdk,
        release: $release,
        fingerprint: $fingerprint
      },
      paths: {
        currentRoot: "logs/device/mapper-toolkit/current",
        archiveRoot: "logs/device/mapper-toolkit/archive"
      },
      sessions: {
        logcat: "mapper_toolkit_logcat"
      },
      runtime: {
        scopeMode: $scope_mode,
        targetHostFamily: $target_host_family,
        missionId: ""
      }
    }' >"$STATE_FILE"
}

quoted_cmd() {
  local rendered
  printf -v rendered '%q ' "$@"
  printf '%s' "$rendered"
}

start_logcat_lane() {
  local serial="$1"
  local out_file="$CURRENT_DIR/lanes/logcat_events.log"

  if tmux has-session -t "$SESSION_LOGCAT" 2>/dev/null; then
    tmux kill-session -t "$SESSION_LOGCAT" || true
  fi

  local cmd=(
    "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" logcat -v threadtime PIPELAB/RTK:D '*:S'
  )

  tmux new-session -d -s "$SESSION_LOGCAT" "$(quoted_cmd "${cmd[@]}") >> $(printf '%q' "$out_file")"
}

stop_logcat_lane() {
  if tmux has-session -t "$SESSION_LOGCAT" 2>/dev/null; then
    tmux kill-session -t "$SESSION_LOGCAT" || true
  fi
}

runtime_broadcast() {
  local serial="$1"
  local op="$2"
  shift 2

  local cmd=(
    "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" shell am broadcast
    -a "$RUNTIME_TOOLKIT_ACTION"
    -n "$RUNTIME_TOOLKIT_RECEIVER"
    --es op "$op"
  )

  while [[ $# -gt 0 ]]; do
    local key="$1"
    local value="$2"
    shift 2
    cmd+=(--es "$key" "$value")
  done

  local out
  out="$(${cmd[@]} 2>&1 || true)"

  if printf '%s' "$out" | rg -q "Error:|Exception"; then
    echo "WARN: runtime broadcast '$op' failed: $out" >&2
    return 1
  fi

  if printf '%s' "$out" | rg -q "Broadcast completed"; then
    return 0
  fi

  echo "WARN: runtime broadcast '$op' inconclusive: $out" >&2
  return 1
}

pull_runtime_events() {
  local serial="$1"
  local target="$CURRENT_DIR/events/runtime_events.jsonl"
  : >"$target"

  if adb_device "$serial" shell "run-as $APP_ID test -f files/runtime-toolkit/events/runtime_events.jsonl" >/dev/null 2>&1; then
    adb_device "$serial" shell "run-as $APP_ID cat files/runtime-toolkit/events/runtime_events.jsonl" >"$target" || true
  fi

  if [[ ! -s "$target" ]]; then
    local lane="$CURRENT_DIR/lanes/logcat_events.log"
    if [[ -f "$lane" ]]; then
      rg 'PIPELAB_EVT' "$lane" | sed -E 's/^.*PIPELAB_EVT //' >"$target" || true
    fi
  fi

  if [[ ! -s "$target" ]]; then
    echo "WARN: no runtime events captured yet" >&2
  fi
}

pull_response_store() {
  local serial="$1"
  local target_dir="$CURRENT_DIR/response_store"
  mkdir -p "$target_dir"

  local -a entries=()
  mapfile -t entries < <(
    adb_device "$serial" shell "run-as $APP_ID sh -lc 'cd files/runtime-toolkit/response_store 2>/dev/null && ls -1 || true'" 2>/dev/null \
      | tr -d '\r' \
      | tr '\0' '\n'
  )

  local total="${#entries[@]}"
  local i=0
  local use_timeout=0
  if command -v timeout >/dev/null 2>&1; then
    use_timeout=1
  fi

  for entry in "${entries[@]}"; do
    i=$((i + 1))
    [[ -n "$entry" ]] || continue
    if [[ ! "$entry" =~ ^[A-Za-z0-9._-]+$ ]]; then
      continue
    fi

    local out_file="$target_dir/$entry"
    if [[ "$use_timeout" -eq 1 ]]; then
      if ! timeout --foreground 20s \
        "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" \
        shell "run-as $APP_ID cat files/runtime-toolkit/response_store/$entry" < /dev/null >"$out_file"
      then
        echo "WARN: response_store pull timeout/failed for $entry; skipped" >&2
        rm -f "$out_file"
        continue
      fi
    else
      if ! adb_device "$serial" shell "run-as $APP_ID cat files/runtime-toolkit/response_store/$entry" < /dev/null >"$out_file"; then
        echo "WARN: response_store pull failed for $entry; skipped" >&2
        rm -f "$out_file"
        continue
      fi
    fi

    if (( i % 50 == 0 || i == total )); then
      echo "response_store pull progress: $i/$total"
    fi
  done
}

run_dataset_cli() {
  require_file "$DATASET_CLI"
  local has_runtime_dir=0
  for arg in "$@"; do
    if [[ "$arg" == "--runtime-dir" ]]; then
      has_runtime_dir=1
      break
    fi
  done

  if [[ "$has_runtime_dir" -eq 1 ]]; then
    python3 "$DATASET_CLI" "$@"
  else
    python3 "$DATASET_CLI" "$@" --runtime-dir "$CURRENT_DIR"
  fi
}

json_non_empty() {
  local file="$1"
  local jq_filter="$2"
  [[ -s "$file" ]] || return 1
  jq -e "$jq_filter" "$file" >/dev/null 2>&1
}

update_latest_json_if_valid() {
  local source="$1"
  local target="$2"
  local jq_filter="$3"

  if [[ ! -f "$source" ]]; then
    return 0
  fi

  if ! json_non_empty "$source" "$jq_filter"; then
    echo "WARN: skip latest update for $target (invalid or empty source: $source)" >&2
    return 0
  fi

  mkdir -p "$(dirname "$target")"
  local tmp="${target}.tmp.$$"
  cp "$source" "$tmp"
  mv "$tmp" "$target"
}

update_latest_jsonl_if_valid() {
  local source="$1"
  local target="$2"
  if [[ ! -f "$source" ]]; then
    return 0
  fi
  if [[ ! -s "$source" ]] || [[ "$(wc -l < "$source" | xargs)" -eq 0 ]]; then
    echo "WARN: skip latest update for $target (empty jsonl source: $source)" >&2
    return 0
  fi
  mkdir -p "$(dirname "$target")"
  local tmp="${target}.tmp.$$"
  cp "$source" "$tmp"
  mv "$tmp" "$target"
}

finalize_snapshot_artifacts() {
  local quality_report="$CURRENT_DIR/rollups/pipeline_ready_report.json"
  if ! run_dataset_cli housekeeping validate >"$quality_report"; then
    echo "WARN: pipeline-ready validation failed; latest artifacts remain unchanged." >&2
    return 0
  fi

  local stage_dir="$CURRENT_DIR/latest.stage.$$"
  local final_dir="$CURRENT_DIR/latest"
  rm -rf "$stage_dir"
  mkdir -p "$stage_dir"

  if [[ -d "$final_dir" ]]; then
    cp -a "$final_dir/." "$stage_dir/" 2>/dev/null || true
  fi

  update_latest_jsonl_if_valid \
    "$CURRENT_DIR/events.jsonl" \
    "$stage_dir/events_latest.jsonl"
  update_latest_jsonl_if_valid \
    "$CURRENT_DIR/requests.normalized.jsonl" \
    "$stage_dir/requests_normalized_latest.jsonl"
  update_latest_jsonl_if_valid \
    "$CURRENT_DIR/responses.normalized.jsonl" \
    "$stage_dir/responses_normalized_latest.jsonl"
  update_latest_jsonl_if_valid \
    "$CURRENT_DIR/correlation_index.jsonl" \
    "$stage_dir/correlation_index_latest.jsonl"
  update_latest_json_if_valid \
    "$CURRENT_DIR/field_matrix.json" \
    "$stage_dir/field_matrix_latest.json" \
    '.fields | type == "array" and length > 0'
  update_latest_json_if_valid \
    "$CURRENT_DIR/required_headers_report.json" \
    "$stage_dir/required_headers_latest.json" \
    '.hosts | type == "object" and length > 0'
  update_latest_json_if_valid \
    "$CURRENT_DIR/required_cookies_report.json" \
    "$stage_dir/required_cookies_latest.json" \
    '.hosts | type == "object"'
  update_latest_json_if_valid \
    "$CURRENT_DIR/replay_requirements.json" \
    "$stage_dir/replay_requirements_latest.json" \
    '.operations | type == "object"'
  update_latest_json_if_valid \
    "$CURRENT_DIR/endpoint_candidates.json" \
    "$stage_dir/endpoint_candidates_latest.json" \
    '.candidates | type == "array" and length > 0'
  update_latest_json_if_valid \
    "$CURRENT_DIR/replay_seed.json" \
    "$stage_dir/replay_seed_latest.json" \
    '.steps | type == "array" and length > 0'
  update_latest_json_if_valid \
    "$CURRENT_DIR/cookie_timeline.json" \
    "$stage_dir/cookie_timeline_latest.json" \
    '.timeline | type == "array"'
  update_latest_json_if_valid \
    "$CURRENT_DIR/site_profile.draft.json" \
    "$stage_dir/site_profile_latest.json" \
    '.endpoint_candidates | type == "array"'
  update_latest_json_if_valid \
    "$CURRENT_DIR/profile_candidate.json" \
    "$stage_dir/profile_candidate_latest.json" \
    '.endpoint_candidates | type == "array"'
  update_latest_json_if_valid \
    "$CURRENT_DIR/provenance_graph.json" \
    "$stage_dir/provenance_graph_latest.json" \
    '.edge_count >= 0'
  update_latest_json_if_valid \
    "$CURRENT_DIR/provenance_registry.json" \
    "$stage_dir/provenance_registry_latest.json" \
    '.entry_count >= 0'
  update_latest_json_if_valid \
    "$CURRENT_DIR/response_index.json" \
    "$stage_dir/response_index_latest.json" \
    '.item_count >= 0'
  update_latest_json_if_valid \
    "$CURRENT_DIR/mission_export_summary.json" \
    "$stage_dir/mission_export_summary_latest.json" \
    '.export_readiness | type == "string"'

  local backup_dir="$CURRENT_DIR/latest.backup.$$"
  if [[ -d "$final_dir" ]]; then
    mv "$final_dir" "$backup_dir"
  fi
  mv "$stage_dir" "$final_dir"
  rm -rf "$backup_dir"
}

session_start() {
  local profile="full"
  local requested_device=""
  local scope_mode="strict_target"
  local target_host_family=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --profile)
        profile="$2"
        shift 2
        ;;
      --scope-mode)
        scope_mode="$2"
        shift 2
        ;;
      --target-host-family)
        target_host_family="$2"
        shift 2
        ;;
      --device)
        requested_device="$2"
        shift 2
        ;;
      *)
        echo "ERROR: unknown arg for session start: $1" >&2
        exit 1
        ;;
    esac
  done

  local serial
  serial="$(resolve_device_serial "$requested_device")"

  archive_current_if_present
  ensure_layout

  local run_id
  run_id="mapper_$(timestamp_compact_utc)"
  write_state_file "$run_id" "$profile" "$serial" "$scope_mode" "$target_host_family"

  runtime_broadcast \
    "$serial" \
    session_start \
    runId "$run_id" \
    traceId "$run_id" \
    scopeMode "$scope_mode" \
    targetHostFamily "$target_host_family"
  start_logcat_lane "$serial"

  echo "session started"
  echo "run_id=$run_id"
  echo "serial=$serial"
  echo "scope_mode=$scope_mode"
  echo "target_host_family=$target_host_family"
}

session_stop() {
  local requested_device=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --device)
        requested_device="$2"
        shift 2
        ;;
      *)
        echo "ERROR: unknown arg for session stop: $1" >&2
        exit 1
        ;;
    esac
  done

  local serial
  serial="$(resolve_device_serial "$requested_device")"

  runtime_broadcast "$serial" session_stop || true
  stop_logcat_lane

  echo "session stopped"
}

session_status() {
  if [[ -f "$STATE_FILE" ]]; then
    jq . "$STATE_FILE"
  else
    echo "no active runtime state file"
  fi

  if tmux has-session -t "$SESSION_LOGCAT" 2>/dev/null; then
    echo "lane.logcat=running"
  else
    echo "lane.logcat=stopped"
  fi

  local events_file="$CURRENT_DIR/events/runtime_events.jsonl"
  if [[ -f "$events_file" ]]; then
    echo "events.count=$(wc -l < "$events_file" | xargs)"
  fi
}

session_resume() {
  local target="home"
  local requested_device=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --target)
        target="$2"
        shift 2
        ;;
      --device)
        requested_device="$2"
        shift 2
        ;;
      *)
        echo "ERROR: unknown arg for session resume: $1" >&2
        exit 1
        ;;
    esac
  done

  local serial
  serial="$(resolve_device_serial "$requested_device")"

  if ! runtime_broadcast "$serial" open_screen screen "$target"; then
    adb_device "$serial" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  fi

  echo "resume -> $target"
}

doctor() {
  require_cmd "$ADB_BIN"
  require_cmd jq
  require_cmd python3

  echo "mapper-toolkit doctor"
  echo "adb_endpoint=$ADB_HOST:$ADB_PORT"
  echo "app_id=$APP_ID"
  echo "receiver=$RUNTIME_TOOLKIT_RECEIVER"
  echo

  "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" devices -l || true

  local serial
  serial="$(resolve_device_serial "" || true)"
  if [[ -n "$serial" ]]; then
    runtime_broadcast "$serial" ping || true
  fi
}

connect_device() {
  local device=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --device)
        device="$2"
        shift 2
        ;;
      *)
        echo "ERROR: unknown arg for connect: $1" >&2
        exit 1
        ;;
    esac
  done

  if [[ -z "$device" ]]; then
    echo "ERROR: connect requires --device <serial|host:port>" >&2
    exit 1
  fi

  if [[ "$device" == *:* ]]; then
    "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" connect "$device" >/dev/null || true
  fi

  "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" devices -l
}

session_cmd() {
  local sub="${1:-}"
  shift || true

  case "$sub" in
    start) session_start "$@" ;;
    stop) session_stop "$@" ;;
    status) session_status "$@" ;;
    doctor) doctor ;;
    resume) session_resume "$@" ;;
    *)
      echo "ERROR: unknown session subcommand '$sub'" >&2
      exit 1
      ;;
  esac
}

wizard_cmd() {
  local sub="${1:-}"
  shift || true

  local serial
  serial="$(resolve_device_serial "")"

  case "$sub" in
    start)
      local mission_id="FISHIT_PIPELINE"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --mission-id)
            mission_id="$2"
            shift 2
            ;;
          --device)
            serial="$(resolve_device_serial "$2")"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for wizard start: $1" >&2
            exit 1
            ;;
        esac
      done
      runtime_broadcast "$serial" wizard_start missionId "$mission_id"
      if [[ -f "$STATE_FILE" ]]; then
        local tmp="${STATE_FILE}.tmp.$$"
        jq --arg mission_id "$mission_id" '.runtime.missionId = $mission_id' "$STATE_FILE" >"$tmp" && mv "$tmp" "$STATE_FILE"
      fi
      ;;
    status)
      runtime_broadcast "$serial" wizard_status
      ;;
    set-target-url)
      local url="${1:-}"
      if [[ -z "$url" ]]; then
        echo "ERROR: wizard set-target-url requires <url>" >&2
        exit 1
      fi
      runtime_broadcast "$serial" wizard_set_target_url url "$url"
      ;;
    next-step)
      runtime_broadcast "$serial" wizard_next_step
      ;;
    retry-step)
      runtime_broadcast "$serial" wizard_retry_step
      ;;
    skip-optional-step)
      runtime_broadcast "$serial" wizard_skip_optional_step
      ;;
    finish)
      local saturation_state="SATURATED"
      local mission_id
      mission_id="$(read_state_field '.runtime.missionId' || true)"
      mission_id="${mission_id:-FISHIT_PIPELINE}"
      local broadcast_only=0
      local pipeline_status="IN_PROGRESS"
      local pipeline_reason=""
      local stage_records='[]'
      local pipeline_started_at
      pipeline_started_at="$(timestamp_utc)"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --saturation-state)
            saturation_state="$2"
            shift 2
            ;;
          --mission-id)
            mission_id="$2"
            shift 2
            ;;
          --broadcast-only)
            broadcast_only=1
            shift 1
            ;;
          --device)
            serial="$(resolve_device_serial "$2")"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for wizard finish: $1" >&2
            exit 1
            ;;
        esac
      done
      ensure_layout
      local pipeline_status_file="$CURRENT_DIR/rollups/wizard_finish_pipeline.json"
      local quality_report="$CURRENT_DIR/rollups/pipeline_ready_report.json"
      local reindex_report="$CURRENT_DIR/rollups/reindex.log.json"
      local mission_summary_report="$CURRENT_DIR/rollups/mission_export_summary.log.json"
      local had_failures=0
      local validate_rc=0

      _wizard_finish_write_status() {
        local finished_at
        finished_at="$(timestamp_utc)"
        local run_id
        run_id="$(read_state_field '.runId' || true)"
        jq -n \
          --arg schema_version "1" \
          --arg run_id "${run_id:-unknown_run}" \
          --arg mission_id "$mission_id" \
          --arg started_at "$pipeline_started_at" \
          --arg finished_at "$finished_at" \
          --arg status "$pipeline_status" \
          --arg reason "$pipeline_reason" \
          --arg quality_report "$quality_report" \
          --arg reindex_report "$reindex_report" \
          --arg mission_summary_report "$mission_summary_report" \
          --argjson stages "$stage_records" \
          '{
            schema_version: ($schema_version | tonumber),
            run_id: $run_id,
            mission_id: $mission_id,
            started_at_utc: $started_at,
            updated_at_utc: $finished_at,
            pipeline_status: $status,
            pipeline_reason: $reason,
            stages: $stages,
            reports: {
              reindex: $reindex_report,
              validate: $quality_report,
              mission_summary: $mission_summary_report
            }
          }' >"$pipeline_status_file"
      }

      _wizard_finish_add_stage() {
        local stage_name="$1"
        local stage_status="$2"
        local stage_rc="$3"
        local stage_reason="${4:-}"
        stage_records="$(jq -c \
          --arg stage "$stage_name" \
          --arg status "$stage_status" \
          --argjson rc "$stage_rc" \
          --arg reason "$stage_reason" \
          --arg ts "$(timestamp_utc)" \
          '. + [{stage:$stage,status:$status,rc:$rc,reason:$reason,ts_utc:$ts}]' <<<"$stage_records")"
        _wizard_finish_write_status
      }

      _wizard_finish_run_stage() {
        local stage_name="$1"
        local fail_reason="$2"
        shift 2
        set +e
        "$@"
        local rc=$?
        set -e
        if [[ "$rc" -eq 0 ]]; then
          _wizard_finish_add_stage "$stage_name" "SUCCESS" "$rc" ""
          return 0
        fi
        had_failures=1
        if [[ -z "$pipeline_reason" ]]; then
          pipeline_reason="$fail_reason"
        fi
        _wizard_finish_add_stage "$stage_name" "FAILED" "$rc" "$fail_reason"
        return "$rc"
      }

      _wizard_finish_write_status

      set +e
      _wizard_finish_run_stage \
        "broadcast_wizard_finish" \
        "broadcast_failed" \
        runtime_broadcast "$serial" wizard_finish saturationState "$saturation_state" missionId "$mission_id"
      local broadcast_rc=$?
      set -e
      if [[ "$broadcast_rc" -ne 0 ]]; then
        pipeline_status="BLOCKED"
        _wizard_finish_write_status
        echo "WARN: wizard finish blocked at broadcast stage." >&2
        return "$broadcast_rc"
      fi
      if [[ -f "$STATE_FILE" ]]; then
        local tmp="${STATE_FILE}.tmp.$$"
        jq --arg mission_id "$mission_id" '.runtime.missionId = $mission_id' "$STATE_FILE" >"$tmp" && mv "$tmp" "$STATE_FILE"
      fi
      if [[ "$broadcast_only" -eq 1 ]]; then
        pipeline_status="SUCCESS"
        pipeline_reason="broadcast_only"
        _wizard_finish_write_status
        return 0
      fi

      _wizard_finish_run_stage \
        "pull_runtime_events" \
        "pull_runtime_events_failed" \
        pull_runtime_events "$serial" || true

      _wizard_finish_run_stage \
        "pull_response_store" \
        "pull_response_store_failed" \
        pull_response_store "$serial" || true

      _wizard_finish_run_stage \
        "housekeeping_reindex" \
        "reindex_failed" \
        run_dataset_cli housekeeping reindex --mission-id "$mission_id" >"$reindex_report" || true

      set +e
      run_dataset_cli housekeeping validate --mission-id "$mission_id" >"$quality_report"
      validate_rc=$?
      set -e
      if [[ "$validate_rc" -eq 0 ]]; then
        _wizard_finish_add_stage "housekeeping_validate" "SUCCESS" "$validate_rc" ""
      else
        had_failures=1
        if [[ "$validate_rc" -eq 2 ]]; then
          pipeline_reason="pipeline_not_ready"
          _wizard_finish_add_stage "housekeeping_validate" "BLOCKED" "$validate_rc" "pipeline_not_ready"
        else
          if [[ -z "$pipeline_reason" ]]; then
            pipeline_reason="validate_failed"
          fi
          _wizard_finish_add_stage "housekeeping_validate" "FAILED" "$validate_rc" "validate_failed"
        fi
      fi

      _wizard_finish_run_stage \
        "housekeeping_mission_summary" \
        "mission_summary_not_ready" \
        run_dataset_cli housekeeping mission-summary --mission-id "$mission_id" --strict-readiness >"$mission_summary_report" || true

      finalize_snapshot_artifacts

      if [[ "$had_failures" -eq 0 ]]; then
        pipeline_status="SUCCESS"
        pipeline_reason=""
      else
        pipeline_status="BLOCKED"
        if [[ -z "$pipeline_reason" ]]; then
          pipeline_reason="wizard_finish_stage_failure"
        fi
      fi
      _wizard_finish_write_status

      if [[ "$pipeline_status" != "SUCCESS" ]]; then
        echo "WARN: wizard finish blocked (${pipeline_reason}). See $pipeline_status_file" >&2
        if [[ "$validate_rc" -ne 0 ]]; then
          return "$validate_rc"
        fi
        return 1
      fi
      echo "wizard finish finalized: $CURRENT_DIR"
      echo "wizard finish pipeline status: $pipeline_status_file"
      ;;
    *)
      echo "ERROR: unknown wizard subcommand '$sub'" >&2
      exit 1
      ;;
  esac
}

scope_cmd() {
  local sub="${1:-}"
  shift || true

  case "$sub" in
    set)
      local mode="strict_target"
      local hosts=""
      local requested_device=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --mode)
            mode="$2"
            shift 2
            ;;
          --target-host-family|--hosts)
            hosts="$2"
            shift 2
            ;;
          --device)
            requested_device="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for scope set: $1" >&2
            exit 1
            ;;
        esac
      done

      local serial
      serial="$(resolve_device_serial "$requested_device")"
      runtime_broadcast "$serial" set_scope scopeMode "$mode" targetHostFamily "$hosts"

      if [[ -f "$STATE_FILE" ]]; then
        local tmp="${STATE_FILE}.tmp.$$"
        jq --arg mode "$mode" --arg hosts "$hosts" '.runtime.scopeMode = $mode | .runtime.targetHostFamily = $hosts' "$STATE_FILE" >"$tmp" && mv "$tmp" "$STATE_FILE"
      fi

      echo "scope updated"
      echo "mode=$mode"
      echo "target_host_family=$hosts"
      ;;
    show)
      if [[ -f "$STATE_FILE" ]]; then
        jq '.runtime // {}' "$STATE_FILE"
      else
        echo "{}"
      fi
      ;;
    *)
      echo "ERROR: unknown scope subcommand '$sub'" >&2
      exit 1
      ;;
  esac
}

probe_cmd() {
  local sub="${1:-}"
  shift || true

  case "$sub" in
    start-phase)
      local phase_id="${1:-}"
      local transition="start"
      local requested_device=""
      local -a allowed_phases=("home_probe" "search_probe" "detail_probe" "playback_probe" "auth_probe" "background_noise")
      if [[ -z "$phase_id" ]]; then
        echo "ERROR: probe start-phase requires <phase_id>" >&2
        exit 1
      fi
      local valid_phase=0
      for allowed in "${allowed_phases[@]}"; do
        if [[ "$phase_id" == "$allowed" ]]; then
          valid_phase=1
          break
        fi
      done
      if [[ "$valid_phase" -ne 1 ]]; then
        echo "ERROR: invalid phase_id '$phase_id' (allowed: ${allowed_phases[*]})" >&2
        exit 1
      fi
      shift || true
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --transition)
            transition="$2"
            shift 2
            ;;
          --device)
            requested_device="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for probe start-phase: $1" >&2
            exit 1
            ;;
        esac
      done
      local serial
      serial="$(resolve_device_serial "$requested_device")"
      runtime_broadcast "$serial" set_probe_phase phaseId "$phase_id" transition "$transition"
      if [[ -f "$STATE_FILE" ]]; then
        local tmp="${STATE_FILE}.tmp.$$"
        jq --arg phase "$phase_id" '.runtime.activePhaseId = $phase' "$STATE_FILE" >"$tmp" && mv "$tmp" "$STATE_FILE"
      fi
      echo "probe phase set: phase_id=$phase_id transition=$transition"
      ;;
    stop-phase)
      local requested_device=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --device)
            requested_device="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for probe stop-phase: $1" >&2
            exit 1
            ;;
        esac
      done
      local serial
      serial="$(resolve_device_serial "$requested_device")"
      runtime_broadcast "$serial" clear_probe_phase
      if [[ -f "$STATE_FILE" ]]; then
        local tmp="${STATE_FILE}.tmp.$$"
        jq '.runtime.activePhaseId = "background_noise"' "$STATE_FILE" >"$tmp" && mv "$tmp" "$STATE_FILE"
      fi
      echo "probe phase cleared"
      ;;
    status)
      if [[ -f "$STATE_FILE" ]]; then
        jq '.runtime // {}' "$STATE_FILE"
      else
        echo "{}"
      fi
      ;;
    *)
      echo "ERROR: unknown probe subcommand '$sub'" >&2
      exit 1
      ;;
  esac
}

provenance_cmd() {
  local sub="${1:-}"
  shift || true

  case "$sub" in
    mark)
      local entity_type="custom"
      local entity_key=""
      local produced_by=""
      local consumed_by=""
      local derived_from=""
      local requested_device=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --entity-type)
            entity_type="$2"
            shift 2
            ;;
          --entity-key)
            entity_key="$2"
            shift 2
            ;;
          --produced-by)
            produced_by="$2"
            shift 2
            ;;
          --consumed-by)
            consumed_by="$2"
            shift 2
            ;;
          --derived-from)
            derived_from="$2"
            shift 2
            ;;
          --device)
            requested_device="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for provenance mark: $1" >&2
            exit 1
            ;;
        esac
      done
      if [[ -z "$entity_key" ]]; then
        echo "ERROR: provenance mark requires --entity-key" >&2
        exit 1
      fi
      local serial
      serial="$(resolve_device_serial "$requested_device")"
      runtime_broadcast \
        "$serial" \
        mark_provenance \
        entityType "$entity_type" \
        entityKey "$entity_key" \
        producedBy "$produced_by" \
        consumedBy "$consumed_by" \
        derivedFrom "$derived_from"
      ;;
    query|graph|export)
      ensure_layout
      run_dataset_cli provenance "$sub" "$@"
      ;;
    *)
      echo "ERROR: unknown provenance subcommand '$sub'" >&2
      exit 1
      ;;
  esac
}

ui_cmd() {
  local sub="${1:-}"
  shift || true

  local serial
  serial="$(resolve_device_serial "")"

  case "$sub" in
    open-screen)
      local screen="${1:-home}"
      runtime_broadcast "$serial" open_screen screen "$screen"
      ;;
    open-url)
      local url="${1:-}"
      if [[ -z "$url" ]]; then
        echo "ERROR: ui open-url requires <url>" >&2
        exit 1
      fi
      runtime_broadcast "$serial" open_url url "$url"
      ;;
    reset-site-state)
      local url="${1:-}"
      if [[ -z "$url" ]]; then
        echo "ERROR: ui reset-site-state requires <url>" >&2
        exit 1
      fi
      runtime_broadcast "$serial" reset_site_state url "$url"
      ;;
    open-detail)
      local work_key=""
      local title=""
      local source_type="UNKNOWN"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --work-key)
            work_key="$2"
            shift 2
            ;;
          --title)
            title="$2"
            shift 2
            ;;
          --source-type)
            source_type="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for ui open-detail: $1" >&2
            exit 1
            ;;
        esac
      done
      runtime_broadcast "$serial" open_detail workKey "$work_key" title "$title" sourceType "$source_type"
      ;;
    tap)
      require_file "$UI_SCRIPT"
      runtime_broadcast "$serial" mark_ui_start actionName "adb_tap" screen "browser" || true
      "$UI_SCRIPT" tap "$@"
      runtime_broadcast "$serial" mark_ui_end actionName "adb_tap" result "ok" || true
      ;;
    flow-run)
      local flow_script="${1:-}"
      if [[ -z "$flow_script" ]]; then
        echo "ERROR: ui flow-run requires <script>" >&2
        exit 1
      fi
      shift || true
      if [[ ! -f "$flow_script" ]]; then
        echo "ERROR: flow script not found: $flow_script" >&2
        exit 1
      fi
      bash "$flow_script" "$@"
      ;;
    anchor-scan)
      require_file "$UI_SCRIPT"
      "$UI_SCRIPT" clickables "$@"
      ;;
    anchor-create)
      local name=""
      local anchor_type="custom"
      local url=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --name)
            name="$2"
            shift 2
            ;;
          --type)
            anchor_type="$2"
            shift 2
            ;;
          --url)
            url="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for ui anchor-create: $1" >&2
            exit 1
            ;;
        esac
      done
      runtime_broadcast "$serial" anchor_create anchorName "$name" anchorType "$anchor_type" url "$url"
      ;;
    anchor-label)
      local anchor_id=""
      local name=""
      local anchor_type=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --anchor-id)
            anchor_id="$2"
            shift 2
            ;;
          --name)
            name="$2"
            shift 2
            ;;
          --type)
            anchor_type="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for ui anchor-label: $1" >&2
            exit 1
            ;;
        esac
      done
      if [[ -z "$anchor_id" ]]; then
        echo "ERROR: ui anchor-label requires --anchor-id" >&2
        exit 1
      fi
      runtime_broadcast "$serial" anchor_label anchorId "$anchor_id" anchorName "$name" anchorType "$anchor_type"
      ;;
    anchor-remove)
      local anchor_id=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --anchor-id)
            anchor_id="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for ui anchor-remove: $1" >&2
            exit 1
            ;;
        esac
      done
      if [[ -z "$anchor_id" ]]; then
        echo "ERROR: ui anchor-remove requires --anchor-id" >&2
        exit 1
      fi
      runtime_broadcast "$serial" anchor_remove anchorId "$anchor_id"
      ;;
    *)
      echo "ERROR: unknown ui subcommand '$sub'" >&2
      exit 1
      ;;
  esac
}

replay_cmd() {
  local sub="${1:-}"
  shift || true

  case "$sub" in
    native-request)
      local url=""
      local method="GET"
      local headers_input=""
      local query=""
      local body_input=""
      local content_type=""
      local timeout_sec="20"
      local follow_redirects="yes"
      local requested_device=""

      while [[ $# -gt 0 ]]; do
        case "$1" in
          --url)
            url="$2"
            shift 2
            ;;
          --method)
            method="$2"
            shift 2
            ;;
          --headers)
            headers_input="$2"
            shift 2
            ;;
          --query)
            query="$2"
            shift 2
            ;;
          --body)
            body_input="$2"
            shift 2
            ;;
          --content-type)
            content_type="$2"
            shift 2
            ;;
          --timeout)
            timeout_sec="$2"
            shift 2
            ;;
          --follow-redirects)
            follow_redirects="$2"
            shift 2
            ;;
          --device)
            requested_device="$2"
            shift 2
            ;;
          *)
            echo "ERROR: unknown arg for replay native-request: $1" >&2
            exit 1
            ;;
        esac
      done

      if [[ -z "$url" ]]; then
        echo "ERROR: replay native-request requires --url" >&2
        exit 1
      fi

      local serial
      serial="$(resolve_device_serial "$requested_device")"

      local headers_payload=""
      if [[ -n "$headers_input" ]]; then
        if [[ -f "$headers_input" ]]; then
          headers_payload="$(cat "$headers_input")"
        else
          headers_payload="$headers_input"
        fi
      fi

      local body_payload=""
      if [[ -n "$body_input" ]]; then
        if [[ -f "$body_input" ]]; then
          body_payload="$(cat "$body_input")"
        else
          body_payload="$body_input"
        fi
      fi
      local body_b64=""
      if [[ -n "$body_payload" ]]; then
        body_b64="$(printf '%s' "$body_payload" | base64 | tr -d '\n')"
      fi

      local timeout_ms
      timeout_ms=$((timeout_sec * 1000))
      local redirect_policy="follow"
      if [[ "$follow_redirects" == "no" ]]; then
        redirect_policy="no_follow"
      fi

      runtime_broadcast \
        "$serial" \
        native_replay_request \
        url "$url" \
        method "$method" \
        headers "$headers_payload" \
        queryParams "$query" \
        bodyBase64 "$body_b64" \
        contentType "$content_type" \
        timeoutMs "$timeout_ms" \
        redirectPolicy "$redirect_policy"
      ;;
    *)
      ensure_layout
      run_dataset_cli replay "$sub" "$@"
      ;;
  esac
}

capture_cmd() {
  local sub="${1:-}"
  shift || true

  case "$sub" in
    start)
      session_start "$@"
      ;;
    stop)
      session_stop "$@"
      ;;
    snapshot)
      local serial
      serial="$(resolve_device_serial "")"
      ensure_layout
      pull_runtime_events "$serial"
      pull_response_store "$serial"
      run_dataset_cli housekeeping reindex
      finalize_snapshot_artifacts
      echo "snapshot captured: $CURRENT_DIR"
      ;;
    lanes)
      tmux ls 2>/dev/null | rg 'mapper_toolkit_|mapper_mitm' || true
      ;;
    mitm)
      require_file "$MITM_SCRIPT"
      "$MITM_SCRIPT" "$@"
      ;;
    *)
      echo "ERROR: unknown capture subcommand '$sub'" >&2
      exit 1
      ;;
  esac
}

main() {
  local cmd="${1:-}"
  if [[ -z "$cmd" ]]; then
    usage
    exit 1
  fi
  shift || true

  maybe_restore_adb_endpoint_from_state

  case "$cmd" in
    bootstrap)
      require_file "$BOOTSTRAP_SCRIPT"
      "$BOOTSTRAP_SCRIPT" "$@"
      ;;
    tui)
      if [[ "${1:-}" == "--legacy" ]]; then
        require_file "$LEGACY_TUI_SCRIPT"
        "$LEGACY_TUI_SCRIPT"
        exit 0
      fi

      require_cmd python3
      require_file "$PYTHON_TUI_SCRIPT"

      if python3 "$PYTHON_TUI_SCRIPT"; then
        exit 0
      fi

      echo "WARN: Python TUI failed, falling back to legacy dialog TUI." >&2
      require_file "$LEGACY_TUI_SCRIPT"
      "$LEGACY_TUI_SCRIPT"
      ;;
    connect)
      connect_device "$@"
      ;;
    doctor)
      doctor
      ;;

    session)
      session_cmd "$@"
      ;;
    wizard)
      wizard_cmd "$@"
      ;;
    scope)
      scope_cmd "$@"
      ;;
    probe)
      probe_cmd "$@"
      ;;
    provenance)
      provenance_cmd "$@"
      ;;
    ui)
      ui_cmd "$@"
      ;;
    capture)
      capture_cmd "$@"
      ;;

    replay)
      replay_cmd "$@"
      ;;

    trace|triage|cookies|headers|responses|mapping|housekeeping)
      ensure_layout
      run_dataset_cli "$cmd" "$@"
      ;;

    help|-h|--help)
      usage
      ;;
    *)
      echo "ERROR: unknown command '$cmd'" >&2
      usage
      exit 1
      ;;
  esac
}

main "$@"
