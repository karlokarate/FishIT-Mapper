# Build Workflow Anleitung

## Überblick

Der GitHub Actions Workflow "Build Android App" ermöglicht das manuelle Bauen der FishIT-Mapper Android App in verschiedenen Varianten.

## Workflow Starten

### Via GitHub UI

1. Navigiere zu **Actions** im GitHub Repository
2. Wähle den Workflow **"Build Android App"** aus der Liste
3. Klicke auf **"Run workflow"**
4. Wähle den gewünschten **Build Type**:
   - **debug**: Erzeugt einen Debug-Build (APK)
   - **unsigned-release**: Erzeugt einen unsignierten Release-Build (APK + AAB)
5. Klicke auf **"Run workflow"** zum Starten

### Via GitHub CLI

```bash
# Debug Build
gh workflow run build-app.yml -f build_type=debug

# Unsigned Release Build
gh workflow run build-app.yml -f build_type=unsigned-release
```

## Build-Typen

### Debug Build
- **Gradle Task**: `:androidApp:assembleDebug`
- **Output**: Debug APK
- **Verwendung**: Für Entwicklung und Testing
- **Signierung**: Debug-Signatur (automatisch)
- **Artefakt**: `app-debug` (enthält die Debug-APK)

### Unsigned Release Build
- **Gradle Tasks**: 
  - `:androidApp:assembleRelease` (APK)
  - `:androidApp:bundleRelease` (AAB)
- **Output**: Release APK + Release Bundle (AAB)
- **Verwendung**: Für Produktion (muss manuell signiert werden)
- **Signierung**: Keine (unsigned)
- **Artefakte**: 
  - `app-unsigned-release-apk` (enthält die unsigned APK)
  - `app-unsigned-release-bundle` (enthält das unsigned AAB)

## Artefakte Herunterladen

Nach erfolgreichem Build-Durchlauf:

1. Öffne den Workflow-Run auf der Actions-Seite
2. Scrolle zum Abschnitt **"Artifacts"**
3. Klicke auf das gewünschte Artefakt zum Herunterladen
4. Die heruntergeladene ZIP-Datei enthält die APK/AAB-Datei

## Build-Konfiguration

Der Workflow verwendet:
- **Runner**: ubuntu-latest
- **JDK**: 17 (Temurin Distribution)
- **Gradle**: Version aus `gradle/wrapper/gradle-wrapper.properties`
- **Cache**: Gradle Dependencies werden gecached für schnellere Builds
- **Retention**: Artefakte werden 30 Tage aufbewahrt

## Fehlerbehandlung

Bei Build-Fehlern:
1. Überprüfe die Workflow-Logs in der Actions-Übersicht
2. Der `--stacktrace` Parameter liefert detaillierte Fehlerinformationen
3. Stelle sicher, dass alle Dependencies korrekt konfiguriert sind

## Lokaler Build

Zum lokalen Testen der Build-Commands:

```bash
# Debug Build
./gradlew :androidApp:assembleDebug

# Release Build
./gradlew :androidApp:assembleRelease
./gradlew :androidApp:bundleRelease
```

Die Outputs befinden sich in:
- **APK**: `androidApp/build/outputs/apk/{debug|release}/`
- **AAB**: `androidApp/build/outputs/bundle/release/`

## Hinweise

- **Unsigned Release**: Die unsigned Release-Builds müssen vor der Veröffentlichung im Play Store signiert werden
- **Debug**: Debug-Builds sind bereits mit einem Debug-Key signiert und können direkt installiert werden
- **Signing**: Für signierte Release-Builds muss ein separater Signing-Workflow erstellt werden
