# FishIT Runtime Toolkit

Debug-only runtime supervisor for resilient, re-entrant device control and observability.

## Trigger Contract
- Chat trigger phrase: `FISHIT_RUNTIME_TOOLKIT_ON`
- Shell trigger: `scripts/device/fishit-runtime-toolkit.sh on --profile full`
- Contract file: `scripts/device/toolkit/runtime_toolkit.contract.json`

## Core Commands
- `scripts/device/fishit-runtime-toolkit.sh on --profile full`
- `scripts/device/fishit-runtime-toolkit.sh status`
- `scripts/device/fishit-runtime-toolkit.sh doctor`
- `scripts/device/fishit-runtime-toolkit.sh snapshot`
- `scripts/device/fishit-runtime-toolkit.sh resume --target home`
- `scripts/device/fishit-runtime-toolkit.sh inspect-work --title "Werkname"`
- `scripts/device/fishit-runtime-toolkit.sh query-entities --entity-type NX_Work --filters-json '{"predicates":[{"field":"workType","op":"eq","value":"SERIES"}]}'`
- `scripts/device/fishit-runtime-toolkit.sh off`

Optional capture flags (special cases):
- `--ui-evidence-mode off|minimal|full` (screenshot-free, machine-readable UI capture)
- `--with-screenshot` (PNG screenshot artifact)
- `--with-perfetto` (short perfetto trace artifact, best-effort)

## tmux Lanes
- `fishit_rt_logcat`: rotating logcat chunk capture
- `fishit_rt_guard`: heartbeat (`adb state`, `topResumedActivity`, `pid`)
- `fishit_rt_watchdog`: restarts missing lanes
- `fishit_rt_perf`: `meminfo`, `gfxinfo framestats`, `top`
- `fishit_rt_parser`: incremental parse/index + rollups

## Artifact Layout
- Active run: `logs/device/runtime-toolkit/current/`
- Archived runs: `logs/device/runtime-toolkit/archive/<run-id>/`
- Rollups:
  - `logs/device/runtime-toolkit/current/rollups/runtime_health.json`
  - `logs/device/runtime-toolkit/current/rollups/runtime_perf_summary.json`
  - `logs/device/runtime-toolkit/current/rollups/runtime_sync_summary.json`

## Work-Centric Inspection
`inspect-work` creates a deterministic artifact bundle for one work:
- detail open (direct route)
- DB work graph export (`NX_Work`, `SourceRef`, `Variants`, `Relations`, `UserState`, etc.)
- log trace lines matching `workKey`/title

Output path:
- `logs/device/runtime-toolkit/current/snapshots/inspect_work_<UTC>/`

Files:
- `latest.meta.json`
- `latest.graph.json`
- `log_trace.txt`
- `log_trace_summary.json`
- `inspect_manifest.json`
- optional: `ui_hierarchy.xml`, `ui_clickables.json`, `ui_anchors.tsv`, `screenshot.png`, `perfetto_trace.pftrace`

## Generic Entity Query
`query-entities` executes field-level filters across one entity or all entities (`ALL` / `*`) and stores machine-readable results.

Output path:
- `logs/device/runtime-toolkit/current/snapshots/query_entities_<UTC>/`

Files:
- `latest.meta.json`
- `latest.json`
- `query_manifest.json`

## UI Command Matrix
Build/update static-first matrix:
- `python3 scripts/device/toolkit/build_command_matrix.py`

Runtime navigation helpers:
- `scripts/device/ui-anchor-nav.sh open-screen settings`
- `scripts/device/ui-anchor-nav.sh open-detail --work-key "..." --source-type TELEGRAM`
- `scripts/device/ui-anchor-nav.sh set-setting --key diag_sync_enabled --value true`
- `scripts/device/ui-anchor-nav.sh clickables --screen live --output /tmp/live_clickables.json`

## Safety
Default flow is non-destructive.
Forbidden by default in normal toolkit flow:
- `adb kill-server`
- `am start -S`
