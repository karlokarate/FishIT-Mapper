# GitHub Konfiguration für FishIT-Mapper

Dieser Ordner enthält alle GitHub-spezifischen Konfigurationen für optimale Nutzung von GitHub Copilot, Codespaces und automatisierten Workflows.

## 📁 Struktur

```
.github/
├── copilot/
│   ├── agents.json          # Agent-Berechtigungen und -Konfiguration
│   └── mcp.json            # MCP Server Einstellungen für Long Context
├── workflows/
│   ├── copilot-permissions.yml   # Workflow-Permissions für Copilot
│   ├── codex-agent.yml          # OpenAI Codex Agent (@codex)
│   ├── copilot-agent.yml        # GitHub Copilot Agent (@copilot)
│   ├── auto-review-request.yml  # Automatische Review-Anfragen
│   ├── agent-pr-ready.yml       # PR Ready nach Agent-Arbeit
│   └── prepare-fix-task.yml     # Fix-Tasks nach Reviews
├── copilot-instructions.md # Repository-weite Copilot-Anweisungen
├── CODEOWNERS             # Automatische Review-Requests
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
- `codex-agent`: OpenAI Codex Agent mit GPT-4 Integration

### `copilot/mcp.json`
MCP (Model Context Protocol) Server Konfiguration:
- **Long Context**: Bis zu 128.000 Tokens
- **Intelligentes Chunking**: Semantik-bewusste Kontextaufteilung
- **Multiple Server**: GitHub, Filesystem, Gradle, Kotlin, Android, Codex
- **Erweiterte Features**:
  - Code Intelligence (Go to Definition, Find References, etc.)
  - Refactoring-Unterstützung
  - Code-Generierung
  - Kontinuierliche Analyse
  - OpenAI Codex Integration

## 🔧 Workflows

### Agent-Workflows (Neu! 🎉)

#### `workflows/codex-agent.yml` - OpenAI Codex Agent
Reagiert auf `@codex` Mentions in PR-Kommentaren:
- **Review**: `@codex review` - Detailliertes Code-Review mit GPT-4
- **Fix**: `@codex fix` - Intelligente Bug-Fix-Vorschläge
- **Explain**: `@codex explain` - Tiefgehende Code-Erklärungen
- **Test**: `@codex test` - Automatische Test-Generierung

#### `workflows/copilot-agent.yml` - GitHub Copilot Agent
Reagiert auf `@copilot` Mentions in PR-Kommentaren:
- **Review**: `@copilot review` - Code-Review mit Build-Checks
- **Fix**: `@copilot fix` - Automatische Code-Fixes
- **Explain**: `@copilot explain` - Code-Änderungs-Erklärung
- **Test**: `@copilot test` - Test-Vorschläge

#### `workflows/auto-review-request.yml` - Automatische Review-Anfragen
- Wird automatisch bei PR-Erstellung ausgelöst
- Sendet Review-Anfragen an @copilot und @codex
- Fügt Labels hinzu: `review:requested`
- Erstellt PR-Zusammenfassung

#### `workflows/agent-pr-ready.yml` - PR Ready Status
- Wird nach erfolgreicher Agent-Arbeit ausgelöst
- Setzt Draft-PRs auf "Ready for Review"
- Aktualisiert Status-Checks und Labels

#### `workflows/prepare-fix-task.yml` - Fix-Task Vorbereitung
- Wird nach jedem Review ausgelöst
- Sammelt alle Review-Findings
- Erstellt strukturierte Fix-Tasks mit Aktions-Buttons
- Fügt Label `fix-needed` hinzu

**📚 Weitere Details:** Siehe [AGENT_SETUP.md](../AGENT_SETUP.md) und [AGENT_QUICK_REFERENCE.md](../AGENT_QUICK_REFERENCE.md)

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

### Repository Settings

### `CODEOWNERS`
Definiert automatische Review-Requests:
- `@copilot` als Standard-Reviewer für alle Dateien
- `@codex` für GitHub Workflows und Schema-Dateien
- Automatische Review-Anfragen bei PR-Erstellung

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
- `@copilot` oder `@codex` Mention in Kommentaren
- Nach Reviews (für Fix-Task Vorbereitung)
- Manuell via `workflow_dispatch`

### Agents verwenden
In jedem PR-Kommentar können folgende Kommandos verwendet werden:
```
@copilot review    # Code-Review durchführen
@copilot fix       # Automatische Fixes anwenden
@codex explain     # Code erklären
@codex test        # Tests generieren
```

Siehe [AGENT_QUICK_REFERENCE.md](../AGENT_QUICK_REFERENCE.md) für alle Kommandos.

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
