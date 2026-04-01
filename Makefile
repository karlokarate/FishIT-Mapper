.PHONY: generate generate-check decision-guard hard-cutover-guard handover-guard roadmap-guard ci-contract-guard dependency-intake-guard guard handover wave01-build wave01-device-smoke

generate:
	./scripts/generate.sh

generate-check:
	./scripts/generate.sh --check

decision-guard:
	./scripts/guards/decision-guard.sh

hard-cutover-guard:
	./scripts/guards/hard-cutover-guard.sh

handover-guard:
	./scripts/guards/handover-guard.sh

roadmap-guard:
	./scripts/guards/roadmap-guard.sh

ci-contract-guard:
	./scripts/guards/ci-contract-guard.sh

dependency-intake-guard:
	./scripts/guards/dependency-intake-guard.sh

guard: generate-check decision-guard roadmap-guard ci-contract-guard dependency-intake-guard hard-cutover-guard handover-guard

handover:
	@echo "Usage example:"
	@echo "./scripts/handover/create-handover.sh --scope \"<scope>\" --highlight \"<important note>\""

wave01-build:
	./scripts/waves/build-wave01.sh

wave01-device-smoke:
	./scripts/waves/device-smoke-wave01.sh
