# GitHub Actions Orchestrator

## Überblick

Der **GitHub Actions Orchestrator** ist eine vollständig GitHub-native Lösung zur automatisierten Verwaltung von Issues und Pull Requests als deterministische State Machine. Er ermöglicht mehrstufige, zeitunabhängige Iterationen ohne Timeouts durch Batch-Verarbeitung und persistente Zustandsspeicherung.

## Architektur

Der Orchestrator besteht aus drei Hauptkomponenten:

1. **Label-basierte State Machine** - Labels definieren den aktuellen Zustand
2. **Checkpoint-Persistenz** - Repository-Dateien speichern Fortschritt
3. **Workflow-Orchestrierung** - GitHub Actions führen Transitionen aus

## Label-Konventionen

### Master Switch
- `orchestrator:enabled` - Muss vorhanden sein, damit der Orchestrator aktiv wird

### Workflow-Trigger
- `orchestrator:run` - Startet die Verarbeitung eines Issues

### Zustands-Labels
- `state:queued` - Issue wartet auf Verarbeitung
- `state:running` - Arbeit läuft aktuell
- `state:needs-review` - PR wartet auf Review
- `state:changes-requested` - Review hat Änderungen angefordert
- `state:fixing` - Änderungen werden implementiert
- `state:passed` - Review erfolgreich, bereit zum Merge
- `state:merged` - PR wurde gemerged
- `state:blocked` - Arbeit ist blockiert (zu viele Fehler/Iterationen)

### Prioritäts-Labels (optional)
- `priority:p0` - Höchste Priorität

## State Machine

```
┌─────────────┐
│   Issue     │
│   Created   │
└──────┬──────┘
       │ Label: orchestrator:run
       │ Label: orchestrator:enabled
       ▼
┌─────────────┐
│   QUEUED    │ ← Issue ist markiert zur Verarbeitung
└──────┬──────┘
       │ 1. Branch erstellen
       │ 2. Ersten Task aus TODO_QUEUE posten
       │ 3. Agent instruieren
       ▼
┌─────────────┐
│   RUNNING   │ ← Agent arbeitet am Task
└──────┬──────┘
       │ 1. PR erstellt/aktualisiert
       │ 2. Checks grün (oder keine Checks)
       ▼
┌──────────────┐
│ NEEDS-REVIEW │ ← PR wartet auf Review
└──────┬───┬───┘
       │   │
       │   └──────────────┐
       │ Approved         │ Changes Requested
       │                  │
       ▼                  ▼
┌─────────┐        ┌──────────┐
│ PASSED  │        │  FIXING  │ ← Änderungen werden gemacht
└────┬────┘        └─────┬────┘
     │                   │ 1. Fixes implementiert
     │                   │ 2. Checks grün
     │                   │ 3. Iteration++
     │                   ▼
     │            ┌──────────────┐
     │            │ NEEDS-REVIEW │ (zurück zu Review)
     │            └──────────────┘
     │
     │ 1. Squash Merge
     │ 2. Issue schließen
     │ 3. Follow-up Issue erstellen
     ▼
┌─────────┐
│ MERGED  │ ← Fertig!
└─────────┘

Blockierung (jederzeit möglich):
┌──────────┐
│ BLOCKED  │ ← Checks fehlgeschlagen 2x ODER Iteration > 5
└──────────┘
```

## Transitionen im Detail

### 1. Start: Issue wird markiert
**Trigger:** User fügt Label `orchestrator:run` + `orchestrator:enabled` hinzu

**Aktion:**
- Label `state:queued` setzen
- Orchestrator-Workflow wird beim nächsten Trigger ausgeführt

### 2. QUEUED → RUNNING
**Bedingungen:**
- Issue hat Labels: `orchestrator:run`, `state:queued`, `orchestrator:enabled`
- Kein PR für dieses Issue existiert

**Aktionen:**
1. Erstelle Branch `orchestrator/issue-<id>-<slug>`
2. Lese ersten unerledigten Task aus `codex/TODO_QUEUE.md`
3. Poste Kommentar mit Anweisung für Agent:
   ```
   🤖 **Orchestrator Task**
   
   Bitte führe NUR den folgenden Task aus:
   
   - [ ] <Task aus TODO_QUEUE>
   
   Weitere Informationen: siehe codex/CHECKPOINT.md
   
   Nach Fertigstellung erstelle einen PR.
   ```
4. Aktualisiere `codex/CHECKPOINT.md` mit aktuellem Task und Iteration=1
5. Entferne Label `state:queued`, füge `state:running` hinzu

### 3. RUNNING → NEEDS-REVIEW
**Bedingungen:**
- Issue hat Label `state:running`
- PR für den Branch existiert
- Checks sind grün ODER keine Checks definiert

**Aktionen:**
1. Fordere Review an (GitHub API)
2. Poste Kommentar:
   ```
   🤖 **Orchestrator: Review benötigt**
   
   Der Task ist abgeschlossen. Bitte reviewe die Änderungen.
   ```
3. Entferne Label `state:running`, füge `state:needs-review` hinzu

### 4. NEEDS-REVIEW → FIXING
**Bedingungen:**
- PR hat Label `state:needs-review`
- Review wurde submitted mit state "changes_requested"

**Aktionen:**
1. Sammle Review-Kommentare
2. Poste Kommentar mit Fix-Anweisung:
   ```
   🤖 **Orchestrator: Änderungen erforderlich**
   
   Bitte behebe NUR die folgenden Review-Findings:
   
   <Liste der Review-Kommentare>
   
   Aktualisiere codex/CHECKPOINT.md nach den Fixes.
   ```
3. Entferne Label `state:needs-review`, füge `state:fixing` hinzu

### 5. FIXING → NEEDS-REVIEW
**Bedingungen:**
- PR hat Label `state:fixing`
- Neue Commits wurden gepusht
- Checks sind grün

**Aktionen:**
1. Inkrementiere Iteration in `codex/CHECKPOINT.md`
2. Prüfe Iteration < 6 (sonst → BLOCKED)
3. Fordere erneutes Review an
4. Poste Kommentar:
   ```
   🤖 **Orchestrator: Fixes angewendet**
   
   Änderungen wurden implementiert. Bitte erneut reviewen.
   
   Iteration: <N>/5
   ```
5. Entferne Label `state:fixing`, füge `state:needs-review` hinzu

### 6. NEEDS-REVIEW → PASSED
**Bedingungen:**
- PR hat Label `state:needs-review`
- Review wurde submitted mit state "approved"
- Checks sind grün

**Aktionen:**
1. Poste Kommentar:
   ```
   🤖 **Orchestrator: Bereit zum Merge**
   
   Review approved und Checks erfolgreich.
   Merge wird vorbereitet...
   ```
2. Entferne Label `state:needs-review`, füge `state:passed` hinzu

### 7. PASSED → MERGED
**Bedingungen:**
- PR hat Label `state:passed`
- Checks sind grün
- Keine Merge-Konflikte

**Aktionen:**
1. Merge PR (squash merge)
2. Schließe Issue
3. Erstelle Follow-up Issue aus verbleibenden Tasks in `codex/TODO_QUEUE.md`:
   ```markdown
   # Follow-up: <Original Issue Title>
   
   Fortsetzung von #<original-issue-number>
   
   ## Verbleibende Tasks:
   <Unerledigte Tasks aus TODO_QUEUE>
   
   ## Context:
   <Link zum Original-Issue>
   ```
4. Füge Labels hinzu: `orchestrator:run`, `state:queued`, `orchestrator:enabled`
5. Entferne alle Labels vom geschlossenen Issue, füge `state:merged` hinzu

### 8. Blockierung (BLOCKED)
**Bedingungen:**
- Checks fehlgeschlagen bei gleicher Root Cause (2x)
- ODER Iteration > 5

**Aktionen:**
1. Poste Kommentar:
   ```
   ⚠️ **Orchestrator: BLOCKIERT**
   
   Grund: <Iteration-Limit ODER wiederholte Check-Fehler>
   
   Manuelle Intervention erforderlich.
   Entferne Label `state:blocked` und füge `state:fixing` hinzu zum Fortfahren.
   ```
2. Entferne alle state-Labels, füge `state:blocked` hinzu

## Checkpoint-Persistenz

### codex/CHECKPOINT.md

Speichert den aktuellen Fortschritt:

```markdown
# Orchestrator Checkpoint

**Issue:** #123
**PR:** #124
**Branch:** orchestrator/issue-123-implement-feature
**Current Task:** Implement authentication logic
**Iteration:** 2/5
**Last Check:** 2026-01-14T10:00:00Z
**Last Check Status:** ✅ passed
**Previous Check Failures:** 0

## History
- 2026-01-14T09:00:00Z - Transition: QUEUED → RUNNING
- 2026-01-14T09:30:00Z - Transition: RUNNING → NEEDS-REVIEW
- 2026-01-14T09:45:00Z - Transition: NEEDS-REVIEW → FIXING (changes requested)
- 2026-01-14T10:00:00Z - Transition: FIXING → NEEDS-REVIEW (iteration 2)
```

### codex/TODO_QUEUE.md

Verwaltet die Task-Warteschlange:

```markdown
# Orchestrator TODO Queue

## Current Issue: #123

- [x] Implement authentication logic
- [ ] Add unit tests for auth
- [ ] Update documentation

## Future Tasks (Follow-up Issues)

- [ ] Implement authorization
- [ ] Add integration tests
```

## Vollautomatisierung (NEU!)

### Automatische Erkennung von Agent-Completion

Der Orchestrator erkennt automatisch, wenn der Copilot Agent seine Arbeit beendet hat:

1. **Agent-Commit Detection**: Commits von `copilot[bot]`, `github-actions[bot]` oder mit 🤖 Emoji
2. **Check Verification**: Wartet bis alle CI-Checks erfolgreich sind
3. **Auto-Ready**: Setzt Draft PRs automatisch auf "Ready for Review"

### Automatischer Fix bei Review-Findings

Wenn ein Reviewer "Changes Requested" gibt:

1. **Findings Sammeln**: Alle Review-Kommentare werden gesammelt
2. **Fix-Command Posten**: `@copilot fix` wird automatisch gepostet
3. **Agent arbeitet**: Copilot Agent behebt die Findings
4. **Re-Review**: Nach Fixes wird automatisch erneut Review angefordert

### Workflow-Dateien

- **`orchestrator-complete.yml`** - Hauptworkflow für vollständige Automatisierung
- **`copilot-agent-handler.yml`** - Reagiert auf @copilot Befehle
- **`orchestrator.yml`** - Legacy-Workflow (weiterhin funktional)

### Trigger-Events

Der Complete Orchestrator wird ausgelöst bei:
- **Push auf orchestrator/**, **copilot/**, **feature/** Branches
- **PR Events**: opened, synchronize, ready_for_review
- **Review Events**: submitted
- **Check Suite**: completed
- **Schedule**: Alle 5 Minuten für verpasste Events

## Nutzung

### 1. Orchestrator aktivieren

Erstelle oder stelle sicher, dass folgende Dateien existieren:
- `codex/TODO_QUEUE.md` mit Tasks
- `codex/CHECKPOINT.md` (wird automatisch erstellt)

Erstelle ein Issue mit:
- Klarer Beschreibung
- Tasks aufgelistet im Issue-Body

Füge Labels hinzu:
- `orchestrator:enabled`
- `orchestrator:run`

Der Orchestrator startet automatisch beim nächsten Trigger.

### 2. Manueller Trigger

Über GitHub Actions UI:
1. Gehe zu Actions → Complete Orchestrator
2. Klicke "Run workflow"
3. Optionale Aktionen:
   - `auto-detect` - Automatische Erkennung
   - `mark-ready` - PR auf Ready setzen (benötigt PR-Nummer)
   - `trigger-fix` - Fix-Task starten (benötigt PR-Nummer)
   - `merge` - PR mergen

### 3. Transition Script direkt nutzen

```bash
# Auto-Transition
node scripts/orchestrator/transition.mjs

# Spezifisches Issue
node scripts/orchestrator/transition.mjs --issue 42

# Spezifischer PR
node scripts/orchestrator/transition.mjs --pr 43

# PR als Ready markieren
node scripts/orchestrator/transition.mjs --pr 43 --action mark-ready

# Fix triggern
node scripts/orchestrator/transition.mjs --pr 43 --action trigger-fix

# Alle Draft PRs prüfen
node scripts/orchestrator/transition.mjs --action check-draft-prs
```

### 4. Nach Timeout fortsetzen

Der Orchestrator ist **time-safe**:
- Jede Transition wird in `codex/CHECKPOINT.md` persistiert
- Bei Workflow-Timeouts oder -Fehlern läuft der nächste Run einfach weiter
- Keine Duplikate durch idempotente Operationen

### 5. Blockierung auflösen

Wenn `state:blocked`:
1. Prüfe Issue-Kommentare für Grund
2. Behebe Problem manuell
3. Entferne Label `state:blocked`
4. Füge Label `state:fixing` hinzu
5. Orchestrator setzt beim nächsten Run fort

## Integration mit existierenden Workflows

### Reused Workflows
Der Orchestrator **ergänzt** bestehende Workflows, ersetzt sie nicht:

- `copilot-agent-handler.yml` - **NEU:** Reagiert auf @copilot Befehle
- `orchestrator-complete.yml` - **NEU:** Vollautomatischer Workflow
- `copilot-agent.yml` - Legacy Agent-Unterstützung
- `codex-agent.yml` - Alternative Agent-Unterstützung
- `auto-review-request.yml` - Review-Anfragen
- `prepare-fix-task.yml` - Fix-Task-Vorbereitung
- `agent-pr-ready.yml` - Legacy PR-Status-Updates

### Build Verification
Der Orchestrator führt minimale Builds durch:
```bash
./gradlew :shared:contract:generateFishitContract
./gradlew :androidApp:compileDebugKotlin
```

Voller Build nur wenn nötig.

## Sicherheit

### Permissions
Orchestrator-Workflow benötigt:
- `contents: write` - Branch/PR-Erstellung
- `pull-requests: write` - PR-Management
- `issues: write` - Issue-Updates
- `actions: write` - Workflow-Trigger
- `checks: read` - Check-Status lesen
- `statuses: read` - Status-Checks lesen

### Keine externen Services
- ✅ Nur GitHub Actions
- ✅ Nur Repository-Scripts
- ✅ Kein OpenAI API Key erforderlich
- ✅ Keine kostenpflichtigen Services

## Troubleshooting

### Issue wird nicht verarbeitet
**Prüfe:**
- Label `orchestrator:enabled` vorhanden?
- Label `orchestrator:run` vorhanden?
- Label `state:queued` vorhanden?
- `codex/TODO_QUEUE.md` existiert und enthält Tasks?

### Orchestrator bleibt in RUNNING hängen
**Ursache:** Kein PR erstellt oder Checks laufen
**Lösung:** 
- Prüfe ob PR existiert
- Prüfe Check-Status
- Manuell PR erstellen falls nötig

### Draft PR wird nicht auf Ready gesetzt
**Ursache:** Agent-Commits nicht erkannt oder Checks nicht erfolgreich
**Lösung:**
- Prüfe ob Commits von Agent stammen (copilot[bot], github-actions[bot])
- Prüfe ob alle CI-Checks grün sind
- Manuell triggern: Actions → Complete Orchestrator → mark-ready

### Fix wird nicht automatisch gestartet
**Ursache:** Keine Review-Findings gefunden
**Lösung:**
- Stelle sicher, dass Review "Changes Requested" ist
- Prüfe ob Review-Kommentare existieren
- Manuell triggern: Actions → Complete Orchestrator → trigger-fix

### Zu viele Iterationen
**Ursache:** Review-Cycle > 5 Iterationen
**Lösung:**
- Manuelle Review und Merge
- Oder: `codex/CHECKPOINT.md` zurücksetzen (Iteration auf 1)
- Oder: Label `state:blocked` entfernen und `state:fixing` setzen

### Checks schlagen fehl
**Erste Fehler:** Automatischer Retry
**Zweiter Fehler (gleiche Ursache):** `state:blocked`
**Lösung:**
- Fehler manuell beheben
- Label `state:blocked` entfernen
- Label `state:fixing` hinzufügen

## Limits und Garantien

### Garantien
✅ **Time-safe**: Läuft über mehrere Workflow-Runs
✅ **Idempotent**: Mehrfaches Ausführen ist sicher
✅ **Deterministic**: Gleiches Input → gleiches Output
✅ **Kostenlos**: Nur GitHub Actions (Free Tier)
✅ **Vollautomatisch**: Draft PR → Ready → Review → Fix → Merge

### Limits
⚠️ Max. 5 Fix-Iterationen pro Task (konfigurierbar in `transition.mjs`: `MAX_ITERATIONS`)
⚠️ Max. 2 Check-Failures mit gleicher Root Cause (konfigurierbar in `transition.mjs`: `MAX_CHECK_FAILURES`)
⚠️ Workflow läuft alle 5 Minuten (Schedule - änderbar in `orchestrator-complete.yml`)
⚠️ Keine parallele Verarbeitung mehrerer Issues (sequentiell)

## Workflow-Trigger

Der Complete Orchestrator wird getriggert bei:
- **Push:** orchestrator/**, copilot/**, feature/** Branches
- **Pull Requests:** opened, synchronize, ready_for_review, reopened
- **Pull Request Reviews:** submitted
- **Check Suite:** completed
- **Schedule:** Alle 5 Minuten
- **Workflow Dispatch:** Manuell mit Optionen

## Best Practices

1. **Kleine Tasks**: Halte Tasks in `TODO_QUEUE.md` klein (max. 1-2h Arbeit)
2. **Klare Beschreibungen**: Issue-Beschreibungen sollten self-contained sein
3. **Review zeitnah**: Schnelle Reviews vermeiden lange Wartezeiten
4. **Monitor Checkpoints**: Prüfe `codex/CHECKPOINT.md` bei Problemen
5. **Nicht force-pushen**: Branch-History wird für Rollbacks benötigt
6. **orchestrator:enabled Label**: Immer setzen für automatische Workflows

## Beispiel-Workflow (Vollautomatisch)

```
Tag 1, 09:00 - Issue #42 erstellt mit Tasks, Labels gesetzt
Tag 1, 09:05 - Orchestrator: QUEUED → RUNNING, Task-Instruktion gepostet
Tag 1, 09:30 - Copilot Agent erstellt Draft PR mit Commits
Tag 1, 09:35 - Complete Orchestrator: Agent-Commits erkannt, Checks laufen
Tag 1, 09:45 - Checks erfolgreich → PR automatisch auf "Ready for Review" gesetzt
Tag 1, 09:45 - Orchestrator: RUNNING → NEEDS-REVIEW
Tag 1, 14:00 - Review submitted: changes_requested
Tag 1, 14:01 - Complete Orchestrator: Findings gesammelt, @copilot fix gepostet
Tag 1, 14:01 - Orchestrator: NEEDS-REVIEW → FIXING
Tag 1, 14:30 - Copilot Agent pusht Fixes
Tag 1, 14:35 - Complete Orchestrator: Fixes erkannt, Checks laufen
Tag 1, 14:45 - Checks erfolgreich → Orchestrator: FIXING → NEEDS-REVIEW (Iteration 2)
Tag 1, 16:00 - Review submitted: approved
Tag 1, 16:01 - Complete Orchestrator: Approval erkannt
Tag 1, 16:01 - Orchestrator: NEEDS-REVIEW → PASSED
Tag 1, 16:02 - Orchestrator: PASSED → MERGED (Squash Merge)
Tag 1, 16:02 - Issue #42 geschlossen, Follow-up Issue #43 erstellt
```

**Total: ~7 Stunden mit Review-Iteration, vollständig automatisch!**
