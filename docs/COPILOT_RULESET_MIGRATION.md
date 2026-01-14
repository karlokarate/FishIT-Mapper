# Migration zum Copilot Ruleset Workflow

## Von manuellem Workflow zu vollautomatischem Workflow

### Vorher (Manuell)

```
1. Issue erstellen
2. Branch erstellen
3. Code implementieren
4. PR erstellen
5. Review anfordern
6. Review-Kommentare lesen
7. Fixes implementieren
8. Review-Kommentare erneut lesen
9. Weitere Fixes
10. ...mehrere Runden...
11. Manuell mergen
12. Issue manuell schließen
13. Dokumentation manuell aktualisieren
```

⏱️ **Zeit:** 1-3 Tage pro Feature  
👤 **Aufwand:** Hoch (viele manuelle Schritte)

### Nachher (Vollautomatisch mit Ruleset)

```
1. Issue erstellen mit Labels
2. ☕ Kaffee trinken
```

⏱️ **Zeit:** 2-8 Stunden (vollautomatisch)  
👤 **Aufwand:** Minimal (nur Issue-Beschreibung)

## Migrationsschritte

### Schritt 1: Workflow-Konfiguration ist bereits vorhanden ✅

Die Automation ist bereits eingerichtet:
- ✅ `.github/copilot/workflow-automation.json` - Dokumentiert die Workflow-Logik
- ✅ `.github/workflows/orchestrator.yml` - Implementiert die Automation
- ✅ `.github/copilot/agents.json` - Konfiguriert die Agents

**Keine Installation notwendig!**

**WICHTIG:** GitHub Repository Rulesets (für Branch Protection) werden über Settings > Branches > Rulesets konfiguriert. Die `workflow-automation.json` ist eine Dokumentation der Workflow-Logik, kein offizielles GitHub Ruleset.

**Verifizierung:**
```bash
# Prüfe ob die Dateien vorhanden sind:
ls -la .github/copilot/workflow-automation.json
ls -la .github/workflows/orchestrator.yml
ls -la .github/copilot/agents.json
```

### Schritt 2: Labels erstellen

Die Labels werden automatisch erstellt wenn das Ruleset importiert wird. Manuell kannst du sie so erstellen:

```bash
# Required Labels
gh label create "orchestrator:enabled" --color "0e8a16" --description "Aktiviert den Copilot Orchestrator"
gh label create "orchestrator:run" --color "1d76db" --description "Startet die automatische Verarbeitung"

# State Labels
gh label create "state:queued" --color "d4c5f9" --description "Wartet auf Verarbeitung"
gh label create "state:running" --color "fbca04" --description "Task wird bearbeitet"
gh label create "state:needs-review" --color "0075ca" --description "Wartet auf Review"
gh label create "state:fixing" --color "d93f0b" --description "Review Findings werden behoben"
gh label create "state:passed" --color "0e8a16" --description "Review approved"
gh label create "state:merged" --color "6f42c1" --description "PR gemerged"
gh label create "state:blocked" --color "b60205" --description "Workflow blockiert"
gh label create "status:completed" --color "0e8a16" --description "Alle Tasks abgeschlossen"
```

### Schritt 3: Existierende Issues migrieren

Für bestehende offene Issues:

```bash
# Beispiel: Issue #10 auf automatischen Workflow umstellen
gh issue edit 10 --add-label "orchestrator:enabled,orchestrator:run"

# Erstelle TODO_QUEUE.md für das Issue
cat > codex/TODO_QUEUE.md << 'EOF'
# Orchestrator TODO Queue

## Current Issue: #10

- [ ] Task 1 aus Issue-Beschreibung
- [ ] Task 2 aus Issue-Beschreibung
- [ ] Task 3 aus Issue-Beschreibung
EOF

git add codex/TODO_QUEUE.md
git commit -m "Initialize TODO_QUEUE for issue #10"
git push

# Issue wird nun automatisch verarbeitet
```

### Schritt 4: Erste Tests

**Test-Issue erstellen:**

```markdown
# [TEST] Copilot Ruleset Workflow Test

## Beschreibung
Dies ist ein Test-Issue um den automatischen Workflow zu testen.

## Anforderungen
- Erstelle neue Datei: `test/workflow-test.txt`
- Inhalt: "Copilot Ruleset Test erfolgreich"
- Füge Timestamp hinzu

## Akzeptanzkriterien
- [x] Datei existiert
- [x] Inhalt korrekt
- [x] Timestamp vorhanden
```

**Labels hinzufügen:**
```bash
gh issue create --title "[TEST] Copilot Ruleset Workflow Test" \
  --body-file test-issue.md \
  --label "orchestrator:enabled,orchestrator:run"
```

**Workflow beobachten:**
- Issue-Kommentare beobachten
- `codex/CHECKPOINT.md` prüfen
- `codex/TODO_QUEUE.md` prüfen
- GitHub Actions Logs ansehen

### Schritt 5: Produktiv-Einsatz

Nach erfolgreichem Test:

1. **Issue-Template nutzen:**
   - Neues Issue via Template erstellen: "Automated Workflow Issue"
   - Labels werden automatisch gesetzt

2. **Best Practices befolgen:**
   - Klare, präzise Issue-Beschreibungen
   - Anforderungen strukturiert auflisten
   - Akzeptanzkriterien definieren
   - Kontext und Links angeben

3. **Monitoring einrichten:**
   - GitHub Actions Notifications aktivieren
   - Regelmäßig `codex/CHECKPOINT.md` prüfen
   - Bei Problemen in Issue-Kommentaren nachsehen

## Vergleich: Alt vs. Neu

### Alter Workflow (Manuell)

| Schritt | Aufwand | Zeit |
|---------|---------|------|
| Issue analysieren | 15 min | 👤 Manuell |
| Tasks planen | 30 min | 👤 Manuell |
| Branch erstellen | 5 min | 👤 Manuell |
| Code implementieren | 2h | 👤 Manuell |
| Tests schreiben | 1h | 👤 Manuell |
| PR erstellen | 10 min | 👤 Manuell |
| Review anfordern | 5 min | 👤 Manuell |
| Review-Kommentare lesen | 15 min | 👤 Manuell |
| Fixes implementieren | 1h | 👤 Manuell |
| Re-Review | 15 min | 👤 Manuell |
| Mergen | 5 min | 👤 Manuell |
| Issue schließen | 5 min | 👤 Manuell |
| Doku aktualisieren | 30 min | 👤 Manuell |
| **TOTAL** | **~6h** | **100% manuell** |

### Neuer Workflow (Automatisch)

| Schritt | Aufwand | Zeit |
|---------|---------|------|
| Issue erstellen | 15 min | 👤 Manuell |
| Labels setzen | 1 min | 👤 Manuell |
| Tasklist generieren | - | 🤖 5 min |
| Branch erstellen | - | 🤖 1 min |
| Code implementieren | - | 🤖 30 min |
| Tests schreiben | - | 🤖 20 min |
| PR erstellen | - | 🤖 1 min |
| Review durchführen | - | 🤖 5 min |
| Fixes implementieren | - | 🤖 15 min |
| Re-Review | - | 🤖 5 min |
| Mergen | - | 🤖 1 min |
| Issue schließen | - | 🤖 1 min |
| Doku aktualisieren | - | 🤖 10 min |
| **TOTAL** | **~16 min** | **~84 min (automatisch)** |

**Ersparnis:**
- ⏱️ **Zeit:** 6h → 1.5h (75% Ersparnis)
- 👤 **Manueller Aufwand:** 6h → 16min (95% Ersparnis)
- ✅ **Qualität:** Konsistent hoch durch automatische Reviews
- 🐛 **Fehlerrate:** Reduziert durch automatisierte Tests

## Häufige Fragen (FAQ)

### Kann ich den Workflow pausieren?

Ja, entferne einfach das Label `orchestrator:run` vom Issue.

### Was passiert bei Merge-Konflikten?

Der Workflow setzt Label `state:blocked` und benachrichtigt dich. Du musst Konflikte manuell lösen.

### Kann ich manuell eingreifen?

Ja, du kannst jederzeit:
- Kommentare im Issue schreiben
- PRs manuell bearbeiten
- Labels manuell ändern
- `codex/CHECKPOINT.md` manuell editieren

### Was kostet das?

Vollständig kostenlos! Läuft auf GitHub Actions Free Tier (2000 min/Monat).

### Wie viele Issues kann ich parallel laufen lassen?

Aktuell: 1 Issue zur Zeit (sequentielle Verarbeitung). Dies vermeidet Merge-Konflikte.

### Funktioniert das mit privaten Repositories?

Ja, solange GitHub Copilot für das Repository aktiviert ist.

## Rollback

Falls du zum manuellen Workflow zurückkehren möchtest:

1. **Ruleset deaktivieren:**
```bash
gh api repos/karlokarate/FishIT-Mapper/copilot/rulesets/1 \
  -X PATCH \
  -f enabled=false
```

2. **Labels entfernen:**
```bash
# Von allen Issues
gh issue list --state all --json number -q '.[].number' | \
  xargs -I {} gh issue edit {} --remove-label "orchestrator:enabled,orchestrator:run"
```

3. **Workflow-Dateien behalten:**
Die Dateien bleiben im Repository und können jederzeit wieder aktiviert werden.

## Support

Bei Problemen:
- 📖 Dokumentation: [docs/COPILOT_RULESET.md](../docs/COPILOT_RULESET.md)
- 🚀 Quick Start: [docs/COPILOT_RULESET_QUICKSTART.md](../docs/COPILOT_RULESET_QUICKSTART.md)
- 🐛 Issue erstellen mit Label `type:support`
- 💬 Diskussionen: GitHub Discussions

---

**Viel Erfolg mit dem vollautomatischen Workflow! 🚀**
