# Technical Implementation: HTTPS Traffic Capture

## Architektur-Übersicht

Die Implementierung der HTTPS-Traffic-Erfassung besteht aus vier Hauptkomponenten:

### 1. Certificate Management (`CertificateManager.kt`)

**Zweck:** Generierung und Verwaltung von SSL/TLS-Zertifikaten für den MITM-Proxy.

**Technologie:**
- BouncyCastle 1.70 (bcprov-jdk15on, bcpkix-jdk15on)
- Java KeyStore (PKCS12)

**Funktionen:**
- `getOrCreateCACertificate()`: Generiert oder lädt CA-Zertifikat
- `generateServerCertificate(domain)`: Erstellt dynamisch Server-Zertifikate
- `exportCACertificate(file)`: Exportiert CA als PEM zur Installation
- `deleteCACertificate()`: Entfernt CA-Zertifikat (Reset)

**Zertifikats-Details:**
- **Algorithmus:** RSA 2048-bit
- **Signatur:** SHA256WithRSA
- **Gültigkeit:** CA: 365 Tage, Server: 1 Tag
- **Format:** X.509v3 mit BasicConstraints Extension

**Speicherort:**
```
/data/data/dev.fishit.mapper.android/files/fishit_ca.p12
```

### 2. VPN Service (`TrafficCaptureVpnService.kt`)

**Zweck:** System-weite Netzwerk-Traffic-Erfassung ohne Root-Zugriff.

**Technologie:**
- Android VpnService API (android.net.VpnService)
- TUN/TAP Interface
- Foreground Service

**Konfiguration:**
```kotlin
Builder()
    .setSession("FishIT-Mapper VPN")
    .addAddress("10.0.0.2", 24)       // VPN IP-Adresse
    .addRoute("0.0.0.0", 0)           // Alle Routen
    .addDnsServer("8.8.8.8")          // Google DNS
    .setMtu(1500)                      // Standard MTU
```

**Packet Processing:**
- Liest IP-Pakete vom TUN-Interface
- Analysiert IP-Header (IPv4)
- Identifiziert Protokoll (TCP=6, UDP=17)
- Leitet HTTP/HTTPS-Traffic (Port 80/443) zum Proxy

**Limitierungen:**
- Vereinfachte Implementierung (MVP)
- Kein vollständiger TCP/IP-Stack
- Produktionsversion benötigt Bibliothek wie `pcap4j` oder `PacketCapture`

### 3. MITM Proxy Server (`MitmProxyServer.kt`)

**Zweck:** HTTP/HTTPS-Proxy für Traffic-Entschlüsselung und -Logging.

**Technologie:**
- ServerSocket (Port 8888)
- Java SSLContext für TLS
- OkHttp 4.12.0 für HTTP-Client
- Kotlin Coroutines für asynchrone Verarbeitung

**Request-Handling:**

#### HTTP (Port 80):
```
Client → Proxy → Server
  |       ↓
  |    Log Request
  |       ↓
  ← ── Response ←──
```

#### HTTPS (Port 443):
```
Client → CONNECT example.com:443 → Proxy
  |                                   ↓
  |                          200 Connection Established
  |                                   ↓
  |                          SSL Handshake (mit eigenem Cert)
  ├────── Encrypted ────────────────►│
  |                                   ↓ Decrypt
  |                                Log Request
  |                                   ↓ Forward
  |                             Real Server (example.com)
  |                                   ↓
  |                                Response
  |                                   ↓ Encrypt
  ←───── Encrypted ────────────────┤
```

**SSL/TLS-Handling:**
```kotlin
// Erstelle SSLContext mit dynamisch generiertem Server-Cert
val (serverCert, serverKey) = certificateManager.generateServerCertificate(domain, caCert, caKey)

val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
keyStore.setKeyEntry("server", serverKey, "password".toCharArray(), arrayOf(serverCert))

val sslContext = SSLContext.getInstance("TLS")
sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())

val sslSocket = sslContext.socketFactory.createSocket(clientSocket, ...)
```

**Event Logging:**
```kotlin
val event = ResourceRequestEvent(
    id = IdGenerator.newEventId(),
    at = Clock.System.now(),
    url = "https://api.example.com/users",
    method = "POST",
    resourceKind = ResourceKind.XHR
)
onEvent(event)  // Callback zum Recording-System
```

### 4. Network Security Config (`network_security_config.xml`)

**Zweck:** Android-System anweisen, User-CA-Zertifikate zu vertrauen.

**Problem:** Ab Android 7.0 (API 24) vertrauen Apps standardmäßig NICHT User-CAs.

**Lösung:**
```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />  <!-- System CAs -->
            <certificates src="user" />    <!-- User CAs (MITM) -->
        </trust-anchors>
    </base-config>
</network-security-config>
```

**Integration:**
```xml
<!-- AndroidManifest.xml -->
<application
    android:networkSecurityConfig="@xml/network_security_config">
```

## Datenfluss

### End-to-End Flow

```
┌──────────────────────────────────────────────────────────────┐
│                      1. Zertifikat Setup                      │
├──────────────────────────────────────────────────────────────┤
│  User → Settings → Generate Cert → Export → Install on Device│
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│                      2. VPN Aktivierung                       │
├──────────────────────────────────────────────────────────────┤
│  User → Settings → Start VPN → VpnService läuft              │
│                                  ↓                            │
│              Alle TCP/UDP-Pakete → TUN-Interface             │
└──────────────────────────────────────────────────────────────┘
                              ↓
┌──────────────────────────────────────────────────────────────┐
│                    3. Traffic-Erfassung                       │
├──────────────────────────────────────────────────────────────┤
│  Browser → HTTPS Request (z.B. https://api.example.com)      │
│              ↓                                                │
│      VpnService empfängt Paket                                │
│              ↓                                                │
│      Erkennt Port 443 (HTTPS)                                 │
│              ↓                                                │
│      Leitet zu MitmProxyServer:8888                           │
│              ↓                                                │
│      CONNECT-Request                                          │
│              ↓                                                │
│      SSL Handshake mit FishIT-Mapper-Cert                     │
│              ↓                                                │
│      Decrypt HTTPS → Klartext                                 │
│              ↓                                                │
│      Log als ResourceRequestEvent                             │
│              ↓                                                │
│      Forward zu Real Server                                   │
│              ↓                                                │
│      Response empfangen                                       │
│              ↓                                                │
│      Encrypt → zurück zum Browser                             │
└──────────────────────────────────────────────────────────────┘
```

## Sicherheits-Überlegungen

### Was geschützt ist:
- ✅ CA Private Key im App-internen Keystore (nur App-Zugriff)
- ✅ Zertifikat wird bei App-Deinstallation gelöscht
- ✅ Lokale Verarbeitung (keine Cloud)

### Was ein Angreifer benötigt:
- ⚠️ Zugriff auf exportierte PEM-Datei + Installation
- ⚠️ Root-Zugriff auf Gerät (um Keystore zu lesen)

### Empfehlungen:
1. Zertifikat nach Verwendung aus Android-Einstellungen löschen
2. Exportierte PEM-Datei löschen, wenn nicht benötigt
3. Nie in öffentlichen Netzwerken mit installiertem CA-Cert

## Performance-Überlegungen

### Overhead:
- **VPN:** ~5-10ms Latenz (Packet-Routing)
- **MITM-Proxy:** ~20-50ms Latenz (SSL-Handshake)
- **Gesamt:** ~25-60ms zusätzliche Latenz pro Request

### Optimierungen:
- Server-Zertifikate können gecached werden (1 Tag Gültigkeit)
- Connection Pooling via OkHttp
- Asynchrone Verarbeitung mit Coroutines

## Bekannte Limitierungen

1. **Certificate Pinning:** Apps mit Certificate Pinning verweigern MITM
2. **WebSockets:** Begrenzte Unterstützung (nur initiale Handshake)
3. **UDP-Traffic:** DNS wird geroutet, andere UDP-Protokolle nicht entschlüsselt
4. **HTTP/2, HTTP/3:** OkHttp unterstützt HTTP/2, aber TLS 1.3 kann Probleme machen
5. **Performance:** Nicht für High-Traffic-Szenarien optimiert

## Zukünftige Verbesserungen

### Phase 2: Response-Erfassung
```kotlin
data class ResourceResponseEvent(
    val id: String,
    val requestId: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String?,
    val at: Instant
)
```

### Phase 3: WebSocket-Support
- Live-Capture von WS-Messages
- Bidirektionale Kommunikation loggen

### Phase 4: Advanced Proxy
- HTTP/2 Push
- Server-Sent Events
- Brotli/Gzip Decompression

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    // BouncyCastle für Zertifikate
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    
    // OkHttp für HTTP-Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
```

## Testing

### Unit Tests
- `CertificateManagerTest`: Zertifikats-Generierung und Export
- `MitmProxyServerTest`: Request-Handling und SSL

### Integration Tests
- End-to-End mit lokalem Server
- Verschiedene TLS-Versionen

### Manuelles Testing
```bash
# Logcat für Debugging
adb logcat -s CertificateManager MitmProxyServer TrafficCaptureVpn

# VPN-Status prüfen
adb shell dumpsys connectivity | grep VPN

# Zertifikat prüfen
adb shell ls -la /data/data/dev.fishit.mapper.android/files/
```

## Referenzen

- [RFC 5280 - X.509 Certificates](https://tools.ietf.org/html/rfc5280)
- [RFC 2818 - HTTP Over TLS](https://tools.ietf.org/html/rfc2818)
- [Android VpnService](https://developer.android.com/reference/android/net/VpnService)
- [BouncyCastle Docs](https://www.bouncycastle.org/documentation.html)
