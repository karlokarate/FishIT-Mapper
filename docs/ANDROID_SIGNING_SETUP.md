# Android App Signing Setup

Dieser Guide erklärt, wie Sie den Keystore für das Signieren der FishIT-Mapper Android-App einrichten.

## 📋 Übersicht

FishIT-Mapper unterstützt automatisches App-Signing in GitHub Actions durch:
1. **Keystore-Generierung**: Automatischer Workflow zur Keystore-Erstellung
2. **GitHub Secrets**: Sichere Speicherung der Signing-Informationen
3. **Automatisches Signing**: Integration in Build-Workflows

## 🔐 Schritt 1: Keystore generieren

### Option A: GitHub Actions Workflow (Empfohlen)

1. Gehen Sie zu **Actions** → **Generate Keystore and Secrets**
2. Klicken Sie auf **Run workflow**
3. Füllen Sie die Felder aus:
   - **Keystore Password**: Mindestens 6 Zeichen (z.B. `MySecureKeystorePass123`)
   - **Key Password**: Mindestens 6 Zeichen (z.B. `MySecureKeyPass123`)
   - **Key Alias**: Standard ist `fishit-mapper` (kann geändert werden)
   - **Validity Days**: Standard ist `10000` (~27 Jahre)
   - **Distinguished Name**: Standard ist `CN=FishIT Mapper,OU=Development,O=FishIT,L=Unknown,ST=Unknown,C=DE`

4. Klicken Sie auf **Run workflow** und warten Sie auf die Fertigstellung

5. **Wichtig**: Kopieren Sie den Base64-kodierten Keystore aus dem Job-Log:
   - Öffnen Sie den abgeschlossenen Workflow-Run
   - Klicken Sie auf den Job "generate-keystore"
   - Scrollen Sie zum Step "Display Base64 Encoded Keystore"
   - Kopieren Sie den kompletten Base64-String (die lange Zeichenkette)

### Option B: Manuell (für lokale Entwicklung)

```bash
# Keystore generieren
keytool -genkeypair \
  -v \
  -keystore keystore/release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias fishit-mapper \
  -storepass "YourKeystorePassword" \
  -keypass "YourKeyPassword" \
  -dname "CN=FishIT Mapper,OU=Development,O=FishIT,L=Unknown,ST=Unknown,C=DE"

# Für GitHub Actions: Base64-kodieren
base64 -w 0 keystore/release.jks > keystore-base64.txt
```

## 🔑 Schritt 2: GitHub Secrets konfigurieren

1. Gehen Sie zu **Settings** → **Secrets and variables** → **Actions**

2. Klicken Sie auf **New repository secret** und fügen Sie folgende Secrets hinzu:

### Secret 1: KEYSTORE_BASE64
- **Name:** `KEYSTORE_BASE64`
- **Value:** Der komplette Base64-String aus Schritt 1
  - Achten Sie darauf, dass KEINE Zeilenumbrüche im String sind
  - Der String sollte mit Zeichen wie `MIIJ...` beginnen

### Secret 2: KEYSTORE_PASSWORD
- **Name:** `KEYSTORE_PASSWORD`
- **Value:** Das Keystore-Passwort aus Schritt 1 (z.B. `MySecureKeystorePass123`)

### Secret 3: KEY_ALIAS
- **Name:** `KEY_ALIAS`
- **Value:** Der Key-Alias aus Schritt 1 (Standard: `fishit-mapper`)

### Secret 4: KEY_PASSWORD
- **Name:** `KEY_PASSWORD`
- **Value:** Das Key-Passwort aus Schritt 1 (z.B. `MySecureKeyPass123`)

3. **Verifizieren**: Sie sollten nun 4 Secrets sehen:
   - ✅ KEYSTORE_BASE64
   - ✅ KEYSTORE_PASSWORD
   - ✅ KEY_ALIAS
   - ✅ KEY_PASSWORD

## 🚀 Schritt 3: Signierte Builds erstellen

Nach der Konfiguration können Sie signierte Builds auf verschiedene Arten erstellen:

### Automatisch bei Push/PR
- Bei jedem Push auf `main` wird automatisch der **Android Build** Workflow ausgeführt
- Der Workflow erstellt eine signierte Release-APK
- Die APK ist als Artifact verfügbar

### Manuell über GitHub Actions

#### Workflow: Android Build
```
Actions → Android Build → Run workflow
```
- Erstellt automatisch eine signierte Release-APK
- Fallback auf Debug-APK wenn kein Keystore verfügbar

#### Workflow: Build Android App
```
Actions → Build Android App → Run workflow
```
- **Build Type**: Wählen Sie zwischen:
  - `debug`: Debug-APK (nicht signiert)
  - `release`: Signierte Release-APK
  - `both`: Beide Varianten

## 📱 Schritt 4: APK herunterladen und installieren

1. Gehen Sie zum abgeschlossenen Workflow-Run
2. Scrollen Sie zu **Artifacts**
3. Laden Sie die APK herunter:
   - `app-release-signed`: Signierte Release-APK
   - `app-debug`: Debug-APK (falls verfügbar)

4. Entpacken Sie die ZIP-Datei
5. Installieren Sie die APK auf Ihrem Android-Gerät:
   ```bash
   adb install app-release.apk
   ```

## 🔧 Lokale Entwicklung (Optional)

Für lokale signierte Builds:

1. Laden Sie den Keystore herunter:
   - Aus dem Workflow-Artifact (Schritt 1), oder
   - Verwenden Sie Ihren eigenen Keystore

2. Speichern Sie den Keystore unter:
   ```
   keystore/release.jks
   ```

3. Setzen Sie die Umgebungsvariablen:
   ```bash
   export KEYSTORE_PASSWORD="YourKeystorePassword"
   export KEY_ALIAS="fishit-mapper"
   export KEY_PASSWORD="YourKeyPassword"
   ```

4. Build signierte Release-APK:
   ```bash
   ./gradlew :androidApp:assembleRelease
   ```

5. APK finden unter:
   ```
   androidApp/build/outputs/apk/release/androidApp-release.apk
   ```

⚠️ **WICHTIG:** Committen Sie den Keystore **NIEMALS** ins Git-Repository!
- Der `keystore/` Ordner ist bereits in `.gitignore` eingetragen
- Bei versehentlichem Commit: Keystore sofort wechseln!

## 🔒 Sicherheitshinweise

### Für Entwicklungs-/Test-Builds
- Die generierten Keystores sind für Entwicklung und Testing geeignet
- Verwenden Sie starke, einzigartige Passwörter
- Löschen Sie alte Workflow-Runs nach der Secret-Konfiguration

### Für Production-Builds
Für Production-Apps empfehlen wir:
- Separaten, professionell verwalteten Keystore
- Verwendung von Google Play App Signing
- Hardware Security Module (HSM) für Schlüsselspeicherung
- Regelmäßige Security Audits

### GitHub Secrets
- ✅ Secrets sind verschlüsselt in GitHub gespeichert
- ✅ Nur Workflows mit entsprechenden Berechtigungen haben Zugriff
- ✅ Secrets werden nicht in Logs angezeigt
- ⚠️ Base64-kodierter Keystore ist im Job-Log sichtbar - Runs löschen!

## 🛠️ Troubleshooting

### Problem: "No keystore available"
**Lösung**: Überprüfen Sie, ob alle 4 Secrets korrekt konfiguriert sind

### Problem: "Keystore was tampered with, or password was incorrect"
**Lösung**: 
- Überprüfen Sie `KEYSTORE_PASSWORD` und `KEY_PASSWORD`
- Stellen Sie sicher, dass der Base64-String vollständig kopiert wurde
- Regenerieren Sie den Keystore falls nötig

### Problem: Build schlägt mit Signing-Fehler fehl
**Lösung**:
- Überprüfen Sie die Workflow-Logs auf Fehlermeldungen
- Validieren Sie, dass der Keystore korrekt dekodiert wurde
- Prüfen Sie, ob `KEY_ALIAS` mit dem Alias im Keystore übereinstimmt

### Problem: APK kann nicht installiert werden
**Lösung**:
- Deinstallieren Sie alte Versionen der App
- Aktivieren Sie "Installation aus unbekannten Quellen"
- Überprüfen Sie, ob die APK signiert ist: `jarsigner -verify app-release.apk`

## 📚 Weitere Ressourcen

- [Android App Signing Dokumentation](https://developer.android.com/studio/publish/app-signing)
- [GitHub Secrets Dokumentation](https://docs.github.com/en/actions/security-guides/encrypted-secrets)
- [Keytool Dokumentation (Java 17)](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
- [Google Play App Signing](https://developer.android.com/studio/publish/app-signing#app-signing-google-play)

## 🔄 Keystore erneuern

Wenn Sie den Keystore erneuern müssen:

1. Führen Sie den **Generate Keystore and Secrets** Workflow erneut aus
2. Aktualisieren Sie alle 4 GitHub Secrets mit den neuen Werten
3. ⚠️ **ACHTUNG**: Apps mit unterschiedlichen Keystores können nicht überschrieben werden
   - Benutzer müssen die alte App deinstallieren
   - Für Updates: Verwenden Sie immer denselben Keystore!

## ✅ Checkliste

- [ ] Keystore-Generierung Workflow erfolgreich ausgeführt
- [ ] Base64-Keystore aus Job-Log kopiert
- [ ] 4 GitHub Secrets konfiguriert (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
- [ ] Android Build Workflow erfolgreich ausgeführt
- [ ] Signierte APK heruntergeladen und getestet
- [ ] Alte Workflow-Runs mit sichtbarem Base64-String gelöscht (optional aber empfohlen)
- [ ] Keystore-Backup erstellt (optional aber empfohlen)

---

**Status**: ✅ Setup abgeschlossen! Die App kann nun automatisch signiert gebaut werden.
