# Android Build Keystore Fix - Anleitung

## Problem

Die Android Build Workflows schlugen fehl mit folgendem Fehler:

```
Caused by: com.android.ide.common.signing.KeytoolException: Failed to read key *** from store "/home/runner/work/FishIT-Mapper/FishIT-Mapper/androidApp/keystore.jks": No key with alias '***' found in keystore /home/runner/work/FishIT-Mapper/FishIT-Mapper/androidApp/keystore.jks
```

## Ursache

Das Problem trat auf, weil der **KEY_ALIAS** GitHub Secret nicht mit dem Alias übereinstimmte, der beim Generieren des Keystores verwendet wurde:

1. ✅ `KEYSTORE_BASE64` - Keystore-Datei (Base64-kodiert) war korrekt konfiguriert
2. ✅ `KEYSTORE_PASSWORD` - Keystore-Passwort war korrekt konfiguriert
3. ✅ `KEY_PASSWORD` - Key-Passwort war korrekt konfiguriert
4. ❌ `KEY_ALIAS` - **War entweder nicht gesetzt oder stimmte nicht mit dem Keystore überein**

## Lösung

### Was wurde geändert?

Die Android Build Workflows (`android-build.yml` und `build-app.yml`) wurden verbessert:

#### 1. Keystore-Validierung hinzugefügt

Vor dem Build wird jetzt überprüft, ob der angegebene `KEY_ALIAS` im Keystore existiert:

```bash
# Check if the specified alias exists
if keytool -list -keystore ./androidApp/keystore.jks -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null 2>&1; then
  echo "✅ Key alias '$KEY_ALIAS' found in keystore"
else
  echo "❌ ERROR: Key alias '$KEY_ALIAS' NOT found in keystore!"
  echo ""
  echo "Available aliases in keystore:"
  keytool -list -keystore ./androidApp/keystore.jks -storepass "$KEYSTORE_PASSWORD" | grep "Alias name:"
  echo ""
  echo "💡 FIX: Set the KEY_ALIAS secret to match one of the aliases above"
fi
```

#### 2. Verbesserte Fehlermeldungen

Bei einem Alias-Mismatch zeigt der Workflow jetzt:
- ❌ Welcher Alias angefordert wurde
- 📋 Welche Aliases im Keystore verfügbar sind  
- 💡 Wie das Problem behoben werden kann

#### 3. Dokumentation aktualisiert

Die `generate-keystore-secrets.yml` Workflow-Dokumentation wurde erweitert, um die Wichtigkeit des `KEY_ALIAS` Secrets zu betonen.

## Wie Sie das Problem beheben

### Option 1: KEY_ALIAS Secret korrigieren (empfohlen)

1. **Finden Sie den korrekten Alias:**
   - Führen Sie den `Android Build` Workflow aus
   - Schauen Sie in die Logs des "Decode Keystore" Steps
   - Der korrekte Alias wird unter "Available aliases in keystore:" angezeigt

2. **GitHub Secret aktualisieren:**
   - Gehen Sie zu: `Settings` → `Secrets and variables` → `Actions`
   - Bearbeiten Sie das `KEY_ALIAS` Secret oder erstellen Sie es, falls es nicht existiert
   - Setzen Sie den Wert auf den korrekten Alias aus dem Keystore

3. **Workflow erneut ausführen:**
   - Der Build sollte jetzt erfolgreich sein

### Option 2: Neuen Keystore generieren

Falls Sie den korrekten Alias nicht finden oder einen neuen Keystore generieren möchten:

1. **Workflow ausführen:**
   - Gehen Sie zu: `Actions` → `Generate Keystore and Secrets`
   - Klicken Sie auf "Run workflow"
   - Geben Sie die gewünschten Werte ein (inkl. `key_alias`)

2. **Secrets konfigurieren:**
   - Folgen Sie den Anweisungen im Workflow-Summary
   - Konfigurieren Sie alle 4 Secrets:
     - `KEYSTORE_BASE64`
     - `KEYSTORE_PASSWORD`
     - `KEY_ALIAS` (⚠️ MUSS mit dem gewählten Alias übereinstimmen!)
     - `KEY_PASSWORD`

3. **Workflow testen:**
   - Führen Sie den `Android Build` Workflow aus
   - Der Build sollte jetzt erfolgreich sein

## Überprüfung der Konfiguration

Nach der Konfiguration sollten alle 4 GitHub Secrets vorhanden sein:

```
Settings → Secrets and variables → Actions

✅ KEYSTORE_BASE64
✅ KEYSTORE_PASSWORD  
✅ KEY_ALIAS
✅ KEY_PASSWORD
```

## Weitere Hinweise

### Sicherheit

- ⚠️ Behandeln Sie Keystore und Passwörter vertraulich
- ⚠️ Keystore sollte NIE ins Git-Repository committet werden
- ✅ GitHub Secrets sind verschlüsselt und sicher gespeichert

### Lokale Entwicklung

Für lokale Release-Builds können Sie optional einen Keystore unter `keystore/release.jks` ablegen. Die `build.gradle.kts` erkennt diesen automatisch.

### Workflow-Übersicht

**android-build.yml**
- Läuft bei Push auf `main` und bei Pull Requests
- Baut automatisch Release-APK wenn Keystore konfiguriert ist
- Fällt auf Debug-APK zurück wenn kein Keystore vorhanden

**build-app.yml**  
- Läuft bei Änderungen an Android-Code
- Unterstützt manuelle Auswahl des Build-Typs (debug/release/both)
- Validiert Keystore vor Release-Builds

**generate-keystore-secrets.yml**
- Manueller Workflow zur Keystore-Generierung
- Erstellt Base64-kodierten Keystore für GitHub Secrets
- Zeigt detaillierte Setup-Anleitung

## Technische Details

### Keystore-Pfad-Auflösung

Die Workflows dekodieren den Keystore nach `./androidApp/keystore.jks` und setzen die Umgebungsvariable:

```yaml
env:
  KEYSTORE_FILE: keystore.jks
```

In der `androidApp/build.gradle.kts` wird dieser Pfad korrekt aufgelöst:

```kotlin
val envKeystoreFile = System.getenv("KEYSTORE_FILE")
val keystoreFile = when {
    envKeystoreFile != null -> file(envKeystoreFile)  // Resolves to androidApp/keystore.jks
    rootProject.file("keystore/release.jks").exists() -> rootProject.file("keystore/release.jks")
    else -> null
}
```

Die `file()` Funktion in Gradle löst Pfade relativ zum Projekt-Verzeichnis auf, wodurch `file("keystore.jks")` korrekt zu `androidApp/keystore.jks` wird.

### Alias-Validierung

Der Workflow verwendet `keytool` zur Validierung:

```bash
keytool -list -keystore ./androidApp/keystore.jks -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS"
```

- Exit Code 0 = Alias gefunden ✅
- Exit Code != 0 = Alias nicht gefunden ❌

## Fragen oder Probleme?

Falls Sie weiterhin Probleme haben:

1. Überprüfen Sie alle 4 GitHub Secrets
2. Schauen Sie in die Workflow-Logs nach der Fehlerursache
3. Regenerieren Sie ggf. den Keystore mit dem `generate-keystore-secrets` Workflow
4. Stellen Sie sicher, dass der `KEY_ALIAS` exakt mit dem generierten Alias übereinstimmt

---

**Aktualisiert:** 2026-01-19  
**Workflows betroffen:** `android-build.yml`, `build-app.yml`, `generate-keystore-secrets.yml`
