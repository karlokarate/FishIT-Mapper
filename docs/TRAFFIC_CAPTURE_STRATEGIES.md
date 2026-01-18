# FishIT-Mapper: Traffic-Capture Architektur

## 🎯 Kernaussage: KEIN HttpCanary nötig!

Die App erfasst **ALLEN Traffic selbst** durch ihren eingebauten Browser (WebView).
HttpCanary oder andere externe Tools sind **nicht erforderlich**.

```
┌─────────────────────────────────────────────────────────────────────┐
│                      FishIT-Mapper App                              │
│                                                                     │
│   ┌───────────────────────────────────────────────────────────┐    │
│   │              TrafficInterceptWebView                       │    │
│   │                                                            │    │
│   │   Website läuft hier (wie ein normaler Browser)            │    │
│   │                           │                                │    │
│   │   JavaScript Hooks fangen ALLES ab:                        │    │
│   │   ✅ XHR (XMLHttpRequest)                                  │    │
│   │   ✅ Fetch API                                             │    │
│   │   ✅ Form Submissions                                      │    │
│   │   ✅ Redirects (301, 302, 303, 307, 308)                   │    │
│   │   ✅ Cookies (document.cookie)                             │    │
│   │   ✅ sendBeacon (Analytics)                                │    │
│   │   ✅ History/Navigation (pushState, replaceState)          │    │
│   │   ✅ User Actions (Click, Submit, Input)                   │    │
│   │   ✅ Request/Response Headers                              │    │
│   │   ✅ Request/Response Bodies                               │    │
│   │   ✅ Error Handling (Timeouts, Network Errors)             │    │
│   └───────────────────────────────────────────────────────────┘    │
│                                                                     │
│   Vorteile:                                                        │
│   • Kein Root erforderlich                                         │
│   • Kein CA-Zertifikat nötig                                       │
│   • Umgeht Certificate Pinning KOMPLETT                            │
│   • Perfekte Event-Korrelation                                     │
│   • Alles in einer App                                             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 📊 Aktueller Status der Module

### ✅ Vollständig implementiert
| Modul                       | Pfad                      | Beschreibung                        |
| --------------------------- | ------------------------- | ----------------------------------- |
| **TrafficInterceptWebView** | `androidApp/.../capture/` | In-App Browser mit JS Hooks         |
| **CaptureSessionManager**   | `androidApp/.../capture/` | Recording Sessions mit Korrelation  |
| **CaptureWebViewScreen**    | `androidApp/ui/capture/`  | Browser-UI mit Live Stats           |
| **SessionToEngineAdapter**  | `androidApp/.../capture/` | Integration mit Analyse-Engine      |
| API Blueprint Engine        | `shared/engine/api/`      | Endpoint-Extraktion, Auth-Detection |
| Export Pipeline             | `shared/engine/export/`   | HAR, Markdown, GitHub Repo, OpenAPI |
| UI Screens                  | `androidApp/ui/api/`      | Blueprint-Anzeige, Endpoint-Details |

### ⚠️ Legacy (nicht mehr benötigt)
| Modul                    | Status     | Grund                                 |
| ------------------------ | ---------- | ------------------------------------- |
| `HttpCanaryZipImporter`  | Legacy     | WebView Capture ist besser            |
| `MitmProxyServer`        | Deprecated | Zu komplex                            |
| `WebViewProxyController` | Deprecated | Ersetzt durch TrafficInterceptWebView |

### 🔄 Optional (nur für native Apps)
| Modul              | Priorität | Beschreibung                                        |
| ------------------ | --------- | --------------------------------------------------- |
| VpnService Capture | P3        | Nur nötig wenn native Apps analysiert werden sollen |

---

## 🔍 Was wird alles erfasst?

### HTTP Requests/Responses
| Feature              | Status | Beschreibung                      |
| -------------------- | ------ | --------------------------------- |
| XHR (XMLHttpRequest) | ✅      | Klassische AJAX Calls             |
| Fetch API            | ✅      | Moderne API Calls                 |
| Form Submissions     | ✅      | `<form>` Submit + `form.submit()` |
| sendBeacon           | ✅      | Analytics/Tracking Calls          |
| Navigation Requests  | ✅      | Link-Clicks, URL-Änderungen       |

### Redirects & Komplexe Flows
| Feature              | Status | Beschreibung                           |
| -------------------- | ------ | -------------------------------------- |
| HTTP Redirects       | ✅      | 301, 302, 303, 307, 308                |
| JavaScript Redirects | ✅      | `window.location`, `history.pushState` |
| Meta Refresh         | ✅      | Durch Page-Load-Events                 |
| OAuth Flows          | ✅      | Redirect-Chain wird komplett erfasst   |
| Login Flows          | ✅      | Credentials + Token-Responses          |

### Headers & Bodies
| Feature          | Status | Beschreibung                  |
| ---------------- | ------ | ----------------------------- |
| Request Headers  | ✅      | Alle Headers inkl. Auth       |
| Response Headers | ✅      | Alle Headers inkl. Set-Cookie |
| Request Body     | ✅      | JSON, Form-Data, Text         |
| Response Body    | ✅      | JSON, HTML, Text (bis 1MB)    |
| Cookies          | ✅      | `document.cookie` Hook        |

### User Actions (Korrelation)
| Feature        | Status | Beschreibung              |
| -------------- | ------ | ------------------------- |
| Click Events   | ✅      | Alle Clicks mit Selector  |
| Form Submits   | ✅      | Form Action + Method      |
| Input Events   | ✅      | Text-Eingaben (debounced) |
| Navigation     | ✅      | URL-Änderungen, History   |
| Cookie Changes | ✅      | Cookie Set/Delete         |

---

## 🚀 Warum WebView besser ist als VPN/Proxy

| Aspekt                      | WebView Hooks      | VPN/Proxy (HttpCanary)     |
| --------------------------- | ------------------ | -------------------------- |
| **Setup**                   | Null - eingebaut   | CA-Zertifikat installieren |
| **HTTPS**                   | Funktioniert immer | Nur mit CA                 |
| **Certificate Pinning**     | ✅ Umgangen         | ❌ Blockiert                |
| **Request Bodies**          | ✅ Immer            | ⚠️ Nur bei HTTPS-Decrypt    |
| **User-Action Korrelation** | ✅ Perfekt          | ❌ Nur Zeitstempel          |
| **Apps nötig**              | 1                  | 2                          |
| **Export**                  | Automatisch        | Manuell (ZIP)              |
| **Root**                    | Nicht nötig        | Nicht nötig                |

---

## 📁 Architektur-Diagramm

```
┌─────────────────────────────────────────────────────────────────────┐
│                        FishIT-Mapper App                            │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────────┐│
│  │                    CAPTURE LAYER                                ││
│  │                                                                 ││
│  │  TrafficInterceptWebView                                        ││
│  │  ├── JavaScript Hooks (XHR, Fetch, Form, Beacon, History)       ││
│  │  ├── WebViewClient (Redirects, Page Events)                     ││
│  │  └── JavaScriptInterface (Bridge zu Kotlin)                     ││
│  │                           │                                     ││
│  │                           ▼                                     ││
│  │  CaptureSessionManager                                          ││
│  │  ├── startSession() / stopSession()                             ││
│  │  ├── Exchange Collection                                        ││
│  │  ├── Action Correlation (2s Window)                             ││
│  │  └── Session Export                                             ││
│  └────────────────────────────────────────────────────────────────┘│
│                           │                                         │
│                           ▼                                         │
│  ┌────────────────────────────────────────────────────────────────┐│
│  │                    ANALYSIS LAYER                               ││
│  │                                                                 ││
│  │  SessionToEngineAdapter → HttpExchange Format                   ││
│  │                           │                                     ││
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             ││
│  │  │ Endpoint    │  │ Parameter   │  │ Auth        │             ││
│  │  │ Extractor   │  │ Analyzer    │  │ Detector    │             ││
│  │  └─────────────┘  └─────────────┘  └─────────────┘             ││
│  │          │               │               │                      ││
│  │          └───────────────┼───────────────┘                      ││
│  │                          ▼                                      ││
│  │              ApiBlueprintBuilder                                ││
│  │                          │                                      ││
│  │                          ▼                                      ││
│  │                  API Blueprint                                  ││
│  └────────────────────────────────────────────────────────────────┘│
│                           │                                         │
│                           ▼                                         │
│  ┌────────────────────────────────────────────────────────────────┐│
│  │                    EXPORT LAYER                                 ││
│  │                                                                 ││
│  │  ExportOrchestrator                                             ││
│  │  ├── HAR (Standard Format)                                      ││
│  │  ├── Copilot-Ready Markdown                                     ││
│  │  ├── GitHub Repo Template                                       ││
│  │  ├── OpenAPI YAML                                               ││
│  │  ├── Kotlin/TypeScript Clients                                  ││
│  │  └── cURL Commands                                              ││
│  └────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

---

## 💡 Anwendungsfälle

### Use Case 1: Login-Flow analysieren
1. Session starten: "Login Flow Example.com"
2. Zur Login-Seite navigieren
3. Credentials eingeben + Submit
4. App erfasst:
   - POST `/api/auth/login` mit Credentials
   - Response mit Token/Session-Cookie
   - Redirect zur Dashboard-Seite
   - Korrelation: Submit-Button → Login-Request

### Use Case 2: API Endpoints entdecken
1. Session starten
2. Durch die Website browsen
3. Verschiedene Features nutzen
4. Session stoppen
5. Export als:
   - HAR für Postman Import
   - Markdown für Copilot
   - Kotlin Client Code

### Use Case 3: OAuth Flow tracken
1. Session starten
2. "Login with Google" klicken
3. App erfasst die komplette Redirect-Chain:
   - `/oauth/authorize` → Google
   - Google Auth → Callback
   - Callback → Token Exchange
   - Token → User Info

---

## 🔧 Implementierte Module

### TrafficInterceptWebView
```kotlin
val webView = TrafficInterceptWebView(context)

// Traffic beobachten
webView.capturedExchanges.collect { exchanges ->
    // Alle HTTP Requests/Responses
}

// User Actions beobachten
webView.userActions.collect { actions ->
    // Click, Submit, Input, Navigation
}
```

### CaptureSessionManager
```kotlin
val session = sessionManager.startSession("My API Analysis")

// ... User browst die Website ...

val completed = sessionManager.stopSession()
// completed.exchanges = alle Requests
// completed.userActions = alle User-Events
// completed.correlate(action) = verwandte Requests
```

### Export
```kotlin
val adapter = SessionToEngineAdapter()
val exchanges = adapter.toHttpExchanges(session)
val blueprint = ApiBlueprintBuilder().build(exchanges, session.name)

// Export
val har = ExportOrchestrator.exportToHar(exchanges)
val markdown = ExportOrchestrator.exportToCopilotMarkdown(blueprint)
val repoFiles = GitHubRepoGenerator().generate(blueprint)
```
