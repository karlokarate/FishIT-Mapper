# FishIT-Mapper

**FishIT-Mapper** is a standalone Android-first mapping app that records website navigation + resource requests
and builds a reusable **Map Graph** (nodes/edges) plus exportable session bundles.

Core design principle: the domain contract is **generated** via **KotlinPoet** from `schema/contract.schema.json`.

## Quickstart

### Option 1: Android Studio (Lokal)

1. Open this project in Android Studio.
2. Sync Gradle.
3. Run the `androidApp` configuration.

The contract is generated automatically on build. You can also run:

```bash
./gradlew :shared:contract:generateFishitContract
```

### Option 2: ChatGPT Codex Browser

Für die Nutzung im ChatGPT Codex Browser:

```bash
# Vollständiges Setup (einmalig)
./scripts/codex-setup.sh

# Maintenance bei Änderungen
./scripts/maintenance.sh
```

Siehe [scripts/README.md](scripts/README.md) für Details und Troubleshooting.

## Modules

- `:tools:codegen-contract` — KotlinPoet generator (reads `schema/contract.schema.json`)
- `:shared:contract` — generated contract models + JSON configuration
- `:shared:engine` — in-memory mapping engine + bundle builder helpers
- `:androidApp` — Compose UI + WebView recorder + local file storage + share/export

## Updating versions

Versions are pinned in `gradle/libs.versions.toml`. For latest stable versions, run:

```bash
./gradlew -q dependencyUpdates
```

(Add the Gradle Versions Plugin later if you want automated reporting.)

