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

render_mission_registry_json() {
  local source_file="$1"
  python3 - "$source_file" <<'PY'
import json
import sys
from collections import OrderedDict

import yaml

source_file = sys.argv[1]
with open(source_file, "r", encoding="utf-8") as handle:
    data = yaml.safe_load(handle) or {}

built_in = data.get("built_in_mission_presets") or {}
flow_by_mission = (data.get("wizard_flow_templates") or {}).get("by_mission") or {}
availability = data.get("mission_runtime_availability") or {}
mission_kinds = ((data.get("mission_profile") or {}).get("missionKind") or {}).get("allowed") or []
mission_specific_bundles = data.get("mission_specific_export_bundles") or {}

step_phase = {
    "target_url_input": None,
    "home_probe_step": "home_probe",
    "search_probe_step": "search_probe",
    "detail_probe_step": "detail_probe",
    "playback_probe_step": "playback_probe",
    "auth_probe_step_optional": "auth_probe",
    "final_validation_export": None,
}
step_display = {
    "target_url_input": "Target URL Input",
    "home_probe_step": "Home Probe",
    "search_probe_step": "Search Probe",
    "detail_probe_step": "Detail Probe",
    "playback_probe_step": "Playback Probe",
    "auth_probe_step_optional": "Auth Probe (Optional)",
    "final_validation_export": "Final Validation & Export",
}
step_instruction = {
    "target_url_input": "Ziel-URL eingeben und laden.",
    "home_probe_step": "Startseite laden und stabilen Home-Traffic erzeugen.",
    "search_probe_step": "Suche ausfuehren, bis Such-Traffic gesaettigt ist.",
    "detail_probe_step": "Detailseite oeffnen und Detail-Requests erfassen.",
    "playback_probe_step": "Playback starten und Resolver/Manifest erfassen.",
    "auth_probe_step_optional": "Optional anmelden, um Auth-/Refresh-Evidenz zu erfassen.",
    "final_validation_export": "Saettigung pruefen und missionrelevante Exporte finalisieren.",
}

artifact_id_by_contract_file = {
    "site_runtime_model.json": "site_runtime_model",
    "fishit_provider_draft.json": "fishit_provider_draft",
    "source_pipeline_bundle.json": "source_pipeline_bundle",
    "manifest.json": "source_bundle_manifest",
    "source_plugin_bundle.zip": "source_plugin_bundle",
    "webapp_runtime_draft.json": "webapp_runtime_draft",
    "endpoint_templates.json": "endpoint_templates",
    "field_matrix.json": "field_matrix",
    "auth_draft.json": "auth_draft",
    "playback_draft.json": "playback_draft",
    "confidence_report.json": "confidence_report",
    "warnings.json": "warnings",
    "replay_bundle.json": "replay_bundle",
    "runtime_events.jsonl": "runtime_events",
    "response_index.json": "response_index",
    "fixture_manifest.json": "fixture_manifest",
}
artifact_path_aliases = {
    "site_runtime_model": ["site_profile.draft.json", "site_runtime_model.json"],
    "fishit_provider_draft": ["provider_draft_export.json", "fishit_provider_draft.json"],
    "source_pipeline_bundle": ["source_pipeline_bundle.json"],
    "source_bundle_manifest": ["manifest.json"],
    "source_plugin_bundle": ["exports/source_plugin_bundle.zip", "source_plugin_bundle.zip"],
    "webapp_runtime_draft": ["webapp_runtime_draft.json"],
    "endpoint_templates": ["endpoint_candidates.json", "endpoint_templates.json"],
    "field_matrix": ["field_matrix.json"],
    "auth_draft": ["replay_requirements.json", "auth_draft.json"],
    "playback_draft": ["replay_seed.json", "playback_draft.json"],
    "confidence_report": ["pipeline_ready_report.json", "confidence_report.json"],
    "warnings": ["mission_export_summary.json", "warnings.json"],
    "replay_bundle": ["replay_seed.json", "replay_bundle.json"],
    "runtime_events": ["events/runtime_events.jsonl", "runtime_events.jsonl"],
    "response_index": ["response_index.json"],
    "fixture_manifest": ["fixture_manifest.json"],
}


def mission_ids():
    ordered = []
    for mission_id in mission_kinds:
        if mission_id == "CUSTOM":
            continue
        if mission_id in built_in and mission_id not in ordered:
            ordered.append(mission_id)
    for mission_id in built_in.keys():
        if mission_id not in ordered:
            ordered.append(mission_id)
    return ordered


def artifact_id_from_file(relative_path: str) -> str:
    base = relative_path.rsplit("/", 1)[-1]
    if base in artifact_id_by_contract_file:
        return artifact_id_by_contract_file[base]
    normalized = base.replace(".jsonl", "").replace(".json", "").replace(".", "_")
    return normalized or "artifact"


missions = []
for mission_id in mission_ids():
    preset = built_in.get(mission_id) or {}
    flow = flow_by_mission.get(mission_id) or {}
    required_steps = list(flow.get("required_steps") or [])
    optional_steps = list(flow.get("optional_steps") or [])
    ordered_steps = required_steps + [step for step in optional_steps if step not in required_steps]

    evidence_matrix = preset.get("requiredEvidenceMatrix") or {}
    steps = []
    for step_id in ordered_steps:
        step_required_signals = list((evidence_matrix.get(step_id) or {}).get("requiredSignals") or [])
        steps.append(
            OrderedDict(
                [
                    ("step_id", step_id),
                    ("display_name", step_display.get(step_id, step_id)),
                    ("optional", step_id in optional_steps),
                    ("phase_id", step_phase.get(step_id)),
                    ("browser_instruction", step_instruction.get(step_id, "")),
                    ("required_signals", step_required_signals),
                ]
            )
        )

    required_files = list((mission_specific_bundles.get(mission_id) or {}).get("required_files") or [])
    required_artifacts = []
    seen_artifact_ids = set()
    for rel in required_files:
        artifact_id = artifact_id_from_file(rel)
        if artifact_id in seen_artifact_ids:
            continue
        seen_artifact_ids.add(artifact_id)
        aliases = list(artifact_path_aliases.get(artifact_id) or [])
        if rel not in aliases:
            aliases.insert(0, rel)
        deduped_paths = []
        seen_paths = set()
        for candidate in aliases:
            if candidate and candidate not in seen_paths:
                seen_paths.add(candidate)
                deduped_paths.append(candidate)
        required_artifacts.append(
            OrderedDict(
                [
                    ("id", artifact_id),
                    ("paths", deduped_paths),
                ]
            )
        )

    mission_availability = availability.get(mission_id) or {}
    implemented = bool(mission_availability.get("implemented", mission_id == "FISHIT_PIPELINE"))
    notes = str(mission_availability.get("notes") or "").strip()

    missions.append(
        OrderedDict(
            [
                ("mission_id", mission_id),
                ("display_name", str(preset.get("displayName") or mission_id)),
                ("description", str(preset.get("description") or "")),
                ("implemented", implemented),
                ("availability_notes", notes),
                ("required_probe_set", list(preset.get("requiredProbeSet") or [])),
                ("optional_probe_set", list(preset.get("optionalProbeSet") or [])),
                ("expected_output_targets", list(preset.get("outputTargets") or [])),
                ("required_steps", required_steps),
                ("optional_steps", optional_steps),
                ("steps", steps),
                ("required_artifacts", required_artifacts),
            ]
        )
    )

payload = OrderedDict(
    [
        ("schema_version", 1),
        ("source_contract", "contracts/mapper_target.yaml"),
        ("missions", missions),
    ]
)

json.dump(payload, sys.stdout, ensure_ascii=True, indent=2)
sys.stdout.write("\n")
PY
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
    mission_registry_json)
      render_mission_registry_json "$source_file" >>"$tmp_file"
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
