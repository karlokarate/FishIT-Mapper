#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INTAKE_FILE="$ROOT_DIR/contracts/dependency_intake.json"

jq -e '
  .version == 1
  and (.integration_policy | type == "string" and length > 0)
  and (.items | type == "array" and length > 0)
' "$INTAKE_FILE" >/dev/null

jq -e '
  all(.items[];
    has("id")
    and has("name")
    and has("kind")
    and has("source_url")
    and has("target_module")
    and has("integration_mode")
    and has("pinned_ref")
    and has("local_path")
    and has("license")
    and has("status")
    and has("notes")
    and (.id | type == "string" and length > 0)
    and (.name | type == "string" and length > 0)
    and (.kind | IN("repo","tool"))
    and (.source_url | type == "string" and test("^https://"))
    and (.target_module | type == "string" and length > 0)
    and (.integration_mode | type == "string" and length > 0)
    and (.pinned_ref | type == "string" and length > 0)
    and (.local_path | type == "string" and length > 0)
    and (.license | type == "string" and length > 0)
    and (.status | IN("planned","integrating","integrated","blocked"))
    and (.notes | type == "string" and length > 0)
  )
' "$INTAKE_FILE" >/dev/null

jq -e '([.items[].id] | length) == ([.items[].id] | unique | length)' "$INTAKE_FILE" >/dev/null
jq -e '([.items[].local_path] | length) == ([.items[].local_path] | unique | length)' "$INTAKE_FILE" >/dev/null

while IFS=$'\t' read -r dep_id local_path pinned_ref; do
  path="$ROOT_DIR/$local_path"
  if [[ ! -d "$path" ]]; then
    echo "Integrated dependency $dep_id missing local path: $local_path" >&2
    exit 1
  fi

  upstream_file="$path/UPSTREAM_REF.json"
  if [[ ! -f "$upstream_file" ]]; then
    echo "Integrated dependency $dep_id missing $local_path/UPSTREAM_REF.json" >&2
    exit 1
  fi

  jq -e --arg pinned "$pinned_ref" '
    .pinned_ref == $pinned
    and (.source_url | type == "string" and test("^https://"))
    and (.integration_mode | type == "string" and length > 0)
  ' "$upstream_file" >/dev/null
done < <(jq -r '.items[] | select(.status == "integrated") | [.id, .local_path, .pinned_ref] | @tsv' "$INTAKE_FILE")

echo "dependency-intake-guard passed"
