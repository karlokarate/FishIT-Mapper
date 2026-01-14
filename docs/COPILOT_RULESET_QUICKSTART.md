# Copilot Workflow Automation Quick Start Guide

## 🚀 In 5 Minuten zum automatisierten Workflow

### Schritt 1: Workflow ist bereits eingerichtet ✅

Die Automation ist bereits konfiguriert:
- ✅ `.github/copilot/workflow-automation.json` - Dokumentiert die Workflow-Logik
- ✅ `.github/workflows/orchestrator.yml` - GitHub Actions Orchestrator
- ✅ `.github/copilot/agents.json` - Copilot Agents Konfiguration

**Keine Installation notwendig!** Der Workflow läuft automatisch sobald du ein Issue mit den richtigen Labels erstellst.

**HINWEIS:** GitHub Repository Rulesets (für Branch Protection) sind ein separates Feature und werden über Settings > Branches > Rulesets konfiguriert. Die `workflow-automation.json` ist eine Dokumentation, kein GitHub Ruleset.

### Schritt 2: Issue erstellen

```markdown
# Titel: Implementiere Feature X

## Beschreibung
Was soll implementiert werden?

## Anforderungen
- Anforderung 1
- Anforderung 2
- Anforderung 3

## Akzeptanzkriterien
- [ ] Tests vorhanden
- [ ] Dokumentation aktualisiert
- [ ] Keine Breaking Changes
```

### Schritt 3: Labels hinzufügen

Füge diese 2 Labels hinzu:
- ✅ `orchestrator:enabled`
- ✅ `orchestrator:run`

### Schritt 4: Zurücklehnen ☕

**Der Workflow läuft vollautomatisch:**

```
⏱️  0 Min    → Copilot generiert Tasklist
⏱️  5 Min    → Erster Task startet
⏱️  30 Min   → PR erstellt
⏱️  35 Min   → Review angefordert
⏱️  40 Min   → Review Findings (optional)
⏱️  60 Min   → Fixes implementiert (optional)
⏱️  65 Min   → Re-Review (optional)
⏱️  70 Min   → PR gemerged ✅
⏱️  71 Min   → Nächster Task startet
...
⏱️  4h       → Alle Tasks fertig
⏱️  4h 5min  → Issue geschlossen
⏱️  4h 10min → Dokumentation aktualisiert ✅
```

## 📊 Was passiert automatisch?

### ✅ Issue-Phase
- Tasklist wird generiert
- TODO_QUEUE.md wird erstellt
- CHECKPOINT.md wird initialisiert
- Erster Task wird gestartet

### ✅ Entwicklungs-Phase
- Branch wird erstellt
- Code wird implementiert
- Tests werden geschrieben
- PR wird erstellt

### ✅ Review-Phase
- Automatisches Code-Review
- Review Findings werden behoben
- Re-Review wird angefordert
- Bis zu 5 Iterationen

### ✅ Merge-Phase
- Squash Merge bei Approval
- Task wird als erledigt markiert
- Nächster Task startet

### ✅ Abschluss-Phase
- Issue wird geschlossen
- Zusammenfassung wird erstellt
- Dokumentation wird aktualisiert
- Follow-up Issue bei Bedarf

## 🎯 Die wichtigsten Labels

| Label | Bedeutung | Wer setzt es? |
|-------|-----------|---------------|
| `orchestrator:enabled` | Workflow aktiv | **DU** (manuell) |
| `orchestrator:run` | Issue starten | **DU** (manuell) |
| `state:queued` | Wartet auf Start | Copilot |
| `state:running` | Task läuft | Copilot |
| `state:needs-review` | Review nötig | Copilot |
| `state:fixing` | Fixes laufen | Copilot |
| `state:passed` | Review OK | Copilot |
| `state:merged` | PR gemerged | Copilot |
| `state:blocked` | ⚠️ Problem! | Copilot |
| `status:completed` | ✅ Fertig! | Copilot |

## 🔍 Fortschritt überwachen

### Live-Status im Issue
Copilot kommentiert automatisch:
```markdown
✅ Task 1 abgeschlossen
🚀 Task 2 gestartet
🔧 Review Findings werden behoben
🎉 Alle Tasks fertig!
```

### CHECKPOINT.md
```bash
cat codex/CHECKPOINT.md
```
Zeigt:
- Aktueller Task
- Iteration (X/5)
- Letzter Check-Status
- History

### TODO_QUEUE.md
```bash
cat codex/TODO_QUEUE.md
```
Zeigt:
- [x] Erledigte Tasks
- [ ] Offene Tasks

## 🛠️ Häufige Aktionen

### Workflow pausieren
```
Entferne Label: orchestrator:run
```

### Workflow fortsetzen
```
Füge Label hinzu: orchestrator:run
```

### Blockierung auflösen
```
1. Entferne Label: state:blocked
2. Füge Label hinzu: state:fixing
```

### Iteration zurücksetzen
```bash
# In codex/CHECKPOINT.md ändern:
**Iteration:** 1/5
```

### Manuell einen Task überspringen
```bash
# In codex/TODO_QUEUE.md ändern:
- [x] Task der übersprungen werden soll
```

## ⚠️ Troubleshooting

### Problem: Workflow startet nicht

**Checkliste:**
- [ ] Labels `orchestrator:enabled` + `orchestrator:run` gesetzt?
- [ ] Ruleset importiert?
- [ ] `codex/TODO_QUEUE.md` existiert?

**Lösung:**
```bash
# TODO_QUEUE manuell erstellen:
echo "# Orchestrator TODO Queue\n\n## Current Issue: #XX\n\n- [ ] Task 1" > codex/TODO_QUEUE.md
git add codex/TODO_QUEUE.md
git commit -m "Initialize TODO_QUEUE"
git push
```

### Problem: Stuck in state:running

**Ursache:** PR nicht erstellt oder Checks laufen

**Lösung:**
```
Option 1: Warten (bis zu 1h)
Option 2: Manuell PR erstellen
Option 3: Kommentar im Issue: "task completed"
```

### Problem: state:blocked

**Ursachen:**
- ❌ Zu viele Iterationen (>5)
- ❌ Build-Fehler (>2x)

**Lösung:**
```bash
# 1. Problem in CHECKPOINT.md prüfen:
cat codex/CHECKPOINT.md

# 2. Manuell fixen

# 3. Workflow fortsetzen:
# Labels: state:blocked entfernen, state:fixing hinzufügen
```

## 💡 Pro-Tipps

### Bessere Issue-Beschreibungen
✅ **Gut:**
```markdown
Implementiere JWT Authentication mit:
- Login Endpoint (POST /api/auth/login)
- Token Refresh (POST /api/auth/refresh)
- Logout (POST /api/auth/logout)
- Role-Based Access Control

Tests erforderlich: >80% Coverage
Dokumentation: API.md aktualisieren
```

❌ **Schlecht:**
```markdown
Auth machen
```

### Optimale Task-Größe
- ✅ 1-2 Stunden Arbeit
- ✅ Ein klares Feature/Bugfix
- ✅ Testbar und reviewbar
- ❌ Nicht "Implementiere gesamte App"

### Review-Kommentare präzise formulieren
✅ **Gut:**
```
In user.service.kt Zeile 42:
Null-Check für userId fehlt.
Ergänze: if (userId == null) throw IllegalArgumentException()
```

❌ **Schlecht:**
```
Funktioniert nicht
```

## 📈 Monitoring Dashboard

### Statistiken abrufen
Copilot erstellt automatisch bei Issue-Abschluss:
```markdown
🎊 Alle Tasks erfolgreich abgeschlossen!

**Statistiken:**
- Anzahl Tasks: 5
- PRs erstellt: 5
- Durchschnittliche Iterationen: 1.4
- Gesamtdauer: 6h 30min
- Erfolgsrate: 100%
```

### Workflow-Performance
```bash
# Aktuellen Status:
cat codex/CHECKPOINT.md | grep "Iteration"

# Task-Fortschritt:
cat codex/TODO_QUEUE.md | grep -c "\[x\]"  # Fertig
cat codex/TODO_QUEUE.md | grep -c "\[ \]"  # Offen
```

## 🔗 Weiterführende Docs

- **Vollständige Dokumentation:** [docs/COPILOT_RULESET.md](./COPILOT_RULESET.md)
- **Orchestrator Details:** [docs/ORCHESTRATOR.md](./ORCHESTRATOR.md)
- **Agent Setup:** [AGENT_SETUP.md](../AGENT_SETUP.md)
- **Copilot Setup:** [COPILOT_SETUP.md](../COPILOT_SETUP.md)

## 🎓 Beispiel-Session

### 1. Issue erstellen
```markdown
# Implementiere Dark Mode

- [ ] Erstelle Theme-System
- [ ] Implementiere Dark/Light Toggle
- [ ] Speichere Preference
- [ ] Update alle Screens
```

### 2. Labels setzen
```
orchestrator:enabled
orchestrator:run
```

### 3. Copilot generiert TODO_QUEUE
```markdown
# Orchestrator TODO Queue

## Current Issue: #50

- [ ] Erstelle Theme Domain Model (colors, typography)
- [ ] Implementiere Theme Provider mit State
- [ ] Füge Dark/Light Toggle UI hinzu
- [ ] Implementiere Persistence (DataStore)
- [ ] Aktualisiere alle Compose Screens
- [ ] Füge Unit Tests hinzu
- [ ] Aktualisiere Dokumentation
```

### 4. Workflow läuft
```
10:00 → Task 1 startet: "Erstelle Theme Domain Model"
10:20 → PR #51 erstellt
10:25 → Review: Changes Requested (Naming Convention)
10:35 → Fixes implementiert
10:40 → Re-Review: Approved
10:45 → PR #51 gemerged ✅

10:46 → Task 2 startet: "Implementiere Theme Provider"
...
```

### 5. Nach 4 Stunden
```
14:00 → Alle 7 Tasks fertig
14:05 → Issue #50 geschlossen
14:10 → Dokumentation aktualisiert
14:15 → PR #58 (Docs) gemerged
```

**Total:** 4h 15min, 8 PRs, 0 manuelle Interventionen ✅

## 🎉 Fertig!

Du bist jetzt bereit, den vollautomatischen Copilot Workflow zu nutzen!

**Nächste Schritte:**
1. ✅ Ruleset importieren
2. ✅ Erstes Issue mit Labels erstellen
3. ✅ Copilot arbeiten lassen
4. ☕ Kaffee trinken

---

Bei Fragen: Issue mit Label `type:support` erstellen
