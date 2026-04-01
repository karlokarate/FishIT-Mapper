#!/usr/bin/env bash
set -euo pipefail

SELF_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

ADB_BIN="${ADB_BIN:-adb}"
ADB_HOST="${ADB_HOST:-127.0.0.1}"
ADB_PORT="${ADB_PORT:-5037}"
APP_ID="${APP_ID:-dev.fishit.mapper.wave01}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-dev.fishit.mapper.wave01/info.plateaukao.einkbro.activity.BrowserActivity}"
PROFILE_DEFAULT="${RUNTIME_TOOLKIT_PROFILE:-full}"
UI_EVIDENCE_MODE_DEFAULT="${UI_EVIDENCE_MODE_DEFAULT:-off}"

TOOLKIT_ROOT="${ROOT_DIR}/logs/device/runtime-toolkit"
CURRENT_DIR="${TOOLKIT_ROOT}/current"
ARCHIVE_DIR="${TOOLKIT_ROOT}/archive"
CONTRACT_FILE="${ROOT_DIR}/scripts/device/toolkit/runtime_toolkit.contract.json"
EVENT_INDEXER="${ROOT_DIR}/scripts/device/toolkit/runtime_event_indexer.py"
MATRIX_BUILDER="${ROOT_DIR}/scripts/device/toolkit/build_command_matrix.py"
UI_ANCHOR_NAV="${ROOT_DIR}/scripts/device/ui-anchor-nav.sh"

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  if [[ -x "/home/vscode/.android-sdk/platform-tools/adb" ]]; then
    ADB_BIN="/home/vscode/.android-sdk/platform-tools/adb"
  fi
fi

STATE_FILE="${CURRENT_DIR}/runtime_state.json"
RUNTIME_HEALTH_JSON="${CURRENT_DIR}/rollups/runtime_health.json"
RUNTIME_SYNC_JSON="${CURRENT_DIR}/rollups/runtime_sync_summary.json"
RUNTIME_PERF_JSON="${CURRENT_DIR}/rollups/runtime_perf_summary.json"

SESSION_LOGCAT="fishit_rt_logcat"
SESSION_GUARD="fishit_rt_guard"
SESSION_WATCHDOG="fishit_rt_watchdog"
SESSION_PERF="fishit_rt_perf"
SESSION_PARSER="fishit_rt_parser"

EXPECTED_SESSIONS=(
  "$SESSION_LOGCAT"
  "$SESSION_GUARD"
  "$SESSION_WATCHDOG"
  "$SESSION_PERF"
  "$SESSION_PARSER"
)

mkdir -p "$TOOLKIT_ROOT" "$ARCHIVE_DIR"

timestamp_utc() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

timestamp_compact_utc() {
  date -u +%Y%m%dT%H%M%SZ
}

require_cmd() {
  local name="$1"
  if ! command -v "$name" >/dev/null 2>&1; then
    echo "ERROR: missing required command '$name'" >&2
    exit 1
  fi
}

tmux_has_session() {
  local session="$1"
  tmux has-session -t "$session" 2>/dev/null
}

quoted_cmd() {
  local rendered
  printf -v rendered '%q ' "$@"
  printf '%s' "$rendered"
}

read_state_field() {
  local field="$1"
  if [[ ! -f "$STATE_FILE" ]]; then
    return 1
  fi
  jq -r "$field // empty" "$STATE_FILE"
}

resolve_device_serial() {
  local requested="${1:-}"
  if [[ -n "$requested" ]]; then
    printf '%s' "$requested"
    return 0
  fi
  local detected
  detected="$($ADB_BIN -H "$ADB_HOST" -P "$ADB_PORT" devices | awk '/\tdevice$/{print $1; exit}')"
  if [[ -z "$detected" ]]; then
    echo "ERROR: no online device via adb -H $ADB_HOST -P $ADB_PORT" >&2
    exit 1
  fi
  printf '%s' "$detected"
}

adb_device() {
  local serial="$1"
  shift
  "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" "$@"
}

ensure_layout() {
  mkdir -p \
    "$CURRENT_DIR/logcat/chunks" \
    "$CURRENT_DIR/lanes" \
    "$CURRENT_DIR/perf" \
    "$CURRENT_DIR/events" \
    "$CURRENT_DIR/rollups" \
    "$CURRENT_DIR/guard" \
    "$CURRENT_DIR/snapshots" \
    "$CURRENT_DIR/tmp"
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

  local archive_target="$ARCHIVE_DIR/$run_id"
  if [[ -e "$archive_target" ]]; then
    archive_target="$ARCHIVE_DIR/${run_id}_$(timestamp_compact_utc)"
  fi

  mkdir -p "$ARCHIVE_DIR"
  mv "$CURRENT_DIR" "$archive_target"
  mkdir -p "$CURRENT_DIR"
}

validate_ui_evidence_mode() {
  local mode="$1"
  case "$mode" in
    off|minimal|full) return 0 ;;
    *)
      echo "ERROR: unsupported --ui-evidence-mode '$mode' (allowed: off|minimal|full)" >&2
      exit 1
      ;;
  esac
}

resolve_ui_evidence_mode() {
  local requested="${1:-}"
  local mode="$requested"
  if [[ -z "$mode" ]]; then
    mode="$(read_state_field '.uiEvidenceMode' || true)"
  fi
  if [[ -z "$mode" ]]; then
    mode="$UI_EVIDENCE_MODE_DEFAULT"
  fi
  validate_ui_evidence_mode "$mode"
  printf '%s' "$mode"
}

write_state_file() {
  local run_id="$1"
  local profile="$2"
  local serial="$3"
  local ui_evidence_mode="$4"

  jq -n \
    --arg run_id "$run_id" \
    --arg profile "$profile" \
    --arg ui_evidence_mode "$ui_evidence_mode" \
    --arg started_at "$(timestamp_utc)" \
    --arg adb_host "$ADB_HOST" \
    --arg adb_port "$ADB_PORT" \
    --arg adb_serial "$serial" \
    --arg app_id "$APP_ID" \
    --arg main_activity "$MAIN_ACTIVITY" \
    --arg logcat_session "$SESSION_LOGCAT" \
    --arg guard_session "$SESSION_GUARD" \
    --arg watchdog_session "$SESSION_WATCHDOG" \
    --arg perf_session "$SESSION_PERF" \
    --arg parser_session "$SESSION_PARSER" \
    '{
      schemaVersion: "1.0",
      runId: $run_id,
      profile: $profile,
      uiEvidenceMode: $ui_evidence_mode,
      startedAtUtc: $started_at,
      adb: {
        host: $adb_host,
        port: $adb_port,
        serial: $adb_serial
      },
      app: {
        appId: $app_id,
        mainActivity: $main_activity
      },
      sessions: {
        logcat: $logcat_session,
        guard: $guard_session,
        watchdog: $watchdog_session,
        perf: $perf_session,
        parser: $parser_session
      },
      paths: {
        currentRoot: "logs/device/runtime-toolkit/current",
        archiveRoot: "logs/device/runtime-toolkit/archive"
      }
    }' >"$STATE_FILE"
}

spawn_lane_session() {
  local lane="$1"
  local session="$2"
  local profile="$3"
  local serial="$4"

  if tmux_has_session "$session"; then
    return 0
  fi

  local cmd=(
    "$SELF_PATH"
    "__lane"
    "--lane" "$lane"
    "--profile" "$profile"
    "--device" "$serial"
  )

  tmux new-session -d -s "$session" "$(quoted_cmd "${cmd[@]}")"
}

ensure_all_lanes_running() {
  local profile="$1"
  local serial="$2"

  spawn_lane_session "logcat" "$SESSION_LOGCAT" "$profile" "$serial"
  spawn_lane_session "guard" "$SESSION_GUARD" "$profile" "$serial"
  spawn_lane_session "perf" "$SESSION_PERF" "$profile" "$serial"
  spawn_lane_session "parser" "$SESSION_PARSER" "$profile" "$serial"
  spawn_lane_session "watchdog" "$SESSION_WATCHDOG" "$profile" "$serial"
}

stop_all_lanes() {
  local session
  for session in "${EXPECTED_SESSIONS[@]}"; do
    if tmux_has_session "$session"; then
      tmux kill-session -t "$session" || true
    fi
  done
}

write_runtime_health() {
  local serial="$1"
  local adb_state top_activity app_pid
  adb_state="$($ADB_BIN -H "$ADB_HOST" -P "$ADB_PORT" get-state 2>/dev/null || true)"
  top_activity="$(adb_device "$serial" shell dumpsys activity activities 2>/dev/null | rg -m 1 "topResumedActivity|mResumedActivity" || true)"
  app_pid="$(adb_device "$serial" shell pidof "$APP_ID" 2>/dev/null || true)"

  local tmp_json
  tmp_json="$(mktemp)"
  jq -n \
    --arg generated_at "$(timestamp_utc)" \
    --arg run_id "$(read_state_field '.runId' || true)" \
    --arg adb_state "$adb_state" \
    --arg top_activity "$top_activity" \
    --arg app_pid "$app_pid" \
    --arg app_id "$APP_ID" \
    --arg profile "$(read_state_field '.profile' || true)" \
    --argjson sessions "$(lane_status_json)" \
    '{
      schemaVersion: "1.0",
      generatedAtUtc: $generated_at,
      runId: $run_id,
      profile: $profile,
      adbState: ($adb_state | if . == "" then "unknown" else . end),
      app: {
        appId: $app_id,
        pid: ($app_pid | if . == "" then null else . end),
        topResumedActivity: ($top_activity | if . == "" then null else . end)
      },
      sessions: $sessions
    }' >"$tmp_json"

  mv "$tmp_json" "$RUNTIME_HEALTH_JSON"
}

lane_status_json() {
  local items=()
  local session
  for session in "${EXPECTED_SESSIONS[@]}"; do
    local status="stopped"
    if tmux_has_session "$session"; then
      status="running"
    fi
    items+=("{\"session\":\"$session\",\"status\":\"$status\"}")
  done
  printf '[%s]' "$(IFS=,; echo "${items[*]}")"
}

print_status() {
  local serial="${1:-}"
  local run_id profile ui_evidence_mode
  run_id="$(read_state_field '.runId' || true)"
  profile="$(read_state_field '.profile' || true)"
  ui_evidence_mode="$(read_state_field '.uiEvidenceMode' || true)"
  if [[ -z "$ui_evidence_mode" ]]; then
    ui_evidence_mode="$UI_EVIDENCE_MODE_DEFAULT"
  fi

  echo "FishIT Runtime Toolkit"
  echo "  current_root: $CURRENT_DIR"
  echo "  run_id: ${run_id:-<none>}"
  echo "  profile: ${profile:-<none>}"
  echo "  ui_evidence_mode: ${ui_evidence_mode:-<none>}"
  if [[ -n "$serial" ]]; then
    echo "  device: $serial"
  fi

  local session
  for session in "${EXPECTED_SESSIONS[@]}"; do
    if tmux_has_session "$session"; then
      echo "  lane[$session]: running"
    else
      echo "  lane[$session]: stopped"
    fi
  done

  if [[ -f "$RUNTIME_HEALTH_JSON" ]]; then
    echo "  health: $RUNTIME_HEALTH_JSON"
  fi
}

parse_bool() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) echo "true" ;;
    0|false|FALSE|no|NO|off|OFF) echo "false" ;;
    *) echo "" ;;
  esac
}

resume_target() {
  local target="$1"
  local work_key="$2"
  local source_type="$3"
  local title="$4"
  local setting_key="$5"
  local setting_value="$6"

  case "$target" in
    ""|screen)
      if [[ -n "$title" ]]; then
        "$UI_ANCHOR_NAV" open-screen "$title"
      else
        "$UI_ANCHOR_NAV" open-screen home
      fi
      ;;
    detail)
      if [[ -n "$work_key" ]]; then
        "$UI_ANCHOR_NAV" open-detail --work-key "$work_key" --source-type "$source_type"
      elif [[ -n "$title" ]]; then
        "$UI_ANCHOR_NAV" open-detail --title "$title"
      else
        echo "ERROR: resume detail requires --work-key or --title" >&2
        exit 1
      fi
      ;;
    setting-op)
      if [[ -z "$setting_key" || -z "$setting_value" ]]; then
        echo "ERROR: resume setting-op requires --setting-key and --value" >&2
        exit 1
      fi
      "$UI_ANCHOR_NAV" set-setting --key "$setting_key" --value "$setting_value"
      ;;
    home|library|settings|search|movies|series|live|refresh)
      "$UI_ANCHOR_NAV" open-screen "$target"
      ;;
    *)
      echo "ERROR: unsupported resume target '$target'" >&2
      exit 1
      ;;
  esac
}

run_lane_logcat() {
  local serial="$1"
  local lane_log="$CURRENT_DIR/lanes/logcat.log"
  mkdir -p "$CURRENT_DIR/logcat/chunks"
  echo "[$(timestamp_utc)] lane=logcat start serial=$serial" >>"$lane_log"

  while true; do
    local ts file
    ts="$(timestamp_compact_utc)"
    file="$CURRENT_DIR/logcat/chunks/logcat_${ts}.txt"
    ln -sfn "chunks/$(basename "$file")" "$CURRENT_DIR/logcat/current.log"
    echo "[$(timestamp_utc)] chunk_start file=$file" >>"$lane_log"

    # Rotation-by-time (10m) keeps chunks bounded and resilient.
    timeout --signal=INT 600 "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" logcat -v threadtime >>"$file" 2>>"$lane_log" || true

    echo "[$(timestamp_utc)] chunk_end file=$file" >>"$lane_log"
    sleep 1
  done
}

run_lane_guard() {
  local serial="$1"
  local lane_log="$CURRENT_DIR/lanes/guard.log"
  local heartbeat_json="$CURRENT_DIR/guard/heartbeat.json"
  local history_tsv="$CURRENT_DIR/guard/heartbeat.tsv"

  echo "[$(timestamp_utc)] lane=guard start serial=$serial" >>"$lane_log"
  if [[ ! -f "$history_tsv" ]]; then
    echo -e "timestamp_utc\tadb_state\tapp_pid\ttop_resumed_activity" >"$history_tsv"
  fi

  while true; do
    local ts adb_state app_pid top_activity
    ts="$(timestamp_utc)"
    adb_state="$($ADB_BIN -H "$ADB_HOST" -P "$ADB_PORT" get-state 2>/dev/null || true)"
    app_pid="$(adb_device "$serial" shell pidof "$APP_ID" 2>/dev/null || true)"
    top_activity="$(adb_device "$serial" shell dumpsys activity activities 2>/dev/null | rg -m 1 'topResumedActivity|mResumedActivity' || true)"

    jq -n \
      --arg ts "$ts" \
      --arg adb_state "$adb_state" \
      --arg app_pid "$app_pid" \
      --arg top_activity "$top_activity" \
      '{
        schemaVersion: "1.0",
        timestampUtc: $ts,
        adbState: ($adb_state | if . == "" then "unknown" else . end),
        appPid: ($app_pid | if . == "" then null else . end),
        topResumedActivity: ($top_activity | if . == "" then null else . end)
      }' >"$heartbeat_json"

    printf "%s\t%s\t%s\t%s\n" "$ts" "${adb_state:-unknown}" "${app_pid:-}" "${top_activity:-}" >>"$history_tsv"
    sleep 5
  done
}

run_lane_watchdog() {
  local serial="$1"
  local profile="$2"
  local lane_log="$CURRENT_DIR/lanes/watchdog.log"
  echo "[$(timestamp_utc)] lane=watchdog start serial=$serial profile=$profile" >>"$lane_log"

  while true; do
    "$SELF_PATH" __ensure-lanes --profile "$profile" --device "$serial" >>"$lane_log" 2>&1 || true
    sleep 15
  done
}

run_lane_perf() {
  local serial="$1"
  local lane_log="$CURRENT_DIR/lanes/perf.log"
  echo "[$(timestamp_utc)] lane=perf start serial=$serial" >>"$lane_log"

  while true; do
    local ts base
    ts="$(timestamp_compact_utc)"
    base="$CURRENT_DIR/perf/${ts}"

    adb_device "$serial" shell dumpsys meminfo "$APP_ID" >"${base}_meminfo.txt" 2>>"$lane_log" || true
    adb_device "$serial" shell dumpsys gfxinfo "$APP_ID" framestats >"${base}_gfxinfo.txt" 2>>"$lane_log" || true
    adb_device "$serial" shell top -b -n 1 -o PID,CPU,RES,ARGS >"${base}_top.txt" 2>>"$lane_log" || true

    jq -n \
      --arg ts "$(timestamp_utc)" \
      --arg base "$(basename "$base")" \
      '{timestampUtc:$ts, sampleBase:$base}' >"${base}_meta.json"

    sleep 30
  done
}

run_lane_parser() {
  local serial="$1"
  local lane_log="$CURRENT_DIR/lanes/parser.log"
  echo "[$(timestamp_utc)] lane=parser start serial=$serial" >>"$lane_log"

  while true; do
    python3 "$EVENT_INDEXER" \
      --runtime-dir "$CURRENT_DIR" \
      --app-id "$APP_ID" \
      --adb-host "$ADB_HOST" \
      --adb-port "$ADB_PORT" \
      --device "$serial" >>"$lane_log" 2>&1 || true

    sleep 12
  done
}

lane_dispatch() {
  local lane="$1"
  local profile="$2"
  local serial="$3"
  ensure_layout

  case "$lane" in
    logcat) run_lane_logcat "$serial" ;;
    guard) run_lane_guard "$serial" ;;
    watchdog) run_lane_watchdog "$serial" "$profile" ;;
    perf) run_lane_perf "$serial" ;;
    parser) run_lane_parser "$serial" ;;
    *)
      echo "ERROR: unknown lane '$lane'" >&2
      exit 1
      ;;
  esac
}

run_doctor() {
  local serial="$1"
  ensure_layout
  write_runtime_health "$serial"

  echo "Doctor checks"
  local adb_state
  adb_state="$($ADB_BIN -H "$ADB_HOST" -P "$ADB_PORT" get-state 2>/dev/null || true)"
  echo "  adb_state: ${adb_state:-unknown}"

  local top
  top="$(adb_device "$serial" shell dumpsys activity activities 2>/dev/null | rg -m 1 'topResumedActivity|mResumedActivity' || true)"
  echo "  top_resumed_activity: ${top:-<none>}"

  local session
  for session in "${EXPECTED_SESSIONS[@]}"; do
    if tmux_has_session "$session"; then
      echo "  lane[$session]: ok"
    else
      echo "  lane[$session]: missing"
    fi
  done

  if [[ -f "$RUNTIME_SYNC_JSON" ]]; then
    echo "  sync_summary: present"
  else
    echo "  sync_summary: missing"
  fi

  if [[ -f "$RUNTIME_PERF_JSON" ]]; then
    echo "  perf_summary: present"
  else
    echo "  perf_summary: missing"
  fi

  echo "  health_json: $RUNTIME_HEALTH_JSON"
}

run_snapshot() {
  local serial="$1"
  local ui_evidence_mode="${2:-off}"
  local with_screenshot="${3:-false}"
  local with_perfetto="${4:-false}"
  ensure_layout

  local stamp out_dir
  stamp="$(timestamp_compact_utc)"
  out_dir="$CURRENT_DIR/snapshots/snapshot_${stamp}"
  mkdir -p "$out_dir"

  adb_device "$serial" shell dumpsys activity activities >"$out_dir/dumpsys_activity_activities.txt" 2>/dev/null || true
  adb_device "$serial" shell dumpsys window windows >"$out_dir/dumpsys_window_windows.txt" 2>/dev/null || true
  adb_device "$serial" shell dumpsys meminfo "$APP_ID" >"$out_dir/dumpsys_meminfo_app.txt" 2>/dev/null || true
  adb_device "$serial" shell pm path "$APP_ID" >"$out_dir/pm_path.txt" 2>/dev/null || true
  adb_device "$serial" shell pidof "$APP_ID" >"$out_dir/pidof.txt" 2>/dev/null || true

  # Debug builds allow run-as for app-internal paths.
  adb_device "$serial" shell run-as "$APP_ID" ls -al >"$out_dir/run_as_root_ls.txt" 2>/dev/null || true
  adb_device "$serial" shell run-as "$APP_ID" ls -al databases >"$out_dir/run_as_databases_ls.txt" 2>/dev/null || true
  adb_device "$serial" shell run-as "$APP_ID" ls -al cache >"$out_dir/run_as_cache_ls.txt" 2>/dev/null || true
  adb_device "$serial" shell run-as "$APP_ID" ls -al shared_prefs >"$out_dir/run_as_shared_prefs_ls.txt" 2>/dev/null || true
  adb_device "$serial" shell run-as "$APP_ID" du -h cache files databases shared_prefs >"$out_dir/run_as_du.txt" 2>/dev/null || true

  if [[ "$ui_evidence_mode" != "off" && -x "$UI_ANCHOR_NAV" ]]; then
    local ui_tag ui_xml
    ui_tag="snapshot_${stamp}"
    ui_xml="$("$UI_ANCHOR_NAV" dump --screen "$ui_tag" 2>/dev/null || true)"
    if [[ -n "$ui_xml" && -f "$ui_xml" ]]; then
      cp -f "$ui_xml" "$out_dir/ui_hierarchy.xml" 2>/dev/null || true
    fi
    "$UI_ANCHOR_NAV" clickables --screen "$ui_tag" --output "$out_dir/ui_clickables.json" >"$out_dir/ui_clickables_capture.log" 2>&1 || true
    if [[ "$ui_evidence_mode" == "full" ]]; then
      "$UI_ANCHOR_NAV" anchors --screen "$ui_tag" >"$out_dir/ui_anchors.tsv" 2>"$out_dir/ui_anchors.stderr" || true
    fi
  fi

  if [[ "$with_screenshot" == "true" ]]; then
    adb_device "$serial" exec-out screencap -p >"$out_dir/screenshot.png" 2>/dev/null || true
  fi

  if [[ "$with_perfetto" == "true" ]]; then
    local remote_trace config_file perfetto_stamp
    perfetto_stamp="$(timestamp_compact_utc)"
    remote_trace="/data/misc/perfetto-traces/fishit_runtime_${perfetto_stamp}.pftrace"
    config_file="$out_dir/perfetto_config.pbtxt"
    cat >"$config_file" <<'EOF'
buffers: { size_kb: 8192 fill_policy: RING_BUFFER }
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "view"
      atrace_categories: "gfx"
      atrace_categories: "input"
      atrace_apps: "dev.fishit.mapper.wave01"
    }
  }
}
duration_ms: 8000
write_into_file: true
EOF

    if adb_device "$serial" shell command -v perfetto >/dev/null 2>&1; then
      timeout --signal=INT 30 "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" shell perfetto --txt -c - -o "$remote_trace" <"$config_file" >"$out_dir/perfetto_stdout.log" 2>"$out_dir/perfetto_stderr.log" || true
      adb_device "$serial" shell cat "$remote_trace" >"$out_dir/perfetto_trace.pftrace" 2>"$out_dir/perfetto_pull.log" || true
      adb_device "$serial" shell rm -f "$remote_trace" >/dev/null 2>&1 || true
      if [[ ! -s "$out_dir/perfetto_trace.pftrace" ]]; then
        rm -f "$out_dir/perfetto_trace.pftrace"
        echo "perfetto capture failed or empty trace output" >"$out_dir/perfetto_unavailable.txt"
      fi
    else
      echo "perfetto binary not available on device" >"$out_dir/perfetto_unavailable.txt"
    fi
  fi

  write_runtime_health "$serial"
  cp -f "$RUNTIME_HEALTH_JSON" "$out_dir/runtime_health.json" 2>/dev/null || true
  cp -f "$RUNTIME_SYNC_JSON" "$out_dir/runtime_sync_summary.json" 2>/dev/null || true
  cp -f "$RUNTIME_PERF_JSON" "$out_dir/runtime_perf_summary.json" 2>/dev/null || true

  echo "$out_dir"
}

pull_app_inspect_artifacts() {
  local serial="$1"
  local out_dir="$2"
  mkdir -p "$out_dir"

  adb_device "$serial" shell run-as "$APP_ID" cat files/runtime-toolkit/inspect-work/latest.meta.json >"$out_dir/latest.meta.json" 2>/dev/null || true
  adb_device "$serial" shell run-as "$APP_ID" cat files/runtime-toolkit/inspect-work/latest.graph.json >"$out_dir/latest.graph.json" 2>/dev/null || true
}

pull_app_query_entities_artifacts() {
  local serial="$1"
  local out_dir="$2"
  mkdir -p "$out_dir"

  adb_device "$serial" shell run-as "$APP_ID" cat files/runtime-toolkit/query-entities/latest.meta.json >"$out_dir/latest.meta.json" 2>/dev/null || true
  adb_device "$serial" shell run-as "$APP_ID" cat files/runtime-toolkit/query-entities/latest.json >"$out_dir/latest.json" 2>/dev/null || true
}

extract_meta_field() {
  local file="$1"
  local jq_expr="$2"
  if [[ -f "$file" ]]; then
    jq -r "$jq_expr // empty" "$file" 2>/dev/null || true
  fi
}

collect_work_log_trace() {
  local work_key="$1"
  local title="$2"
  local out_dir="$3"
  local source_id="${4:-}"
  local chat_id="${5:-}"
  local attempt_id="${6:-}"
  local trace_file="$out_dir/log_trace.txt"
  local summary_file="$out_dir/log_trace_summary.json"
  local correlation_json="$out_dir/log_correlation_window.json"
  local -a candidates=()
  local -a latest_chunks=()

  : >"$trace_file"

  mapfile -t latest_chunks < <(ls -1t "$CURRENT_DIR"/logcat/chunks/logcat_*.txt 2>/dev/null | head -n 40 || true)
  if [[ "${#latest_chunks[@]}" -gt 0 ]]; then
    candidates+=("${latest_chunks[@]}")
  fi
  if [[ -f "$CURRENT_DIR/logcat/current.log" ]]; then
    candidates+=("$CURRENT_DIR/logcat/current.log")
  fi

  if [[ "${#candidates[@]}" -eq 0 ]]; then
    jq -n \
      --arg generated_at "$(timestamp_utc)" \
      --arg work_key "$work_key" \
      --arg title "$title" \
      --arg source_id "$source_id" \
      --arg chat_id "$chat_id" \
      --arg attempt_id "$attempt_id" \
      '{
        schemaVersion: "1.0",
        generatedAtUtc: $generated_at,
        query: {
          workKey: ($work_key | if . == "" then null else . end),
          title: ($title | if . == "" then null else . end),
          sourceId: ($source_id | if . == "" then null else . end),
          chatId: ($chat_id | if . == "" then null else . end),
          attemptId: ($attempt_id | if . == "" then null else . end)
        },
        scannedFiles: {
          candidateCount: 0,
          chunkCount: 0
        }
      }' >"$correlation_json"
    jq -n \
      --arg generated_at "$(timestamp_utc)" \
      '{
        schemaVersion: "1.0",
        generatedAtUtc: $generated_at,
        matchedLineCount: 0,
        countsByCategory: {
          join: 0,
          sync: 0,
          jank: 0,
          error: 0,
          other: 0
        },
        timelineHead: []
      }' >"$summary_file"
    return 0
  fi

  if [[ -n "$work_key" ]]; then
    timeout --signal=INT 10 rg -n --no-heading --fixed-strings "$work_key" "${candidates[@]}" 2>/dev/null >>"$trace_file" || true
  fi
  if [[ -n "$title" ]]; then
    timeout --signal=INT 10 rg -n --no-heading --fixed-strings "$title" "${candidates[@]}" 2>/dev/null >>"$trace_file" || true
  fi
  if [[ -n "$source_id" ]]; then
    timeout --signal=INT 10 rg -n --no-heading --fixed-strings "$source_id" "${candidates[@]}" 2>/dev/null >>"$trace_file" || true
  fi
  if [[ -n "$chat_id" ]]; then
    timeout --signal=INT 10 rg -n --no-heading --fixed-strings "$chat_id" "${candidates[@]}" 2>/dev/null >>"$trace_file" || true
  fi
  if [[ -n "$attempt_id" ]]; then
    timeout --signal=INT 10 rg -n --no-heading --fixed-strings "$attempt_id" "${candidates[@]}" 2>/dev/null >>"$trace_file" || true
  fi
  timeout --signal=INT 10 rg -n --no-heading "tg_join_|tg_scope_binding_|tg_postsync_|tg_detail_hydration_probe|JOIN_TARGETED_CHAT|intent_join_targeted_chat" "${candidates[@]}" 2>/dev/null >>"$trace_file" || true

  jq -n \
    --arg generated_at "$(timestamp_utc)" \
    --arg work_key "$work_key" \
    --arg title "$title" \
    --arg source_id "$source_id" \
    --arg chat_id "$chat_id" \
    --arg attempt_id "$attempt_id" \
    --argjson candidate_count "${#candidates[@]}" \
    --argjson chunk_count "${#latest_chunks[@]}" \
    '{
      schemaVersion: "1.0",
      generatedAtUtc: $generated_at,
      query: {
        workKey: ($work_key | if . == "" then null else . end),
        title: ($title | if . == "" then null else . end),
        sourceId: ($source_id | if . == "" then null else . end),
        chatId: ($chat_id | if . == "" then null else . end),
        attemptId: ($attempt_id | if . == "" then null else . end)
      },
      scannedFiles: {
        candidateCount: $candidate_count,
        chunkCount: $chunk_count
      }
    }' >"$correlation_json"

  python3 - "$trace_file" "$summary_file" <<'PY'
import json
import pathlib
import re
import sys

trace_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])

lines = trace_path.read_text(encoding="utf-8", errors="replace").splitlines()
lines = list(dict.fromkeys(lines))
trace_path.write_text("\\n".join(lines) + ("\\n" if lines else ""), encoding="utf-8")

categories = {"join": 0, "sync": 0, "jank": 0, "error": 0, "other": 0}
timeline = []

for row in lines:
    lower = row.lower()
    category = "other"
    if "tg_join_" in lower or "tg_scope_binding_" in lower or "tg_postsync_" in lower or "join_targeted_chat" in lower:
        category = "join"
    elif "sync" in lower or "workmanager" in lower:
        category = "sync"
    elif "ui_jank_frame" in lower or "jank" in lower:
        category = "jank"
    elif " error " in lower or "exception" in lower or " failed" in lower:
        category = "error"
    categories[category] += 1
    timeline.append({"category": category, "line": row})

payload = {
    "schemaVersion": "1.0",
    "matchedLineCount": len(lines),
    "countsByCategory": categories,
    "timelineHead": timeline[:200],
}
summary_path.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
PY
}

inspect_work_command() {
  local serial="$1"
  local profile="$2"
  local work_key="$3"
  local title="$4"
  local source_type="$5"
  local ui_evidence_mode="$6"
  local with_screenshot="$7"
  local with_perfetto="$8"

  if [[ -z "$work_key" && -z "$title" ]]; then
    echo "ERROR: inspect-work requires --work-key or --title" >&2
    exit 1
  fi

  if [[ ! -f "$STATE_FILE" ]]; then
    on_command "$profile" "$serial" "$ui_evidence_mode"
  fi

  serial="$(resolve_device_serial "${serial:-$(read_state_field '.adb.serial' || true)}")"

  local ts out_dir
  ts="$(timestamp_compact_utc)"
  out_dir="$CURRENT_DIR/snapshots/inspect_work_${ts}"
  mkdir -p "$out_dir"

  local inspect_cmd=("$UI_ANCHOR_NAV" inspect-work --source-type "$source_type")
  if [[ -n "$work_key" ]]; then
    inspect_cmd+=(--work-key "$work_key")
  else
    inspect_cmd+=(--title "$title")
  fi
  if ! timeout --signal=INT 90 "${inspect_cmd[@]}"; then
    echo "ERROR: inspect-work runtime command timed out (90s)" >&2
    exit 1
  fi
  sleep 1

  pull_app_inspect_artifacts "$serial" "$out_dir"

  local resolved_work_key resolved_title resolved_source_id resolved_chat_id resolved_attempt_id
  resolved_work_key="$(extract_meta_field "$out_dir/latest.meta.json" '.workKey')"
  resolved_title="$(extract_meta_field "$out_dir/latest.meta.json" '.matchedTitle')"
  resolved_source_id="$(extract_meta_field "$out_dir/latest.meta.json" '.sourceId')"
  resolved_chat_id="$(extract_meta_field "$out_dir/latest.meta.json" '.chatId')"
  resolved_attempt_id="$(extract_meta_field "$out_dir/latest.meta.json" '.attemptId')"
  if [[ -z "$resolved_work_key" ]]; then
    resolved_work_key="$work_key"
  fi
  if [[ -z "$title" && -n "$resolved_title" ]]; then
    title="$resolved_title"
  fi

  collect_work_log_trace "$resolved_work_key" "$title" "$out_dir" "$resolved_source_id" "$resolved_chat_id" "$resolved_attempt_id"

  if [[ "$ui_evidence_mode" != "off" && -x "$UI_ANCHOR_NAV" ]]; then
    local ui_tag ui_xml
    ui_tag="inspect_${ts}"
    ui_xml="$("$UI_ANCHOR_NAV" dump --screen "$ui_tag" 2>/dev/null || true)"
    if [[ -n "$ui_xml" && -f "$ui_xml" ]]; then
      cp -f "$ui_xml" "$out_dir/ui_hierarchy.xml" 2>/dev/null || true
    fi
    "$UI_ANCHOR_NAV" clickables --screen "$ui_tag" --output "$out_dir/ui_clickables.json" >"$out_dir/ui_clickables_capture.log" 2>&1 || true
    if [[ "$ui_evidence_mode" == "full" ]]; then
      "$UI_ANCHOR_NAV" anchors --screen "$ui_tag" >"$out_dir/ui_anchors.tsv" 2>"$out_dir/ui_anchors.stderr" || true
    fi
  fi

  if [[ "$with_screenshot" == "true" ]]; then
    adb_device "$serial" exec-out screencap -p >"$out_dir/screenshot.png" 2>/dev/null || true
  fi

  if [[ "$with_perfetto" == "true" ]]; then
    local remote_trace config_file perfetto_stamp
    perfetto_stamp="$(timestamp_compact_utc)"
    remote_trace="/data/misc/perfetto-traces/fishit_runtime_inspect_${perfetto_stamp}.pftrace"
    config_file="$out_dir/perfetto_config.pbtxt"
    cat >"$config_file" <<'EOF'
buffers: { size_kb: 8192 fill_policy: RING_BUFFER }
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "view"
      atrace_categories: "gfx"
      atrace_categories: "input"
      atrace_apps: "dev.fishit.mapper.wave01"
    }
  }
}
duration_ms: 8000
write_into_file: true
EOF
    if adb_device "$serial" shell command -v perfetto >/dev/null 2>&1; then
      timeout --signal=INT 30 "$ADB_BIN" -H "$ADB_HOST" -P "$ADB_PORT" -s "$serial" shell perfetto --txt -c - -o "$remote_trace" <"$config_file" >"$out_dir/perfetto_stdout.log" 2>"$out_dir/perfetto_stderr.log" || true
      adb_device "$serial" shell cat "$remote_trace" >"$out_dir/perfetto_trace.pftrace" 2>"$out_dir/perfetto_pull.log" || true
      adb_device "$serial" shell rm -f "$remote_trace" >/dev/null 2>&1 || true
      if [[ ! -s "$out_dir/perfetto_trace.pftrace" ]]; then
        rm -f "$out_dir/perfetto_trace.pftrace"
        echo "perfetto capture failed or empty trace output" >"$out_dir/perfetto_unavailable.txt"
      fi
    else
      echo "perfetto binary not available on device" >"$out_dir/perfetto_unavailable.txt"
    fi
  fi

  jq -n \
    --arg generated_at "$(timestamp_utc)" \
    --arg requested_work_key "$work_key" \
    --arg resolved_work_key "$resolved_work_key" \
    --arg requested_title "$title" \
    --arg source_type "$source_type" \
    --arg ui_evidence_mode "$ui_evidence_mode" \
    --arg with_screenshot "$with_screenshot" \
    --arg with_perfetto "$with_perfetto" \
    --arg meta_file "$out_dir/latest.meta.json" \
    --arg graph_file "$out_dir/latest.graph.json" \
    --arg trace_file "$out_dir/log_trace.txt" \
    --arg trace_summary "$out_dir/log_trace_summary.json" \
    --arg correlation_window "$out_dir/log_correlation_window.json" \
    --arg work_identity_signature "$(extract_meta_field "$out_dir/latest.meta.json" '.workIdentitySignature // empty')" \
    --arg db_entity_matches "$(extract_meta_field "$out_dir/latest.meta.json" '.dbEntityMatches // empty')" \
    --arg ui_command_ack "$(extract_meta_field "$out_dir/latest.meta.json" '.uiCommandAck // empty')" \
    '{
      schemaVersion: "1.0",
      generatedAtUtc: $generated_at,
      requested: {
        workKey: ($requested_work_key | if . == "" then null else . end),
        title: ($requested_title | if . == "" then null else . end),
        sourceType: $source_type
      },
      resolved: {
        workKey: ($resolved_work_key | if . == "" then null else . end)
      },
      work_identity_signature: ($work_identity_signature | if . == "" then null else (fromjson? // .) end),
      db_entity_matches: ($db_entity_matches | if . == "" then null else (fromjson? // .) end),
      ui_command_ack: ($ui_command_ack | if . == "" then null else (fromjson? // .) end),
      log_correlation_window: $correlation_window,
      uiEvidenceMode: $ui_evidence_mode,
      captureOptions: {
        screenshot: ($with_screenshot == "true"),
        perfetto: ($with_perfetto == "true")
      },
      artifacts: {
        meta: $meta_file,
        graph: $graph_file,
        logTrace: $trace_file,
        logTraceSummary: $trace_summary
      }
    }' >"$out_dir/inspect_manifest.json"

  echo "$out_dir"
}

query_entities_command() {
  local serial="$1"
  local profile="$2"
  local entity_type="$3"
  local filters_json="$4"
  local filters_file="$5"
  local limit="$6"
  local offset="$7"
  local scan_limit="$8"
  local page_size="$9"
  local search_query="${10}"
  local sort_field="${11}"
  local sort_ascending="${12}"

  if [[ ! -f "$STATE_FILE" ]]; then
    on_command "$profile" "$serial" "$UI_EVIDENCE_MODE_DEFAULT"
  fi

  serial="$(resolve_device_serial "${serial:-$(read_state_field '.adb.serial' || true)}")"

  local ts out_dir
  ts="$(timestamp_compact_utc)"
  out_dir="$CURRENT_DIR/snapshots/query_entities_${ts}"
  mkdir -p "$out_dir"

  local query_cmd=("$UI_ANCHOR_NAV" query-entities --entity-type "$entity_type")
  if [[ -n "$filters_json" ]]; then
    query_cmd+=(--filters-json "$filters_json")
  fi
  if [[ -n "$filters_file" ]]; then
    query_cmd+=(--filters-file "$filters_file")
  fi
  if [[ -n "$limit" ]]; then
    query_cmd+=(--limit "$limit")
  fi
  if [[ -n "$offset" ]]; then
    query_cmd+=(--offset "$offset")
  fi
  if [[ -n "$scan_limit" ]]; then
    query_cmd+=(--scan-limit "$scan_limit")
  fi
  if [[ -n "$page_size" ]]; then
    query_cmd+=(--page-size "$page_size")
  fi
  if [[ -n "$search_query" ]]; then
    query_cmd+=(--search-query "$search_query")
  fi
  if [[ -n "$sort_field" ]]; then
    query_cmd+=(--sort-field "$sort_field")
  fi
  if [[ -n "$sort_ascending" ]]; then
    query_cmd+=(--sort-ascending "$sort_ascending")
  fi

  if ! timeout --signal=INT 60 "${query_cmd[@]}" >"$out_dir/query_command.stdout.log" 2>"$out_dir/query_command.stderr.log"; then
    echo "ERROR: query-entities runtime command timed out (60s)" >&2
    exit 1
  fi
  sleep 1

  pull_app_query_entities_artifacts "$serial" "$out_dir"
  if [[ ! -s "$out_dir/latest.json" ]]; then
    echo "ERROR: query-entities produced no latest.json artifact" >&2
    exit 1
  fi

  jq -n \
    --arg generated_at "$(timestamp_utc)" \
    --arg entity_type "$entity_type" \
    --arg filters_json "$filters_json" \
    --arg filters_file "$filters_file" \
    --arg limit "$limit" \
    --arg offset "$offset" \
    --arg scan_limit "$scan_limit" \
    --arg page_size "$page_size" \
    --arg search_query "$search_query" \
    --arg sort_field "$sort_field" \
    --arg sort_ascending "$sort_ascending" \
    --arg latest_meta "$out_dir/latest.meta.json" \
    --arg latest_json "$out_dir/latest.json" \
    '{
      schemaVersion: "1.0",
      generatedAtUtc: $generated_at,
      request: {
        entityType: $entity_type,
        filtersJson: ($filters_json | if . == "" then null else . end),
        filtersFile: ($filters_file | if . == "" then null else . end),
        limit: ($limit | if . == "" then null else . end),
        offset: ($offset | if . == "" then null else . end),
        scanLimit: ($scan_limit | if . == "" then null else . end),
        pageSize: ($page_size | if . == "" then null else . end),
        searchQuery: ($search_query | if . == "" then null else . end),
        sortField: ($sort_field | if . == "" then null else . end),
        sortAscending: ($sort_ascending | if . == "" then null else . end)
      },
      artifacts: {
        latestMeta: $latest_meta,
        latest: $latest_json
      }
    }' >"$out_dir/query_manifest.json"

  echo "$out_dir"
}

on_command() {
  local profile="$1"
  local requested_serial="$2"
  local ui_evidence_mode="$3"

  require_cmd "$ADB_BIN"
  require_cmd tmux
  require_cmd jq
  require_cmd python3

  local serial
  serial="$(resolve_device_serial "$requested_serial")"

  archive_current_if_present
  ensure_layout

  local run_id
  run_id="runtime_$(timestamp_compact_utc)"
  validate_ui_evidence_mode "$ui_evidence_mode"
  write_state_file "$run_id" "$profile" "$serial" "$ui_evidence_mode"

  if [[ -f "$MATRIX_BUILDER" ]]; then
    python3 "$MATRIX_BUILDER" --repo-root "$ROOT_DIR" --output "$ROOT_DIR/scripts/device/anchors/command_matrix.latest.json" >/dev/null 2>&1 || true
  fi

  ensure_all_lanes_running "$profile" "$serial"
  write_runtime_health "$serial"

  echo "runtime_toolkit_on run_id=$run_id device=$serial profile=$profile ui_evidence_mode=$ui_evidence_mode"
  print_status "$serial"
}

off_command() {
  stop_all_lanes
  archive_current_if_present
  ensure_layout
  echo "runtime_toolkit_off"
}

status_command() {
  local serial
  serial="$(read_state_field '.adb.serial' || true)"
  print_status "$serial"
}

restart_command() {
  local profile="$1"
  local serial="$2"
  local ui_evidence_mode="$3"
  off_command
  on_command "$profile" "$serial" "$ui_evidence_mode"
}

resume_command() {
  local target="$1"
  local serial="$2"
  local work_key="$3"
  local source_type="$4"
  local title="$5"
  local setting_key="$6"
  local setting_value="$7"
  local profile="$8"
  local ui_evidence_mode="$9"
  local with_screenshot="${10}"
  local with_perfetto="${11}"

  if [[ ! -f "$STATE_FILE" ]]; then
    on_command "$profile" "$serial" "$ui_evidence_mode"
  fi

  serial="$(resolve_device_serial "${serial:-$(read_state_field '.adb.serial' || true)}")"
  ui_evidence_mode="$(resolve_ui_evidence_mode "$ui_evidence_mode")"
  adb_device "$serial" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 1

  resume_target "$target" "$work_key" "$source_type" "$title" "$setting_key" "$setting_value"

  local snapshot_dir
  snapshot_dir="$(run_snapshot "$serial" "$ui_evidence_mode" "$with_screenshot" "$with_perfetto")"
  echo "runtime_toolkit_resume target=${target:-screen} snapshot=$snapshot_dir ui_evidence_mode=$ui_evidence_mode"
}

ensure_lanes_command() {
  local profile="$1"
  local serial="$2"

  if [[ -z "$serial" ]]; then
    serial="$(read_state_field '.adb.serial' || true)"
  fi
  if [[ -z "$serial" ]]; then
    serial="$(resolve_device_serial "")"
  fi
  if [[ -z "$profile" ]]; then
    profile="$(read_state_field '.profile' || true)"
  fi
  if [[ -z "$profile" ]]; then
    profile="$PROFILE_DEFAULT"
  fi

  local restarted=0
  if ! tmux_has_session "$SESSION_LOGCAT"; then spawn_lane_session "logcat" "$SESSION_LOGCAT" "$profile" "$serial"; restarted=1; fi
  if ! tmux_has_session "$SESSION_GUARD"; then spawn_lane_session "guard" "$SESSION_GUARD" "$profile" "$serial"; restarted=1; fi
  if ! tmux_has_session "$SESSION_PERF"; then spawn_lane_session "perf" "$SESSION_PERF" "$profile" "$serial"; restarted=1; fi
  if ! tmux_has_session "$SESSION_PARSER"; then spawn_lane_session "parser" "$SESSION_PARSER" "$profile" "$serial"; restarted=1; fi

  # Watchdog lane is only restarted by external command to avoid recursive self-restart loops.
  if [[ "$restarted" -eq 1 ]]; then
    echo "[$(timestamp_utc)] watchdog restarted missing lane(s)" >&2
  fi
}

parse_common_runtime_opts() {
  local profile_ref="$1"
  local serial_ref="$2"
  shift 2

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --profile)
        printf -v "$profile_ref" '%s' "$2"
        shift 2
        ;;
      --device)
        printf -v "$serial_ref" '%s' "$2"
        shift 2
        ;;
      --adb-host)
        ADB_HOST="$2"
        shift 2
        ;;
      --adb-port)
        ADB_PORT="$2"
        shift 2
        ;;
      --app-id)
        APP_ID="$2"
        shift 2
        ;;
      *)
        break
        ;;
    esac
  done

  echo "$*"
}

usage() {
  cat <<'USAGE'
FishIT Runtime Toolkit

Usage:
  scripts/device/fishit-runtime-toolkit.sh on [--profile full] [--device <serial>] [--ui-evidence-mode <off|minimal|full>]
  scripts/device/fishit-runtime-toolkit.sh off
  scripts/device/fishit-runtime-toolkit.sh status
  scripts/device/fishit-runtime-toolkit.sh restart [--profile full] [--device <serial>] [--ui-evidence-mode <off|minimal|full>]
  scripts/device/fishit-runtime-toolkit.sh resume [--target <home|library|settings|search|movies|series|live|detail|setting-op>] [options]
  scripts/device/fishit-runtime-toolkit.sh inspect-work [--work-key <key> | --title <title>] [--source-type <TELEGRAM|XTREAM>] [--device <serial>]
  scripts/device/fishit-runtime-toolkit.sh query-entities [--entity-type <id|ALL|*>] [--filters-json <json> | --filters-file <path>] [--limit <n>] [--offset <n>] [--scan-limit <n>] [--page-size <n>] [--search-query <q>] [--sort-field <field>] [--sort-ascending <true|false>] [--device <serial>]
  scripts/device/fishit-runtime-toolkit.sh doctor [--device <serial>]
  scripts/device/fishit-runtime-toolkit.sh snapshot [--device <serial>] [--ui-evidence-mode <off|minimal|full>] [--with-screenshot] [--with-perfetto]

Resume options:
  --work-key <workKey>             For --target detail
  --source-type <TELEGRAM|XTREAM>  For --target detail (default TELEGRAM)
  --title <title-or-screen>        Detail fallback title or screen name for --target screen
  --setting-key <key>              For --target setting-op
  --value <value>                  For --target setting-op
  --with-screenshot                Optional PNG capture
  --with-perfetto                  Optional short perfetto trace capture

Inspect-work options:
  --work-key <key>                 Prefer deterministic work key
  --title <title>                  Fallback title search
  --source-type <TELEGRAM|XTREAM>  Detail route source type (default: TELEGRAM)
  --ui-evidence-mode <off|minimal|full> Optional screenshot-free UI capture mode
  --with-screenshot                Optional PNG capture
  --with-perfetto                  Optional short perfetto trace capture

Query-entities options:
  --entity-type <id|ALL|*>         Entity scope (default: NX_Work)
  --filters-json <json>            JSON filter payload
  --filters-file <path>            JSON filter payload file
  --limit <n>                      Max returned rows
  --offset <n>                     Offset after matches
  --scan-limit <n>                 Max scanned rows
  --page-size <n>                  DB inspector page size
  --search-query <q>               Optional pre-filter query
  --sort-field <field>             Optional sort field
  --sort-ascending <true|false>    Sort direction

Hidden:
  __lane --lane <logcat|guard|watchdog|perf|parser>
  __ensure-lanes
USAGE
}

main() {
  local cmd="${1:-}"
  if [[ -z "$cmd" ]]; then
    usage
    exit 1
  fi
  shift || true

  case "$cmd" in
    on)
      local profile="$PROFILE_DEFAULT"
      local serial=""
      local ui_evidence_mode="$UI_EVIDENCE_MODE_DEFAULT"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --profile) profile="$2"; shift 2 ;;
          --device) serial="$2"; shift 2 ;;
          --ui-evidence-mode) ui_evidence_mode="$2"; shift 2 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
        esac
      done
      on_command "$profile" "$serial" "$ui_evidence_mode"
      ;;

    off)
      off_command
      ;;

    status)
      status_command
      ;;

    restart)
      local profile="$PROFILE_DEFAULT"
      local serial=""
      local ui_evidence_mode="$UI_EVIDENCE_MODE_DEFAULT"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --profile) profile="$2"; shift 2 ;;
          --device) serial="$2"; shift 2 ;;
          --ui-evidence-mode) ui_evidence_mode="$2"; shift 2 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
        esac
      done
      restart_command "$profile" "$serial" "$ui_evidence_mode"
      ;;

    resume)
      local target="screen"
      local serial=""
      local work_key=""
      local source_type="TELEGRAM"
      local title=""
      local setting_key=""
      local setting_value=""
      local profile="$PROFILE_DEFAULT"
      local ui_evidence_mode=""
      local with_screenshot="false"
      local with_perfetto="false"

      while [[ $# -gt 0 ]]; do
        case "$1" in
          --target) target="$2"; shift 2 ;;
          --device) serial="$2"; shift 2 ;;
          --work-key) work_key="$2"; shift 2 ;;
          --source-type) source_type="$2"; shift 2 ;;
          --title) title="$2"; shift 2 ;;
          --setting-key) setting_key="$2"; shift 2 ;;
          --value) setting_value="$2"; shift 2 ;;
          --profile) profile="$2"; shift 2 ;;
          --ui-evidence-mode) ui_evidence_mode="$2"; shift 2 ;;
          --with-screenshot) with_screenshot="true"; shift 1 ;;
          --with-perfetto) with_perfetto="true"; shift 1 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
        esac
      done

      resume_command "$target" "$serial" "$work_key" "$source_type" "$title" "$setting_key" "$setting_value" "$profile" "$ui_evidence_mode" "$with_screenshot" "$with_perfetto"
      ;;

    inspect-work)
      local serial=""
      local profile="$PROFILE_DEFAULT"
      local work_key=""
      local title=""
      local source_type="TELEGRAM"
      local ui_evidence_mode=""
      local with_screenshot="false"
      local with_perfetto="false"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --device) serial="$2"; shift 2 ;;
          --profile) profile="$2"; shift 2 ;;
          --work-key) work_key="$2"; shift 2 ;;
          --title) title="$2"; shift 2 ;;
          --source-type) source_type="$2"; shift 2 ;;
          --ui-evidence-mode) ui_evidence_mode="$2"; shift 2 ;;
          --with-screenshot) with_screenshot="true"; shift 1 ;;
          --with-perfetto) with_perfetto="true"; shift 1 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
        esac
      done
      ui_evidence_mode="$(resolve_ui_evidence_mode "$ui_evidence_mode")"
      inspect_work_command "$serial" "$profile" "$work_key" "$title" "$source_type" "$ui_evidence_mode" "$with_screenshot" "$with_perfetto"
      ;;

    query-entities)
      local serial=""
      local profile="$PROFILE_DEFAULT"
      local entity_type="NX_Work"
      local filters_json=""
      local filters_file=""
      local limit=""
      local offset=""
      local scan_limit=""
      local page_size=""
      local search_query=""
      local sort_field=""
      local sort_ascending=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --device) serial="$2"; shift 2 ;;
          --profile) profile="$2"; shift 2 ;;
          --entity-type|--entity-type-id) entity_type="$2"; shift 2 ;;
          --filters-json) filters_json="$2"; shift 2 ;;
          --filters-file) filters_file="$2"; shift 2 ;;
          --limit) limit="$2"; shift 2 ;;
          --offset) offset="$2"; shift 2 ;;
          --scan-limit) scan_limit="$2"; shift 2 ;;
          --page-size) page_size="$2"; shift 2 ;;
          --search-query) search_query="$2"; shift 2 ;;
          --sort-field) sort_field="$2"; shift 2 ;;
          --sort-ascending) sort_ascending="$2"; shift 2 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
        esac
      done
      if [[ -n "$filters_json" && -n "$filters_file" ]]; then
        echo "ERROR: provide either --filters-json or --filters-file" >&2
        exit 1
      fi
      query_entities_command "$serial" "$profile" "$entity_type" "$filters_json" "$filters_file" "$limit" "$offset" "$scan_limit" "$page_size" "$search_query" "$sort_field" "$sort_ascending"
      ;;

    doctor)
      local serial=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --device) serial="$2"; shift 2 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
        esac
      done
      serial="$(resolve_device_serial "${serial:-$(read_state_field '.adb.serial' || true)}")"
      run_doctor "$serial"
      ;;

    snapshot)
      local serial=""
      local ui_evidence_mode=""
      local with_screenshot="false"
      local with_perfetto="false"
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --device) serial="$2"; shift 2 ;;
          --ui-evidence-mode) ui_evidence_mode="$2"; shift 2 ;;
          --with-screenshot) with_screenshot="true"; shift 1 ;;
          --with-perfetto) with_perfetto="true"; shift 1 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; usage; exit 1 ;;
        esac
      done
      serial="$(resolve_device_serial "${serial:-$(read_state_field '.adb.serial' || true)}")"
      ui_evidence_mode="$(resolve_ui_evidence_mode "$ui_evidence_mode")"
      run_snapshot "$serial" "$ui_evidence_mode" "$with_screenshot" "$with_perfetto"
      ;;

    __lane)
      local lane=""
      local profile="$PROFILE_DEFAULT"
      local serial=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --lane) lane="$2"; shift 2 ;;
          --profile) profile="$2"; shift 2 ;;
          --device) serial="$2"; shift 2 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; exit 1 ;;
        esac
      done
      if [[ -z "$lane" ]]; then
        echo "ERROR: --lane is required" >&2
        exit 1
      fi
      serial="$(resolve_device_serial "${serial:-$(read_state_field '.adb.serial' || true)}")"
      lane_dispatch "$lane" "$profile" "$serial"
      ;;

    __ensure-lanes)
      local profile="$PROFILE_DEFAULT"
      local serial=""
      while [[ $# -gt 0 ]]; do
        case "$1" in
          --profile) profile="$2"; shift 2 ;;
          --device) serial="$2"; shift 2 ;;
          --adb-host) ADB_HOST="$2"; shift 2 ;;
          --adb-port) ADB_PORT="$2"; shift 2 ;;
          --app-id) APP_ID="$2"; shift 2 ;;
          *) echo "Unknown arg: $1" >&2; exit 1 ;;
        esac
      done
      ensure_lanes_command "$profile" "$serial"
      ;;

    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
