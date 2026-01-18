# FishIT-Mapper: Output-Strategie für direkte GitHub/Codespace-Nutzung

## 📊 Aktueller Stand

### Was die App aktuell exportiert:
```
export.zip/
├── manifest.json       # Meta-Informationen
├── graph.json          # Node/Edge Graph
├── chains.json         # Redirect-Chains
├── sessions/           # Recording Sessions
│   └── <id>.json
└── README.txt
```

**Problem**: Diese JSON-Dateien müssen erst interpretiert und in Code umgewandelt werden.

---

## 🎯 Bessere Output-Strategie: Direkt nutzbarer Kotlin-Code

### Option A: **Generierter Kotlin-Code direkt aus der App**

Statt JSON exportieren wir **direkt kompilierbaren Kotlin-Code**:

```
fishit-api-client/
├── build.gradle.kts           # KMP-ready Build-Config
├── src/commonMain/kotlin/
│   ├── ApiClient.kt           # Generierter HTTP-Client
│   ├── Models.kt              # Data Classes für Request/Response
│   ├── Endpoints.kt           # Alle Endpoints als Funktionen
│   └── Auth.kt                # Auth-Helper
└── README.md                  # Auto-generierte Dokumentation
```

**Vorteil**: Copy-Paste ins Projekt, sofort nutzbar.

---

### Option B: **GitHub Repository Template** (Empfohlen! 🌟)

Die App erstellt ein **vollständiges GitHub Repository** mit:

```
<project>-api/
├── .github/
│   └── workflows/
│       └── update-from-traffic.yml   # GitHub Action für Updates
├── shared/
│   └── src/commonMain/kotlin/
│       ├── client/
│       │   ├── ApiClient.kt
│       │   └── HttpEngine.kt
│       ├── models/
│       │   └── *.kt                  # Generierte Data Classes
│       └── endpoints/
│           └── *.kt                  # Endpoint-Funktionen
├── traffic-data/                     # Raw Traffic für Re-Analyse
│   └── sessions/
├── build.gradle.kts
└── settings.gradle.kts
```

**Workflow**:
1. App exportiert Traffic-Daten + generiert Basis-Code
2. Push zu GitHub (direkt aus App oder manuell)
3. GitHub Action analysiert Traffic und regeneriert Code bei Änderungen
4. KMP-Modul direkt als Git-Dependency nutzbar

---

## 🛠️ Coole GitHub Tools für Korrelation im Codespace

### 1. **GitHub Copilot Workspace** (Dein bester Freund!)
- Öffne Traffic-Daten in Codespace
- Copilot analysiert und generiert Code
- Iterativ verfeinern

### 2. **GitHub Actions für Auto-Generierung**
```yaml
name: Generate API Client
on:
  push:
    paths: ['traffic-data/**']
jobs:
  generate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Analyze Traffic & Generate Code
        run: ./gradlew generateApiClient
      - name: Commit Generated Code
        run: |
          git add -A
          git commit -m "chore: regenerate API client from traffic"
          git push
```

### 3. **MCP Server für Copilot Integration**
Ein MCP-Server der Traffic-Daten versteht:
```
@fishit analyze /traffic-data/session-1.json
→ "Gefunden: 5 Endpoints, 2 Auth-Patterns"

@fishit generate client --language kotlin
→ Generiert ApiClient.kt
```

### 4. **HAR Import in Tools**
HAR (HTTP Archive) ist der Standard:
- **Chrome DevTools**: Network Tab → Import HAR
- **Postman**: Import → HAR File
- **Insomnia**: Import → HAR
- **GitHub Copilot**: Versteht HAR nativ mit `@workspace`

### 5. **GitHub Copilot Prompts**
Beispiel-Prompts für Codespace:
```
@workspace Analysiere traffic.har und generiere einen Ktor Client

@workspace Was sind die Auth-Patterns in dieser HAR-Datei?

@workspace Erstelle Data Classes für alle Response-Bodies in traffic.har

@workspace Generiere Tests basierend auf den echten Responses in traffic.har
```

### 6. **Codex CLI / GitHub Copilot CLI**
```bash
gh copilot explain "Was macht diese API?" < traffic.har

gh copilot suggest "Generiere einen API Client" --file api-analysis.md
```

---

## 🚀 Empfohlene Architektur: Hybrid-Ansatz

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         ANDROID APP                                     │
│  ┌─────────────┐    ┌──────────────────┐    ┌──────────────────────┐   │
│  │   Traffic   │───▶│   Basis-Analyse  │───▶│   Export als:        │   │
│  │   Import    │    │   (Endpoints)    │    │   - Raw JSON         │   │
│  └─────────────┘    └──────────────────┘    │   - Kotlin Stubs     │   │
│                                              │   - GitHub Repo      │   │
│                                              └──────────┬───────────┘   │
└─────────────────────────────────────────────────────────┼───────────────┘
                                                          │
                                                          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         GITHUB / CODESPACE                              │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    GitHub Actions                               │   │
│  │  ┌─────────────┐    ┌──────────────┐    ┌──────────────────┐   │   │
│  │  │ Traffic     │───▶│  Korrelation │───▶│ Code Generation  │   │   │
│  │  │ Watch       │    │  (Copilot)   │    │ (KotlinPoet)     │   │   │
│  │  └─────────────┘    └──────────────┘    └──────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Copilot Workspace                            │   │
│  │  "Analysiere den Traffic und optimiere den generierten Client"  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    Output: KMP Module                           │   │
│  │  implementation("com.github.user:my-api-client:1.0.0")         │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 📦 Konkreter Output-Vorschlag: **HAR + Kotlin Stubs**

### Was die App exportiert:

#### 1. `traffic.har` (Standard-Format!)
```json
{
  "log": {
    "entries": [
      {
        "request": { "method": "GET", "url": "..." },
        "response": { "status": 200, "content": {...} }
      }
    ]
  }
}
```
**Warum HAR?**
- Standard-Format, von vielen Tools verstanden
- Chrome DevTools, Postman, Charles, etc. können es lesen
- GitHub Copilot versteht es nativ

#### 2. `api-stubs.kt` (Generierte Basis)
```kotlin
// Auto-generated by FishIT-Mapper
// Refine with GitHub Copilot in Codespace

interface MyApi {
    @GET("/api/users/{userId}")
    suspend fun getUser(userId: String): User

    @POST("/api/auth/login")
    suspend fun login(credentials: LoginRequest): AuthResponse
}

@Serializable
data class User(
    val id: String,
    val name: String,
    // TODO: Copilot can infer more fields from traffic.har
)
```

#### 3. `analysis.json` (Für Copilot-Context)
```json
{
  "endpoints": [
    {
      "method": "GET",
      "pathTemplate": "/api/users/{userId}",
      "pathParams": ["userId"],
      "queryParams": ["include"],
      "authRequired": true,
      "exampleResponses": [...]
    }
  ],
  "authPatterns": [
    { "type": "bearer", "header": "Authorization" }
  ]
}
```

---

## 🎮 Workflow in der Praxis

### 1. In der Android App:
```
[Traffic importieren] → [Analyse] → [Export: HAR + Stubs + Analysis]
                                            ↓
                                    [Share to GitHub]
```

### 2. Im Codespace:
```bash
# Traffic-Daten sind da
ls traffic/
# → traffic.har, api-stubs.kt, analysis.json

# Copilot verfeinert
# @workspace Analysiere traffic.har und vervollständige api-stubs.kt
```

### 3. Ergebnis:
```kotlin
// Vollständiger, getesteter API-Client
// Direkt nutzbar in deinem KMP-Projekt
```

---

## ✅ Implementierte Exporter

Die folgenden Exporter sind jetzt verfügbar:

### 1. `HarExporter` ⭐⭐⭐⭐⭐
**Pfad**: `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/export/HarExporter.kt`

Exportiert Traffic als HAR 1.2 (HTTP Archive):
```kotlin
val harContent = HarExporter().export(exchanges)
// → Standard-Format für Chrome, Postman, Copilot
```

### 2. `CopilotReadyExporter` ⭐⭐⭐⭐⭐
**Pfad**: `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/export/CopilotReadyExporter.kt`

Generiert Copilot-optimiertes Markdown:
```kotlin
val markdown = CopilotReadyExporter().export(blueprint)
// → Enthält: Endpoint-Docs, Mermaid-Diagramme, TODO-Listen, Copilot-Prompts
```

### 3. `GitHubRepoGenerator` ⭐⭐⭐⭐⭐
**Pfad**: `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/export/GitHubRepoGenerator.kt`

Generiert ein vollständiges GitHub Repository Template:
```kotlin
val files = GitHubRepoGenerator().generate(blueprint, "com.example.api")
// → Enthält: build.gradle.kts, ApiClient.kt, Models.kt, GitHub Actions, README
```

### 4. `ExportOrchestrator`
**Pfad**: `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/export/ExportOrchestrator.kt`

Zentrale Orchestrierung für alle Formate:
```kotlin
// Alles für Codespace
val bundle = ExportOrchestrator.exportForCodespace(blueprint, exchanges)

// Einzelne Formate
val har = ExportOrchestrator.exportToHar(exchanges)
val md = ExportOrchestrator.exportToCopilotMarkdown(blueprint)
val kotlin = ExportOrchestrator.exportToKotlin(blueprint)
```

### 5. `ApiExporter` (bereits vorhanden)
**Pfad**: `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/export/ApiExporter.kt`

Legacy-Exporter für:
- OpenAPI YAML
- Postman Collection
- cURL Commands
- TypeScript Client
- Kotlin Client
- JSON Analysis

---

## 🎯 Finaler Workflow: App → Codespace → KMP Module

```
┌────────────────────────────────────────────────────────────────────┐
│                      1. ANDROID APP                                │
│                                                                    │
│   HttpCanary ZIP ──▶ Import ──▶ Analyse ──▶ Export                │
│                                               │                    │
│                                               ▼                    │
│                                        ┌──────────────┐           │
│                                        │ Export Menu  │           │
│                                        │ • HAR        │           │
│                                        │ • Markdown   │           │
│                                        │ • Full Repo  │           │
│                                        └──────┬───────┘           │
└───────────────────────────────────────────────┼────────────────────┘
                                                │
                                                ▼
┌────────────────────────────────────────────────────────────────────┐
│                      2. GITHUB / CODESPACE                         │
│                                                                    │
│   ┌─────────────────────────────────────────────────────────┐     │
│   │  Neues Repo erstellen oder in bestehendes importieren   │     │
│   │                                                         │     │
│   │  my-api-client/                                         │     │
│   │  ├── .github/                                           │     │
│   │  │   ├── copilot-instructions.md  ◀── Copilot Config    │     │
│   │  │   └── workflows/                                     │     │
│   │  │       └── regenerate.yml       ◀── Auto-Update       │     │
│   │  ├── shared/src/commonMain/kotlin/                      │     │
│   │  │   ├── ApiClient.kt             ◀── Generierter Code  │     │
│   │  │   └── Models.kt                ◀── Data Classes      │     │
│   │  ├── traffic/                                           │     │
│   │  │   ├── traffic.har              ◀── Echter Traffic    │     │
│   │  │   └── analysis.json            ◀── Blueprint         │     │
│   │  └── API_ANALYSIS.md              ◀── Copilot-Ready     │     │
│   └─────────────────────────────────────────────────────────┘     │
│                                                                    │
│   ┌─────────────────────────────────────────────────────────┐     │
│   │                  COPILOT PROMPTS                        │     │
│   │                                                         │     │
│   │  @workspace Vervollständige Models.kt mit echten        │     │
│   │             Feldern aus traffic.har                     │     │
│   │                                                         │     │
│   │  @workspace Generiere Unit Tests für alle Endpoints     │     │
│   │                                                         │     │
│   │  @workspace Füge Fehler-Handling für alle 4xx/5xx       │     │
│   │             Status-Codes hinzu                          │     │
│   └─────────────────────────────────────────────────────────┘     │
└────────────────────────────────────────────────────────────────────┘
                                                │
                                                ▼
┌────────────────────────────────────────────────────────────────────┐
│                      3. NUTZUNG IN PROJEKTEN                       │
│                                                                    │
│   // settings.gradle.kts                                           │
│   dependencyResolutionManagement {                                 │
│       repositories {                                               │
│           maven("https://jitpack.io")                              │
│       }                                                            │
│   }                                                                │
│                                                                    │
│   // build.gradle.kts                                              │
│   dependencies {                                                   │
│       implementation("com.github.USER:my-api-client:1.0.0")        │
│   }                                                                │
│                                                                    │
│   // Nutzung                                                       │
│   val api = MyApi()                                                │
│   val user = api.getUser("123")                                    │
└────────────────────────────────────────────────────────────────────┘
```

---

## 💡 Quick Win: Copilot-Optimierter Export

Exportiere eine Datei die Copilot perfekt versteht:

```markdown
# API Analysis for MyWebsite

## Discovered Endpoints

### GET /api/users/{userId}
- Auth: Bearer Token
- Path Params: userId (string)
- Response: User object

Example:
\`\`\`http
GET https://api.example.com/api/users/123
Authorization: Bearer xxx

Response 200:
{
  "id": "123",
  "name": "John"
}
\`\`\`

## TODO for Copilot
- [ ] Generate Kotlin data classes from examples
- [ ] Create Ktor client functions
- [ ] Add error handling
```

Copilot kann das **direkt** in Code umwandeln!
