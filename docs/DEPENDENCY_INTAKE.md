<!-- GENERATED FILE - DO NOT EDIT DIRECTLY. -->
<!-- source: contracts/dependency_intake.json -->
<!-- regenerate via: ./scripts/generate.sh -->

# Dependency Intake

Intake ID: `fishit-mapper-external-dependency-intake`
Integration policy: `vendor_snapshot_pinned_commit`

## Items

### dep-einkbro - EinkBro
- kind: `repo`
- source_url: `https://github.com/plateaukao/einkbro`
- target_module: `feature_browser_lab`
- integration_mode: `vendor_snapshot`
- pinned_ref: `26faa847c9b50211f4372fb7efbb0187c7cd7b7a`
- local_path: `third_party/einkbro`
- license: `AGPL-3.0`
- status: `integrated`
- notes: Imported as pinned snapshot to bootstrap browser base patterns.

### dep-mapper-app-wave01 - Mapper App Wave01 Fork Baseline
- kind: `repo`
- source_url: `https://github.com/plateaukao/einkbro`
- target_module: `app_lab`
- integration_mode: `derived_from_vendor_snapshot`
- pinned_ref: `26faa847c9b50211f4372fb7efbb0187c7cd7b7a`
- local_path: `apps/mapper-app`
- license: `AGPL-3.0`
- status: `integrated`
- notes: Runtime build baseline for Wave-01; third_party snapshot remains immutable reference.

### dep-fishit-player-runtime-toolkit - Mapper-Toolkit Runtime Baseline
- kind: `tool`
- source_url: `https://github.com/karlokarate/FishIT-Player`
- target_module: `device_runtime_toolkit`
- integration_mode: `adapted_copy`
- pinned_ref: `HEAD`
- local_path: `scripts/device`
- license: `project_internal`
- status: `integrated`
- notes: Initial seed imported from FishIT-Player only; Mapper-Toolkit v2 now runs as independent portable CLI/TUI without FishIT-Player path/function backward-compatibility guarantees.

### dep-request-inspector-webview - Android-Request-Inspector-WebView
- kind: `repo`
- source_url: `https://github.com/acsbendi/Android-Request-Inspector-WebView`
- target_module: `feature_request_inspector`
- integration_mode: `vendor_snapshot`
- pinned_ref: `TBD`
- local_path: `third_party/android-request-inspector-webview`
- license: `TBD`
- status: `planned`
- notes: Next repository to integrate after EinkBro validation.

### dep-mitmproxy - mitmproxy
- kind: `tool`
- source_url: `https://github.com/mitmproxy/mitmproxy`
- target_module: `tooling_mitmproxy`
- integration_mode: `external_tool_reference`
- pinned_ref: `v11.0.2`
- local_path: `third_party/mitmproxy`
- license: `MIT`
- status: `integrating`
- notes: Hybrid capture optional path for full raw HTTP body/header tracing.

### dep-playwright - Playwright
- kind: `tool`
- source_url: `https://github.com/microsoft/playwright`
- target_module: `tooling_playwright`
- integration_mode: `external_tool_reference`
- pinned_ref: `v1.55.0`
- local_path: `third_party/playwright`
- license: `Apache-2.0`
- status: `integrating`
- notes: Replay and deterministic validation companion tool.

### dep-textual - Textual
- kind: `tool`
- source_url: `https://github.com/Textualize/textual`
- target_module: `tooling_mapper_toolkit_tui`
- integration_mode: `external_tool_reference`
- pinned_ref: `v8.2.1`
- local_path: `third_party/textual`
- license: `MIT`
- status: `integrating`
- notes: Primary Python TUI framework for main.py no-command-input user experience.

### dep-duckdb - DuckDB
- kind: `tool`
- source_url: `https://github.com/duckdb/duckdb`
- target_module: `tooling_query_analytics`
- integration_mode: `external_tool_reference`
- pinned_ref: `v1.2.2`
- local_path: `third_party/duckdb`
- license: `MIT`
- status: `planned`
- notes: Large JSONL/Parquet analysis for field matrix and endpoint heuristics.

### dep-jq - jq
- kind: `tool`
- source_url: `https://github.com/jqlang/jq`
- target_module: `tooling_query_analytics`
- integration_mode: `external_tool_reference`
- pinned_ref: `jq-1.7.1`
- local_path: `third_party/jq`
- license: `MIT`
- status: `planned`
- notes: Fast local filtering pipeline in toolkit trace and response commands.

### dep-perfetto - Perfetto
- kind: `tool`
- source_url: `https://github.com/google/perfetto`
- target_module: `tooling_perfetto`
- integration_mode: `external_tool_reference`
- pinned_ref: `v49.0`
- local_path: `third_party/perfetto`
- license: `Apache-2.0`
- status: `planned`
- notes: Optional timing and latency traces for UI↔backend correlation windows.

