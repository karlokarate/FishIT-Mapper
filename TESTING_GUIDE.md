# Testing-Anleitung für Zertifikat und VPN Verbesserungen

## Übersicht

Diese Anleitung hilft beim Testen der implementierten Verbesserungen.

## Voraussetzungen

- Android-Gerät oder Emulator (API Level 26+)
- Debug-APK der App installiert

## Test 1: Zertifikat-Status-Erkennung

### Schritt 1: Zertifikat generieren
1. App starten
2. Hauptbildschirm → Settings-Icon (⚙️) oben rechts klicken
3. Im Settings-Screen nach unten zum "CA-Zertifikat"-Bereich scrollen
4. Button "Zertifikat generieren" klicken

**Erwartetes Ergebnis:**
- Status-Meldung: "Zertifikat erfolgreich generiert"
- Zertifikats-Informationen werden angezeigt:
  - Subject: CN=FishIT-Mapper CA,O=FishIT-Mapper,C=DE
  - Gültig von: [Aktuelles Datum]
  - Gültig bis: [Datum + 365 Tage]
  - Seriennummer: [Nummer]
  - **Status: ⚠️ Nicht installiert** (Rot)

### Schritt 2: Zertifikat exportieren
1. Button "Zertifikat exportieren" klicken

**Erwartetes Ergebnis:**
- Status-Meldung: "Zertifikat erfolgreich exportiert"
- Export-Pfad wird angezeigt (z.B. `/storage/emulated/0/Android/data/.../files/certificates/fishit-mapper-ca.pem`)

### Schritt 3: Zertifikat installieren
1. Button "Zertifikat installieren" klicken
   - Dies öffnet Android Security Settings
2. In Android-Einstellungen:
   - Navigieren zu: Sicherheit → Verschlüsselung & Anmeldedaten
   - "Zertifikat installieren" wählen
   - "CA-Zertifikat" wählen
3. Dateimanager öffnet sich
4. Zum Export-Pfad navigieren (siehe Schritt 2)
5. Datei `fishit-mapper-ca.pem` auswählen
6. Zertifikat-Name bestätigen ("FishIT-Mapper CA")
7. Installation mit Screen-Lock (PIN/Pattern) bestätigen

**Erwartetes Ergebnis:**
- Android zeigt: "Zertifikat installiert"

### Schritt 4: Status aktualisieren
1. Zurück zur FishIT-Mapper App
2. In Settings-Screen
3. Button "Status aktualisieren" klicken

**Erwartetes Ergebnis:**
- **Status: ✓ Im System installiert** (Grün)
- Status bleibt auch nach App-Neustart erhalten

### Schritt 5: App-Neustart testen
1. App komplett schließen (aus Task-Manager entfernen)
2. App neu starten
3. Zu Settings navigieren

**Erwartetes Ergebnis:**
- Status zeigt immer noch: **✓ Im System installiert** (Grün)

## Test 2: Browser-Sichtbarkeit

### Schritt 1: Projekt erstellen
1. Zurück zum Hauptbildschirm
2. FAB (Floating Action Button) "+" unten rechts klicken
3. Projekt-Name eingeben (z.B. "Test Website")
4. Start-URL eingeben (z.B. "https://example.com")
5. "Create" klicken

**Erwartetes Ergebnis:**
- Projekt wird erstellt
- Navigation zu Project-Bildschirm

### Schritt 2: Browser-Tab prüfen
1. Im Project-Bildschirm
2. Bottom Navigation Bar prüfen
3. Tabs sollten sichtbar sein:
   - 🌍 Browser (sollte aktiv/selected sein)
   - 🧠 Graph
   - 🧾 Sessions
   - 🔗 Chains

**Erwartetes Ergebnis:**
- **Browser-Tab ist sichtbar und aktiv**
- Browser-UI zeigt:
  - URL-Eingabefeld mit Start-URL
  - "Go" Button
  - "Record" Button
  - WebView-Bereich

### Schritt 3: Browser testen
1. Im Browser-Tab
2. URL im Textfeld prüfen (sollte Start-URL zeigen)
3. "Go" Button klicken
4. Website sollte im WebView laden

**Erwartetes Ergebnis:**
- Website wird angezeigt
- WebView zeigt den Inhalt

### Schritt 4: Recording testen
1. "Record" Button klicken (sollte zu "Stop" wechseln)
2. Im WebView auf Links klicken oder durch Seite navigieren
3. "Events: X" Counter sollte sich erhöhen

**Erwartetes Ergebnis:**
- Event-Counter steigt mit jeder Aktion
- Button zeigt "Stop"
- Recording funktioniert

## Test 3: VPN-Funktionalität (mit Einschränkung)

### Schritt 1: VPN-Status prüfen
1. In Settings navigieren
2. Zum "VPN Traffic Capture" Bereich scrollen
3. VPN Status prüfen

**Erwartetes Ergebnis:**
- Status: ⚠️ Inaktiv (Rot)
- VPN-Warnung ist sichtbar:
  - "⚠️ VPN-Einschränkung"
  - Erklärung der Limitierung
  - Empfehlung: Browser verwenden

### Schritt 2: VPN starten (optional)
1. Button "VPN starten" klicken
2. Android VPN-Permission Dialog erscheint
3. "OK" klicken

**Erwartetes Ergebnis:**
- VPN Status: ✓ Aktiv (Grün)
- VPN-Icon in Android-Statusleiste erscheint

### Schritt 3: Internet-Konnektivität testen
1. Mit VPN aktiv
2. Andere App öffnen (z.B. Chrome, Browser)
3. Website aufrufen versuchen

**Erwartetes Ergebnis:**
- **KEINE Verbindung möglich** (wie dokumentiert)
- Dies ist erwartetes Verhalten
- Grund: Vereinfachte VPN-Implementierung ohne TCP/IP-Stack

### Schritt 4: VPN stoppen
1. Zurück zu FishIT-Mapper Settings
2. Button "VPN stoppen" klicken

**Erwartetes Ergebnis:**
- Status: ⚠️ Inaktiv (Rot)
- VPN-Icon verschwindet aus Statusleiste
- Internet-Konnektivität in anderen Apps wiederhergestellt

## Test 4: End-to-End Website Mapping

### Kompletter Workflow (empfohlen)
1. **Setup (einmalig):**
   - Settings → Zertifikat generieren
   - Zertifikat exportieren
   - In Android installieren
   - Status prüfen (sollte "installiert" zeigen)

2. **Projekt erstellen:**
   - Hauptbildschirm → "+" Button
   - Name: "Example Website"
   - URL: "https://example.com"
   - Create

3. **Recording:**
   - Browser-Tab (sollte aktiv sein)
   - "Record" klicken
   - "Go" klicken → Website lädt
   - Durch Website navigieren
   - Links klicken, Formulare ausfüllen
   - Events werden gezählt
   - "Stop" klicken

4. **Ergebnisse prüfen:**
   - Graph-Tab: Nodes und Verbindungen
   - Sessions-Tab: Recording-Session anzeigen
   - Session öffnen → Events-Liste

**Erwartetes Ergebnis:**
- Vollständige Erfassung aller:
  - HTTP/HTTPS Requests
  - Navigation Events
  - User Actions
  - Resource Loading
  - Form Submissions

## Bekannte Issues (nicht zu testen)

### Pre-existing Build-Fehler
- `shared:engine` Modul kompiliert nicht
- Unrelated zu unseren Änderungen
- Siehe BUILD_ISSUE.md
- **androidApp baut korrekt** (relevanter Teil)

### VPN-Limitation
- VPN leitet keinen Traffic
- Dies ist **keine Regression**
- War bereits bekannt/dokumentiert
- Empfehlung: Browser verwenden

## Screenshots/Logs

### Erfolgreiche Tests sollten zeigen:

**Settings - Zertifikat installiert:**
```
CA-Zertifikat
├── Status: ✓ Im System installiert (grün)
├── Subject: CN=FishIT-Mapper CA,...
├── Gültig von: [Datum]
└── Gültig bis: [Datum]
```

**Browser-Tab:**
```
┌─────────────────────────────────┐
│ URL: https://example.com    [Go]│
├─────────────────────────────────┤
│ [Record]  Events: 15            │
├─────────────────────────────────┤
│                                 │
│   [WebView mit Website-Inhalt]  │
│                                 │
└─────────────────────────────────┘
```

**VPN-Warnung:**
```
⚠️ VPN-Einschränkung
Die aktuelle VPN-Implementierung ist vereinfacht 
und funktioniert NICHT für system-weiten Traffic.

✅ Empfehlung: Verwenden Sie den integrierten 
Browser im Project-Tab für vollständige 
Traffic-Erfassung.
```

## Fehlerbehebung

### Problem: Status zeigt "Nicht installiert" obwohl installiert
**Lösung:**
1. "Status aktualisieren" Button klicken
2. Falls immer noch nicht: Zertifikat-Installation in Android-Settings prüfen
3. Settings → Sicherheit → Vertrauenswürdige Anmeldedaten → Nutzer-Tab
4. "FishIT-Mapper CA" sollte aufgelistet sein

### Problem: Browser-Tab nicht sichtbar
**Lösung:**
1. Sicherstellen, dass ein Projekt geöffnet ist
2. Bottom Navigation Bar prüfen
3. Falls fehlt: Bug reporten (sollte nicht passieren)

### Problem: WebView lädt keine Seiten
**Lösung:**
1. Internet-Verbindung prüfen
2. VPN ausschalten (falls aktiv)
3. Chrome/WebView-System-App aktualisieren
4. App-Permissions prüfen (Internet)

## Test-Checkliste

- [ ] Zertifikat generieren funktioniert
- [ ] Export erstellt Datei
- [ ] Installation in Android erfolgreich
- [ ] Status zeigt "Nicht installiert" → "Installiert"
- [ ] Status aktualisieren funktioniert
- [ ] Status bleibt nach Neustart
- [ ] Browser-Tab ist sichtbar
- [ ] WebView lädt Websites
- [ ] Recording erfasst Events
- [ ] VPN kann gestartet werden
- [ ] VPN-Warnung wird angezeigt
- [ ] VPN kann gestoppt werden

## Erfolg-Kriterien

✅ **Hauptziele erreicht:**
1. Zertifikat-Status wird im UI angezeigt
2. Status bleibt nach App-Neustart erhalten
3. Browser ist sichtbar und funktional
4. VPN-Limitation ist dokumentiert
5. Empfohlener Workflow funktioniert

✅ **User kann jetzt:**
1. Sehen, ob Zertifikat installiert ist
2. Status manuell aktualisieren
3. Browser für vollständiges Website-Mapping nutzen
4. Versteht VPN-Limitationen
