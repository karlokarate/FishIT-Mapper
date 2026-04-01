# Runtime Capture Platinum Architecture (Normative)

## Raw Event Store SSOT
- `logs/device/mapper-toolkit/current/events/runtime_events.jsonl` is the append-only source of truth.
- All derived outputs are produced from raw events through deterministic normalization.
- Derived artifacts are advisory views and must not mutate or overwrite raw capture history.

## Phase Model
- Canonical phases:
  - `home_probe`
  - `search_probe`
  - `detail_probe`
  - `playback_probe`
  - `auth_probe`
  - `background_noise`
- Every event must resolve to exactly one phase.
- `probe_phase_event` transitions are first-class markers and are used to resolve phase context deterministically.

## Host Classification Model
- Host classification is target-family aware and deterministic.
- Canonical host classes:
  - `target`
  - `provider_bootstrap`
  - `browser_bootstrap`
  - `google_noise`
  - `analytics_noise`
  - `ad_noise`
  - `external_noise`
  - `background_noise`
  - `ignored`
  - `unknown`
- `target` and `provider_bootstrap` are signal-eligible.
- Noise classes are excluded from endpoint/auth scoring.

## Candidate-Only Body Capture
- All requests/responses keep metadata; heavy body blobs are stored only for candidate responses.
- Candidate capture policy must persist:
  - `body_capture_policy`
  - `capture_reason`
  - `capture_truncated`
  - `capture_limit_bytes`
  - `content_length_header`
- Body blobs are content-addressed by SHA-256 and compressed when available.

## Provenance Model
- Dynamic runtime inputs are persisted in `provenance_registry.json`.
- Each provenance entry tracks:
  - `name`
  - `value_hash`
  - `safe_value_repr`
  - `source_type`
  - `source_refs`
  - `first_seen_ts`
  - `last_seen_ts`
  - `phase_id`
  - `target_site_id`
- `provenance_graph.json` remains the edge-centric relation view.

## Finalization and Atomic Export
- Rollups are generated from normalized raw events only.
- `latest/` artifacts are published only after successful validation.
- Publishing is atomic via stage directory swap (`latest.stage.*` -> `latest`).
- Failed validation must keep previous `latest/` artifacts unchanged.

## WebKit Boundary
- `mapper-webkit-compat` is limited to WebView capability checks, runtime safety hooks, and deterministic site-data reset helpers.
- It must not replace browser core behavior and must not become a mapper business-logic container.

## Cronet Boundary
- `mapper-native-network-cronet` is used for mapper-owned native replay/validation/auth/playback-preflight requests.
- WebView page loading remains in the browser/WebView stack and is not routed through Cronet.

## Browser Foundation
- EinkBro remains the browser foundation.
- Mapper logic is layered around it and should remain outside borrowed core code paths where feasible.
