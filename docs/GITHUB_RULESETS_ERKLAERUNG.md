# GitHub Rulesets vs. Workflow Automation - Erklärung

## Das Problem

Es gab Verwirrung zwischen zwei verschiedenen Konzepten:

### 1. **GitHub Repository Rulesets** (Offizielles GitHub Feature)
- **Was:** Branch Protection Rules, Tag Protection, Push Protection
- **Wo konfiguriert:** Settings > Branches > Rulesets (UI oder API)
- **Format:** Wird NICHT über JSON-Dateien in `.github/` konfiguriert
- **Zweck:** Repository-Sicherheit und Workflow-Kontrolle
- **Beispiele:** 
  - Branches vor Force Push schützen
  - Review-Anforderungen erzwingen
  - Bestimmte Datei-Typen blockieren
  - Commit-Signaturen verlangen

**Dokumentation:** https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets

### 2. **Workflow Automation Configuration** (Dieses Repository)
- **Was:** Dokumentation der gewünschten Issue-zu-PR Automation
- **Wo:** `.github/copilot/workflow-automation.json`
- **Format:** Custom JSON-Konfiguration (kein offizielles GitHub Feature)
- **Zweck:** Dokumentiert wie der Orchestrator arbeiten soll
- **Implementierung:** Läuft über `.github/workflows/orchestrator.yml` (GitHub Actions)

## Was wurde geändert

### Vorher (Problem)
```
.github/copilot/ruleset.json  ← Irreführender Name!
```
- Datei hieß `ruleset.json` → Verwechslung mit GitHub Rulesets
- Referenzierte nicht-existierendes Schema
- Konnte nicht "in Settings importiert" werden

### Nachher (Lösung)
```
.github/copilot/workflow-automation.json  ← Klarer Name!
```
- Umbenennung zu `workflow-automation.json` → Klare Unterscheidung
- Schema-Referenz entfernt (da kein offizielles Schema existiert)
- Kommentar hinzugefügt der klarstellt: Dies ist KEINE GitHub Ruleset-Konfiguration

## Wie funktioniert die Automation?

Die Workflow-Automation läuft über **GitHub Actions**, nicht über GitHub Rulesets:

```
1. Issue mit Labels erstellen
   ↓
2. GitHub Actions Orchestrator (.github/workflows/orchestrator.yml) wird getriggert
   ↓
3. Orchestrator liest TODO_QUEUE.md und CHECKPOINT.md
   ↓
4. Orchestrator startet Copilot Agents (konfiguriert in agents.json)
   ↓
5. Agents implementieren Tasks, erstellen PRs
   ↓
6. Orchestrator managed Reviews und Merges
```

Die Datei `workflow-automation.json` dokumentiert die Logik, wird aber **nicht direkt verwendet**. Die eigentliche Logik ist in `orchestrator.yml` und `transition.mjs` implementiert.

## Wenn du GitHub Rulesets nutzen willst

Falls du tatsächlich GitHub Repository Rulesets konfigurieren möchtest:

### Via GitHub UI:
1. Gehe zu: Settings > Branches > Rulesets
2. Klicke: "New branch ruleset"
3. Konfiguriere:
   - Target branches (z.B. `main`)
   - Rules (z.B. "Require pull request reviews")
   - Bypass permissions
4. Klicke: "Create"

### Via GitHub API:
```bash
# Beispiel: Ruleset erstellen
gh api repos/OWNER/REPO/rulesets \
  -X POST \
  -f name="Protect main branch" \
  -f enforcement="active" \
  -f target="branch" \
  -f conditions[ref_name][include][0]="refs/heads/main" \
  -f rules[0][type]="pull_request" \
  -F rules[0][parameters][required_approving_review_count]=1
```

**Dokumentation:** https://docs.github.com/en/rest/repos/rules

## Zusammenfassung

| Feature | GitHub Rulesets | Workflow Automation (dieses Repo) |
|---------|----------------|-----------------------------------|
| **Zweck** | Branch/Tag Protection | Issue-zu-PR Automation |
| **Konfiguration** | Settings UI oder API | `.github/copilot/workflow-automation.json` (Doku) |
| **Implementierung** | GitHub intern | GitHub Actions (`.github/workflows/orchestrator.yml`) |
| **Format** | GitHub API JSON | Custom JSON |
| **Import** | Via UI/API | Nicht importierbar (ist nur Dokumentation) |
| **Offizielle Docs** | Ja (siehe oben) | Nein (projekt-spezifisch) |

## Nächste Schritte

1. **Für Workflow Automation:** Nutze die bestehende Konfiguration, erstelle Issues mit Labels
2. **Für Branch Protection:** Konfiguriere GitHub Rulesets über Settings > Branches > Rulesets
3. **Dokumentation:** Siehe `docs/COPILOT_RULESET.md` für Details zur Workflow Automation

---

**Erstellt:** 2026-01-14  
**Zweck:** Klarstellung der Verwirrung zwischen GitHub Rulesets und Workflow Automation
