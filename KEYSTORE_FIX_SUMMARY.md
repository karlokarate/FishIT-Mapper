# Android Build Workflows - Keystore Fix Summary

## Änderungen vom 2026-01-19

### Problem
Android Build Workflows schlugen mit folgendem Fehler fehl:
```
KeytoolException: No key with alias '***' found in keystore
```

**Ursache:** Der `KEY_ALIAS` GitHub Secret stimmte nicht mit dem Alias im Keystore überein.

### Lösung

#### 1. Validation hinzugefügt
Beide Build-Workflows (`android-build.yml` und `build-app.yml`) validieren jetzt den Keystore **vor** dem Build:
- ✅ Prüft ob KEYSTORE_PASSWORD korrekt ist
- ✅ Prüft ob KEY_ALIAS im Keystore existiert
- ✅ Zeigt verfügbare Aliases bei Fehlern an
- ✅ Gibt klare Anweisungen zur Fehlerbehebung

#### 2. Workflow-Updates

**android-build.yml**
- Neue Umgebungsvariablen für Validation (KEYSTORE_PASSWORD, KEY_ALIAS)
- Keystore-Validation Step mit detailliertem Logging
- Graceful Fallback zu Debug-Build wenn Validation fehlschlägt

**build-app.yml**
- Identische Validation wie android-build.yml
- Funktioniert mit allen Build-Typen (debug/release/both)

**generate-keystore-secrets.yml**
- Erweiterte Dokumentation
- Betonung der Wichtigkeit von KEY_ALIAS
- Warnhinweise bei Secret-Konfiguration

#### 3. Dokumentation

**KEYSTORE_FIX_GUIDE.md**
- Umfassende Anleitung zur Fehlerbehebung
- Schritt-für-Schritt Lösungen
- Technische Details zur Pfad-Auflösung
- Sicherheitshinweise

### Wie funktioniert die Validation?

```bash
# 1. Keystore dekodieren
echo "$KEYSTORE_BASE64" | base64 -d > ./androidApp/keystore.jks

# 2. Keystore-Inhalt prüfen
keytool -list -keystore ./androidApp/keystore.jks -storepass "$KEYSTORE_PASSWORD"

# 3. Alias validieren
keytool -list -keystore ./androidApp/keystore.jks \
  -storepass "$KEYSTORE_PASSWORD" \
  -alias "$KEY_ALIAS"

# Exit Code 0 = ✅ Alias gefunden
# Exit Code != 0 = ❌ Alias nicht gefunden → Zeige verfügbare Aliases
```

### Benötigte GitHub Secrets

Für Release-Builds müssen alle 4 Secrets korrekt konfiguriert sein:

1. **KEYSTORE_BASE64** - Base64-kodierter Keystore (aus generate-keystore-secrets Workflow)
2. **KEYSTORE_PASSWORD** - Keystore-Passwort
3. **KEY_ALIAS** - Alias des Signing Keys (MUSS mit Keystore übereinstimmen!)
4. **KEY_PASSWORD** - Passwort des Signing Keys

### Was passiert bei Fehlern?

**Vorher (alte Workflows):**
```
❌ Build failed immediately with cryptic error
No useful debugging information
```

**Nachher (neue Workflows):**
```
🔍 Validating keystore...
❌ ERROR: Key alias 'my-wrong-alias' NOT found in keystore!

Available aliases in keystore:
- fishit-mapper

💡 FIX: Set the KEY_ALIAS secret to match one of the aliases above
   or regenerate the keystore with the correct alias.

ℹ️  Building debug APK as fallback...
```

### Vorteile

1. **Frühe Fehlererkennung** - Fehler werden vor dem Build erkannt
2. **Klare Fehlermeldungen** - Entwickler sehen sofort was falsch ist
3. **Automatische Lösung** - Workflow zeigt die richtigen Werte an
4. **Graceful Degradation** - Fallback zu Debug-Build wenn keine Signierung möglich
5. **Besseres Debugging** - Vollständige Keystore-Information in Logs

### Testing

- ✅ YAML-Syntax validiert
- ✅ Keytool-Validation getestet
- ✅ Error-Handling verifiziert
- ✅ Robustheit über verschiedene keytool-Versionen sichergestellt

### Nächste Schritte für Entwickler

1. **Wenn KEY_ALIAS fehlt:**
   - Workflow ausführen → Verfügbare Aliases werden angezeigt
   - `KEY_ALIAS` Secret mit korrektem Wert erstellen

2. **Wenn KEY_ALIAS falsch ist:**
   - Workflow-Logs checken
   - `KEY_ALIAS` Secret aktualisieren ODER
   - Neuen Keystore mit `generate-keystore-secrets` generieren

3. **Bei weiteren Problemen:**
   - `KEYSTORE_FIX_GUIDE.md` lesen
   - Workflow-Logs analysieren
   - Alle 4 Secrets überprüfen

### Technische Details

**Keystore-Pfad:** 
- Dekodiert nach: `./androidApp/keystore.jks` (von Repo-Root)
- In Gradle aufgelöst als: `androidApp/keystore.jks` (relativ zum Projekt)
- ✅ Pfad-Auflösung funktioniert korrekt

**Fallback-Strategie:**
1. Primär: Release-Build mit Signierung (wenn Keystore valid)
2. Fallback: Debug-Build ohne Signierung (bei Validation-Fehler)

**Exit Codes:**
- `exit 0` bei Validation-Fehler → Workflow fortsetzten mit Debug-Build
- Build-Fehler → Workflow scheitert mit aussagekräftiger Fehlermeldung

---

**Betroffene Dateien:**
- `.github/workflows/android-build.yml` (+34 Zeilen)
- `.github/workflows/build-app.yml` (+34 Zeilen)
- `.github/workflows/generate-keystore-secrets.yml` (+4 Zeilen)
- `KEYSTORE_FIX_GUIDE.md` (neu, 182 Zeilen)

**Status:** ✅ Bereit für Merge
