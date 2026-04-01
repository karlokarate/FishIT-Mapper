<!-- GENERATED FILE - DO NOT EDIT DIRECTLY. -->
<!-- source: contracts/ci_contract.json -->
<!-- regenerate via: ./scripts/generate.sh -->

# Delivery Gates

Contract ID: `fishit-mapper-ci-contract`
Failure policy: `fail_fast_block_merge`

## Local Contract

- blocking: `true`
- commands:
  - `./scripts/generate.sh --check`
  - `./scripts/guards/decision-guard.sh`
  - `./scripts/guards/hard-cutover-guard.sh`
  - `./scripts/guards/handover-guard.sh`
  - `./scripts/guards/roadmap-guard.sh`
  - `./scripts/guards/ci-contract-guard.sh`
  - `./scripts/guards/dependency-intake-guard.sh`

## CI Jobs

### contract-and-guard-gate (order: 1)
- blocking: `true`
- commands:
  - `./scripts/generate.sh --check`
  - `./scripts/guards/decision-guard.sh`
  - `./scripts/guards/roadmap-guard.sh`
  - `./scripts/guards/ci-contract-guard.sh`
  - `./scripts/guards/dependency-intake-guard.sh`
  - `./scripts/guards/hard-cutover-guard.sh`
  - `./scripts/guards/handover-guard.sh`
- artifacts:
  - `docs/V2_ssot.yaml`
  - `docs/ROADMAP.md`
  - `docs/AGENT_RULESET.md`
  - `docs/DELIVERY_GATES.md`
  - `docs/DEPENDENCY_INTAKE.md`

### handover-presence-and-validity (order: 2)
- blocking: `true`
- commands:
  - `./scripts/guards/handover-guard.sh`
- artifacts:
  - `handovers/HO-*.json`

### wave01-debug-apk-build (order: 3)
- blocking: `true`
- commands:
  - `./scripts/waves/build-wave01.sh`
- artifacts:
  - `artifacts/wave01/mapper-debug.apk`
  - `artifacts/wave01/build-metadata.json`

