# GitHub Konfiguration für FishIT-Mapper

Dieser Ordner enthält alle GitHub-spezifischen Konfigurationen für optimale Nutzung von GitHub Copilot, Codespaces und automatisierten Workflows.

## 📁 Struktur

```
.github/
├── copilot/
│   ├── agents.json          # Agent-Berechtigungen und -Konfiguration
│   └── mcp.json            # MCP Server Einstellungen für Long Context
├── workflows/
│   └── copilot-permissions.yml  # Workflow-Permissions für Copilot
├── copilot-instructions.md # Repository-weite Copilot-Anweisungen
└── settings.yml           # Repository-Settings (für probot/settings)
```

## 🤖 Copilot Konfiguration

### `copilot-instructions.md`
Enthält repository-weite Anweisungen für GitHub Copilot:
- **Sprachpräferenz**: Deutsch für Erklärungen
- **Long Context**: Immer vollen Kontext nutzen
- **Tool-Empfehlungen**: Automatische Vorschläge für jeden Task
- **Proaktive Unterstützung**: Bei Code-Reviews, Debugging, Testing, Documentation

### `copilot/agents.json`
Definiert Agent-Berechtigungen und -Fähigkeiten:
- ✅ Pull Requests erstellen und mergen
- ✅ Code schreiben und committen
- ✅ Branches erstellen/löschen
- ✅ Issues erstellen/bearbeiten
- ✅ Workflows ausführen
- ❌ Repository NICHT löschen (Sicherheitseinschränkung)

**Verfügbare Agents:**
- `copilot-workspace`: Hauptagent mit vollen Berechtigungen
- `copilot-code-review`: Spezialisiert auf Code-Reviews
- `copilot-test-generator`: Automatische Test-Generierung
- `copilot-documentation`: Dokumentations-Updates

### `copilot/mcp.json`
MCP (Model Context Protocol) Server Konfiguration:
- **Long Context**: Bis zu 128.000 Tokens
- **Intelligentes Chunking**: Semantik-bewusste Kontextaufteilung
- **Multiple Server**: GitHub, Filesystem, Gradle, Kotlin, Android
- **Erweiterte Features**:
  - Code Intelligence (Go to Definition, Find References, etc.)
  - Refactoring-Unterstützung
  - Code-Generierung
  - Kontinuierliche Analyse

## 🔧 Workflows

### `workflows/copilot-permissions.yml`
Definiert Berechtigungen für GitHub Actions Workflows:
- `contents: write` - Code-Änderungen
- `pull-requests: write` - PR-Management
- `issues: write` - Issue-Management
- `actions: write` - Workflow-Management
- `checks: write` - Status-Checks
- `statuses: write` - Commit-Status

**Jobs:**
1. **copilot-code-changes**: Build und Tests durchführen
2. **copilot-code-review**: Automatische Code-Reviews
3. **copilot-security-check**: Dependency- und Security-Checks
4. **copilot-docs-update**: Dokumentations-Updates
5. **copilot-workflow-summary**: Zusammenfassung aller Checks

## ⚙️ Repository Settings

### `settings.yml`
Konfiguration für die [probot/settings](https://github.com/probot/settings) App:
- **Branch Protection**: Konfiguration für `main` Branch
- **Labels**: Vordefinierte Labels für Issues und PRs
- **Autolinks**: Automatische Verlinkung von Issue-Referenzen
- **Merge Settings**: Squash, Merge, Rebase erlaubt
- **Auto-Delete**: Branches nach Merge automatisch löschen

## 🚀 Verwendung

### Copilot aktivieren
1. GitHub Copilot für das Repository aktivieren
2. Copilot Chat in VS Code oder GitHub.com verwenden
3. Copilot Workspace für komplexe Tasks nutzen

### Codespaces verwenden
1. Codespace erstellen via GitHub UI
2. Alle Extensions werden automatisch installiert
3. Setup-Script läuft automatisch nach Container-Erstellung

### Workflows triggern
Workflows werden automatisch ausgelöst bei:
- Pull Request erstellen/aktualisieren
- Push zu `main` oder `develop`
- Manuell via `workflow_dispatch`

## 📚 Best Practices

### Pull Requests
- Aussagekräftige Titel verwenden
- Beschreibung mit Problem, Lösung, Testing
- Automatische Checks abwarten
- Copilot-Reviews berücksichtigen

### Branch-Naming
- `feature/` - Neue Features
- `bugfix/` - Bug-Fixes
- `refactor/` - Refactorings
- `docs/` - Dokumentations-Änderungen
- `copilot/` - Copilot-generierte Änderungen

### Commit Messages
Conventional Commits Format verwenden:
```
<type>(<scope>): <subject>

<body>

<footer>
```

**Typen:**
- `feat`: Neue Features
- `fix`: Bug-Fixes
- `docs`: Dokumentation
- `refactor`: Code-Refactoring
- `test`: Tests
- `chore`: Maintenance

## 🔒 Sicherheit

### Erlaubte Aktionen
- ✅ Code lesen und schreiben
- ✅ Pull Requests erstellen und mergen
- ✅ Issues erstellen und bearbeiten
- ✅ Branches erstellen und löschen
- ✅ Workflows ausführen
- ✅ Releases erstellen

### Verbotene Aktionen
- ❌ Repository löschen
- ❌ Repository transferieren
- ❌ Sichtbarkeit ändern
- ❌ Git History ändern (Force Push)
- ❌ Admin-Einstellungen ändern

## 🛠️ Troubleshooting

### Copilot funktioniert nicht
1. Copilot-Subscription prüfen
2. Repository-Zugriff prüfen
3. `.github/copilot-instructions.md` validieren
4. MCP Server Status prüfen

### Workflow-Fehler
1. Permissions in Workflow-Datei prüfen
2. GitHub Actions Logs ansehen
3. Secrets und Tokens validieren

### Codespace-Probleme
1. `.devcontainer/devcontainer.json` validieren
2. Container neu erstellen
3. Extensions manuell installieren falls nötig

## 📖 Weiterführende Dokumentation

- [GitHub Copilot Docs](https://docs.github.com/copilot)
- [GitHub Actions Docs](https://docs.github.com/actions)
- [Devcontainer Docs](https://containers.dev/)
- [Probot Settings](https://github.com/probot/settings)
- [MCP Protocol](https://modelcontextprotocol.io/)

## 🤝 Beitragen

Verbesserungen an der GitHub-Konfiguration sind willkommen:
1. Fork erstellen
2. Feature-Branch erstellen
3. Änderungen committen
4. Pull Request erstellen
5. Copilot-Review abwarten

---

**Letzte Aktualisierung**: 2026-01-14
**Maintainer**: FishIT-Mapper Team
