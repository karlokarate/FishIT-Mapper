#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CLI="$ROOT_DIR/scripts/device/mapper-toolkit.sh"
TMP_OUT="$(mktemp)"
trap 'rm -f "$TMP_OUT"' EXIT

run_and_show() {
  local cmd=("$CLI" "$@")
  {
    echo "$ ${cmd[*]}"
    echo
    "${cmd[@]}"
  } >"$TMP_OUT" 2>&1 || true

  if command -v dialog >/dev/null 2>&1; then
    dialog --backtitle "Mapper-Toolkit" --title "Command Output" --textbox "$TMP_OUT" 28 120
  else
    cat "$TMP_OUT"
    echo
    read -r -p "Press Enter to continue..." _
  fi
}

prompt_input() {
  local title="$1"
  local prompt="$2"
  local default_value="${3:-}"

  if command -v dialog >/dev/null 2>&1; then
    dialog --stdout --backtitle "Mapper-Toolkit" --title "$title" --inputbox "$prompt" 10 90 "$default_value"
  else
    read -r -p "$prompt: " value
    echo "$value"
  fi
}

menu_select() {
  local title="$1"
  shift
  if command -v dialog >/dev/null 2>&1; then
    dialog --stdout --backtitle "Mapper-Toolkit" --title "$title" --menu "Select action" 22 90 14 "$@"
  else
    echo "dialog not installed; run bootstrap for arrow-key UI." >&2
    return 1
  fi
}

session_menu() {
  while true; do
    local choice
    choice="$(menu_select "Session" \
      1 "Start session" \
      2 "Stop session" \
      3 "Status" \
      4 "Doctor" \
      5 "Resume home" \
      9 "Back")" || return 0
    case "$choice" in
      1) run_and_show session start --profile full ;;
      2) run_and_show session stop ;;
      3) run_and_show session status ;;
      4) run_and_show session doctor ;;
      5) run_and_show session resume --target home ;;
      9) return 0 ;;
    esac
  done
}

ui_menu() {
  while true; do
    local choice
    choice="$(menu_select "UI" \
      1 "Open home" \
      2 "Open library" \
      3 "Open settings" \
      4 "Tap by regex" \
      5 "Anchor scan (matrix)" \
      9 "Back")" || return 0
    case "$choice" in
      1) run_and_show ui open-screen home ;;
      2) run_and_show ui open-screen library ;;
      3) run_and_show ui open-screen settings ;;
      4)
        local pattern
        pattern="$(prompt_input "Tap Regex" "Regex to match label" "^Search$")"
        [[ -n "$pattern" ]] && run_and_show ui tap --match "$pattern"
        ;;
      5) run_and_show ui anchor-scan ;;
      9) return 0 ;;
    esac
  done
}

capture_menu() {
  while true; do
    local choice
    choice="$(menu_select "Capture" \
      1 "Start capture" \
      2 "Stop capture" \
      3 "Snapshot" \
      4 "List lanes" \
      5 "MITM ON" \
      6 "MITM OFF" \
      7 "MITM STATUS" \
      9 "Back")" || return 0
    case "$choice" in
      1) run_and_show capture start --profile full ;;
      2) run_and_show capture stop ;;
      3) run_and_show capture snapshot --ui-evidence-mode full ;;
      4) run_and_show capture lanes ;;
      5) run_and_show capture mitm on ;;
      6) run_and_show capture mitm off ;;
      7) run_and_show capture mitm status ;;
      9) return 0 ;;
    esac
  done
}

analytics_menu() {
  while true; do
    local choice
    choice="$(menu_select "Analytics" \
      1 "Trace query" \
      2 "Trace correlate" \
      3 "Cookies timeline" \
      4 "Headers infer-required" \
      5 "Responses sample" \
      6 "Mapping profile-draft" \
      7 "Housekeeping reindex" \
      8 "Housekeeping purge" \
      9 "Back")" || return 0
    case "$choice" in
      1)
        local domain
        domain="$(prompt_input "Trace Query" "Domain filter (optional)" "")"
        if [[ -n "$domain" ]]; then
          run_and_show trace query --domain "$domain" --limit 100
        else
          run_and_show trace query --limit 100
        fi
        ;;
      2) run_and_show trace correlate ;;
      3) run_and_show cookies timeline ;;
      4) run_and_show headers infer-required ;;
      5) run_and_show responses sample --limit 20 ;;
      6) run_and_show mapping profile-draft ;;
      7) run_and_show housekeeping reindex ;;
      8) run_and_show housekeeping purge --yes ;;
      9) return 0 ;;
    esac
  done
}

main_menu() {
  if ! command -v dialog >/dev/null 2>&1; then
    echo "ERROR: 'dialog' is required for arrow-key TUI."
    echo "Run: scripts/device/mapper-toolkit.sh bootstrap"
    exit 1
  fi

  while true; do
    local choice
    choice="$(menu_select "Mapper-Toolkit" \
      1 "Bootstrap dependencies" \
      2 "Connect device" \
      3 "Doctor" \
      4 "Session" \
      5 "UI" \
      6 "Capture" \
      7 "Analytics" \
      9 "Exit")" || break

    case "$choice" in
      1) run_and_show bootstrap --yes ;;
      2)
        local device
        device="$(prompt_input "Connect Device" "Enter serial or host:port" "")"
        [[ -n "$device" ]] && run_and_show connect --device "$device"
        ;;
      3) run_and_show doctor ;;
      4) session_menu ;;
      5) ui_menu ;;
      6) capture_menu ;;
      7) analytics_menu ;;
      9) break ;;
    esac
  done

  clear
}

main_menu
