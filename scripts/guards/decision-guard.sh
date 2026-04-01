#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TRACE_FILE="$ROOT_DIR/contracts/decision_trace.json"

jq -e '.version and (.decisions | type == "array" and length > 0)' "$TRACE_FILE" >/dev/null

jq -e '
  all(.decisions[];
    has("id")
    and has("aliases")
    and has("title")
    and has("status")
    and has("date")
    and has("supersedes")
    and has("superseded_by")
    and (.id | test("^DEC-[0-9]{4}$"))
    and (.aliases | type == "array" and length > 0)
    and (.supersedes | type == "array")
    and ((.superseded_by == null) or (.superseded_by | type == "string"))
    and (. as $d | ["proposed", "accepted", "superseded", "rejected", "deprecated"] | index($d.status) != null)
  )
' "$TRACE_FILE" >/dev/null

jq -e '([.decisions[].id] | length) == ([.decisions[].id] | unique | length)' "$TRACE_FILE" >/dev/null

jq -e '([.decisions[].aliases[]] | length) == ([.decisions[].aliases[]] | unique | length)' "$TRACE_FILE" >/dev/null

jq -e '
  (.decisions | map(.id) | unique) as $ids
  | all(.decisions[];
      (. as $d
      | all($d.supersedes[]; $ids | index(.) != null)
      and (($d.superseded_by == null) or ($ids | index($d.superseded_by) != null)))
    )
' "$TRACE_FILE" >/dev/null

jq -e '
  .decisions as $all
  | all($all[];
      . as $d
      | ($d.superseded_by == null)
        or any($all[]; .id == $d.superseded_by and (.supersedes | index($d.id) != null))
    )
' "$TRACE_FILE" >/dev/null

echo "decision-guard passed"
