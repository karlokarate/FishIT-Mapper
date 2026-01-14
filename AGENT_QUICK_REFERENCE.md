# Agent Quick Reference

## 🤖 Agent-Kommandos

### GitHub Copilot
```
@copilot review     # Code-Review mit Build-Checks durchführen
@copilot fix        # Automatische Fixes anwenden (ktlint, etc.)
@copilot explain    # Code-Änderungen erklären
@copilot test       # Test-Vorschläge generieren
```

### OpenAI Codex
```
@codex review       # Detailliertes Code-Review mit GPT-4
@codex fix          # Intelligente Bug-Fix-Vorschläge
@codex explain      # Tiefgehende Code-Erklärung
@codex test         # Automatische Test-Cases generieren
```

## 📋 Workflow-Übersicht

| Workflow | Trigger | Beschreibung |
|----------|---------|--------------|
| **codex-agent** | `@codex` in Kommentar | OpenAI Codex Aktionen |
| **copilot-agent** | `@copilot` in Kommentar | GitHub Copilot Aktionen |
| **auto-review-request** | PR opened/ready | Automatische Review-Anfragen |
| **agent-pr-ready** | Nach Agent-Arbeit | Draft → Ready konvertieren |
| **prepare-fix-task** | Nach Review | Fix-Tasks sammeln und vorbereiten |

## 🎯 Häufige Anwendungsfälle

### 1. Schnelles Code-Review
```
@copilot review
```
→ Schnelle Analyse mit Build-Checks

### 2. Tiefgehendes Review
```
@codex review
```
→ Detaillierte AI-Analyse mit GPT-4

### 3. Automatische Fixes
```
@copilot fix
```
→ Code-Formatierung, einfache Fixes

### 4. Code verstehen
```
@codex explain
```
→ Ausführliche Erklärung der Änderungen

### 5. Tests generieren
```
@copilot test
@codex test
```
→ Test-Vorschläge und Beispiele

## ⚙️ Manuelle Workflow-Ausführung

1. Gehe zu **Actions** Tab
2. Wähle Workflow (z.B. "GitHub Copilot Agent")
3. Klicke **Run workflow**
4. Parameter:
   - **task_type**: review, fix, explain, test
   - **target_ref**: PR-Nummer (z.B. 123)
   - **agent**: codex, copilot, both

## 🏷️ Labels

| Label | Bedeutung |
|-------|-----------|
| `review:requested` | Review wurde angefordert |
| `fix-needed` | Fixes sind erforderlich |
| `ready-for-review` | Bereit für manuelles Review |

## 📊 Status-Checks

| Check | Bedeutung |
|-------|-----------|
| **Auto Review** | Review-Anfragen Status |
| **Agent PR Ready** | PR Ready-Status nach Agent-Arbeit |
| **Fix Task** | Fix-Task Status |

## 🔍 Workflow-Logs prüfen

1. Gehe zu **Actions** Tab
2. Wähle Workflow-Run
3. Klicke auf Job
4. Prüfe Step-Logs

## ⚡ Tipps

- **Kombiniere Agenten:** Nutze beide für umfassende Reviews
- **Spezifische Anfragen:** Je konkreter, desto besser
- **Kontext geben:** Erkläre was der Agent prüfen soll
- **Iterativ arbeiten:** Review → Fix → Review

## 🔗 Links

- [Vollständige Dokumentation](./AGENT_SETUP.md)
- [GitHub Actions](../../actions)
- [Workflow-Konfiguration](../.github/workflows/)

---
*Zuletzt aktualisiert: 2026-01-14*
