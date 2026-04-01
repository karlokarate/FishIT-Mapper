#!/usr/bin/env bash
set -euo pipefail

ADB_HOST="${ADB_HOST:-127.0.0.1}"
ADB_PORT="${ADB_PORT:-5037}"
APP_ID="${APP_ID:-dev.fishit.mapper.wave01}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-dev.fishit.mapper.wave01/info.plateaukao.einkbro.activity.BrowserActivity}"
OUT_DIR="${OUT_DIR:-logs/device/live/ui_anchors}"
MATRIX_FILE_DEFAULT="${MATRIX_FILE_DEFAULT:-scripts/device/anchors/ui_anchor_matrix_latest.json}"
RUNTIME_TOOLKIT_ACTION="${RUNTIME_TOOLKIT_ACTION:-dev.fishit.mapper.wave01.debug.RUNTIME_TOOLKIT_COMMAND}"
RUNTIME_TOOLKIT_RECEIVER="${RUNTIME_TOOLKIT_RECEIVER:-dev.fishit.mapper.wave01/dev.fishit.mapper.wave01.debug.RuntimeToolkitCommandReceiver}"

mkdir -p "$OUT_DIR"

adb_cmd() {
  adb -H "$ADB_HOST" -P "$ADB_PORT" "$@"
}

ensure_device() {
  local state
  state="$(adb_cmd get-state 2>/dev/null || true)"
  if [[ "$state" != "device" ]]; then
    echo "ERROR: no online device via adb -H $ADB_HOST -P $ADB_PORT" >&2
    exit 1
  fi
}

usage() {
  cat <<'EOF'
Usage:
  scripts/device/ui-anchor-nav.sh state
  scripts/device/ui-anchor-nav.sh dump [--screen <name>]
  scripts/device/ui-anchor-nav.sh anchors [--screen <name>]
  scripts/device/ui-anchor-nav.sh clickables [--screen <name>] [--output <path>]
  scripts/device/ui-anchor-nav.sh tap --match <regex> [--index <n>] [--screen <name>]
  scripts/device/ui-anchor-nav.sh tile --screen <home|library|settings> [--match <regex> | --row <n> --col <n>] [--matrix <path>]
  scripts/device/ui-anchor-nav.sh tap-tile --screen <home|library|settings> [--match <regex> | --row <n> --col <n>] [--matrix <path>]
  scripts/device/ui-anchor-nav.sh goto <home|search|library|movies|series|settings|live|refresh>
  scripts/device/ui-anchor-nav.sh open-screen <home|search|library|movies|series|settings|live|refresh>
  scripts/device/ui-anchor-nav.sh open-detail --work-key <key> [--source-type <TELEGRAM|XTREAM>]
  scripts/device/ui-anchor-nav.sh open-detail --title <title>
  scripts/device/ui-anchor-nav.sh set-setting --key <runtime_key> --value <true|false|VERBOSE|...>
  scripts/device/ui-anchor-nav.sh inspect-work --work-key <key> [--source-type <TELEGRAM|XTREAM>]
  scripts/device/ui-anchor-nav.sh inspect-work --title <title> [--source-type <TELEGRAM|XTREAM>]
  scripts/device/ui-anchor-nav.sh query-entities --entity-type <id|ALL|*> [--filters-json <json> | --filters-file <path>] [--limit <n>] [--offset <n>] [--scan-limit <n>] [--page-size <n>] [--search-query <q>] [--sort-field <field>] [--sort-ascending <true|false>]
  scripts/device/ui-anchor-nav.sh matrix [--output <path>]

Env:
  ADB_HOST (default: 127.0.0.1)
  ADB_PORT (default: 5037)
  APP_ID   (default: dev.fishit.mapper.wave01)
EOF
}

timestamp_utc() {
  date -u +%Y%m%dT%H%M%SZ
}

dump_ui() {
  local screen="${1:-current}"
  local ts xml remote_xml attempt
  for attempt in 1 2 3; do
    ts="$(timestamp_utc)"
    xml="$OUT_DIR/hierarchy_${ts}_${screen}.xml"
    remote_xml="/sdcard/window_dump_${ts}_${screen}.xml"
    adb_cmd shell uiautomator dump "$remote_xml" >/dev/null 2>&1 || true
    adb_cmd shell cat "$remote_xml" >"$xml" 2>/dev/null || true
    adb_cmd shell rm -f "$remote_xml" >/dev/null 2>&1 || true
    if [[ -s "$xml" ]] && rg -q "<hierarchy" "$xml"; then
      echo "$xml"
      return 0
    fi
    sleep 1
  done
  echo "ERROR: empty or invalid ui dump file after retries: $xml" >&2
  exit 1
}

extract_anchors() {
  local xml="$1"
  python3 - "$xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_path = sys.argv[1]
root = ET.parse(xml_path).getroot()

def center(bounds: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return (0, 0)
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)

rows = []

def walk(node, clickable_stack):
    text = (node.attrib.get("text") or "").strip()
    desc = (node.attrib.get("content-desc") or "").strip()
    label = desc if desc else text
    bounds = node.attrib.get("bounds", "")
    clickable = node.attrib.get("clickable") == "true" or node.attrib.get("focusable") == "true"

    next_stack = list(clickable_stack)
    if clickable and bounds:
        next_stack.append(bounds)

    tap_bounds = next_stack[-1] if next_stack else bounds
    cx, cy = center(tap_bounds)
    if label and tap_bounds:
        rows.append({
            "label": label,
            "class": node.attrib.get("class", ""),
            "bounds": bounds,
            "tap_bounds": tap_bounds,
            "cx": cx,
            "cy": cy,
        })

    for child in list(node):
        walk(child, next_stack)

walk(root, [])

dedup = []
seen = set()
for row in rows:
    key = (row["label"], row["tap_bounds"])
    if key in seen:
        continue
    seen.add(key)
    dedup.append(row)

def sort_key(r):
    return (r["cy"], r["cx"], r["label"].lower())

for idx, row in enumerate(sorted(dedup, key=sort_key)):
    print(
        f"{idx}\t{row['label']}\t{row['class']}\t{row['bounds']}\t"
        f"{row['tap_bounds']}\t{row['cx']}\t{row['cy']}"
    )
PY
}

extract_clickables_json() {
  local xml="$1"
  python3 - "$xml" <<'PY'
import json
import re
import sys
import xml.etree.ElementTree as ET

xml_path = sys.argv[1]
root = ET.parse(xml_path).getroot()

def center(bounds: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return (0, 0)
    x1, y1, x2, y2 = map(int, m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)

def first_label_in_subtree(node):
    text = (node.attrib.get("text") or "").strip()
    desc = (node.attrib.get("content-desc") or "").strip()
    if desc:
        return desc
    if text:
        return text
    for child in list(node):
        got = first_label_in_subtree(child)
        if got:
            return got
    return ""

rows = []

def walk(node, path):
    text = (node.attrib.get("text") or "").strip()
    desc = (node.attrib.get("content-desc") or "").strip()
    resource_id = (node.attrib.get("resource-id") or "").strip()
    bounds = (node.attrib.get("bounds") or "").strip()
    clickable = node.attrib.get("clickable") == "true"
    focusable = node.attrib.get("focusable") == "true"
    is_click_target = clickable or focusable
    if is_click_target and bounds:
        label = desc or text or first_label_in_subtree(node) or ""
        cx, cy = center(bounds)
        rows.append(
            {
                "path": path,
                "label": label,
                "text": text,
                "contentDesc": desc,
                "resourceId": resource_id,
                "className": node.attrib.get("class", ""),
                "bounds": bounds,
                "centerX": cx,
                "centerY": cy,
                "clickable": clickable,
                "focusable": focusable,
                "enabled": node.attrib.get("enabled") == "true",
                "checked": node.attrib.get("checked") == "true",
                "selected": node.attrib.get("selected") == "true",
                "scrollable": node.attrib.get("scrollable") == "true",
            }
        )
    for idx, child in enumerate(list(node)):
        walk(child, f"{path}/{idx}")

walk(root, "0")

dedup = []
seen = set()
for row in rows:
    key = (row["bounds"], row["className"], row["label"])
    if key in seen:
        continue
    seen.add(key)
    dedup.append(row)

dedup.sort(key=lambda r: (r["centerY"], r["centerX"], (r["label"] or "").lower()))
print(json.dumps(dedup, ensure_ascii=True))
PY
}

print_state() {
  ensure_device
  echo "ADB: ${ADB_HOST}:${ADB_PORT}"
  adb_cmd devices -l | sed -n '1,4p'
  echo
  adb_cmd shell dumpsys window windows | rg "mCurrentFocus|mFocusedApp" | tail -n 8 || true
}

tap_by_regex() {
  local match="$1"
  local index="${2:-0}"
  local screen="${3:-tap}"
  local xml anchors line x y
  xml="$(dump_ui "$screen")"
  anchors="$(extract_anchors "$xml")"
  line="$(
    printf "%s\n" "$anchors" |
      awk -F '\t' -v re="$match" 'BEGIN{IGNORECASE=1} $2 ~ re {print $0}' |
      sed -n "$((index + 1))p"
  )"

  if [[ -z "$line" ]]; then
    echo "ERROR: no anchor match for regex '$match' (index=$index)" >&2
    echo "Hint: run 'scripts/device/ui-anchor-nav.sh anchors --screen debug' to inspect labels." >&2
    exit 1
  fi

  x="$(printf "%s" "$line" | awk -F '\t' '{print $6}')"
  y="$(printf "%s" "$line" | awk -F '\t' '{print $7}')"

  adb_cmd shell input tap "$x" "$y"
  echo "tap regex='$match' index=$index -> x=$x y=$y"
}

tap_by_regex_optional() {
  local match="$1"
  local index="${2:-0}"
  local screen="${3:-tap_optional}"
  local xml anchors line x y
  xml="$(dump_ui "$screen")"
  anchors="$(extract_anchors "$xml")"
  line="$(
    printf "%s\n" "$anchors" |
      awk -F '\t' -v re="$match" 'BEGIN{IGNORECASE=1} $2 ~ re {print $0}' |
      sed -n "$((index + 1))p"
  )"
  if [[ -z "$line" ]]; then
    return 1
  fi
  x="$(printf "%s" "$line" | awk -F '\t' '{print $6}')"
  y="$(printf "%s" "$line" | awk -F '\t' '{print $7}')"
  adb_cmd shell input tap "$x" "$y"
  echo "tap regex='$match' index=$index -> x=$x y=$y"
  return 0
}

runtime_broadcast() {
  local op="$1"
  shift

  local cmd=(
    adb_cmd shell am broadcast
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
  out="$("${cmd[@]}" 2>&1 || true)"
  if printf "%s" "$out" | rg -q "Error:|Exception"; then
    return 1
  fi
  if printf "%s" "$out" | rg -q "Broadcast completed: result=0"; then
    return 0
  fi
  return 1
}

top_activity_line() {
  adb_cmd shell dumpsys activity activities 2>/dev/null | rg -m 1 "topResumedActivity|mResumedActivity" || true
}

is_app_foreground() {
  top_activity_line | rg -q "$APP_ID"
}

screen_has_anchor() {
  local pattern="$1"
  local screen_tag="$2"
  local xml anchors
  xml="$(dump_ui "$screen_tag")"
  anchors="$(extract_anchors "$xml")"
  printf "%s\n" "$anchors" | awk -F '\t' -v re="$pattern" 'BEGIN{IGNORECASE=1} $2 ~ re {found=1} END{exit(found?0:1)}'
}

verify_screen_target() {
  local target="$1"
  if ! is_app_foreground; then
    return 1
  fi

  case "$target" in
    home|refresh)
      screen_has_anchor "Search|Library|Refresh" "verify_home"
      ;;
    library)
      screen_has_anchor "Back|Movies|Series|Live TV" "verify_library"
      ;;
    settings)
      screen_has_anchor "Zur.ck|Settings|Sync|Cache|Sources|Account" "verify_settings"
      ;;
    movies)
      screen_has_anchor "Movies|Series|Live TV" "verify_movies"
      ;;
    series)
      screen_has_anchor "Series|Movies|Live TV" "verify_series"
      ;;
    live)
      screen_has_anchor "Live TV|Movies|Series" "verify_live"
      ;;
    search)
      screen_has_anchor "Search|Suche|Close search|Schlie.en|Filtern|Sortieren" "verify_search"
      ;;
    *)
      return 1
      ;;
  esac
}

open_screen() {
  local target="$1"
  case "$target" in
    home|search|library|movies|series|settings|live|refresh) ;;
    *)
      echo "ERROR: unsupported screen '$target'" >&2
      exit 1
      ;;
  esac

  if runtime_broadcast "open_screen" screen "$target"; then
    sleep 1
    if verify_screen_target "$target"; then
      echo "open-screen direct -> $target"
      return 0
    fi
  fi

  case "$target" in
    home) goto_home ;;
    search) goto_search ;;
    library) goto_library ;;
    movies) goto_movies ;;
    series) goto_series ;;
    settings) goto_settings ;;
    live) goto_live ;;
    refresh) goto_refresh ;;
  esac
}

encode_segment() {
  python3 - "$1" <<'PY'
import sys
import urllib.parse

print(urllib.parse.quote(sys.argv[1], safe=""))
PY
}

open_detail() {
  local work_key="$1"
  local source_type="$2"
  local title="$3"

  if [[ -n "$work_key" ]]; then
    if runtime_broadcast "open_detail" workKey "$work_key" sourceType "$source_type"; then
      sleep 1
      if is_app_foreground; then
        echo "open-detail direct -> workKey=$work_key sourceType=$source_type"
        return 0
      fi
    fi
    local encoded
    encoded="$(encode_segment "$work_key")"
    adb_cmd shell am start -W -n "$MAIN_ACTIVITY" --es benchmark.startDestination "detail/${encoded}/${source_type}" >/dev/null
    sleep 1
    echo "open-detail route-fallback -> detail/${encoded}/${source_type}"
    return 0
  fi

  if [[ -z "$title" ]]; then
    echo "ERROR: open-detail requires --work-key or --title" >&2
    exit 1
  fi

  if runtime_broadcast "open_detail" title "$title" sourceType "$source_type"; then
    sleep 1
    if is_app_foreground; then
      echo "open-detail direct-by-title -> $title"
      return 0
    fi
  fi

  goto_library >/dev/null
  tap_by_regex "$title" 0 "open_detail_title_fallback"
}

setting_key_to_label_regex() {
  case "$1" in
    db_inspector_enabled) echo "DB Inspector" ;;
    jank_verbose_enabled) echo "Jank" ;;
    ops_diagnostics_auto_load) echo "Ops.*Auto" ;;
    log_level) echo "Log-Level" ;;
    diag_sm_enabled) echo "State Machine" ;;
    diag_floodwait_enabled) echo "Flood" ;;
    diag_detail_enabled) echo "Detail" ;;
    diag_sync_enabled) echo "Sync" ;;
    diag_ui_enabled) echo "UI" ;;
    diag_work_enabled) echo "Work" ;;
    *) echo "" ;;
  esac
}

set_setting() {
  local key="$1"
  local value="$2"

  if runtime_broadcast "set_setting" key "$key" value "$value"; then
    sleep 1
    echo "set-setting direct -> $key=$value"
    return 0
  fi

  local label_regex
  label_regex="$(setting_key_to_label_regex "$key")"
  if [[ -z "$label_regex" ]]; then
    echo "ERROR: no fallback selector for setting key '$key'" >&2
    exit 1
  fi
  goto_settings >/dev/null
  tap_by_regex "$label_regex" 0 "set_setting_fallback"
  echo "set-setting fallback tap -> key=$key"
}

inspect_work() {
  local work_key="$1"
  local title="$2"
  local source_type="$3"

  if [[ -z "$work_key" && -z "$title" ]]; then
    echo "ERROR: inspect-work requires --work-key or --title" >&2
    exit 1
  fi

  if [[ -n "$work_key" ]]; then
    runtime_broadcast "inspect_work" workKey "$work_key" sourceType "$source_type" || {
      echo "ERROR: inspect-work broadcast failed for workKey=$work_key" >&2
      exit 1
    }
    echo "inspect-work direct -> workKey=$work_key"
    return 0
  fi

  runtime_broadcast "inspect_work" title "$title" sourceType "$source_type" || {
    echo "ERROR: inspect-work broadcast failed for title=$title" >&2
    exit 1
  }
  echo "inspect-work direct -> title=$title"
}

query_entities() {
  local entity_type="$1"
  local filters_json="$2"
  local limit="$3"
  local offset="$4"
  local scan_limit="$5"
  local page_size="$6"
  local search_query="$7"
  local sort_field="$8"
  local sort_ascending="$9"

  local -a params=()
  params+=("entityTypeId" "$entity_type")

  if [[ -n "$filters_json" ]]; then
    params+=("filtersJson" "$filters_json")
  fi
  if [[ -n "$limit" ]]; then
    params+=("limit" "$limit")
  fi
  if [[ -n "$offset" ]]; then
    params+=("offset" "$offset")
  fi
  if [[ -n "$scan_limit" ]]; then
    params+=("scanLimit" "$scan_limit")
  fi
  if [[ -n "$page_size" ]]; then
    params+=("pageSize" "$page_size")
  fi
  if [[ -n "$search_query" ]]; then
    params+=("searchQuery" "$search_query")
  fi
  if [[ -n "$sort_field" ]]; then
    params+=("sortField" "$sort_field")
  fi
  if [[ -n "$sort_ascending" ]]; then
    params+=("sortAscending" "$sort_ascending")
  fi

  runtime_broadcast "query_entities" "${params[@]}" || {
    echo "ERROR: query-entities broadcast failed for entityType=$entity_type" >&2
    exit 1
  }
  echo "query-entities direct -> entityType=$entity_type"
}

goto_home() {
  ensure_device
  adb_cmd shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
  sleep 1
  local i
  for i in 1 2 3 4; do
    if tap_by_regex_optional "^(Back|Zur.ck|Zurück)$" 0 "goto_home_back_$i" >/dev/null; then
      sleep 1
      continue
    fi
    break
  done
  echo "goto home: ok"
}

goto_search() {
  goto_home >/dev/null
  if tap_by_regex_optional "^(Search|Suche)$" 0 "goto_search_primary" >/dev/null; then
    sleep 1
    return 0
  fi
  if tap_by_regex_optional "(Search|Suche)" 0 "goto_search_fallback" >/dev/null; then
    sleep 1
    return 0
  fi
  echo "ERROR: unable to find search anchor on home screen" >&2
  exit 1
}

goto_library() {
  goto_home >/dev/null
  tap_by_regex "^Library$" 0 "goto_library"
  sleep 1
}

goto_movies() {
  goto_library >/dev/null
  tap_by_regex "^Movies$" 0 "goto_movies"
  sleep 1
}

goto_series() {
  goto_library >/dev/null
  tap_by_regex "^Series$" 0 "goto_series"
  sleep 1
}

goto_settings() {
  goto_home >/dev/null
  if tap_by_regex_optional "^Settings$" 0 "goto_settings_icon" >/dev/null; then
    sleep 1
    return 0
  fi
  # Fallback: non-destructive attempt to route into settings via benchmark startDestination hook.
  adb_cmd shell am start -W -n "$MAIN_ACTIVITY" --es benchmark.startDestination settings >/dev/null
  sleep 2
}

goto_live() {
  goto_home >/dev/null
  tap_by_regex "^Live TV$" 0 "goto_live"
  sleep 1
}

goto_refresh() {
  goto_home >/dev/null
  tap_by_regex "^Refresh$" 0 "goto_refresh"
  sleep 1
}

build_matrix() {
  ensure_device
  local output_file="$1"
  local home_xml library_xml settings_xml settings_method

  mkdir -p "$(dirname "$output_file")"

  goto_home >/dev/null
  home_xml="$(dump_ui "matrix_home")"

  goto_library >/dev/null
  library_xml="$(dump_ui "matrix_library")"

  goto_home >/dev/null
  if tap_by_regex_optional "^Settings$" 0 "matrix_settings_icon" >/dev/null; then
    settings_method="home_settings_icon"
    sleep 1
  else
    adb_cmd shell am start -W -n "$MAIN_ACTIVITY" --es benchmark.startDestination settings >/dev/null
    settings_method="benchmark_start_destination_settings"
    sleep 2
  fi
  settings_xml="$(dump_ui "matrix_settings")"

  python3 - \
    "$output_file" \
    "$ADB_HOST" \
    "$ADB_PORT" \
    "$settings_method" \
    "$home_xml" \
    "$library_xml" \
    "$settings_xml" <<'PY'
import datetime as dt
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

output_file, adb_host, adb_port, settings_method, home_xml, library_xml, settings_xml = sys.argv[1:8]

CONTROL_TARGETS = ["Search", "Refresh", "Library", "Settings", "Movies", "Series", "Live TV", "Back"]
CONTROL_LABEL_HINTS = {
    "search",
    "refresh",
    "library",
    "settings",
    "movies",
    "series",
    "live tv",
    "back",
    "sortieren",
    "filtern",
    "filter",
    "name",
    "all",
    "close search",
    "expand sync progress",
}

def run_adb(*args):
    cmd = ["adb", "-H", adb_host, "-P", adb_port, *args]
    return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL).strip()

def parse_bounds(bounds: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2

def center(bounds: str):
    p = parse_bounds(bounds)
    if p is None:
        return (0, 0)
    x1, y1, x2, y2 = p
    return ((x1 + x2) // 2, (y1 + y2) // 2)

def area(bounds: str):
    p = parse_bounds(bounds)
    if p is None:
        return 0
    x1, y1, x2, y2 = p
    return max(0, x2 - x1) * max(0, y2 - y1)

def first_label_in_subtree(node):
    t = (node.attrib.get("text") or "").strip()
    d = (node.attrib.get("content-desc") or "").strip()
    if d:
        return d
    if t:
        return t
    for c in list(node):
        got = first_label_in_subtree(c)
        if got:
            return got
    return ""

def parse_screen(screen_id: str, xml_path: str):
    root = ET.parse(xml_path).getroot()
    clickables = []
    row_positions = []
    headings = []

    def walk(node, path):
        t = (node.attrib.get("text") or "").strip()
        d = (node.attrib.get("content-desc") or "").strip()
        rid = (node.attrib.get("resource-id") or "").strip()
        bounds = (node.attrib.get("bounds") or "").strip()
        cls = node.attrib.get("class", "")
        clickable = node.attrib.get("clickable") == "true"
        focusable = node.attrib.get("focusable") == "true"
        scrollable = node.attrib.get("scrollable") == "true"
        label = d or t or first_label_in_subtree(node) or ""
        cx, cy = center(bounds)

        if (clickable or focusable) and bounds:
            clickables.append(
                {
                    "path": path,
                    "label": label,
                    "text": t,
                    "contentDesc": d,
                    "resourceId": rid,
                    "className": cls,
                    "bounds": bounds,
                    "centerX": cx,
                    "centerY": cy,
                    "area": area(bounds),
                    "clickable": clickable,
                    "focusable": focusable,
                    "enabled": node.attrib.get("enabled") == "true",
                    "checked": node.attrib.get("checked") == "true",
                    "selected": node.attrib.get("selected") == "true",
                    "scrollable": scrollable,
                }
            )

        if scrollable and bounds:
            row_positions.append(
                {
                    "path": path,
                    "bounds": bounds,
                    "centerX": cx,
                    "centerY": cy,
                    "className": cls,
                }
            )

        if cls == "android.widget.TextView" and t and bounds:
            if len(t) <= 40 and not t.replace(",", "").replace(".", "").isdigit():
                headings.append(
                    {
                        "text": t,
                        "bounds": bounds,
                        "centerX": cx,
                        "centerY": cy,
                        "path": path,
                    }
                )

        for idx, child in enumerate(list(node)):
            walk(child, f"{path}/{idx}")

    walk(root, "0")

    clickables.sort(key=lambda r: (r["centerY"], r["centerX"], (r["label"] or "").lower()))
    row_positions.sort(key=lambda r: (r["centerY"], r["centerX"]))
    headings.sort(key=lambda r: (r["centerY"], r["centerX"], r["text"].lower()))

    def is_probable_tile(entry):
        label = (entry.get("label") or "").strip().lower()
        if not label:
            return False
        if any(h in label for h in CONTROL_LABEL_HINTS):
            return False
        if entry["centerY"] <= 900:
            return False
        if entry["area"] >= 110_000:
            return True
        if label.startswith("#") and entry["centerY"] > 900:
            return True
        return False

    dropped_tiles = []
    control_clickables = []
    for e in clickables:
        if is_probable_tile(e):
            dropped_tiles.append(e)
        else:
            control_clickables.append(e)

    for i, row in enumerate(control_clickables):
        row["anchorId"] = f"{screen_id}:c{i:03d}"

    dropped_tiles.sort(key=lambda r: (r["centerY"], r["centerX"], (r["label"] or "").lower()))
    tile_rows = []
    row_tolerance = 180
    for tile in dropped_tiles:
        placed = False
        for row in tile_rows:
            if abs(tile["centerY"] - row["y_ref"]) <= row_tolerance:
                row["tiles"].append(tile)
                row["y_samples"].append(tile["centerY"])
                row["y_ref"] = sum(row["y_samples"]) / len(row["y_samples"])
                placed = True
                break
        if not placed:
            tile_rows.append({"y_ref": float(tile["centerY"]), "y_samples": [tile["centerY"]], "tiles": [tile]})
    tile_rows.sort(key=lambda r: r["y_ref"])

    tile_candidates = []
    tile_idx = 0
    for row_index, row in enumerate(tile_rows, start=1):
        row["tiles"].sort(key=lambda t: (t["centerX"], t["centerY"]))
        for col_index, tile in enumerate(row["tiles"], start=1):
            entry = {
                "anchorId": f"{screen_id}:t{tile_idx:03d}",
                "label": tile.get("label", ""),
                "text": tile.get("text", ""),
                "contentDesc": tile.get("contentDesc", ""),
                "bounds": tile.get("bounds", ""),
                "centerX": tile.get("centerX", 0),
                "centerY": tile.get("centerY", 0),
                "row": row_index,
                "col": col_index,
            }
            tile_candidates.append(entry)
            tile_idx += 1

    quick = {}
    for target in CONTROL_TARGETS:
        lower = target.lower()
        match = next((c for c in control_clickables if (c.get("label") or "").lower() == lower), None)
        if match is None:
            match = next((c for c in control_clickables if lower in (c.get("label") or "").lower()), None)
        if match is not None:
            quick[target] = {
                "anchorId": match["anchorId"],
                "label": match.get("label", ""),
                "centerX": match["centerX"],
                "centerY": match["centerY"],
                "bounds": match["bounds"],
            }

    return {
        "screenId": screen_id,
        "xmlPath": xml_path,
        "controlClickables": control_clickables,
        "tileCandidates": tile_candidates,
        "rowPositions": row_positions,
        "headings": headings,
        "quickTargets": quick,
        "tileFilterStats": {
            "totalClickablesRaw": len(clickables),
            "keptControlClickables": len(control_clickables),
            "droppedProbableTiles": len(dropped_tiles),
            "droppedTileLabelSamples": [d.get("label", "") for d in dropped_tiles[:12]],
            "tileGridRows": len(tile_rows),
        },
    }

serial = "unknown"
model = "unknown"
android_release = "unknown"
try:
    serial = run_adb("get-serialno")
    model = run_adb("shell", "getprop", "ro.product.model")
    android_release = run_adb("shell", "getprop", "ro.build.version.release")
except Exception:
    pass

screens = [
    parse_screen("home", home_xml),
    parse_screen("library", library_xml),
    parse_screen("settings", settings_xml),
]

settings_labels = []
for c in screens[2]["controlClickables"]:
    label = (c.get("label") or "").strip()
    if label:
        settings_labels.append(label.lower())
settings_detected = any(("einstellungen" in s or "settings" in s) for s in settings_labels)

payload = {
    "schemaVersion": "1.0",
    "generatedAtUtc": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "adb": {"host": adb_host, "port": int(adb_port)},
    "device": {
        "serial": serial,
        "model": model,
        "androidRelease": android_release,
    },
    "captureMethods": {
        "home": "goto home",
        "library": "goto library",
        "settings": settings_method,
    },
    "screens": screens,
    "validation": {
        "settingsScreenDetectedByLabel": settings_detected,
        "notes": [] if settings_detected else ["Settings labels not detected; verify route/UI path on this build."],
    },
}

out = Path(output_file)
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(out.as_posix())
PY

  echo "matrix written: $output_file"
}

tap_tile_from_matrix() {
  ensure_device
  local screen="$1"
  local match="${2:-}"
  local row="${3:-}"
  local col="${4:-}"
  local matrix="${5:-$MATRIX_FILE_DEFAULT}"

  if [[ ! -f "$matrix" ]]; then
    echo "ERROR: matrix file missing: $matrix" >&2
    echo "Run: scripts/device/ui-anchor-nav.sh matrix --output $matrix" >&2
    exit 1
  fi

  local coords
  coords="$(
    python3 - "$matrix" "$screen" "$match" "$row" "$col" <<'PY'
import json
import re
import sys

matrix_path, screen_id, match, row, col = sys.argv[1:6]
obj = json.load(open(matrix_path, "r", encoding="utf-8"))
screen = next((s for s in obj.get("screens", []) if s.get("screenId") == screen_id), None)
if screen is None:
    print("ERR screen_not_found")
    sys.exit(0)

tiles = screen.get("tileCandidates") or []
if not tiles:
    print("ERR no_tiles")
    sys.exit(0)

picked = None
if match:
    rx = re.compile(match, re.IGNORECASE)
    for t in tiles:
        label = t.get("label") or t.get("text") or t.get("contentDesc") or ""
        if rx.search(label):
            picked = t
            break
elif row and col:
    try:
        r = int(row)
        c = int(col)
    except ValueError:
        print("ERR invalid_row_col")
        sys.exit(0)
    picked = next((t for t in tiles if int(t.get("row", -1)) == r and int(t.get("col", -1)) == c), None)
else:
    print("ERR missing_selector")
    sys.exit(0)

if picked is None:
    print("ERR tile_not_found")
    sys.exit(0)

print(
    f"{picked.get('centerX',0)}\t{picked.get('centerY',0)}\t"
    f"{picked.get('anchorId','')}\t{picked.get('label','')}\trow={picked.get('row')}\tcol={picked.get('col')}"
)
PY
  )"

  if [[ "$coords" == ERR* ]]; then
    echo "ERROR: $coords" >&2
    exit 1
  fi

  local x y info
  x="$(printf "%s" "$coords" | awk -F '\t' '{print $1}')"
  y="$(printf "%s" "$coords" | awk -F '\t' '{print $2}')"
  info="$(printf "%s" "$coords" | cut -f3-)"
  adb_cmd shell input tap "$x" "$y"
  echo "tile tap -> x=$x y=$y $info"
}

cmd="${1:-}"
shift || true

case "$cmd" in
  state)
    print_state
    ;;
  dump)
    ensure_device
    screen="current"
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --screen)
          screen="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    dump_ui "$screen"
    ;;
  anchors)
    ensure_device
    screen="current"
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --screen)
          screen="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    xml="$(dump_ui "$screen")"
    echo "xml=$xml"
    echo -e "idx\tlabel\tclass\tbounds\ttap_bounds\tcenter_x\tcenter_y"
    extract_anchors "$xml"
    ;;
  clickables)
    ensure_device
    screen="current"
    output_file=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --screen)
          screen="$2"
          shift 2
          ;;
        --output)
          output_file="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    xml="$(dump_ui "$screen")"
    if [[ -n "$output_file" ]]; then
      mkdir -p "$(dirname "$output_file")"
      extract_clickables_json "$xml" >"$output_file"
      echo "clickables_json=$output_file"
      echo "xml=$xml"
    else
      extract_clickables_json "$xml"
    fi
    ;;
  tap)
    ensure_device
    match=""
    index=0
    screen="tap"
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --match)
          match="$2"
          shift 2
          ;;
        --index)
          index="$2"
          shift 2
          ;;
        --screen)
          screen="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    if [[ -z "$match" ]]; then
      echo "ERROR: --match is required" >&2
      exit 1
    fi
    tap_by_regex "$match" "$index" "$screen"
    ;;
  tile)
    ensure_device
    screen=""
    match=""
    row=""
    col=""
    matrix="$MATRIX_FILE_DEFAULT"
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --screen)
          screen="$2"
          shift 2
          ;;
        --match)
          match="$2"
          shift 2
          ;;
        --row)
          row="$2"
          shift 2
          ;;
        --col)
          col="$2"
          shift 2
          ;;
        --matrix)
          matrix="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    if [[ -z "$screen" ]]; then
      echo "ERROR: --screen is required" >&2
      exit 1
    fi
    if [[ -z "$match" && ( -z "$row" || -z "$col" ) ]]; then
      echo "ERROR: provide --match OR --row + --col" >&2
      exit 1
    fi
    tap_tile_from_matrix "$screen" "$match" "$row" "$col" "$matrix"
    ;;
  tap-tile)
    ensure_device
    screen=""
    match=""
    row=""
    col=""
    matrix="$MATRIX_FILE_DEFAULT"
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --screen)
          screen="$2"
          shift 2
          ;;
        --match)
          match="$2"
          shift 2
          ;;
        --row)
          row="$2"
          shift 2
          ;;
        --col)
          col="$2"
          shift 2
          ;;
        --matrix)
          matrix="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    if [[ -z "$screen" ]]; then
      echo "ERROR: --screen is required" >&2
      exit 1
    fi
    if [[ -z "$match" && ( -z "$row" || -z "$col" ) ]]; then
      echo "ERROR: provide --match OR --row + --col" >&2
      exit 1
    fi
    tap_tile_from_matrix "$screen" "$match" "$row" "$col" "$matrix"
    ;;
  goto)
    ensure_device
    target="${1:-}"
    case "$target" in
      home) goto_home ;;
      search) goto_search ;;
      library) goto_library ;;
      movies) goto_movies ;;
      series) goto_series ;;
      settings) goto_settings ;;
      live) goto_live ;;
      refresh) goto_refresh ;;
      *)
        echo "ERROR: unsupported goto target '$target'" >&2
        usage
        exit 1
        ;;
    esac
    ;;
  open-screen)
    ensure_device
    target="${1:-}"
    if [[ -z "$target" ]]; then
      echo "ERROR: open-screen requires screen argument" >&2
      exit 1
    fi
    open_screen "$target"
    ;;
  open-detail)
    ensure_device
    work_key=""
    source_type="TELEGRAM"
    title=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --work-key)
          work_key="$2"
          shift 2
          ;;
        --source-type)
          source_type="$2"
          shift 2
          ;;
        --title)
          title="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    open_detail "$work_key" "$source_type" "$title"
    ;;
  set-setting)
    ensure_device
    key=""
    value=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --key)
          key="$2"
          shift 2
          ;;
        --value)
          value="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    if [[ -z "$key" || -z "$value" ]]; then
      echo "ERROR: set-setting requires --key and --value" >&2
      exit 1
    fi
    set_setting "$key" "$value"
    ;;
  inspect-work)
    ensure_device
    work_key=""
    title=""
    source_type="TELEGRAM"
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
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    inspect_work "$work_key" "$title" "$source_type"
    ;;
  query-entities)
    ensure_device
    entity_type="NX_Work"
    filters_json=""
    filters_file=""
    limit=""
    offset=""
    scan_limit=""
    page_size=""
    search_query=""
    sort_field=""
    sort_ascending=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --entity-type|--entity-type-id)
          entity_type="$2"
          shift 2
          ;;
        --filters-json)
          filters_json="$2"
          shift 2
          ;;
        --filters-file)
          filters_file="$2"
          shift 2
          ;;
        --limit)
          limit="$2"
          shift 2
          ;;
        --offset)
          offset="$2"
          shift 2
          ;;
        --scan-limit)
          scan_limit="$2"
          shift 2
          ;;
        --page-size)
          page_size="$2"
          shift 2
          ;;
        --search-query)
          search_query="$2"
          shift 2
          ;;
        --sort-field)
          sort_field="$2"
          shift 2
          ;;
        --sort-ascending)
          sort_ascending="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done

    if [[ -n "$filters_json" && -n "$filters_file" ]]; then
      echo "ERROR: provide either --filters-json or --filters-file" >&2
      exit 1
    fi
    if [[ -n "$filters_file" ]]; then
      if [[ ! -f "$filters_file" ]]; then
        echo "ERROR: filters file missing: $filters_file" >&2
        exit 1
      fi
      filters_json="$(
        python3 - "$filters_file" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
obj = json.loads(path.read_text(encoding="utf-8"))
print(json.dumps(obj, ensure_ascii=True, separators=(",", ":")))
PY
      )"
    fi

    query_entities "$entity_type" "$filters_json" "$limit" "$offset" "$scan_limit" "$page_size" "$search_query" "$sort_field" "$sort_ascending"
    ;;
  matrix)
    ensure_device
    output_file="$MATRIX_FILE_DEFAULT"
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --output)
          output_file="$2"
          shift 2
          ;;
        *)
          echo "Unknown arg: $1" >&2
          usage
          exit 1
          ;;
      esac
    done
    build_matrix "$output_file"
    ;;
  *)
    usage
    exit 1
    ;;
esac
