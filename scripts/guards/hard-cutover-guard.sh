#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTRACT_FILE="$ROOT_DIR/contracts/doc_contract.json"

jq -e . "$CONTRACT_FILE" >/dev/null
jq -e . "$ROOT_DIR/contracts/generation_plan.json" >/dev/null
jq -e . "$ROOT_DIR/contracts/decision_trace.json" >/dev/null
jq -e . "$ROOT_DIR/contracts/roadmap.json" >/dev/null
jq -e . "$ROOT_DIR/contracts/agent_ruleset.json" >/dev/null
jq -e . "$ROOT_DIR/contracts/ci_contract.json" >/dev/null
jq -e . "$ROOT_DIR/contracts/dependency_intake.json" >/dev/null

"$ROOT_DIR/scripts/generate.sh" --check
"$ROOT_DIR/scripts/guards/decision-guard.sh"
"$ROOT_DIR/scripts/guards/roadmap-guard.sh"
"$ROOT_DIR/scripts/guards/ci-contract-guard.sh"
"$ROOT_DIR/scripts/guards/dependency-intake-guard.sh"

mapfile -t allowed_exact < <(jq -r '.allowed_docs_paths[] | select(endswith("/**") | not)' "$CONTRACT_FILE")
mapfile -t allowed_prefix < <(jq -r '.allowed_docs_paths[] | select(endswith("/**")) | sub("/\\*\\*$"; "/")' "$CONTRACT_FILE")

while IFS= read -r doc_file; do
  is_allowed=0

  for exact in "${allowed_exact[@]}"; do
    if [[ "$doc_file" == "$ROOT_DIR/$exact" ]]; then
      is_allowed=1
      break
    fi
  done

  if [[ $is_allowed -eq 0 ]]; then
    for prefix in "${allowed_prefix[@]}"; do
      if [[ "$doc_file" == "$ROOT_DIR/$prefix"* ]]; then
        is_allowed=1
        break
      fi
    done
  fi

  if [[ $is_allowed -eq 0 ]]; then
    echo "docs/ contains non-allowed file: ${doc_file#$ROOT_DIR/}" >&2
    exit 1
  fi
done < <(find "$ROOT_DIR/docs" -type f | sort)

mapfile -t generated_outputs < <(jq -r '.canonical_sources[].generated_outputs[]?' "$CONTRACT_FILE")
while IFS= read -r ssot_file; do
  rel_file="${ssot_file#$ROOT_DIR/}"
  allowed=0
  for generated in "${generated_outputs[@]}"; do
    if [[ "$rel_file" == "$generated" ]]; then
      allowed=1
      break
    fi
  done
  if [[ $allowed -eq 0 ]]; then
    echo "Unexpected ssot-like yaml outside contracts/: $rel_file" >&2
    exit 1
  fi
done < <(find "$ROOT_DIR" -type f -name '*ssot*.yaml' ! -path "$ROOT_DIR/contracts/*" | sort)

echo "hard-cutover-guard passed"
