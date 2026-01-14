# FishIT-Mapper Scripts für ChatGPT Codex Browser

Diese Scripts ermöglichen es, FishIT-Mapper vollständig im ChatGPT Codex Browser aufzusetzen und zu warten.

## 📋 Übersicht

### `quick-start.sh` (Empfohlen für Einsteiger)
Interaktiver Guide der dich durch den Setup-Prozess führt:
- 🎯 Erkennt automatisch den aktuellen Status
- 🎯 Bietet passende Optionen basierend auf der Umgebung
- 🎯 Führt Validierung und Setup interaktiv aus
- 🎯 Zeigt verfügbare Befehle an

### `codex-setup.sh`
Vollständiges Setup-Script für die erste Einrichtung:
- ✅ Prüft Java Version (JDK 17+ erforderlich)
- ✅ Installiert Android SDK automatisch
- ✅ Akzeptiert SDK-Lizenzen
- ✅ Lädt benötigte SDK-Komponenten herunter
- ✅ Generiert FishIT Contract Code
- ✅ Baut Android App (Debug APK)

### `maintenance.sh`
Schnelles Maintenance-Script für regelmäßige Wartung:
- ✅ Bereinigt Gradle Cache
- ✅ Regeneriert Contract Code
- ✅ Führt Compile-Check durch
- ⚠️ Kein vollständiger APK Build (schneller)

### `validate-env.sh`
Umgebungs-Validierung (kein Setup, nur Check):
- ✅ Prüft Java Installation und Version
- ✅ Prüft Gradle Wrapper
- ✅ Prüft Android SDK Komponenten
- ✅ Prüft System-Tools und Ressourcen
- ✅ Gibt Empfehlungen für nächste Schritte

## 🚀 Verwendung

### Schnellstart (Empfohlen)

```bash
# Interaktiver Guide - am einfachsten!
./scripts/quick-start.sh
```

Der interaktive Guide erkennt automatisch deinen aktuellen Status und bietet die passenden Optionen an.

### Umgebung prüfen (empfohlen vor dem ersten Setup)

```bash
# Prüft ob alle Voraussetzungen erfüllt sind
./scripts/validate-env.sh
```

Dies gibt einen detaillierten Bericht über:
- Java Version und JAVA_HOME
- Gradle Installation
- Android SDK Status
- Verfügbare System-Tools
- Disk Space und Memory
- Projekt-Struktur

### Erste Einrichtung

```bash
# Im Repository-Root ausführen
./scripts/codex-setup.sh
```

**Was passiert:**
1. Java Version wird geprüft (min. JDK 17)
2. Android SDK wird nach `/opt/android-sdk` installiert
3. Platform-tools, Android 34 Platform und Build-tools 34.0.0 werden heruntergeladen
4. Gradle Dependencies werden aufgelöst
5. Contract Code wird aus `schema/contract.schema.json` generiert
6. Android App wird kompiliert und als Debug APK gebaut

**Dauer:** 5-10 Minuten (beim ersten Mal, abhängig von Internetgeschwindigkeit)

### Regelmäßige Wartung

```bash
# Nach Code-Änderungen oder Schema-Updates
./scripts/maintenance.sh
```

**Was passiert:**
1. Gradle Cache wird bereinigt
2. Build Outputs werden gelöscht
3. Contract Code wird neu generiert
4. Kotlin Compilation Check wird durchgeführt

**Dauer:** 1-3 Minuten

## 🔧 Voraussetzungen

### Minimale Anforderungen

- **Betriebssystem:** Linux (Ubuntu/Debian empfohlen)
- **Java:** JDK 17 oder höher (JDK 21 empfohlen)
- **RAM:** Mindestens 4 GB (8 GB empfohlen)
- **Festplatte:** 5 GB freier Speicher für Android SDK
- **Internet:** Stabile Verbindung für Downloads

### Konfigurierbare Optionen

**Android SDK Location:**
Standardmäßig wird das SDK nach `/opt/android-sdk` installiert. Um einen anderen Pfad zu verwenden:

```bash
export ANDROID_SDK_ROOT=/path/to/your/sdk
./scripts/codex-setup.sh
```

Dies ist nützlich wenn `/opt` nicht beschreibbar ist oder ein anderer Speicherort bevorzugt wird.

### Im Codex Browser

Der ChatGPT Codex Browser sollte Folgendes bereitstellen:
- Linux Container (Ubuntu/Debian)
- Bash Shell
- wget und unzip (oder apt-get um sie zu installieren)
- Schreibzugriff auf `/opt/android-sdk` (oder sudo-Rechte)

## 📂 Verzeichnisstruktur

Nach dem Setup:

```
/opt/android-sdk/
├── cmdline-tools/
│   └── latest/
│       └── bin/sdkmanager
├── platform-tools/
│   └── adb
├── platforms/
│   └── android-34/
└── build-tools/
    └── 34.0.0/
```

## 🐛 Troubleshooting

### Problem: "Java 17 or higher is required"

**Lösung:**
```bash
# Check Java version
java -version

# Install JDK 17 if needed (Ubuntu/Debian)
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### Problem: "ANDROID_SDK_ROOT not set"

**Lösung:**
```bash
# Manually set environment variable
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export PATH=$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH

# Then run setup again
./scripts/codex-setup.sh
```

### Problem: "Permission denied" beim SDK-Download

**Lösung:**
```bash
# Create directory with proper permissions
sudo mkdir -p /opt/android-sdk
sudo chown -R $(whoami) /opt/android-sdk

# Then run setup again
./scripts/codex-setup.sh
```

### Problem: "Failed to download Android Command Line Tools"

**Lösung:**
- Überprüfe Internetverbindung
- Versuche manuellen Download:
  ```bash
  wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  ```
- Falls Download-URL nicht erreichbar ist, prüfe ob Google die URL geändert hat

### Problem: Build schlägt mit Gradle-Fehlern fehl

**Lösung:**
```bash
# Complete clean and retry
./gradlew clean
rm -rf .gradle
./scripts/codex-setup.sh
```

### Problem: "OutOfMemoryError" während des Builds

**Lösung:**
Erhöhe Gradle Memory in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8
```

## 🔐 Umgebungsvariablen

Die Scripts setzen folgende Umgebungsvariablen:

```bash
ANDROID_SDK_ROOT=/opt/android-sdk
ANDROID_HOME=/opt/android-sdk
PATH=$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH
```

Um diese dauerhaft zu setzen, füge sie zu `~/.bashrc` oder `~/.profile` hinzu.

## 📦 Was wird heruntergeladen?

### Android Command Line Tools (~150 MB)
- URL: https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
- Enthält: sdkmanager, avdmanager, apkanalyzer

### SDK-Komponenten (~1-2 GB)
- **platform-tools**: adb, fastboot (~50 MB)
- **platforms;android-34**: Android 14 SDK Platform (~100 MB)
- **build-tools;34.0.0**: Build-Tools für APK-Erstellung (~100 MB)

### Gradle Dependencies (~500 MB)
- Kotlin Compiler und Libraries
- Android Gradle Plugin
- AndroidX Libraries
- Compose Libraries
- KotlinX Serialization

**Gesamt:** ~2-3 GB

## 🧪 Manuelle Tests

Nach dem Setup können folgende Befehle zum Testen ausgeführt werden:

```bash
# Umgebung validieren
./scripts/validate-env.sh

# Gradle Version anzeigen
./gradlew --version

# SDK Manager testen
sdkmanager --list_installed

# ADB Version prüfen
adb --version

# Contract generieren (isoliert)
./gradlew :shared:contract:generateFishitContract

# Nur kompilieren (schnell)
./gradlew :androidApp:compileDebugKotlin

# Tests ausführen
./gradlew test

# Vollständiger Build
./gradlew build

# APK bauen
./gradlew :androidApp:assembleDebug
```

## 📖 Weitere Befehle

```bash
# Dependency Updates prüfen
./gradlew dependencyUpdates

# Alle Tasks anzeigen
./gradlew tasks --all

# Build mit Stack Trace (bei Fehlern)
./gradlew build --stacktrace

# Build mit Debug-Output
./gradlew build --debug

# Gradle Cache komplett löschen
./gradlew clean cleanBuildCache
rm -rf ~/.gradle/caches/

# APK auf Device installieren (benötigt angeschlossenes Gerät)
./gradlew :androidApp:installDebug
```

## 🔄 Workflow-Empfehlung

### Initial Setup (einmalig)
```bash
git clone <repository>
cd FishIT-Mapper
./scripts/codex-setup.sh
```

### Bei Schema-Änderungen
```bash
# 1. Schema bearbeiten
vim schema/contract.schema.json

# 2. Maintenance durchführen
./scripts/maintenance.sh

# 3. Tests laufen lassen
./gradlew test
```

### Bei Code-Änderungen
```bash
# 1. Code bearbeiten
vim androidApp/src/main/...

# 2. Compile Check
./gradlew :androidApp:compileDebugKotlin

# 3. Tests
./gradlew :androidApp:testDebugUnitTest

# 4. APK bauen
./gradlew :androidApp:assembleDebug
```

## 🎯 Optimierungen für Codex Browser

Die Scripts enthalten folgende Optimierungen für den Codex Browser:

1. **Robuste Fehlerbehandlung**: `set -euo pipefail` für sichere Ausführung
2. **Farbige Ausgaben**: Bessere Lesbarkeit der Logs
3. **Automatische Installations**: Fehlende Tools (wget, unzip) werden automatisch installiert
4. **Flexible SDK-Location**: ANDROID_SDK_ROOT kann vorkonfiguriert oder automatisch gesetzt werden
5. **Progress-Feedback**: Klare Status-Meldungen bei jedem Schritt
6. **Cleanup bei Fehlern**: Temporäre Dateien werden aufgeräumt
7. **Validierung**: Jeder Schritt wird validiert bevor fortgefahren wird

## 📝 Hinweise

- Die Scripts sind **idempotent** - mehrfaches Ausführen ist sicher
- Bei Problemen können Scripts einfach neu gestartet werden
- Bereits heruntergeladene Komponenten werden übersprungen
- SDK-Lizenz muss nur einmal akzeptiert werden
- Gradle-Cache wird bei Maintenance bewusst bereinigt für saubere Builds

## 📞 Support

Bei Problemen:
1. Logs der Scripts prüfen (farbige Ausgaben helfen bei der Diagnose)
2. Troubleshooting-Sektion in dieser Datei konsultieren
3. GitHub Issue erstellen mit vollständiger Log-Ausgabe
4. COPILOT_SETUP.md für allgemeine Entwicklungs-Setup-Infos lesen

---

**Erstellt für:** ChatGPT Codex Browser  
**Version:** 1.0.0  
**Datum:** 2026-01-14  
**Kompatibel mit:** FishIT-Mapper Android (AGP 8.2.2, Kotlin 1.9.22)
