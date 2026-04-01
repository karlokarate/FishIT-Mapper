<!-- GENERATED FILE - DO NOT EDIT DIRECTLY. -->
<!-- source: contracts/agent_ruleset.json -->
<!-- regenerate via: ./scripts/generate.sh -->

# Agent Ruleset

Ruleset ID: `fishit-mapper-agent-ruleset`

## Agent Roles

### governance_agent
- maintain_contracts_generation_and_guards
- enforce_decision_traceability
- reject_non_contract_normative_sources

### implementation_agent
- implement_features_via_contract_first_flow
- update_generated_interfaces_via_generator_only
- deliver_machine_readable_outputs_per_phase

### validation_agent
- execute_required_gates
- report_gate_failures_with_root_cause
- block_progress_on_phase_exit_criteria_violation

## Must-Run Gates

- generate-check
- decision-guard
- hard-cutover-guard
- handover-guard
- roadmap-guard
- ci-contract-guard

## Handover Requirements

- required: `true`
- frequency: `end_of_each_agent_session`
- schema_path: `contracts/handover.schema.json`
- output_directory: `handovers`
- required_fields:
  - schema_version
  - handover_id
  - generated_at_utc
  - agent
  - scope
  - highlights
  - changed_files

## PR Requirements

- required: `true`
- template_path: `.github/pull_request_template.md`
- required_sections:
  - scope
  - affected_contracts
  - gate_status
  - handover_id

## Decision Trace Requirements

- required: `true`
- path: `contracts/decision_trace.json`
- must_include:
  - id
  - aliases
  - supersedes
  - superseded_by

## Drift Prohibitions

- docs_manual_normative_edits_forbidden
- generated_outputs_must_not_be_edited_directly
- phase_progress_without_gate_green_forbidden
- workstream_output_without_handover_forbidden
