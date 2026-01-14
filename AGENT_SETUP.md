# GitHub Agents Integration - Setup und Verwendung

## 📋 Übersicht

Diese PR integriert vollständig **OpenAI Codex** und **GitHub Copilot** als automatisierte Agenten mit umfassenden Berechtigungen für Code-Reviews, Fixes und PR-Management.

## ✅ Was wurde implementiert

### 1. Workflows

#### 🤖 Codex Agent (`.github/workflows/codex-agent.yml`)
Reagiert auf `@codex` Mentions in PR-Kommentaren:
- **Review**: `@codex review` - Detailliertes Code-Review mit OpenAI GPT-4
- **Fix**: `@codex fix` - Intelligente Bug-Fix-Vorschläge
- **Explain**: `@codex explain` - Tiefgehende Code-Erklärungen
- **Test**: `@codex test` - Automatische Test-Generierung

#### 🤖 Copilot Agent (`.github/workflows/copilot-agent.yml`)
Reagiert auf `@copilot` Mentions in PR-Kommentaren:
- **Review**: `@copilot review` - Code-Review mit Build-Checks
- **Fix**: `@copilot fix` - Automatische Code-Fixes (ktlint, etc.)
- **Explain**: `@copilot explain` - Code-Änderungs-Erklärung
- **Test**: `@copilot test` - Test-Vorschläge und Best Practices

#### 🔄 Auto Review Request (`.github/workflows/auto-review-request.yml`)
- Wird automatisch ausgelöst wenn PR geöffnet oder auf "Ready" gesetzt wird
- Sendet automatisch Review-Anfragen an beide Agenten
- Fügt Label `review:requested` hinzu
- Erstellt PR-Zusammenfassung

#### ✅ Agent PR Ready (`.github/workflows/agent-pr-ready.yml`)
- Wird nach erfolgreicher Agent-Arbeit ausgelöst
- Setzt Draft-PRs automatisch auf "Ready for Review"
- Aktualisiert Status-Checks und Labels

#### 🔧 Prepare Fix Task (`.github/workflows/prepare-fix-task.yml`)
- Wird nach jedem Review ausgelöst
- Sammelt alle Review-Findings
- Erstellt strukturierte Fix-Task mit Aktions-Buttons
- Fügt Label `fix-needed` hinzu

### 2. Konfigurationsdateien

#### `.github/copilot/agents.json`
- **Codex-Agent** Definition hinzugefügt mit:
  - Vollen Repository-Permissions
  - OpenAI API Integration
  - Build- und Test-Ausführungs-Rechte
  - MCP-Server Zugriff

#### `.github/copilot/mcp.json`
- **Codex-Server** Konfiguration mit:
  - OpenAI API Endpoint
  - Model-Settings (GPT-4)
  - Features und Capabilities
  - GitHub Actions Integration

#### `.github/workflows/copilot-permissions.yml`
- Erweitert um `issue_comment` Trigger
- Ermöglicht Agent-Reaktion auf Kommentare

#### `.github/CODEOWNERS`
- Automatische Review-Requests für alle Dateien
- `@copilot` als Standard-Reviewer
- `@codex` für Workflows und Schema

## 🚀 Verwendung

### Automatische Nutzung

1. **PR öffnen**: Agents werden automatisch benachrichtigt
2. **Review abwarten**: Agents führen automatisch Review durch
3. **Fixes anwenden**: Nach Review wird Fix-Task vorbereitet

### Manuelle Agent-Aktivierung

In jedem PR-Kommentar:

```
@copilot review
```
oder
```
@codex fix
```

**Verfügbare Kommandos:**
- `@copilot review` / `@codex review` - Code-Review durchführen
- `@copilot fix` / `@codex fix` - Automatische Fixes anwenden
- `@copilot explain` / `@codex explain` - Code erklären
- `@copilot test` / `@codex test` - Tests generieren

### Manuelle Workflow-Ausführung

Alle Workflows können auch manuell über GitHub Actions gestartet werden:

1. Gehe zu **Actions** Tab
2. Wähle den gewünschten Workflow
3. Klicke auf **Run workflow**
4. Fülle Parameter aus:
   - `task_type`: review, fix, explain, test
   - `target_ref`: PR-Nummer oder Branch
   - `agent`: codex, copilot, both

## 🔧 Manuelle Setup-Schritte

Die folgenden Schritte müssen **manuell** durchgeführt werden:

### 1. OpenAI API Key (✓ bereits vorhanden laut User)

Der `OPENAI_API_KEY` muss als Repository Secret existieren:
- Gehe zu **Settings** → **Secrets and variables** → **Actions**
- Überprüfe dass `OPENAI_API_KEY` vorhanden ist
- Falls nicht: Erstelle neues Secret mit dem OpenAI API Key

### 2. GitHub Copilot aktivieren

GitHub Copilot muss für das Repository aktiviert sein:
- Gehe zu **Settings** → **Copilot**
- Aktiviere **GitHub Copilot** für das Repository
- Optional: Aktiviere **Copilot for Pull Requests**

### 3. Labels erstellen

Erstelle die folgenden Labels (optional, werden automatisch verwendet):
- `review:requested` - Für PRs die Review benötigen
- `fix-needed` - Für PRs mit offenen Findings
- `ready-for-review` - Für PRs die bereit sind

**Automatisch erstellen:**
```bash
gh label create "review:requested" --color "0e8a16" --description "Automatisches Review wurde angefordert"
gh label create "fix-needed" --color "d93f0b" --description "Fixes sind erforderlich"
gh label create "ready-for-review" --color "0e8a16" --description "Bereit für Review"
```

### 4. Branch Protection Rules (optional)

Wenn gewünscht, passe Branch Protection Rules an:
- Gehe zu **Settings** → **Branches** → **Branch protection rules**
- Für `main` Branch:
  - Optional: Erlaube Agent-Commits ohne Review
  - Optional: Füge Status-Checks hinzu: "Auto Review", "Agent PR Ready"

### 5. CODEOWNERS aktivieren

CODEOWNERS ist bereits erstellt. Stelle sicher dass:
- **Settings** → **Code review** → **Require review from Code Owners** aktiviert ist (optional)
- Oder die automatischen Review-Anfragen funktionieren auch ohne diese Einstellung

## 📊 Workflow-Diagramm

```
PR erstellt
    ↓
Auto Review Request Workflow
    ↓
@copilot und @codex werden benachrichtigt
    ↓
Entwickler oder Auto-Trigger: "@copilot review"
    ↓
Copilot Agent Workflow
    ↓
Review-Kommentar wird gepostet
    ↓
Bei Änderungsanfragen: Prepare Fix Task Workflow
    ↓
Fix-Task mit Aktions-Buttons wird erstellt
    ↓
Entwickler: "@copilot fix" oder "@codex fix"
    ↓
Agent wendet Fixes an
    ↓
Agent PR Ready Workflow
    ↓
Draft → Ready for Review
    ↓
Manuelles Review & Merge
```

## 🔒 Sicherheit

### Berechtigungen
- Agents haben **WRITE** Zugriff auf Repository-Inhalte
- Agents können **KEINE** Repository-Einstellungen ändern
- Agents können **NICHT** Repository löschen oder transferieren
- Branch Protection Rules werden respektiert

### API-Limits
- OpenAI API: Rate-Limits gemäß OpenAI Plan
- GitHub API: Standard Rate-Limits für Actions

### Best Practices
- **Secrets** werden nie im Code committed
- Alle API-Calls verwenden sichere Secret-Variablen
- Agent-Commits sind klar gekennzeichnet
- Alle Änderungen sind nachvollziehbar

## 🧪 Testing

### Workflow testen

1. **Erstelle Test-PR:**
   ```bash
   git checkout -b test/agent-integration
   echo "test" > test.txt
   git add test.txt
   git commit -m "test: Agent integration test"
   git push origin test/agent-integration
   gh pr create --title "Test: Agent Integration" --body "Testing agent workflows"
   ```

2. **Teste Agent-Kommentare:**
   ```bash
   gh pr comment <PR-NUMMER> --body "@copilot review"
   gh pr comment <PR-NUMMER> --body "@codex explain"
   ```

3. **Prüfe Workflow-Ausführung:**
   - Gehe zu **Actions** Tab
   - Überprüfe dass Workflows ausgelöst wurden
   - Prüfe Logs auf Fehler

## 📝 Beispiel-Szenarien

### Szenario 1: Neuer PR mit automatischem Review

1. Developer erstellt PR
2. Auto Review Request Workflow startet
3. @copilot und @codex werden kommentiert
4. Developer oder automatisch: `@copilot review`
5. Copilot Agent führt Review durch
6. Review-Kommentar wird gepostet

### Szenario 2: Review mit Änderungsanfragen

1. Manuelles oder automatisches Review mit Änderungsanfragen
2. Prepare Fix Task Workflow sammelt Findings
3. Strukturierter Fix-Task Kommentar wird erstellt
4. Developer: `@copilot fix`
5. Copilot Agent wendet Fixes an
6. Änderungen werden committed und gepusht
7. Agent PR Ready markiert PR als Ready

### Szenario 3: Code-Erklärung anfordern

1. Developer kommentiert: `@codex explain`
2. Codex Agent Workflow startet
3. Detaillierte Code-Erklärung wird generiert
4. Erklärung wird als Kommentar gepostet

## 🔄 Wartung

### Workflow-Updates
- Workflows können über PR-Updates angepasst werden
- Teste Änderungen in separatem Branch
- Prüfe YAML-Syntax mit `yamllint`

### Agent-Konfiguration
- `agents.json` und `mcp.json` können angepasst werden
- Ändere Model-Settings, Temperature, etc.
- Restart wird automatisch nach Commit erkannt

## 📚 Weitere Ressourcen

- [GitHub Actions Dokumentation](https://docs.github.com/en/actions)
- [GitHub Copilot Dokumentation](https://docs.github.com/en/copilot)
- [OpenAI API Dokumentation](https://platform.openai.com/docs)
- [CODEOWNERS Dokumentation](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)

## ❓ Troubleshooting

### Agent reagiert nicht auf Mention
- Prüfe dass `@codex` oder `@copilot` korrekt geschrieben ist
- Prüfe Workflow-Logs in Actions Tab
- Prüfe Permissions in Workflow-Datei

### OpenAI API Fehler
- Prüfe dass `OPENAI_API_KEY` Secret existiert
- Prüfe OpenAI API Rate-Limits
- Prüfe OpenAI Account Balance

### Workflow schlägt fehl
- Prüfe Workflow-Logs in Actions Tab
- Prüfe YAML-Syntax
- Prüfe Permissions

### Agent committed nicht
- Prüfe dass Branch nicht geschützt ist
- Prüfe Permissions im Workflow
- Prüfe Git-Konfiguration im Workflow

## 🎯 Nächste Schritte

1. ✅ Führe manuelle Setup-Schritte durch (siehe oben)
2. ✅ Teste Workflows mit Test-PR
3. ✅ Passe Agent-Konfiguration nach Bedarf an
4. ✅ Sammle Feedback und optimiere
5. ✅ Dokumentiere Best Practices für dein Team

---

**Erstellt am:** 2026-01-14  
**Version:** 1.0  
**Status:** Bereit für Testing
