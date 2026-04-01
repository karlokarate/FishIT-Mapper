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

### dep-fishit-player-runtime-toolkit - FishIT Runtime Toolkit
- kind: `tool`
- source_url: `https://github.com/karlokarate/FishIT-Player`
- target_module: `device_runtime_toolkit`
- integration_mode: `adapted_copy`
- pinned_ref: `HEAD`
- local_path: `scripts/device`
- license: `project_internal`
- status: `integrated`
- notes: Copied from FishIT-Player scripts/device and adapted for mapper app id and codespace defaults.

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
- pinned_ref: `TBD`
- local_path: `third_party/mitmproxy`
- license: `MIT`
- status: `planned`
- notes: Used as external tooling companion, not runtime app dependency.

### dep-playwright - Playwright
- kind: `tool`
- source_url: `https://github.com/microsoft/playwright`
- target_module: `tooling_playwright`
- integration_mode: `external_tool_reference`
- pinned_ref: `TBD`
- local_path: `third_party/playwright`
- license: `Apache-2.0`
- status: `planned`
- notes: Used for replay validation in external tooling loops.

