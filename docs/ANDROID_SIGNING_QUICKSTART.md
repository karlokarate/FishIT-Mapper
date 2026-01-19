# Erste Schritte: Android App Signing Setup

## 🎯 Ziel
Dieses Quick-Start-Guide führt Sie in 10 Minuten durch das komplette Setup für automatisch signierte Android-Builds.

## ⚡ Quick Start (5 Minuten)

### Schritt 1: Keystore generieren (2 Minuten)

1. **Öffnen Sie GitHub Actions**
   - Gehen Sie zu: `https://github.com/karlokarate/FishIT-Mapper/actions`
   - Klicken Sie auf: **"Generate Keystore and Secrets"** (linke Sidebar)

2. **Workflow starten**
   - Klicken Sie auf: **"Run workflow"** (rechts oben)
   - Branch: `main` (oder Ihr aktueller Branch)

3. **Parameter eingeben**
   ```
   Keystore Password: MeinSicheresPasswort123!
   Key Password: MeinSicheresKeyPasswort123!
   Key Alias: fishit-mapper (default, kann so bleiben)
   Validity Days: 10000 (default, kann so bleiben)
   Distinguished Name: (default, kann so bleiben)
   ```

4. **Starten**
   - Klicken Sie auf: **"Run workflow"** (grüner Button)
   - Warten Sie ~30 Sekunden bis der Workflow fertig ist

### Schritt 2: Secrets konfigurieren (2 Minuten)

1. **Base64-String kopieren**
   - Öffnen Sie den abgeschlossenen Workflow-Run
   - Klicken Sie auf: **"generate-keystore"** (der Job-Name)
   - Scrollen Sie zu: **"Display Base64 Encoded Keystore"**
   - Kopieren Sie den **kompletten** String (die lange Zeichenkette)
     - Beginnt etwa mit: `MIIJ...`
     - Kann mehrere Zeilen haben - alles kopieren!

2. **Secrets hinzufügen**
   - Gehen Sie zu: `Settings` → `Secrets and variables` → `Actions`
   - Klicken Sie: **"New repository secret"** (4x wiederholen)

   **Secret 1:**
   ```
   Name: KEYSTORE_BASE64
   Value: [Der kopierte Base64-String]
   ```

   **Secret 2:**
   ```
   Name: KEYSTORE_PASSWORD
   Value: MeinSicheresPasswort123!
   ```

   **Secret 3:**
   ```
   Name: KEY_ALIAS
   Value: fishit-mapper
   ```

   **Secret 4:**
   ```
   Name: KEY_PASSWORD
   Value: MeinSicheresKeyPasswort123!
   ```

3. **Verifizieren**
   - Sie sollten jetzt 4 Secrets sehen mit grünen Häkchen

### Schritt 3: Workflow-Run löschen (30 Sekunden) ⚠️ WICHTIG!

1. **Zurück zu Actions**
   - Gehen Sie zu: `Actions` → `Generate Keystore and Secrets`
   - Finden Sie den gerade ausgeführten Workflow-Run

2. **Run löschen**
   - Klicken Sie auf den Run
   - Klicken Sie auf das **"..." Menü** (rechts oben)
   - Wählen Sie: **"Delete workflow run"**
   - Bestätigen Sie

   **Warum?** Der Base64-Keystore ist im Workflow-Log sichtbar. Das Löschen verhindert unbefugten Zugriff.

### Schritt 4: Erste signierte APK bauen (5 Minuten)

1. **Android Build Workflow starten**
   - Gehen Sie zu: `Actions` → `Android Build`
   - Klicken Sie: **"Run workflow"**
   - Klicken Sie: **"Run workflow"** (grüner Button)

2. **Warten**
   - Der Build dauert ~5-10 Minuten
   - Status wird in der Actions-Übersicht angezeigt

3. **APK herunterladen**
   - Öffnen Sie den abgeschlossenen Workflow-Run
   - Scrollen Sie zu: **"Artifacts"** (ganz unten)
   - Klicken Sie: **"app-release-signed"**
   - ZIP-Datei wird heruntergeladen

4. **APK entpacken & installieren**
   ```bash
   # ZIP entpacken
   unzip app-release-signed.zip
   
   # APK auf Gerät installieren
   adb install androidApp-release.apk
   ```

## ✅ Fertig!

Ihre App ist jetzt automatisch signiert und kann über GitHub Actions gebaut werden.

### Was passiert nun?

**Bei jedem Push auf `main`:**
- ✅ Automatischer Build-Workflow läuft
- ✅ Signierte Release-APK wird erstellt
- ✅ APK ist als Artifact verfügbar

**Bei Pull Requests:**
- ✅ Build wird getestet
- ✅ Fehler werden sofort erkannt

**Manuell:**
- ✅ Workflow jederzeit manuell starten
- ✅ Build-Typ wählen (Debug/Release/Both)

## 🔧 Troubleshooting

### Problem: "No keystore available"

**Lösung:** Secrets nicht korrekt konfiguriert
- Überprüfen Sie alle 4 Secrets in `Settings → Secrets`
- Stellen Sie sicher, dass die Namen **exakt** übereinstimmen
- Base64-String vollständig kopiert? (keine Zeilenumbrüche fehlen?)

### Problem: "Keystore was tampered with"

**Lösung:** Passwort falsch oder Base64-String beschädigt
- Überprüfen Sie `KEYSTORE_PASSWORD` und `KEY_PASSWORD`
- Regenerieren Sie den Keystore wenn nötig
- Stellen Sie sicher, dass der Base64-String vollständig ist

### Problem: Build schlägt fehl

**Lösung:** Schauen Sie in die Workflow-Logs
- Öffnen Sie den fehlgeschlagenen Workflow-Run
- Klicken Sie auf den Job-Namen
- Lesen Sie die Fehlermeldung
- Häufige Ursachen:
  - Contract-Generierung fehlgeschlagen
  - Gradle-Build-Fehler (unabhängig vom Signing)
  - Netzwerk-Probleme bei Dependency-Download

### Problem: APK lässt sich nicht installieren

**Lösung:** 
1. Deinstallieren Sie alte Versionen der App
2. Aktivieren Sie "Installation aus unbekannten Quellen"
3. Überprüfen Sie, ob die APK signiert ist:
   ```bash
   jarsigner -verify androidApp-release.apk
   ```

## 📱 Nächste Schritte

### Lokale Entwicklung (Optional)

Wenn Sie auch lokal signierte Builds erstellen möchten:

1. **Keystore herunterladen**
   - Gehen Sie zum "Generate Keystore" Workflow-Run
   - Laden Sie das Artifact "keystore-release" herunter
   - Entpacken Sie `release.jks`

2. **Keystore speichern**
   ```bash
   # Im Repository-Root
   mkdir -p keystore
   cp /pfad/zu/release.jks keystore/release.jks
   ```

3. **Umgebungsvariablen setzen**
   ```bash
   export KEYSTORE_PASSWORD="MeinSicheresPasswort123!"
   export KEY_ALIAS="fishit-mapper"
   export KEY_PASSWORD="MeinSicheresKeyPasswort123!"
   ```

4. **Release-Build**
   ```bash
   ./gradlew :androidApp:assembleRelease
   ```

⚠️ **NIEMALS** den Keystore ins Git-Repository committen!

### Build-Varianten

Sie haben mehrere Optionen für Builds:

**Option 1: Automatisch (Empfohlen)**
- Push auf `main` → Automatischer signierter Build

**Option 2: Manuell mit Workflow "Android Build"**
- Einfacher Workflow
- Erstellt Release-APK wenn Secrets verfügbar
- Sonst Debug-APK als Fallback

**Option 3: Manuell mit Workflow "Build Android App"**
- Erweiterte Optionen
- Wählen Sie Build-Typ: Debug / Release / Both
- Nützlich für Testing und Vergleiche

## 🎓 Weiterführende Dokumentation

Für detaillierte Informationen siehe:

- **Vollständiger Setup-Guide**: [ANDROID_SIGNING_SETUP.md](ANDROID_SIGNING_SETUP.md)
- **Workflow-Übersicht**: [KEYSTORE_WORKFLOW_SUMMARY.md](KEYSTORE_WORKFLOW_SUMMARY.md)
- **Repository README**: [../README.md](../README.md)

## 🔒 Sicherheits-Checkliste

Nach dem Setup sollten Sie:

- [x] Alle 4 GitHub Secrets konfiguriert
- [x] Workflow-Run mit sichtbarem Base64-String gelöscht
- [x] Ersten signierten Build erfolgreich erstellt
- [x] APK erfolgreich auf Gerät installiert
- [ ] Keystore-Backup erstellen (empfohlen)
- [ ] Passwörter in Passwort-Manager speichern (empfohlen)
- [ ] Repository-Zugriffsrechte überprüfen (wer hat Zugriff auf Secrets?)

## 💡 Tipps

- **Passwort-Sicherheit**: Verwenden Sie starke, einzigartige Passwörter
- **Backup**: Sichern Sie den Keystore und die Passwörter an einem sicheren Ort
- **Production**: Für Production-Apps verwenden Sie Google Play App Signing
- **CI/CD**: Der Build-Workflow kann erweitert werden (z.B. automatisches Deployment)
- **Testing**: Testen Sie die signierte APK gründlich vor dem Release

---

**Zeit investiert**: ~10 Minuten  
**Ergebnis**: Vollständig automatisierte signierte Android-Builds 🎉

**Fragen?** Siehe [Troubleshooting](#-troubleshooting) oder die vollständige Dokumentation.
