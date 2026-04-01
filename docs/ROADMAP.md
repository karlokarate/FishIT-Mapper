<!-- GENERATED FILE - DO NOT EDIT DIRECTLY. -->
<!-- source: contracts/roadmap.json -->
<!-- regenerate via: ./scripts/generate.sh -->

# Roadmap

Plan ID: `fishit-mapper-v2-platinum-roadmap`
Execution strategy: `bottom_up_agent_first_vertical_slices`
Release rule: `next_phase_requires_all_exit_criteria_and_required_gates_green`

## Workstreams

- `WS-A` - Governance and Contracts: Maintain normative contracts, generators, guards, and decision traceability.
- `WS-B` - Browser and Capture: Implement browser-near observation, session capture, and event collection.
- `WS-C` - Classifier and Profile: Build candidate scoring, capability classification, and profile contracts.
- `WS-D` - Replay and Validation: Deliver deterministic replay flows and replay validation reporting.
- `WS-E` - External Bridge and Import Contract: Provide provider-scoped bridge outputs and FishIT-Player import path.

## Phases

### P0 - agent_governance_foundation

**Depends on:** none

**Entry criteria**
- contracts_ssot_exists
- docs_generation_pipeline_exists

**Exit criteria**
- agent_ruleset_contract_accepted
- roadmap_contract_accepted
- ci_contract_accepted
- required_docs_generated_without_drift
- blocking_ci_guard_job_enabled
- pr_template_requires_scope_contracts_gates_handover

**Workstreams**
- WS-A

**Required outputs**
- docs/ROADMAP.md
- docs/AGENT_RULESET.md
- docs/DELIVERY_GATES.md
- contracts/decision_trace.json
- handovers/HO-*.json

**Required gates**
- generate-check
- decision-guard
- hard-cutover-guard
- handover-guard
- roadmap-guard
- ci-contract-guard

### P1 - browser_near_mapping_lab

**Wave:** WAVE-01
**Artifact target:** `artifacts/wave01/mapper-debug.apk`
**Device required:** `true`
**Acceptance checks**
- apk_installable_on_physical_device
- main_activity_launches_on_device
- browser_lab_loads_https_example_org

**Depends on:** P0

**Entry criteria**
- p0_exit_criteria_satisfied

**Exit criteria**
- embedded_browser_flow_operational
- structured_jsonl_events_emitted_for_major_actions
- session_snapshot_exported_machine_readable

**Workstreams**
- WS-B
- WS-A

**Required outputs**
- artifacts/wave01/mapper-debug.apk
- artifacts/wave01/device-smoke.json
- structured_log_stream.jsonl
- session_metadata.json

**Required gates**
- generate-check
- decision-guard
- hard-cutover-guard
- handover-guard
- roadmap-guard
- ci-contract-guard
- dependency-intake-guard

### P2 - candidate_detection_and_classification

**Depends on:** P1

**Entry criteria**
- p1_exit_criteria_satisfied

**Exit criteria**
- home_and_search_candidates_scored
- capability_classification_generated_with_confidence
- draft_profile_generated_machine_readable

**Workstreams**
- WS-C
- WS-B
- WS-A

**Required outputs**
- capability_scan_report.json
- site_profile.json

**Required gates**
- generate-check
- decision-guard
- hard-cutover-guard
- handover-guard
- roadmap-guard
- ci-contract-guard

### P3 - replay_and_validation

**Depends on:** P2

**Entry criteria**
- p2_exit_criteria_satisfied

**Exit criteria**
- at_least_one_deterministic_replay_flow_passes
- session_restore_validated_or_failure_classified
- replay_report_generated_per_profile

**Workstreams**
- WS-D
- WS-C
- WS-A

**Required outputs**
- replay_report.json
- session_metadata.json

**Required gates**
- generate-check
- decision-guard
- hard-cutover-guard
- handover-guard
- roadmap-guard
- ci-contract-guard

### P4 - external_catalog_bridge

**Depends on:** P3

**Entry criteria**
- p3_exit_criteria_satisfied

**Exit criteria**
- provider_scoped_home_sections_mirrored
- unified_external_search_result_model_available
- provider_scoped_playback_resolution_available

**Workstreams**
- WS-E
- WS-D
- WS-A

**Required outputs**
- site_profile.json
- capability_scan_report.json
- structured_log_stream.jsonl

**Required gates**
- generate-check
- decision-guard
- hard-cutover-guard
- handover-guard
- roadmap-guard
- ci-contract-guard

### P5 - fishit_player_import_path

**Depends on:** P4

**Entry criteria**
- p4_exit_criteria_satisfied

**Exit criteria**
- profile_import_contract_ready
- minimal_runtime_adapter_contract_ready
- reauth_and_session_status_signals_defined

**Workstreams**
- WS-E
- WS-C
- WS-A

**Required outputs**
- site_profile.json
- replay_report.json
- capability_scan_report.json

**Required gates**
- generate-check
- decision-guard
- hard-cutover-guard
- handover-guard
- roadmap-guard
- ci-contract-guard

