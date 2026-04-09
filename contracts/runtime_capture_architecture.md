# Runtime Capture Platinum Architecture (Normative)

## Raw Event Store SSOT
- `logs/device/mapper-toolkit/current/events/runtime_events.jsonl` is the append-only source of truth.
- All derived outputs are produced from raw events through deterministic normalization.
- Derived artifacts are advisory views and must not mutate or overwrite raw capture history.

## Mission-First Wizard Entry
- Mission selection is the required entrypoint before browser launch.
- Wizard execution is guided-manual: the user performs actions in EinkBro while mapper validates step evidence.
- Wizard progress is machine-visible via:
  - `mission_event`
  - `wizard_event`
  - `overlay_anchor_event`
- Mission semantic operations:
  - `mission_selected`
  - `mission_config_applied`
  - `capture_started`
  - `capture_finished`
  - `export_requested`
  - `export_finalized`
- Wizard saturation states are:
  - `INCOMPLETE`
  - `NEEDS_MORE_EVIDENCE`
  - `SATURATED`
  - `BLOCKED`

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
  - `target_document`
  - `target_api`
  - `target_playback`
  - `target_asset`
  - `browser_bootstrap`
  - `google_noise`
  - `analytics_noise`
  - `background_noise`
  - `ignored`
- Deterministic precedence:
  - invalid/missing host -> `ignored`
  - target + playback hints -> `target_playback`
  - target + api/json/graphql hints -> `target_api`
  - target + static asset hints -> `target_asset`
  - target fallback -> `target_document`
  - non-target browser bootstrap -> `browser_bootstrap`
  - non-target google -> `google_noise`
  - non-target analytics -> `analytics_noise`
  - final fallback -> `background_noise`
- Signal-eligible classes:
  - `target_document`
  - `target_api`
  - `target_playback`
- Noise classes are excluded from endpoint/auth scoring.

## Candidate-Only Body Capture
- All requests/responses keep metadata; heavy body blobs are stored only for candidate responses.
- Candidate capture policy must persist:
  - `body_capture_policy`
  - `candidate_relevance`
  - `capture_reason`
  - `capture_truncated`
  - `capture_limit_bytes`
  - `content_length_header`
  - `original_content_length`
  - `stored_size_bytes`
  - `truncation_reason`
- Body blobs are content-addressed by SHA-256 and compressed when available.
- Candidate bodies use an explicit 16 MB cap; any cap hit must set truncation metadata and must never be silent.
- Resolver output is deterministic and uses:
  - `STORE_FULL_REQUIRED`
  - `STORE_FULL`
  - `STORE_TRUNCATED`
  - `STORE_METADATA_ONLY`
  - `SKIP_BODY`

## Truncation Policy Matrix
- Full-required classes (`STORE_FULL_REQUIRED`):
  - GraphQL JSON
  - Candidate REST JSON
  - Playback manifests (`.m3u8`, `.mpd`)
  - Playback resolver payloads
  - Required bootstrap/config JSON
  - Candidate HTML documents (`candidate_document=true`)
- Truncation-allowed classes (`STORE_TRUNCATED`):
  - Large non-candidate HTML
  - Debug media-segment capture overrides
- Metadata-only classes (`STORE_METADATA_ONLY`):
  - Generic playback media segments
  - Binary/non-signal assets
  - Analytics/ad/noise payloads
- Skip classes (`SKIP_BODY`):
  - Ignored/non-URL noise rows that should never carry body blobs
- A 4 MB (`4194304`) stored candidate body is treated as a cap signal and must emit:
  - `capture_truncated=true`
  - `capture_limit_bytes=4194304`
  - `truncation_reason=body_size_limit`
- `truncation_event` rows are emitted for all truncations and required-body failures.

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
- Mission finalization writes `mission_export_summary.json` with:
  - `export_readiness`
  - `reason`
  - `missing_required_steps`
  - `missing_required_files`
  - `pipeline_ready`
  - `hard_gates_passed`
  - `gate_results`
  - `failed_gates`
- `READY` is a hard gate result and requires:
  - required mission steps saturated
  - required mission artifacts present
  - finalized export archive present
  - pipeline quality gate ready
  - provider export schema validation pass (`contracts/provider_draft_export.schema.json`)
  - replay requirements non-empty for endpoint context
  - minimum provider field-matrix coverage threshold
  - source bundle host-compatibility gate pass:
    - `manifest.json.mainContract == source_pipeline_bundle.json`
    - zip contains `source_pipeline_bundle.json`, `site_runtime_model.json`, `manifest.json`
    - source bundle required top-level keys present with no unknown top-level keys
    - `compatiblePluginApiRange` covers host API major `1`
    - `compatibleRuntimeModelVersion == 1`
    - `compatibleCapabilitySchemaVersion` major `1`
    - capability flags must have executable endpoint/replay coupling:
      - playback -> playback resolver endpoint + replay binding
      - search -> search endpoint + replay binding
      - detail -> detail endpoint + replay binding
      - home sync -> `selectionModel` + `syncModel.homeEndpointRefs` non-empty + each home ref replay-bound
    - when search/detail enabled, `fieldMappings` must provide usable `canonicalId` and `title`
- `latest/` artifacts are published only after successful validation.
- Publishing is atomic via stage directory swap (`latest.stage.*` -> `latest`).
- Failed validation must keep previous `latest/` artifacts unchanged.

## Extraction Event Contract
- Every extraction attempt (success or failure) emits an `extraction_event` in derived exports.
- Required extraction payload fields:
  - `source_ref`
  - `phase_id`
  - `host_class`
  - `extraction_kind`
  - `success`
  - `extracted_field_count`
  - `confidence_summary`

## Provider Draft Export Contract
- Final provider export artifact: `provider_draft_export.json`.
- Contract source of truth: `contracts/provider_draft_export.schema.json`.
- Required top-level fields:
  - `export_id`
  - `export_schema_version`
  - `target_site_id`
  - `generated_at`
  - `source_runtime_export_id`
  - `confidence_summary`
  - `capability_class`
  - `endpoint_templates`
  - `replay_requirements`
  - `field_matrix`
  - `auth_draft`
  - `playback_draft`
  - `warnings`
  - `known_limitations`
- Export must be deterministic for identical input runtime fixtures (byte-stable JSON).

## Replay Minimization Rules
- Minimization starts from captured successful request context and uses native replay where available.
- Required minimization dimensions:
  - headers
  - cookies
  - query parameters
  - selected body fields
  - referer/origin dependence
  - provenance-backed dynamic inputs
- Header/cookie result groups:
  - `required`
  - `optional`
  - `observed_only`
  - `forbidden_noise`
- If active replay is unavailable for a dimension, export must keep conservative observed fallback and emit warning semantics.

## Playback/Auth Export Rules
- Playback draft must prioritize manifest/resolver evidence and ignore segment noise in required sets.
- Playback draft must expose browser-context dependence explicitly when referer/origin or dynamic runtime context is required.
- Auth draft must never expose raw critical token values; only provenance-backed token input names are exported.
- Auth modes must distinguish cookie-backed, header-token-backed, browser-context-required, and hybrid sessions.

## Confidence and Warning Semantics
- Confidence exists at:
  - export level
  - endpoint template level
  - replay requirement level
  - playback/auth draft level
  - field level
- Warnings are mandatory when:
  - truncated candidate bodies influence templates/replay
  - browser-context dependence remains
  - minimization cannot prove optionality
  - playback/auth still depends on dynamic token generation
  - field coverage remains incomplete

## WebKit Boundary
- `mapper-webkit-compat` is limited to WebView capability checks, runtime safety hooks, and deterministic site-data reset helpers.
- It must not replace browser core behavior and must not become a mapper business-logic container.

## Cronet Boundary
- `mapper-native-network-cronet` is used for mapper-owned native replay/validation/auth/playback-preflight requests.
- WebView page loading remains in the browser/WebView stack and is not routed through Cronet.

## Browser Foundation
- EinkBro remains the browser foundation.
- Mapper logic is layered around it and should remain outside borrowed core code paths where feasible.
- Wizard orchestration and saturation logic must stay in mapper-owned layers, not donor browser core.
