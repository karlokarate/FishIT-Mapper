#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLAN_FILE="$ROOT_DIR/contracts/generation_plan.json"

mode="${1:-}"
check_only=0
if [[ "$mode" == "--check" ]]; then
  check_only=1
elif [[ -n "$mode" ]]; then
  echo "Usage: ./scripts/generate.sh [--check]" >&2
  exit 2
fi

jq -e '.version and (.pipelines | type == "array" and length > 0)' "$PLAN_FILE" >/dev/null

render_roadmap_markdown() {
  local source_file="$1"
  jq -r '
    "# Roadmap",
    "",
    "Plan ID: `" + .plan_id + "`",
    "Execution strategy: `" + .execution_strategy + "`",
    "Release rule: `" + .release_rule + "`",
    "",
    "## Workstreams",
    "",
    (.workstreams[] | "- `" + .id + "` - " + .name + ": " + .purpose),
    "",
    "## Phases",
    "",
    (.phases[] |
      "### " + .phase_id + " - " + .name,
      "",
      (if .wave_id? then "**Wave:** " + .wave_id else empty end),
      (if .artifact_target? then "**Artifact target:** `" + .artifact_target + "`" else empty end),
      (if .device_required? then "**Device required:** `" + (.device_required|tostring) + "`" else empty end),
      (if .acceptance_checks? then "**Acceptance checks**" else empty end),
      (if .acceptance_checks? then (.acceptance_checks[] | "- " + .) else empty end),
      (if .wave_id? then "" else empty end),
      "**Depends on:** " + (if (.depends_on | length) == 0 then "none" else (.depends_on | join(", ")) end),
      "",
      "**Entry criteria**",
      (.entry_criteria[] | "- " + .),
      "",
      "**Exit criteria**",
      (.exit_criteria[] | "- " + .),
      "",
      "**Workstreams**",
      (.workstreams[] | "- " + .),
      "",
      "**Required outputs**",
      (.required_outputs[] | "- " + .),
      "",
      "**Required gates**",
      (.required_gates[] | "- " + .),
      ""
    )
  ' "$source_file"
}

render_agent_ruleset_markdown() {
  local source_file="$1"
  jq -r '
    "# Agent Ruleset",
    "",
    "Ruleset ID: `" + .ruleset_id + "`",
    "",
    "## Agent Roles",
    "",
    (.agent_roles[] |
      "### " + .id,
      (.responsibilities[] | "- " + .),
      ""
    ),
    "## Must-Run Gates",
    "",
    (.must_run_gates[] | "- " + .),
    "",
    "## Handover Requirements",
    "",
    "- required: `" + (.handover_requirements.required | tostring) + "`",
    "- frequency: `" + .handover_requirements.frequency + "`",
    "- schema_path: `" + .handover_requirements.schema_path + "`",
    "- output_directory: `" + .handover_requirements.output_directory + "`",
    "- required_fields:",
    (.handover_requirements.required_fields[] | "  - " + .),
    "",
    "## PR Requirements",
    "",
    "- required: `" + (.pr_requirements.required | tostring) + "`",
    "- template_path: `" + .pr_requirements.template_path + "`",
    "- required_sections:",
    (.pr_requirements.required_sections[] | "  - " + .),
    "",
    "## Decision Trace Requirements",
    "",
    "- required: `" + (.decision_trace_requirements.required | tostring) + "`",
    "- path: `" + .decision_trace_requirements.path + "`",
    "- must_include:",
    (.decision_trace_requirements.must_include[] | "  - " + .),
    "",
    "## Drift Prohibitions",
    "",
    (.drift_prohibitions[] | "- " + .)
  ' "$source_file"
}

render_ci_contract_markdown() {
  local source_file="$1"
  jq -r '
    "# Delivery Gates",
    "",
    "Contract ID: `" + .contract_id + "`",
    "Failure policy: `" + .failure_policy + "`",
    "",
    "## Local Contract",
    "",
    "- blocking: `" + (.local_contract.blocking | tostring) + "`",
    "- commands:",
    (.local_contract.commands[] | "  - `" + . + "`"),
    "",
    "## CI Jobs",
    "",
    (.jobs | sort_by(.order)[] |
      "### " + .job_id + " (order: " + (.order | tostring) + ")",
      "- blocking: `" + (.blocking | tostring) + "`",
      "- commands:",
      (.commands[] | "  - `" + . + "`"),
      "- artifacts:",
      (.artifacts[] | "  - `" + . + "`"),
      ""
    )
  ' "$source_file"
}

render_dependency_intake_markdown() {
  local source_file="$1"
  jq -r '
    "# Dependency Intake",
    "",
    "Intake ID: `" + .intake_id + "`",
    "Integration policy: `" + .integration_policy + "`",
    "",
    "## Items",
    "",
    (.items[] |
      "### " + .id + " - " + .name,
      "- kind: `" + .kind + "`",
      "- source_url: `" + .source_url + "`",
      "- target_module: `" + .target_module + "`",
      "- integration_mode: `" + .integration_mode + "`",
      "- pinned_ref: `" + .pinned_ref + "`",
      "- local_path: `" + .local_path + "`",
      "- license: `" + .license + "`",
      "- status: `" + .status + "`",
      "- notes: " + .notes,
      ""
    )
  ' "$source_file"
}

drift=0
while IFS= read -r pipeline; do
  pipeline_type="$(jq -r '.type' <<<"$pipeline")"
  source_rel="$(jq -r '.source' <<<"$pipeline")"
  target_rel="$(jq -r '.target' <<<"$pipeline")"
  source_file="$ROOT_DIR/$source_rel"
  target_file="$ROOT_DIR/$target_rel"

  if [[ ! -f "$source_file" ]]; then
    echo "Missing source file: $source_rel" >&2
    exit 1
  fi

  tmp_file="$(mktemp)"
  jq -r '.header[]' <<<"$pipeline" >"$tmp_file"

  case "$pipeline_type" in
    copy_with_header)
      cat "$source_file" >>"$tmp_file"
      ;;
    roadmap_markdown)
      render_roadmap_markdown "$source_file" >>"$tmp_file"
      ;;
    agent_ruleset_markdown)
      render_agent_ruleset_markdown "$source_file" >>"$tmp_file"
      ;;
    ci_contract_markdown)
      render_ci_contract_markdown "$source_file" >>"$tmp_file"
      ;;
    dependency_intake_markdown)
      render_dependency_intake_markdown "$source_file" >>"$tmp_file"
      ;;
    *)
      echo "Unsupported pipeline type: $pipeline_type" >&2
      rm -f "$tmp_file"
      exit 1
      ;;
  esac

  if [[ $check_only -eq 1 ]]; then
    if [[ ! -f "$target_file" ]] || ! cmp -s "$tmp_file" "$target_file"; then
      echo "Drift detected for $target_rel (source: $source_rel)" >&2
      drift=1
    fi
    rm -f "$tmp_file"
    continue
  fi

  mkdir -p "$(dirname "$target_file")"
  mv "$tmp_file" "$target_file"
  echo "Generated $target_rel from $source_rel"
done < <(jq -c '.pipelines[]' "$PLAN_FILE")

if [[ $check_only -eq 1 ]]; then
  if [[ $drift -ne 0 ]]; then
    echo "generate --check failed: run ./scripts/generate.sh" >&2
    exit 1
  fi
  echo "generate --check passed"
fi
