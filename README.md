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

## Automated Issue Workflow (Copilot Ruleset + Orchestrator)

This repository includes a **fully automated workflow** for issue management using GitHub Actions Orchestrator and Copilot integration.

### 🚀 Quick Start (5 Minuten)

1. **Workflow-Konfiguration** ist bereits vorhanden:
   - `.github/copilot/workflow-automation.json` dokumentiert die Automation
   - Orchestrator läuft über GitHub Actions (`.github/workflows/orchestrator.yml`)

2. **Create an issue** with clear requirements

3. **Add labels**: `orchestrator:enabled` and `orchestrator:run`

4. **Sit back** ☕ — Der Workflow läuft vollautomatisch:
   - ✅ Tasklist wird automatisch generiert
   - ✅ Erster Task startet automatisch
   - ✅ PR wird erstellt und reviewed
   - ✅ Review Findings werden automatisch behoben (max 5x)
   - ✅ Automatischer Merge bei Approval
   - ✅ Nächster Task startet automatisch
   - ✅ Issue wird geschlossen wenn alle Tasks fertig
   - ✅ Dokumentation wird automatisch aktualisiert

### Features

✅ **Fully Automated**: Vom Issue bis zum Merge ohne manuelle Eingriffe  
✅ **AI-Powered**: Copilot Agents für Implementierung, Review und Fixes  
✅ **Time-safe**: Splits work into batches, persists progress  
✅ **GitHub-native**: No external services required  
✅ **State machine**: Deterministic transitions with automatic recovery  
✅ **Free**: Runs on GitHub Actions free tier  

### Documentation

- **Quick Start**: [docs/COPILOT_RULESET_QUICKSTART.md](docs/COPILOT_RULESET_QUICKSTART.md) — In 5 Minuten startklar
- **Ruleset Details**: [docs/COPILOT_RULESET.md](docs/COPILOT_RULESET.md) — Vollständige Dokumentation
- **Orchestrator**: [docs/ORCHESTRATOR.md](docs/ORCHESTRATOR.md) — GitHub Actions Integration
- **Agent Setup**: [AGENT_SETUP.md](AGENT_SETUP.md) — Copilot Agent Konfiguration

### Manual Trigger

Run the orchestrator manually via GitHub Actions:
- Go to **Actions** → **GitHub Actions Orchestrator**
- Click **Run workflow**

## Updating versions

Versions are pinned in `gradle/libs.versions.toml`. For latest stable versions, run:

```bash
./gradlew -q dependencyUpdates
```

(Add the Gradle Versions Plugin later if you want automated reporting.)

