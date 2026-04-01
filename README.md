# FishIT-Mapper

Dieses Repository folgt einem contract-first Neuaufbau.

Normative Quelle ist ausschliesslich `contracts/`.
`docs/` ist nur generatorgetriebenes Team-Interface plus Archiv.

Wichtige lokale Gates:
- `./scripts/generate.sh --check`
- `./scripts/guards/decision-guard.sh`
- `./scripts/guards/roadmap-guard.sh`
- `./scripts/guards/ci-contract-guard.sh`
- `./scripts/guards/dependency-intake-guard.sh`
- `./scripts/guards/hard-cutover-guard.sh`
- `./scripts/guards/handover-guard.sh`

Generator-Lauf:
- `./scripts/generate.sh`

Session-Handover (verpflichtend pro Agent-Session):
- `./scripts/handover/create-handover.sh --scope "<scope>" --highlight "<wichtigster Punkt>"`

Wave-01 Build und Device-Smoke:
- `./scripts/waves/build-wave01.sh`
- `./scripts/waves/device-smoke-wave01.sh`

Weitere generatorgetriebene Team-Interfaces:
- `docs/ROADMAP.md`
- `docs/AGENT_RULESET.md`
- `docs/DELIVERY_GATES.md`
- `docs/DEPENDENCY_INTAKE.md`
