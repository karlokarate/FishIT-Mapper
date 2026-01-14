# FishIT-Mapper Architecture (MVP)

## Goal

Record website navigation + resource requests (best-effort via Android WebView) and build a reusable **Map Graph**
plus shareable **Export Bundles**.

## Data model strategy

The *domain contract* lives in `:shared:contract` and is **generated** using KotlinPoet from:

- `schema/contract.schema.json`

Why:
- one authoritative schema
- deterministic codegen
- future ability to generate docs / migrations from the same schema

## Modules

- `:tools:codegen-contract`
  - Kotlin/JVM tool
  - reads schema JSON
  - writes Kotlin sources into `:shared:contract/build/generated/...`

- `:shared:contract` (KMP)
  - generated data classes/enums/IDs
  - `FishitJson` config (polymorphic class discriminator)

- `:shared:engine` (KMP)
  - `MappingEngine` that folds recorder events into `MapGraph`
  - `ExportBundleBuilder` that produces a zip-ready file set

- `:androidApp`
  - Compose UI
  - WebView-based recorder
  - file-based local storage (projects + graph + sessions)
  - zip export + Android share sheet

## Data flow

1. User opens a project and starts recording.
2. WebView emits:
   - `NavigationEvent` (on document navigation)
   - `ResourceRequestEvent` (on resource requests seen by WebView)
3. App accumulates events in-memory.
4. On stop:
   - session is persisted as `sessions/<sessionId>.json`
   - the mapping engine updates `graph.json`
5. Export:
   - engine builds `manifest.json`, `graph.json`, `chains.json`, and session json files
   - android zips and shares

## Export bundle format (zip)

- `manifest.json` (ExportManifest)
- `graph.json` (MapGraph)
- `chains.json` (ChainsFile)
- `sessions/<sessionId>.json` (RecordingSession)
- `README.txt` (human note)

Redaction is intentionally *not* implemented in MVP.
