# FishIT-Mapper

**FishIT-Mapper** ist eine Android-App für **API Reverse Engineering** - importiere HTTP-Traffic,
korreliere ihn mit Browser-Aktionen und erstelle automatisch API-Blueprints für eigene UIs.

Core design principle: the domain contract is **generated** via **KotlinPoet** from `schema/contract.schema.json`.

## 🎯 Kern-Funktionalität

```
Traffic ZIP Import → Korrelation mit Browsing → API Blueprint → Export (OpenAPI/cURL/Code)
```

### Was die App macht:
1. **Traffic Import**: HttpCanary/Charles ZIP-Exporte importieren
2. **Korrelation**: User Actions mit Network Requests verknüpfen
3. **API Discovery**: Endpoints, Parameter und Auth-Patterns automatisch erkennen
4. **Blueprint Export**: OpenAPI, cURL, Postman, TypeScript/Kotlin Clients generieren

## 🆕 Features

### ✅ Traffic Import & Analyse
- HttpCanary ZIP-Import mit vollständiger Request/Response-Analyse
- Automatische Endpoint-Erkennung und Parameter-Typisierung
- Auth-Pattern Detection (Bearer, Session, API Key, OAuth2)

### ✅ API Blueprint Generation
- Automatische Path-Parameter-Erkennung (`/users/123` → `/users/{userId}`)
- Query/Header/Body-Parameter-Analyse
- Flow-Detection für zusammenhängende API-Sequenzen

### ✅ Multi-Format Export
- **OpenAPI 3.0** (Swagger) - Vollständige API-Spezifikation
- **cURL Commands** - Sofort testbar
- **Postman Collection** - Import in Postman
- **TypeScript Client** - Fertiger API-Client
- **Kotlin Client** - Android-ready mit Ktor

📖 **[Vollständige Scope-Dokumentation](docs/SCOPE_API_REVERSE_ENGINEERING.md)**


## Quickstart

### Option 1: Android Studio (Lokal)

1. Open this project in Android Studio.
2. Sync Gradle.
3. Run the `androidApp` configuration.

The contract is generated automatically on build. You can also run:

```bash
./gradlew :shared:contract:generateFishitContract
```

### Option 2: Signierte APK über GitHub Actions

Erstelle automatisch signierte Release-Builds über GitHub Actions:

1. **Setup (einmalig)**: [Android Signing Setup Guide](docs/ANDROID_SIGNING_SETUP.md)
   - Keystore generieren mit Workflow "Generate Keystore and Secrets"
   - GitHub Secrets konfigurieren (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

2. **Build**: Workflow "Android Build" ausführen
   - Signierte Release-APK wird automatisch erstellt
   - Download als Artifact

📖 **[Vollständiger Setup-Guide](docs/ANDROID_SIGNING_SETUP.md)**

### Option 3: ChatGPT Codex Browser

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

This repository includes:
- **GitHub Repository Ruleset** (`.github/copilot-ruleset.json`) - Importable ruleset for branch protection and Copilot code reviews
- **Workflow Automation** - Fully automated issue management via GitHub Actions Orchestrator

### 🔒 GitHub Ruleset (Branch Protection + Copilot Reviews)

**Import the ruleset** (einmalig):
```bash
# Via GitHub CLI
gh api repos/karlokarate/FishIT-Mapper/rulesets \
  -X POST \
  --input .github/copilot-ruleset.json
```

Or via UI: **Settings → Rules → Rulesets → Import a ruleset** → wähle `.github/copilot-ruleset.json`

Dokumentation: [`docs/COPILOT_RULESET_IMPORT.md`](docs/COPILOT_RULESET_IMPORT.md)

### 🚀 Workflow Automation (5 Minuten)

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

