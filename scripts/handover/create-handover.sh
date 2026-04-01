#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$ROOT_DIR/handovers"

scope=""
agent_name="codex"
session_label=""
declare -a highlights=()

usage() {
  echo "Usage: ./scripts/handover/create-handover.sh --scope <text> --highlight <text> [--highlight <text> ...] [--session-label <text>] [--agent-name <text>]" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scope)
      shift
      scope="${1:-}"
      ;;
    --highlight)
      shift
      highlights+=("${1:-}")
      ;;
    --session-label)
      shift
      session_label="${1:-}"
      ;;
    --agent-name)
      shift
      agent_name="${1:-}"
      ;;
    *)
      usage
      ;;
  esac
  shift || true
done

[[ -n "$scope" ]] || usage
[[ ${#highlights[@]} -gt 0 ]] || usage

ts_id="$(date -u +%Y%m%dT%H%M%SZ)"
ts_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
handover_id="HO-$ts_id"

if [[ -z "$session_label" ]]; then
  session_label="$handover_id"
fi

mkdir -p "$OUT_DIR"

changes_tmp="$(mktemp)"

status_name() {
  case "$1" in
    A) echo "added" ;;
    M) echo "modified" ;;
    D) echo "deleted" ;;
    R) echo "renamed" ;;
    C) echo "copied" ;;
    U) echo "unmerged" ;;
    T) echo "type-changed" ;;
    \?) echo "untracked" ;;
    *) echo "unknown" ;;
  esac
}

while IFS=$'\t' read -r status p1 p2; do
  [[ -n "${status:-}" ]] || continue
  code="${status%%[0-9]*}"
  if [[ "$code" == "R" ]]; then
    new_path="${p2:-}"
    old_path="${p1:-}"
    jq -cn --arg sc "$code" --arg st "$(status_name "$code")" --arg p "$new_path" --arg old "$old_path" \
      '{status_code:$sc,status:$st,path:$p,old_path:$old}' >>"$changes_tmp"
  else
    jq -cn --arg sc "$code" --arg st "$(status_name "$code")" --arg p "${p1:-}" \
      '{status_code:$sc,status:$st,path:$p,old_path:null}' >>"$changes_tmp"
  fi
done < <(cd "$ROOT_DIR" && git diff --name-status HEAD)

while IFS= read -r path; do
  [[ -n "${path:-}" ]] || continue
  jq -cn --arg p "$path" '{status_code:"?",status:"untracked",path:$p,old_path:null}' >>"$changes_tmp"
done < <(cd "$ROOT_DIR" && git ls-files --others --exclude-standard)

changes_json_file="$(mktemp)"
jq -s 'unique_by(.status_code + "|" + .path + "|" + (.old_path // ""))' "$changes_tmp" >"$changes_json_file"
rm -f "$changes_tmp"

if [[ "$(jq 'length' "$changes_json_file")" -eq 0 ]]; then
  echo "No changed files found; handover requires at least one changed file." >&2
  rm -f "$changes_json_file"
  exit 1
fi

highlights_json="$(printf '%s\n' "${highlights[@]}" | jq -R . | jq -s '.')"

out_file="$OUT_DIR/$handover_id.json"
jq -n \
  --arg generated_at "$ts_iso" \
  --arg handover_id "$handover_id" \
  --arg agent_name "$agent_name" \
  --arg session_label "$session_label" \
  --arg scope "$scope" \
  --argjson highlights "$highlights_json" \
  --slurpfile changed_files "$changes_json_file" \
  '{
    schema_version: 1,
    handover_id: $handover_id,
    generated_at_utc: $generated_at,
    agent: {
      name: $agent_name,
      session_label: $session_label
    },
    scope: $scope,
    highlights: $highlights,
    changed_files: $changed_files[0]
  }' >"$out_file"

rm -f "$changes_json_file"

echo "Created $out_file"
