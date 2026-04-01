#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CI_FILE="$ROOT_DIR/contracts/ci_contract.json"
DOC_CONTRACT_FILE="$ROOT_DIR/contracts/doc_contract.json"
AGENT_RULESET_FILE="$ROOT_DIR/contracts/agent_ruleset.json"
MAKEFILE="$ROOT_DIR/Makefile"

jq -e '
  .version == 1
  and (.failure_policy | type == "string" and length > 0)
  and (.local_contract.commands | type == "array" and length > 0)
  and (.jobs | type == "array" and length > 0)
' "$CI_FILE" >/dev/null

jq -e '([.jobs[].job_id] | length) == ([.jobs[].job_id] | unique | length)' "$CI_FILE" >/dev/null
jq -e '([.jobs[].order] | length) == ([.jobs[].order] | unique | length)' "$CI_FILE" >/dev/null

mapfile -t gate_commands < <(jq -r '.gates[].command' "$DOC_CONTRACT_FILE")
mapfile -t local_commands < <(jq -r '.local_contract.commands[]' "$CI_FILE")
mapfile -t ci_commands < <(jq -r '.jobs[].commands[]' "$CI_FILE")
mapfile -t must_run_gates < <(jq -r '.must_run_gates[]' "$AGENT_RULESET_FILE")
mapfile -t gate_ids < <(jq -r '.gates[].id' "$DOC_CONTRACT_FILE")

for gate_id in "${must_run_gates[@]}"; do
  if ! printf '%s\n' "${gate_ids[@]}" | grep -Fxq "$gate_id"; then
    echo "must_run_gates contains unknown gate id: $gate_id" >&2
    exit 1
  fi
done

for cmd in "${gate_commands[@]}"; do
  if ! printf '%s\n' "${local_commands[@]}" | grep -Fxq "$cmd"; then
    echo "Gate command missing in ci_contract.local_contract.commands: $cmd" >&2
    exit 1
  fi

  if ! printf '%s\n' "${ci_commands[@]}" | grep -Fxq "$cmd"; then
    echo "Gate command missing in ci_contract.jobs.commands: $cmd" >&2
    exit 1
  fi

  if ! grep -Fq "$cmd" "$MAKEFILE"; then
    echo "Gate command missing in Makefile: $cmd" >&2
    exit 1
  fi
done

jq -e '
  all(.jobs[];
    has("job_id")
    and has("blocking")
    and has("order")
    and has("commands")
    and has("artifacts")
    and (.commands | type == "array" and length > 0)
    and (.artifacts | type == "array" and length > 0)
  )
' "$CI_FILE" >/dev/null

jq -e '.jobs | any(.job_id == "wave01-debug-apk-build" and .blocking == true)' "$CI_FILE" >/dev/null

while IFS= read -r cmd; do
  script_token="$(awk '{print $1}' <<<"$cmd")"
  script_path="${script_token#./}"
  if [[ ! -f "$ROOT_DIR/$script_path" ]]; then
    echo "CI command references missing script: $cmd" >&2
    exit 1
  fi
done < <(jq -r '.jobs[].commands[] | select(startswith("./scripts/"))' "$CI_FILE")

echo "ci-contract-guard passed"
