# FishIT-Mapper - Neuer Scope: API Reverse Engineering

## 🎯 Ziel

**Ziel**: Traffic per ZIP importieren, mit Browsing korrelieren und eine perfekte API-Map erstellen,
die es ermöglicht, per Reverse Engineering die API einer Website in ein eigenes UI zu bringen.

## 📋 Kern-Features

### 1. Traffic Import (✅ Bereits implementiert)
- HttpCanary ZIP-Import
- Traffic-Parsing und Normalisierung
- Unterstützung für Request/Response-Bodies

### 2. Browsing-Korrelation (✅ Bereits implementiert)
- User Actions → Network Requests Mapping
- Zeitfenster-basierte Korrelation
- Redirect-Chain-Erkennung

### 3. API Discovery & Mapping (🆕 Neu)
- **Endpoint-Extraktion**: Automatische Erkennung von API-Endpunkten
- **Parameter-Analyse**: Query-Parameter, Path-Parameter, Body-Parameter
- **Request-Templates**: Wiederverwendbare Request-Vorlagen
- **Auth-Pattern-Erkennung**: OAuth, Session, API-Keys, Bearer Tokens

### 4. API Blueprint Generator (🆕 Neu)
- **OpenAPI/Swagger-Export**: Automatische Spec-Generierung
- **cURL-Export**: Kopierbare cURL-Befehle
- **Code-Generierung**: Kotlin/TypeScript Client-Stubs

### 5. UI Builder Integration (🆕 Neu)
- **API-zu-UI-Mapping**: Verknüpfung von Endpoints mit UI-Elementen
- **Flow-Definition**: Sequenz von API-Calls für User-Flows
- **Parameter-Binding**: Dynamische Wert-Übergabe zwischen Requests

## 🏗️ Architektur-Erweiterungen

```
┌─────────────────────────────────────────────────────────────────────┐
│                         FishIT-Mapper                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────┐    ┌──────────────────┐    ┌──────────────────┐   │
│  │   Traffic   │    │    Correlation   │    │   API Analyzer   │   │
│  │   Import    │───▶│      Engine      │───▶│     Engine       │   │
│  │  (ZIP/HAR)  │    │                  │    │                  │   │
│  └─────────────┘    └──────────────────┘    └──────────────────┘   │
│                                                      │              │
│                                                      ▼              │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    API Blueprint                            │   │
│  │  ┌────────────┐  ┌────────────┐  ┌─────────────────────┐   │   │
│  │  │  Endpoints │  │  Auth      │  │  Request Templates  │   │   │
│  │  │            │  │  Patterns  │  │                     │   │   │
│  │  └────────────┘  └────────────┘  └─────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                       Export                                │   │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌──────────────┐   │   │
│  │  │ OpenAPI │  │  cURL   │  │ Postman │  │ Code Stubs   │   │   │
│  │  └─────────┘  └─────────┘  └─────────┘  └──────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## 📊 Datenmodell-Erweiterungen

### API Blueprint Modell

```kotlin
// Neues Datenmodell für API-Blueprints
data class ApiBlueprint(
    val id: ApiBlueprintId,
    val projectId: ProjectId,
    val baseUrl: String,
    val endpoints: List<ApiEndpoint>,
    val authPatterns: List<AuthPattern>,
    val flows: List<ApiFlow>,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ApiEndpoint(
    val id: EndpointId,
    val method: HttpMethod,
    val pathTemplate: String,           // z.B. "/api/users/{userId}/posts"
    val pathParameters: List<Parameter>,
    val queryParameters: List<Parameter>,
    val headers: List<Parameter>,
    val requestBody: RequestBodySpec?,
    val responses: List<ResponseSpec>,
    val authRequired: AuthType?,
    val examples: List<ExchangeReference>, // Links zu echten Captures
    val metadata: EndpointMetadata
)

data class Parameter(
    val name: String,
    val location: ParameterLocation,    // PATH, QUERY, HEADER, BODY
    val type: ParameterType,            // STRING, INT, BOOLEAN, OBJECT, ARRAY
    val required: Boolean,
    val defaultValue: String?,
    val observedValues: List<String>,   // Tatsächlich beobachtete Werte
    val description: String?
)

data class ApiFlow(
    val id: FlowId,
    val name: String,                   // z.B. "Login Flow", "Create Post"
    val description: String?,
    val steps: List<FlowStep>,
    val sourceActionIds: List<ActionId> // Links zu User Actions
)

data class FlowStep(
    val order: Int,
    val endpointId: EndpointId,
    val parameterBindings: Map<String, ParameterBinding>,
    val expectedStatus: Int?,
    val extractors: List<ResponseExtractor> // Werte für nächsten Step
)

data class ResponseExtractor(
    val name: String,                   // Variable name
    val jsonPath: String,               // z.B. "$.data.token"
    val headerName: String?             // oder aus Header extrahieren
)
```

### Auth Pattern Erkennung

```kotlin
sealed class AuthPattern {
    data class BearerToken(
        val headerName: String,         // meist "Authorization"
        val tokenPrefix: String,        // "Bearer ", "Token ", etc.
        val tokenSource: TokenSource    // Login response, Cookie, etc.
    ) : AuthPattern()

    data class SessionCookie(
        val cookieName: String,
        val domain: String
    ) : AuthPattern()

    data class ApiKey(
        val location: ParameterLocation,
        val name: String
    ) : AuthPattern()

    data class OAuth2(
        val tokenEndpoint: String,
        val grantType: String,
        val scopes: List<String>
    ) : AuthPattern()
}
```

## 🔄 Workflow

### Phase 1: Traffic Capture
1. User browst Website in externem Browser mit HttpCanary/Charles/mitmproxy
2. Export als ZIP/HAR
3. Import in FishIT-Mapper

### Phase 2: Action Recording (Optional)
1. User browst dieselbe Website in FishIT-Mapper WebView
2. App zeichnet User Actions auf (Klicks, Form-Submits)
3. Automatische Korrelation mit importiertem Traffic

### Phase 3: API Analysis
1. **Endpoint Clustering**: Gruppiere ähnliche URLs
2. **Parameter Detection**: Erkenne Path/Query/Body Parameter
3. **Auth Analysis**: Erkenne Auth-Patterns
4. **Flow Detection**: Erkenne zusammenhängende API-Sequenzen

### Phase 4: Blueprint Generation
1. Generiere API Blueprint aus Analyse
2. User kann manuell verfeinern (Parameter umbenennen, etc.)
3. Export in gewünschtes Format

### Phase 5: UI Integration (Future)
1. Verknüpfe Endpoints mit UI-Komponenten
2. Definiere Datenfluss zwischen Screens
3. Generiere UI-Code-Stubs

## 📁 Neue Dateien/Module

### Schema-Erweiterungen
```
schema/
├── contract.schema.json        # Bestehend
├── api-blueprint.schema.json   # NEU: API Blueprint Schema
└── exports.schema.json         # NEU: Export-Formate
```

### Engine-Erweiterungen
```
shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/
├── MappingEngine.kt            # Bestehend
├── api/
│   ├── EndpointExtractor.kt    # NEU: Extrahiert Endpoints aus Exchanges
│   ├── ParameterAnalyzer.kt    # NEU: Analysiert Parameter-Typen
│   ├── AuthPatternDetector.kt  # NEU: Erkennt Auth-Patterns
│   ├── FlowDetector.kt         # NEU: Erkennt API-Flows
│   └── ApiBlueprintBuilder.kt  # NEU: Baut Blueprint zusammen
└── export/
    ├── OpenApiExporter.kt      # NEU: Generiert OpenAPI Spec
    ├── CurlExporter.kt         # NEU: Generiert cURL Commands
    ├── PostmanExporter.kt      # NEU: Generiert Postman Collection
    └── CodeStubGenerator.kt    # NEU: Generiert Client-Code
```

### UI-Erweiterungen
```
androidApp/src/main/java/dev/fishit/mapper/android/ui/
├── api/
│   ├── ApiBlueprintScreen.kt   # NEU: Zeigt API Blueprint
│   ├── EndpointDetailScreen.kt # NEU: Zeigt Endpoint Details
│   ├── FlowEditorScreen.kt     # NEU: Bearbeitet API Flows
│   └── ExportScreen.kt         # NEU: Export-Optionen
└── builder/
    ├── UiBuilderScreen.kt      # NEU: UI Builder Integration
    └── ComponentPicker.kt      # NEU: UI-Komponenten Auswahl
```

## 🚀 Implementierungs-Prioritäten

### Sprint 1: API Analysis Foundation
- [ ] Schema für ApiBlueprint erweitern
- [ ] EndpointExtractor implementieren
- [ ] ParameterAnalyzer implementieren
- [ ] Basis-UI für Blueprint-Anzeige

### Sprint 2: Pattern Detection
- [ ] AuthPatternDetector implementieren
- [ ] FlowDetector implementieren
- [ ] UI für Auth-Patterns

### Sprint 3: Export Capabilities
- [ ] OpenAPI/Swagger Export
- [ ] cURL Export
- [ ] Postman Collection Export

### Sprint 4: Advanced Features
- [ ] Code-Generierung (Kotlin/TypeScript)
- [ ] UI Builder Integration
- [ ] Interactive API Testing

## 💡 Technische Entscheidungen

### URL Pattern Matching
Verwende Regex-basiertes Pattern Matching für Endpoint-Clustering:
```kotlin
// Beispiel: /api/users/123/posts → /api/users/{userId}/posts
val pattern = "/api/users/\\d+/posts"
val template = "/api/users/{userId}/posts"
```

### JSON Path für Response Extraction
Nutze JsonPath-Syntax für Response-Extraktion:
```kotlin
val tokenExtractor = ResponseExtractor(
    name = "authToken",
    jsonPath = "$.data.access_token"
)
```

### Persistent Storage
Erweitere AndroidProjectStore für API Blueprints:
```
projects/<projectId>/
├── meta.json
├── graph.json
├── sessions/
├── maps/
└── blueprints/          # NEU
    └── <blueprintId>.json
```

## 🔗 Verwandte Dokumentation
- [ARCHITECTURE.md](ARCHITECTURE.md) - Bestehende Architektur
- [HTTPS_TRAFFIC_CAPTURE.md](features/HTTPS_TRAFFIC_CAPTURE.md) - Traffic Capture Feature
- [UNIFIED_TIMELINE_AND_CREDENTIALS.md](features/UNIFIED_TIMELINE_AND_CREDENTIALS.md) - Timeline Feature
