#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HANDOVERS_DIR="$ROOT_DIR/handovers"

if [[ ! -d "$HANDOVERS_DIR" ]]; then
  echo "Missing handovers/ directory." >&2
  exit 1
fi

mapfile -t files < <(find "$HANDOVERS_DIR" -type f -name 'HO-*.json' | sort)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "No handover files found in handovers/." >&2
  exit 1
fi

for f in "${files[@]}"; do
  jq -e '
    .schema_version == 1
    and (.handover_id | test("^HO-[0-9]{8}T[0-9]{6}Z$"))
    and (.generated_at_utc | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
    and (.agent.name | type == "string" and length > 0)
    and (.agent.session_label | type == "string" and length > 0)
    and (.scope | type == "string" and length > 0)
    and (.highlights | type == "array" and length > 0)
    and (.changed_files | type == "array" and length > 0)
    and all(.changed_files[];
      (.status_code | IN("A","M","D","R","C","U","T","?"))
      and (.status | type == "string" and length > 0)
      and (.path | type == "string" and length > 0)
    )
  ' "$f" >/dev/null
done

echo "handover-guard passed"
