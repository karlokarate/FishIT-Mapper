# Devcontainer Konfiguration für FishIT-Mapper

Dieser Ordner enthält die Konfiguration für GitHub Codespaces und VS Code Dev Containers, optimiert für Android/Kotlin Multiplatform Development mit GitHub Copilot.

## 📁 Dateien

- **`devcontainer.json`**: Hauptkonfiguration für den Development Container
- **`setup.sh`**: Post-Create Setup-Script für automatische Initialisierung

## 🚀 Features

### Vorinstallierte Tools
- **Java 17**: OpenJDK für Kotlin/Android Development
- **Android SDK**: Vollständiges Android SDK mit Tools
- **Gradle 8.5**: Build-Tool für das Projekt
- **Git**: Version Control

### VS Code Extensions

#### GitHub Copilot
- `GitHub.copilot` - AI-powered Code-Completion
- `GitHub.copilot-chat` - Conversational AI Assistant

#### Kotlin/Android Development
- `fwcd.kotlin` - Kotlin Language Support
- `mathiasfrohlich.Kotlin` - Zusätzliche Kotlin Features
- `vscjava.vscode-gradle` - Gradle Integration

#### Produktivität
- `EditorConfig.EditorConfig` - Einheitliche Editor-Konfiguration
- `GitHub.vscode-pull-request-github` - PR Management
- `eamodio.gitlens` - Git Supercharge
- `christian-kohler.path-intellisense` - Path Auto-Completion
- `wayou.vscode-todo-highlight` - TODO Highlighting
- `Gruntfuggly.todo-tree` - TODO Explorer

#### Dokumentation
- `yzhang.markdown-all-in-one` - Markdown Tools
- `DavidAnson.vscode-markdownlint` - Markdown Linting

#### Code Quality
- `redhat.vscode-yaml` - YAML Support
- `ZainChen.json` - JSON Tools
- `esbenp.prettier-vscode` - Code Formatter

## ⚙️ Konfiguration

### Editor Settings
- **Auto-Save**: Aktiviert (1 Sekunde Delay)
- **Format on Save**: Aktiviert
- **Tab Size**: 4 Spaces
- **Line Ending**: LF
- **Trailing Whitespace**: Automatisch entfernt

### Port Forwarding
- **8080**: Backend Services
- **3000**: Web Services

### Volumes (Persistent)
- **Gradle Cache**: Beschleunigt Builds
- **Android SDK**: Verhindert wiederholte Downloads

## 🏗️ Container Setup

### System Requirements
- **CPU**: Mindestens 4 Cores
- **RAM**: Mindestens 8 GB
- **Storage**: Mindestens 32 GB

### Post-Create Setup
Das `setup.sh` Script führt automatisch aus:
1. ✅ Gradle Wrapper executable machen
2. ✅ Git Safe Directory konfigurieren
3. ✅ Android SDK Licenses akzeptieren
4. ✅ Projekt-Info anzeigen

## 📝 Verwendung

### GitHub Codespaces

1. **Codespace erstellen**:
   - Im GitHub Repository auf "Code" klicken
   - "Codespaces" Tab wählen
   - "Create codespace on main" klicken

2. **Warten auf Setup**:
   - Container wird erstellt (~2-3 Minuten)
   - Extensions werden installiert
   - Setup-Script läuft automatisch

3. **Development starten**:
   - Terminal öffnen
   - `./gradlew build` ausführen
   - Mit GitHub Copilot entwickeln

### VS Code Dev Containers

1. **Prerequisites**:
   - Docker Desktop installiert
   - VS Code mit "Dev Containers" Extension

2. **Container öffnen**:
   - Repository in VS Code öffnen
   - Command Palette: "Dev Containers: Reopen in Container"
   - Warten auf Container-Build

3. **Development starten**:
   - Alle Extensions sind bereits installiert
   - Terminal nutzen für Gradle-Befehle
   - GitHub Copilot nutzen

## 🔧 Anpassungen

### Eigene Extensions hinzufügen
In `devcontainer.json` unter `customizations.vscode.extensions`:
```json
"extensions": [
  "existing.extension",
  "your.new-extension"
]
```

### Port Forwarding ändern
In `devcontainer.json` unter `forwardPorts`:
```json
"forwardPorts": [8080, 3000, 5000]
```

### Setup-Script erweitern
`setup.sh` bearbeiten und eigene Befehle hinzufügen:
```bash
echo "🔧 Custom Setup Step..."
# Your commands here
```

## 🛠️ Nützliche Befehle

### Gradle
```bash
# Build Project
./gradlew build

# Run Tests
./gradlew test

# Generate Contract
./gradlew :shared:contract:generateFishitContract

# Build Android APK
./gradlew :androidApp:assembleDebug

# Clean Build
./gradlew clean build

# Check Dependencies
./gradlew dependencies

# Dependency Updates
./gradlew dependencyUpdates
```

### Git
```bash
# Status
git status

# Add All
git add .

# Commit
git commit -m "Your message"

# Push
git push

# Create Branch
git checkout -b feature/your-feature

# Pull Latest
git pull origin main
```

### Android SDK
```bash
# List Installed Packages
sdkmanager --list_installed

# Update SDK
sdkmanager --update

# Install Package
sdkmanager "platform-tools"
```

## 🐛 Troubleshooting

### Container startet nicht
1. Docker Desktop läuft?
2. Genug Ressourcen verfügbar?
3. `.devcontainer/devcontainer.json` Syntax korrekt?
4. Logs in VS Code Output prüfen

### Gradle Build schlägt fehl
1. `./gradlew clean` ausführen
2. Gradle Cache löschen: `rm -rf ~/.gradle/caches`
3. Container neu erstellen

### Extensions fehlen
1. Command Palette: "Developer: Reload Window"
2. Extensions manuell installieren
3. Container neu erstellen

### Android SDK Probleme
1. Licenses akzeptieren: `yes | sdkmanager --licenses`
2. SDK aktualisieren: `sdkmanager --update`
3. `ANDROID_SDK_ROOT` Environment Variable prüfen

## 🔄 Container Updates

### Extension Updates
Extensions werden automatisch aktualisiert, wenn:
- Container neu erstellt wird
- VS Code Extension Auto-Update aktiviert ist

### Base Image Update
1. `devcontainer.json` bearbeiten
2. Image Version ändern
3. Container neu erstellen

### Setup Script ändern
1. `setup.sh` bearbeiten
2. Container neu erstellen (oder Script manuell ausführen)

## 📚 Weitere Ressourcen

- [Dev Containers Documentation](https://containers.dev/)
- [GitHub Codespaces Docs](https://docs.github.com/codespaces)
- [VS Code Remote Development](https://code.visualstudio.com/docs/remote/remote-overview)
- [Docker Documentation](https://docs.docker.com/)

## 🤝 Best Practices

1. **Regelmäßig neu erstellen**: Container gelegentlich neu erstellen für Updates
2. **Volumes nutzen**: Gradle Cache und SDK als Volumes für Performance
3. **Extensions minimal halten**: Nur benötigte Extensions installieren
4. **Ressourcen überwachen**: CPU und RAM Usage im Auge behalten
5. **Setup automatisieren**: Alles was wiederholt wird, ins Setup-Script

---

**Hinweis**: Diese Konfiguration ist optimiert für FishIT-Mapper. Anpassungen für andere Projekte möglich.
