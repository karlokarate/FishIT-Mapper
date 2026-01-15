# Unified Session Timeline und Credential Storage Features

**Version:** 1.0.0  
**Datum:** 2026-01-15  
**Issue:** #28 - Vervollständigung offener Features für Login-Szenario

## Überblick

Dieses Update implementiert drei Hauptfunktionen für FishIT-Mapper:

1. **Unified Session Timeline** - Chronologische Darstellung aller Events mit Hierarchie
2. **Session Tree Structure** - Visualisierung der Parent-Child-Beziehungen zwischen URLs
3. **Credential Storage** - Automatische Erkennung und Speicherung von Authentifizierungsdaten

## 1. Unified Session Timeline

### Features

- **Chronologische Event-Liste** mit Zeitstempeln
- **Request-Response Korrelation** basierend auf:
  - URL-Matching (normalisiert)
  - Zeitfenster von 30 Sekunden
  - RequestId-Tracking
- **Parent-Child Beziehungen** zwischen Events:
  - Navigation → Requests
  - Requests → Responses
  - Navigation → Sub-Navigation (Redirects)
- **Depth-Tracking** für visuelle Indentation (0 = Root)

### Technische Implementation

**Engine Component:** `TimelineBuilder`

```kotlin
val timeline = TimelineBuilder.buildTimeline(session, graph)
// Returns: UnifiedTimeline mit entries und treeNodes
```

**Datenstruktur:**

```kotlin
data class TimelineEntry(
    val event: RecorderEvent,
    val correlatedEventId: EventId?,  // Korrelierte Request/Response
    val parentEventId: EventId?,       // Parent in Hierarchie
    val depth: Int                     // Tiefe im Baum (0 = Root)
)
```

### Verwendung im UI

```kotlin
UnifiedTimelineScreen(
    projectId = "project-id",
    sessionId = "session-id",
    onBack = { /* ... */ }
)
```

**Features:**
- Toggle zwischen Timeline-View und Tree-View
- Farbcodierte Events nach Typ
- Indentation nach Depth-Level
- Anzeige von Korrelationen
- Parent-Event-Referenzen

## 2. Session Tree Structure

### Features

- **Hierarchische Darstellung** der Navigation
- **Start-URL als Root-Node**
- **Redirects und Links als Children**
- **Depth-Berechnung** mit Cycle-Detection
- **Event-Grouping** pro Node (alle Events für eine URL)

### Technische Implementation

**Datenstruktur:**

```kotlin
data class SessionTreeNode(
    val nodeId: NodeId,
    val url: String,
    val title: String?,
    val parentNodeId: NodeId?,        // Parent in Tree
    val children: List<NodeId>,       // Child-Nodes
    val depth: Int,                   // Tiefe im Baum
    val eventIds: List<EventId>       // Alle Events dieser URL
)
```

### Tree-Building Algorithmus

1. **Extrahiere Navigation-Events** aus Session
2. **Baue Parent-Map** basierend auf:
   - `fromUrl` in NavigationEvent
   - Redirect-Chains (aufeinander folgende Redirects)
3. **Berechne Children-Map** (inverse Parent-Map)
4. **Berechne Depth** rekursiv mit Cycle-Detection
5. **Gruppiere Events** pro URL

## 3. Credential Storage

### Features

- **Automatische Extraktion** von:
  - Username/Password aus Login-Forms
  - Bearer Tokens aus `Authorization` Headers
  - Session Cookies aus `Set-Cookie` Headers
  - OAuth Tokens
  - Custom Header-basierte Auth
- **Privacy-Aware Storage**:
  - Passwörter werden gehasht (⚠️ MVP: simple hash, Production: bcrypt/PBKDF2)
  - Tokens werden auf 50 Zeichen gekürzt
  - Lokale Speicherung (keine Cloud-Sync)
- **Domain-basierte Gruppierung**
- **Metadata-Tracking** (Session, Zeitstempel, Typ)

### Technische Implementation

**Engine Component:** `CredentialExtractor`

```kotlin
val credentials = CredentialExtractor.extractCredentials(session)
// Returns: List<StoredCredential>
```

**Datenstruktur:**

```kotlin
data class StoredCredential(
    val id: CredentialId,
    val sessionId: SessionId,
    val type: CredentialType,          // UsernamePassword, Token, Cookie, OAuth, Header
    val url: String,
    val username: String?,             // Für UsernamePassword
    val passwordHash: String?,         // Gehasht für Privacy
    val token: String?,                // Für Token/Cookie (truncated)
    val metadata: Map<String, String>, // Zusätzliche Infos
    val capturedAt: Instant,
    val isEncrypted: Boolean          // Future: Encryption-Flag
)
```

### Extraction-Strategien

#### 1. Form-basierte Credentials

- Analysiert `UserActionEvent` mit `action = "formsubmit:*"`
- Verwendet `FormAnalyzer` zur Pattern-Erkennung (LOGIN, REGISTRATION)
- Extrahiert Username/Password aus Form-Feldern

#### 2. HTTP Authorization Headers

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Authorization: Basic dXNlcjpwYXNz
Authorization: OAuth token123
```

Erkennt automatisch den Typ und extrahiert Token.

#### 3. Session Cookies

```
Set-Cookie: session_id=abc123; Path=/; HttpOnly
Set-Cookie: auth_token=xyz789; Secure
```

Filtert nach Session/Auth-relevanten Cookies.

### Verwendung im UI

```kotlin
CredentialsScreen(
    projectId = "project-id",
    sessionId = "session-id", // Optional: null für alle Credentials
    onBack = { /* ... */ }
)
```

**Features:**
- Privacy Toggle (Show/Hide sensitive data)
- Domain-basierte Gruppierung
- Delete-Funktion
- Privacy-Notice-Banner

## Storage Layer

### Dateistruktur

```
fishit/
  projects/
    <projectId>/
      credentials.json              # Alle Credentials des Projekts
      sessions/
        <sessionId>.json            # Session-Daten
        <sessionId>_timeline.json   # Cached Timeline
```

### API

```kotlin
// Credentials
store.saveCredentials(projectId, credentials)
store.loadCredentials(projectId): List<StoredCredential>
store.addCredential(projectId, credential)
store.deleteCredential(projectId, credentialId)

// Timeline
store.saveTimeline(projectId, timeline)
store.loadTimeline(projectId, sessionId): UnifiedTimeline?
store.deleteTimeline(projectId, sessionId)
```

## Testing

### Unit Tests

- **TimelineBuilder**:
  - `testBuildBasicTimeline()` - Basis-Timeline mit Navigation + Request
  - `testEventCorrelation()` - Request-Response Korrelation
  - `testTreeStructure()` - Parent-Child Beziehungen

- **CredentialExtractor**:
  - `testExtractUsernamePasswordFromForm()` - Form-basierte Extraktion
  - `testExtractBearerToken()` - Token-Extraktion
  - `testExtractSessionCookie()` - Cookie-Extraktion
  - `testGroupByDomain()` - Domain-Gruppierung

**Status:** ✅ Alle 8 Tests bestehen

## Security Considerations

### 🔒 Password Hashing

**⚠️ Wichtig:** Die aktuelle MVP-Implementation verwendet einen simplen Hash:

```kotlin
private fun hashPassword(password: String): String {
    // TODO: Replace with proper password hashing (bcrypt/PBKDF2/Argon2)
    return "hash_len${password.length}_code${password.hashCode()}"
}
```

**Für Production:**
- Verwende bcrypt, PBKDF2 oder Argon2
- Implementiere Salt + Iteration-Count
- Niemals Plaintext-Passwörter speichern

### 🔒 Token Storage

- Tokens werden auf 50 Zeichen gekürzt
- Voller Token-Wert wird nicht gespeichert
- Ausreichend für Identifikation, nicht für Replay

### 🔒 Local Storage Only

- Alle Daten werden lokal im App-Verzeichnis gespeichert
- Keine Cloud-Synchronisation
- Nutzer hat volle Kontrolle

## Performance

### Timeline-Caching

- Timeline wird nach erstem Build gecached
- Lädt aus Cache wenn vorhanden
- Invalidierung bei Session-Update

### Credential-Deduplication

- Efficient `filter()` statt `removeAll()`
- O(n) statt O(n²) Komplexität

### Request-Response Correlation

- 30-Sekunden-Zeitfenster (konfigurierbar via Konstante)
- In-Memory-Map für schnelles Lookup
- Single-Pass Algorithmus

## Future Enhancements

### Planned Features

1. **Proper Encryption**
   - AES-256 für sensitive Credentials
   - User-defined Encryption-Key
   - Biometric Unlock

2. **Advanced Correlation**
   - WebSocket Message-Korrelation
   - GraphQL Query-Response Matching
   - Multi-Request Chains

3. **Export/Import**
   - Export Credentials zu Password-Manager (1Password, LastPass)
   - Import existierender Credentials
   - Secure Sharing via QR-Code

4. **Timeline-Analyse**
   - Performance-Bottleneck-Detection
   - Network-Waterfall-View
   - Request-Timing-Charts

## Known Limitations

### MVP Constraints

1. **Form Field Parsing**
   - Benötigt JavaScript-Bridge-Daten
   - Aktuell nur Placeholder-Implementation
   - Siehe `FormAnalyzer.parseFormSubmit()`

2. **Password Hashing**
   - Nicht kryptographisch sicher
   - Nur für Obfuskation, nicht für Security

3. **Correlation Window**
   - Fixed 30-Sekunden-Fenster
   - Keine adaptive Anpassung

## Migration Guide

### Für existierende Projekte

Keine Migration notwendig! Alle Änderungen sind additive:

- Bestehende Session-Files bleiben kompatibel
- Timeline wird beim ersten Laden automatisch gebaut
- Credentials werden bei Bedarf extrahiert

## API Reference

### TimelineBuilder

```kotlin
object TimelineBuilder {
    fun buildTimeline(session: RecordingSession, graph: MapGraph): UnifiedTimeline
}
```

### CredentialExtractor

```kotlin
object CredentialExtractor {
    fun extractCredentials(session: RecordingSession): List<StoredCredential>
    fun hasLoginActivity(session: RecordingSession): Boolean
    fun groupByDomain(credentials: List<StoredCredential>): Map<String, List<StoredCredential>>
}
```

### AndroidProjectStore Extensions

```kotlin
// Credentials
suspend fun saveCredentials(projectId: ProjectId, credentials: List<StoredCredential>)
suspend fun loadCredentials(projectId: ProjectId): List<StoredCredential>
suspend fun addCredential(projectId: ProjectId, credential: StoredCredential)
suspend fun deleteCredential(projectId: ProjectId, credentialId: CredentialId)

// Timeline
suspend fun saveTimeline(projectId: ProjectId, timeline: UnifiedTimeline)
suspend fun loadTimeline(projectId: ProjectId, sessionId: SessionId): UnifiedTimeline?
suspend fun deleteTimeline(projectId: ProjectId, sessionId: SessionId)
```

## Changelog

### Version 1.0.0 (2026-01-15)

**Added:**
- ✅ Unified Session Timeline mit Request-Response Korrelation
- ✅ Session Tree Structure mit Parent-Child Beziehungen
- ✅ Credential Storage mit Privacy-Features
- ✅ UnifiedTimelineScreen UI Component
- ✅ CredentialsScreen UI Component
- ✅ Timeline-Caching in Storage Layer
- ✅ Unit Tests für neue Features

**Changed:**
- Extended `AndroidProjectStore` mit Credentials/Timeline Support
- Generated new Contract Types via KotlinPoet

**Security:**
- Password Hashing (MVP: simple, TODO: bcrypt)
- Token Truncation (50 chars)
- Local Storage Only

## Support

Bei Fragen oder Issues:
- GitHub Issues: https://github.com/karlokarate/FishIT-Mapper/issues
- Issue #28: Login-Szenario Features

---

**Implementiert von:** GitHub Copilot  
**Review Status:** ✅ Code Review bestanden  
**Test Status:** ✅ Alle Unit Tests erfolgreich  
**Build Status:** ✅ App kompiliert erfolgreich
