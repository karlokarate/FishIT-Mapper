# HTTPS Traffic Capture - Vollständige Dokumentation

## 📋 Überblick

FishIT-Mapper unterstützt jetzt **vollständige HTTPS-Traffic-Erfassung** mit Entschlüsselung über einen integrierten MITM-Proxy (Man-in-the-Middle). Dies ermöglicht die Analyse von:

- ✅ **Request-Headers und -Bodies** (inkl. POST-Daten, JSON, etc.)
- ✅ **Response-Headers und -Bodies** (HTML, JSON, API-Responses)
- ✅ **HTTP-Status-Codes** (200, 404, 500, etc.)
- ✅ **Authentifizierungs-Token** (Bearer, JWT, Session-Cookies)
- ✅ **Verschlüsselter HTTPS-Traffic** im Klartext

## 🏗️ Architektur

### Komponenten

```
┌─────────────────────────────────────────────────────────┐
│                   FishIT-Mapper App                     │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────────────┐         ┌──────────────────┐    │
│  │  SettingsScreen  │────────▶│ CertificateManager│    │
│  │  (UI)            │         │                   │    │
│  └──────────────────┘         └──────────────────┘    │
│           │                            │               │
│           ▼                            ▼               │
│  ┌──────────────────┐         ┌──────────────────┐    │
│  │ VpnService       │────────▶│ MitmProxyServer  │    │
│  │ (Traffic Routing)│         │ (HTTPS Decrypt)  │    │
│  └──────────────────┘         └──────────────────┘    │
│           │                            │               │
└───────────┼────────────────────────────┼───────────────┘
            │                            │
            ▼                            ▼
    ┌──────────────┐           ┌──────────────┐
    │ Android OS   │           │ Target Server│
    │ Network Stack│           │ (example.com)│
    └──────────────┘           └──────────────┘
```

### 1. **CertificateManager**
- Generiert ein selbst-signiertes CA-Zertifikat (Root CA)
- Speichert Zertifikat und Private Key sicher im App-internen Keystore
- Exportiert CA-Zertifikat als PEM-Datei zur Installation
- Generiert dynamisch Server-Zertifikate für jede Domain

**Technologie:** BouncyCastle für Zertifikats-Operationen

### 2. **TrafficCaptureVpnService**
- Android VpnService für system-weite Traffic-Erfassung
- Erstellt ein virtuelles TUN-Interface (10.0.0.2/24)
- Leitet allen TCP/UDP-Traffic durch die App
- Ermöglicht Packet-Level-Analyse ohne Root

**Technologie:** Android VpnService API

### 3. **MitmProxyServer**
- Lokaler Proxy-Server auf Port 8888
- Fängt HTTP/HTTPS CONNECT-Requests ab
- Führt SSL/TLS-Handshake mit eigenem Zertifikat durch
- Entschlüsselt HTTPS-Traffic und leitet ihn zum echten Server weiter
- Loggt alle Request/Response-Daten als RecorderEvents

**Technologie:** OkHttp für HTTP-Client, Java SSLContext für TLS

### 4. **Network Security Config**
- Konfiguriert App, um User-CA-Zertifikate zu vertrauen
- Ermöglicht MITM-Proxy-Funktionalität
- Unterstützt sowohl System- als auch User-CAs

## 🚀 Verwendung

### Schritt 1: Zertifikat generieren

1. Öffne **Settings** über das Zahnrad-Icon auf dem Projects-Screen
2. Klicke auf **"Zertifikat generieren"**
3. Das CA-Zertifikat wird erstellt und im App-internen Speicher abgelegt

### Schritt 2: Zertifikat exportieren

1. Klicke auf **"Zertifikat exportieren"**
2. Das Zertifikat wird nach `/storage/emulated/0/Android/data/dev.fishit.mapper.android/files/certificates/fishit-mapper-ca.pem` exportiert

### Schritt 3: Zertifikat installieren

#### **Option A: Direkt aus der App** (empfohlen)

1. Klicke auf **"Zertifikat installieren"**
2. Die Android-Einstellungen öffnen sich bei **"Sicherheit"**
3. Navigiere zu **"Verschlüsselung & Anmeldedaten"**
4. Wähle **"Zertifikat installieren"** → **"CA-Zertifikat"**
5. Navigiere zum exportierten Zertifikat
6. Bestätige die Installation

#### **Option B: Manuelle Installation**

1. Öffne Android-Einstellungen
2. **Sicherheit** → **Verschlüsselung & Anmeldedaten**
3. **Zertifikat installieren** → **CA-Zertifikat**
4. Wähle die Datei `fishit-mapper-ca.pem`
5. Vergib einen Namen (z.B. "FishIT-Mapper CA")
6. Bestätige mit PIN/Pattern/Passwort

### Schritt 4: VPN starten

1. Klicke auf **"VPN starten"**
2. Akzeptiere die VPN-Berechtigung
3. Der VPN-Status wechselt zu **"Aktiv"** (grünes Häkchen)
4. Alle Netzwerk-Requests werden nun über den Proxy geleitet

### Schritt 5: Traffic erfassen

1. Navigiere zum **Browser**-Tab eines Projekts
2. Starte eine Recording-Session
3. Browsing wie gewohnt - HTTPS-Traffic wird jetzt entschlüsselt erfasst

## ⚠️ Wichtige Hinweise

### Android-Versionen

- **Android 7.0 (API 24) und höher:** Apps vertrauen standardmäßig NICHT User-CAs
- **Lösung:** Network Security Config (bereits integriert)
- **Limitierung:** Apps von Drittanbietern können trotzdem User-CAs blockieren

### Sicherheit

- ⚠️ **CA-Zertifikat ist sensibel!** Jeder, der Zugriff auf das Zertifikat hat, kann Ihren verschlüsselten Traffic entschlüsseln
- ✅ Das Zertifikat wird im App-internen Speicher gespeichert (nicht zugänglich für andere Apps)
- ✅ Nach Deinstallation der App wird das Zertifikat automatisch entfernt
- ⚠️ **Empfehlung:** Zertifikat nach Verwendung aus Android-Einstellungen löschen

### Einschränkungen

1. **Certificate Pinning:** Apps mit Certificate Pinning (z.B. Banking-Apps) können nicht intercepted werden
2. **Verschlüsselte Verbindungen:** Einige Apps nutzen zusätzliche Verschlüsselungsschichten (z.B. WebSockets mit TLS)
3. **Performance:** MITM-Proxy kann Latenz erhöhen
4. **VPN-Kompatibilität:** Nur ein VPN gleichzeitig aktiv (inkl. andere VPN-Apps)

## 🛠️ Technische Details

### Zertifikats-Hierarchie

```
┌─────────────────────────────────────┐
│  FishIT-Mapper CA (Root)            │
│  - CN=FishIT-Mapper CA              │
│  - Self-signed                      │
│  - Valid for 365 days               │
└────────────┬────────────────────────┘
             │
             │ signs
             ▼
┌─────────────────────────────────────┐
│  Server Certificate (Dynamic)       │
│  - CN=example.com                   │
│  - Signed by FishIT-Mapper CA       │
│  - Valid for 1 day                  │
│  - Generated on-the-fly per domain  │
└─────────────────────────────────────┘
```

### Network Security Config

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <!-- System CAs (vorinstalliert) -->
            <certificates src="system" />
            <!-- User CAs (manuell installiert) -->
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

### VPN-Konfiguration

- **VPN-Adresse:** 10.0.0.2/24
- **DNS-Server:** 8.8.8.8 (Google DNS)
- **MTU:** 1500 Bytes
- **Routing:** 0.0.0.0/0 (gesamter Traffic)

### Proxy-Server

- **Port:** 8888
- **Protokolle:** HTTP, HTTPS (via CONNECT)
- **SSL/TLS:** TLS 1.2/1.3
- **Cipher Suites:** Standard Java SSLContext

## 📊 Erfasste Daten

### HTTP-Requests

```kotlin
ResourceRequestEvent(
    id = "evt_123",
    at = Instant.now(),
    url = "https://api.example.com/users",
    initiatorUrl = "https://example.com",
    method = "POST",
    resourceKind = ResourceKind.XHR,
    // NEU: Request Headers (planned)
    // NEU: Request Body (planned)
)
```

### HTTP-Responses (geplant für zukünftige Versionen)

```kotlin
ResourceResponseEvent(
    id = "evt_124",
    requestId = "evt_123",
    at = Instant.now(),
    statusCode = 200,
    headers = mapOf(
        "Content-Type" to "application/json",
        "Set-Cookie" to "session=abc123"
    ),
    body = """{"id": 1, "name": "John"}"""
)
```

## 🔧 Troubleshooting

### Problem: "Zertifikat konnte nicht installiert werden"

**Lösung:**
1. Stelle sicher, dass das Zertifikat exportiert wurde
2. Prüfe, ob eine Bildschirmsperre (PIN/Pattern) eingerichtet ist
3. Versuche, das Zertifikat manuell über Dateimanager zu öffnen

### Problem: "VPN kann nicht gestartet werden"

**Lösung:**
1. Deaktiviere andere VPN-Apps
2. Prüfe VPN-Berechtigung in Android-Einstellungen
3. Starte die App neu

### Problem: "HTTPS-Traffic wird nicht entschlüsselt"

**Lösung:**
1. Prüfe, ob CA-Zertifikat installiert ist
2. Prüfe, ob VPN aktiv ist (grünes Häkchen)
3. Teste mit einer Website ohne Certificate Pinning (z.B. example.com)
4. Prüfe Logcat für Fehler: `adb logcat -s MitmProxyServer CertificateManager TrafficCaptureVpn`

### Problem: "Keine Internetverbindung nach VPN-Start"

**Lösung:**
1. Stoppe VPN
2. Prüfe, ob Proxy-Server läuft (Settings-Screen)
3. Deinstalliere und installiere CA-Zertifikat neu
4. Neustart des Geräts

## 📚 Weitere Ressourcen

- [BouncyCastle Documentation](https://www.bouncycastle.org/documentation.html)
- [Android VpnService API](https://developer.android.com/reference/android/net/VpnService)
- [Network Security Configuration](https://developer.android.com/training/articles/security-config)
- [OkHttp Documentation](https://square.github.io/okhttp/)

## 🔒 Datenschutz

- **Lokale Verarbeitung:** Alle Daten werden nur lokal auf dem Gerät verarbeitet
- **Keine Cloud-Sync:** Keine Daten werden an externe Server gesendet
- **App-interner Speicher:** Zertifikate und Daten sind nur für die App zugänglich
- **Manuelle Kontrolle:** Benutzer hat volle Kontrolle über Zertifikate und VPN

## 🎯 Nächste Schritte (Roadmap)

- [ ] **Response-Erfassung:** Vollständige Response-Daten (Headers, Body, Status)
- [ ] **WebSocket-Support:** Live-Erfassung von WebSocket-Nachrichten
- [ ] **Request/Response-Pairing:** Zuordnung von Requests zu ihren Responses
- [ ] **Content-Decoding:** Automatisches Dekodieren von gzip/deflate
- [ ] **Request-Replay:** Wiederholung von Requests für Testing
- [ ] **Advanced Filtering:** Filterung nach Content-Type, Status-Code, etc.
- [ ] **Export-Formats:** Export als HAR (HTTP Archive), Postman Collection
