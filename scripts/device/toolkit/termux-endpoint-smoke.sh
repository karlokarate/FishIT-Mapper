#!/usr/bin/env bash
set -u

usage() {
  cat <<'USAGE'
Termux endpoint smoke test for mapper runtime export ZIP.

Usage:
  termux-endpoint-smoke.sh [--zip <mapper_runtime_export.zip>] [options]

Options:
  --zip <path>             Path to mapper runtime export zip (optional: auto-detects newest ZIP)
  --timeout <sec>          Curl timeout per request (default: 15)
  --max <n>                Max endpoints to test (default: 0 = all)
  --max-bytes <n>          Max response bytes per request (default: 262144)
  --allow-unsafe           Also test POST/PUT/PATCH/DELETE (default: skip)
  --include-playback       Also include playback endpoints (default: skip for failsafe)
  --header "K: V"          Extra header (repeatable)
  --only-role <csv>        Only test roles, e.g. home,search,detail,auth
  --username <name>        Login username (else prompted)
  --password <pass>        Login password (else prompted, hidden input)
  --dry-run                Print what would be called, do not send requests
  --help                   Show this help

Header value resolution for required headers:
1) --header "name:value"
2) ENV var by normalized name, e.g. api-auth -> API_AUTH, authorization -> AUTHORIZATION

Examples:
  ./termux-endpoint-smoke.sh

  ./termux-endpoint-smoke.sh --zip ./mapper_runtime_export_2026-04-10.zip --only-role home,search,detail

  export API_AUTH='...'
  export AUTHORIZATION='Bearer ...'
  ./termux-endpoint-smoke.sh \
    --only-role auth \
    --allow-unsafe
USAGE
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing required command: $1" >&2
    exit 1
  }
}

trim() {
  local s="$1"
  s="${s#${s%%[![:space:]]*}}"
  s="${s%${s##*[![:space:]]}}"
  printf '%s' "$s"
}

to_env_name() {
  local name="$1"
  name="${name^^}"
  name="${name//-/_}"
  name="${name//[^A-Z0-9_]/_}"
  printf '%s' "$name"
}

urlencode_from_json() {
  local json="$1"
  jq -r 'to_entries | map("\(.key|@uri)=\(.value|tostring|@uri)") | join("&")' <<<"$json"
}

is_placeholder_json() {
  local json="$1"
  grep -q '\${' <<<"$json"
}

find_auto_zip() {
  local script_dir candidate
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  for candidate in \
    "$(ls -1t "$PWD"/mapper_runtime_export*.zip 2>/dev/null | head -n 1)" \
    "$(ls -1t "$PWD"/*.zip 2>/dev/null | head -n 1)" \
    "$(ls -1t "$script_dir"/mapper_runtime_export*.zip 2>/dev/null | head -n 1)" \
    "$(ls -1t "$script_dir"/*.zip 2>/dev/null | head -n 1)"; do
    if [[ -n "$candidate" && -f "$candidate" ]]; then
      if unzip -p "$candidate" source_pipeline_bundle.json >/dev/null 2>&1; then
        printf '%s\n' "$candidate"
        return 0
      fi
    fi
  done
  return 1
}

json_escape_string() {
  local value="$1"
  jq -Rn --arg v "$value" '$v | @json | .[1:-1]'
}

replace_placeholder_text() {
  local text="$1"
  local token="$2"
  local value="$3"
  local escaped="\${$token}"
  printf '%s' "${text//"$escaped"/$value}"
}

replace_placeholders_json() {
  local json="$1"
  local token="$2"
  local value="$3"
  local escaped
  escaped="$(json_escape_string "$value")"
  replace_placeholder_text "$json" "$token" "$escaped"
}

prompt_if_empty() {
  local label="$1"
  local var_name="$2"
  local secret="${3:-0}"
  local current="${!var_name:-}"
  if [[ -n "$current" ]]; then
    return 0
  fi
  if [[ ! -t 0 ]]; then
    return 0
  fi
  if [[ "$secret" -eq 1 ]]; then
    read -r -s -p "$label: " current
    echo
  else
    read -r -p "$label: " current
  fi
  printf -v "$var_name" '%s' "$current"
}

ZIP_PATH="${ZIP_PATH:-}"
TIMEOUT_SEC=15
MAX_ENDPOINTS=0
MAX_BYTES=262144
ALLOW_UNSAFE=0
INCLUDE_PLAYBACK=0
DRY_RUN=0
ONLY_ROLE_CSV=""
USERNAME="${MAPPER_USERNAME:-}"
PASSWORD="${MAPPER_PASSWORD:-}"

declare -A EXTRA_HEADERS=()
declare -A INPUT_VALUES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --zip)
      ZIP_PATH="${2:-}"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SEC="${2:-15}"
      shift 2
      ;;
    --max)
      MAX_ENDPOINTS="${2:-0}"
      shift 2
      ;;
    --max-bytes)
      MAX_BYTES="${2:-262144}"
      shift 2
      ;;
    --allow-unsafe)
      ALLOW_UNSAFE=1
      shift
      ;;
    --include-playback)
      INCLUDE_PLAYBACK=1
      shift
      ;;
    --header)
      raw="${2:-}"
      shift 2
      key="$(trim "${raw%%:*}")"
      val="$(trim "${raw#*:}")"
      if [[ -n "$key" ]]; then
        EXTRA_HEADERS["${key,,}"]="$val"
      fi
      ;;
    --only-role)
      ONLY_ROLE_CSV="${2:-}"
      shift 2
      ;;
    --username)
      USERNAME="${2:-}"
      shift 2
      ;;
    --password)
      PASSWORD="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "unknown arg: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$ZIP_PATH" ]]; then
  ZIP_PATH="$(find_auto_zip || true)"
fi
if [[ ! -f "$ZIP_PATH" ]]; then
  echo "zip not found (pass --zip or place export ZIP next to the script/current dir)" >&2
  exit 1
fi

need_cmd unzip
need_cmd jq
need_cmd curl
need_cmd base64

TMP_DIR="${TMPDIR:-/tmp}"
if [[ ! -d "$TMP_DIR" || ! -w "$TMP_DIR" ]]; then
  TMP_DIR="$PWD"
fi
PROBE_BODY_PATH="$TMP_DIR/termux_endpoint_probe_body.txt"
PROBE_ERR_PATH="$TMP_DIR/termux_endpoint_probe_err.txt"

if ! unzip -p "$ZIP_PATH" source_pipeline_bundle.json >/dev/null 2>&1; then
  echo "zip does not contain source_pipeline_bundle.json" >&2
  exit 1
fi

BUNDLE_JSON="$(unzip -p "$ZIP_PATH" source_pipeline_bundle.json)"
echo "using zip: $ZIP_PATH"

ROLE_FILTER_JSON='[]'
if [[ -n "$ONLY_ROLE_CSV" ]]; then
  ROLE_FILTER_JSON="$(jq -cn --arg s "$ONLY_ROLE_CSV" '$s|split(",")|map(gsub("^\\s+|\\s+$";""))|map(select(length>0))')"
fi

auth_required="$(jq -r '.sessionAuth.requiresLogin // false' <<<"$BUNDLE_JSON")"
if [[ "$auth_required" == "true" ]]; then
  prompt_if_empty "Username" USERNAME 0
  prompt_if_empty "Password" PASSWORD 1
fi
INPUT_VALUES["username"]="$USERNAME"
INPUT_VALUES["password"]="$PASSWORD"

mapfile -t ENDPOINT_ROWS < <(
  jq -r --argjson rf "$ROLE_FILTER_JSON" --argjson include_playback "$INCLUDE_PLAYBACK" '
    . as $b
    | $b.endpointTemplates[]
    | . as $e
    | ($b.replayRequirements | map(select(.endpointRef == $e.endpointId)) | .[0] // {}) as $r
    | if $include_playback == 1 then .
      else select((($e.role // "") | test("playback"; "i")) | not)
      end
    | select(($rf|length)==0 or ($rf | index($e.role) != null))
    | {
        endpointId: $e.endpointId,
        role: ($e.role // ""),
        method: ($e.method // "GET"),
        host: ($e.normalizedHost // ""),
        pathTemplate: ($e.pathTemplate // ""),
        queryTemplate: ($e.queryTemplate // {}),
        bodyTemplate: ($e.bodyTemplate // {}),
        requiredHeaders: (($r.requiredHeaders // []) | map(.name)),
        requiredCookies: (($r.requiredCookies // []) | map(.name))
      }
    | @base64
  ' <<<"$BUNDLE_JSON"
)

printf '%-22s %-14s %-7s %-5s %s\n' "endpointId" "role" "method" "code" "note"
printf '%s\n' "--------------------------------------------------------------------------------------------------------------"

count_total=0
count_tested=0
count_ok=0
count_fail=0
count_skip=0
count_processed=0

for row in "${ENDPOINT_ROWS[@]}"; do
  if [[ "$MAX_ENDPOINTS" -gt 0 && "$count_processed" -ge "$MAX_ENDPOINTS" ]]; then
    break
  fi
  ((count_processed+=1))

  obj="$(printf '%s' "$row" | base64 -d)"
  endpoint_id="$(jq -r '.endpointId' <<<"$obj")"
  role="$(jq -r '.role' <<<"$obj")"
  method="$(jq -r '.method|ascii_upcase' <<<"$obj")"
  host="$(jq -r '.host' <<<"$obj")"
  path_t="$(jq -r '.pathTemplate' <<<"$obj")"
  query_t="$(jq -c '.queryTemplate // {}' <<<"$obj")"
  body_t="$(jq -c '.bodyTemplate // {}' <<<"$obj")"
  has_username_placeholder=0
  has_password_placeholder=0
  if [[ "$path_t" == *'${username}'* || "$query_t" == *'${username}'* || "$body_t" == *'${username}'* ]]; then
    has_username_placeholder=1
  fi
  if [[ "$path_t" == *'${password}'* || "$query_t" == *'${password}'* || "$body_t" == *'${password}'* ]]; then
    has_password_placeholder=1
  fi

  ((count_total+=1))

  if [[ -z "$host" || -z "$path_t" ]]; then
    ((count_skip+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "SKIP" "missing_host_or_path"
    continue
  fi

  case "$method" in
    GET|HEAD|OPTIONS) ;;
    *)
      if [[ "$ALLOW_UNSAFE" -ne 1 ]]; then
        ((count_skip+=1))
        printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "SKIP" "unsafe_method"
        continue
      fi
      ;;
  esac

  if [[ "$path_t" == *'${'* ]]; then
    if [[ "$has_username_placeholder" -eq 1 && -n "${INPUT_VALUES[username]}" ]]; then
      path_t="$(replace_placeholder_text "$path_t" "username" "${INPUT_VALUES[username]}")"
    fi
    if [[ "$has_password_placeholder" -eq 1 && -n "${INPUT_VALUES[password]}" ]]; then
      path_t="$(replace_placeholder_text "$path_t" "password" "${INPUT_VALUES[password]}")"
    fi
  fi
  if [[ "$path_t" == *'${'* ]]; then
    ((count_skip+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "SKIP" "path_placeholder"
    continue
  fi

  if [[ "$query_t" == *'${username}'* && -n "${INPUT_VALUES[username]}" ]]; then
    query_t="$(replace_placeholders_json "$query_t" "username" "${INPUT_VALUES[username]}")"
  fi
  if [[ "$query_t" == *'${password}'* && -n "${INPUT_VALUES[password]}" ]]; then
    query_t="$(replace_placeholders_json "$query_t" "password" "${INPUT_VALUES[password]}")"
  fi
  if is_placeholder_json "$query_t"; then
    ((count_skip+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "SKIP" "query_placeholder"
    continue
  fi

  if [[ "$body_t" == *'${username}'* && -n "${INPUT_VALUES[username]}" ]]; then
    body_t="$(replace_placeholders_json "$body_t" "username" "${INPUT_VALUES[username]}")"
  fi
  if [[ "$body_t" == *'${password}'* && -n "${INPUT_VALUES[password]}" ]]; then
    body_t="$(replace_placeholders_json "$body_t" "password" "${INPUT_VALUES[password]}")"
  fi
  if [[ "$method" != "GET" && "$method" != "HEAD" && "$body_t" != "{}" ]] && is_placeholder_json "$body_t"; then
    ((count_skip+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "SKIP" "body_placeholder"
    continue
  fi

  query_string=""
  if [[ "$query_t" != "{}" ]]; then
    query_string="$(urlencode_from_json "$query_t")"
  fi

  url="https://$host$path_t"
  if [[ -n "$query_string" ]]; then
    url="$url?$query_string"
  fi

  curl_args=(
    -sS
    -m "$TIMEOUT_SEC"
    --max-filesize "$MAX_BYTES"
    -o "$PROBE_BODY_PATH"
    -w "%{http_code}"
    -X "$method"
    "$url"
  )

  missing_required=""
  while IFS= read -r h; do
    [[ -z "$h" ]] && continue
    key_lc="${h,,}"
    value="${EXTRA_HEADERS[$key_lc]:-}"
    if [[ -z "$value" ]]; then
      env_name="$(to_env_name "$h")"
      value="${!env_name-}"
    fi
    if [[ -z "$value" ]]; then
      missing_required="$h"
      break
    fi
    curl_args+=( -H "$h: $value" )
  done < <(jq -r '.requiredHeaders[]?' <<<"$obj")

  if [[ -n "$missing_required" ]]; then
    ((count_skip+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "SKIP" "missing_header:$missing_required"
    continue
  fi

  if [[ "$method" != "GET" && "$method" != "HEAD" && "$body_t" != "{}" ]]; then
    curl_args+=( -H "content-type: application/json" --data "$body_t" )
  fi

  if [[ "$DRY_RUN" -eq 1 ]]; then
    ((count_tested+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "DRY" "$url"
    continue
  fi

  echo "RUN $method $url"
  ((count_tested+=1))
  http_code="$(curl "${curl_args[@]}" 2>"$PROBE_ERR_PATH")"
  curl_rc=$?

  if [[ $curl_rc -ne 0 ]]; then
    ((count_fail+=1))
    note="curl_error:$curl_rc"
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "ERR" "$note"
    continue
  fi

  if [[ "$http_code" =~ ^2|3 ]]; then
    ((count_ok+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "$http_code" "ok"
  else
    ((count_fail+=1))
    printf '%-22s %-14s %-7s %-5s %s\n' "$endpoint_id" "$role" "$method" "$http_code" "non_2xx"
  fi
done

echo
echo "summary: total=$count_total tested=$count_tested ok=$count_ok fail=$count_fail skipped=$count_skip"
