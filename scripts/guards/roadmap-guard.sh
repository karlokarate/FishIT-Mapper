#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROADMAP_FILE="$ROOT_DIR/contracts/roadmap.json"
DOC_CONTRACT_FILE="$ROOT_DIR/contracts/doc_contract.json"

jq -e '.version == 1 and (.phases | type == "array" and length > 0) and (.workstreams | type == "array" and length > 0)' "$ROADMAP_FILE" >/dev/null
jq -e '.version == 1 and (.gates | type == "array" and length > 0)' "$DOC_CONTRACT_FILE" >/dev/null

jq -e '([.workstreams[].id] | length) == ([.workstreams[].id] | unique | length)' "$ROADMAP_FILE" >/dev/null
jq -e '([.phases[].phase_id] | length) == ([.phases[].phase_id] | unique | length)' "$ROADMAP_FILE" >/dev/null

jq -e '
  (.workstreams | map(.id) | unique) as $ws_ids
  | all(.phases[];
      (.entry_criteria | type == "array" and length > 0)
      and (.exit_criteria | type == "array" and length > 0)
      and (.required_outputs | type == "array" and length > 0)
      and (.required_gates | type == "array" and length > 0)
      and (.workstreams | type == "array" and length > 0)
      and all(.workstreams[]; $ws_ids | index(.) != null)
    )
' "$ROADMAP_FILE" >/dev/null

jq -e '
  any(.phases[]; .wave_id? != null)
  and all(.phases[];
    if .wave_id? then
      (.wave_id | type == "string" and length > 0)
      and (.artifact_target | type == "string" and length > 0)
      and (.device_required | type == "boolean")
      and (.acceptance_checks | type == "array" and length > 0)
    else
      true
    end
  )
' "$ROADMAP_FILE" >/dev/null

jq -e '
  (.phases | map(.phase_id) | unique) as $phase_ids
  | all(.phases[]; all(.depends_on[]; $phase_ids | index(.) != null))
' "$ROADMAP_FILE" >/dev/null

# Enforce acyclic dependency ordering by phase sequence:
# every dependency must appear earlier in the ordered phase list.
mapfile -t ordered_phase_ids < <(jq -r '.phases[].phase_id' "$ROADMAP_FILE")
declare -A phase_index=()
for i in "${!ordered_phase_ids[@]}"; do
  phase_index["${ordered_phase_ids[$i]}"]="$i"
done

while IFS=$'\t' read -r phase_id dep; do
  [[ -n "${dep:-}" ]] || continue
  if [[ "${phase_index[$dep]}" -ge "${phase_index[$phase_id]}" ]]; then
    echo "Invalid phase dependency order: $phase_id depends on $dep (must be earlier phase)." >&2
    exit 1
  fi
done < <(jq -r '.phases[] | .phase_id as $p | .depends_on[]? | [$p, .] | @tsv' "$ROADMAP_FILE")

jq -n \
  --argfile roadmap "$ROADMAP_FILE" \
  --argfile doc "$DOC_CONTRACT_FILE" \
  '
    ($doc.gates | map(.id) | unique) as $gate_ids
    | all($roadmap.phases[]; all(.required_gates[]; $gate_ids | index(.) != null))
  ' >/dev/null

echo "roadmap-guard passed"
