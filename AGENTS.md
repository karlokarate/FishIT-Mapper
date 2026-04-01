# Agent Working Contract

This repository enforces a contract-first workflow.

Rules for every coding agent:

1. Treat `contracts/` as the only normative source of truth.
2. Treat `docs/` as generated team interface and archive only.
3. Never hand-edit generated files in `docs/`; regenerate from contracts instead.
4. Keep all architecture and policy decisions in `contracts/decision_trace.json`.
5. Every decision must include `id`, `aliases`, `supersedes`, and `superseded_by`.
6. Before proposing or merging changes, run:
   - `./scripts/generate.sh --check`
   - `./scripts/guards/decision-guard.sh`
   - `./scripts/guards/roadmap-guard.sh`
   - `./scripts/guards/ci-contract-guard.sh`
   - `./scripts/guards/dependency-intake-guard.sh`
   - `./scripts/guards/hard-cutover-guard.sh`
   - `./scripts/guards/handover-guard.sh`
7. End every session with a versioned, machine-readable handover file in `handovers/`.
8. Generate the handover via:
   - `./scripts/handover/create-handover.sh --scope "<scope>" --highlight "<key point>"`
9. A valid handover must include changed files, scope, and the key outcomes/risks.
10. Delivery order is bottom-up: complete P0 governance gates first, then phase progression only with exit criteria met.
11. If any gate fails, fix contracts/generation first, then regenerate artifacts.
