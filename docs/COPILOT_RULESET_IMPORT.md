# GitHub Copilot Ruleset - Importierbar

## Übersicht

Die Datei `.github/copilot-ruleset.json` ist ein **importierbares GitHub Repository Ruleset** im offiziellen GitHub-Format.

## Was macht dieses Ruleset?

Dieses Ruleset konfiguriert folgende Regeln für den Default Branch (main):

1. **Deletion Protection** - Verhindert das Löschen des Branches
2. **Non-Fast-Forward Protection** - Verhindert Force Pushes
3. **Pull Request Requirement** - Erfordert Pull Requests (ohne Review-Pflicht)
4. **Copilot Code Review** - Aktiviert automatische Copilot Code Reviews
5. **CodeQL Analysis** - Nutzt CodeQL für Security-Analysen

## Installation

### Via GitHub Web UI

1. Gehe zu: **Settings → Rules → Rulesets**
2. Klicke: **"Import a ruleset"**
3. Wähle die Datei: `.github/copilot-ruleset.json`
4. Klicke: **"Import"**

### Via GitHub CLI

```bash
# Ruleset importieren
gh api repos/karlokarate/FishIT-Mapper/rulesets \
  -X POST \
  --input .github/copilot-ruleset.json
```

### Via REST API

```bash
curl -X POST \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  https://api.github.com/repos/karlokarate/FishIT-Mapper/rulesets \
  -d @.github/copilot-ruleset.json
```

## Verifizierung

Nach dem Import kannst du das Ruleset überprüfen:

```bash
# Alle Rulesets anzeigen
gh api repos/karlokarate/FishIT-Mapper/rulesets

# Spezifisches Ruleset anzeigen
gh api repos/karlokarate/FishIT-Mapper/rulesets/{ruleset_id}
```

## Anpassungen

Du kannst das Ruleset anpassen, indem du die Datei bearbeitest und erneut importierst:

### Target ändern

Andere Branches schützen:
```json
"conditions": {
  "ref_name": {
    "include": ["main", "develop"]
  }
}
```

### Reviews erzwingen

Mindestens 1 Review verlangen:
```json
{
  "type": "pull_request",
  "parameters": {
    "required_approving_review_count": 1
  }
}
```

### Bypass Actors hinzufügen

Bestimmten Bots oder Users erlauben, Regeln zu umgehen:
```json
"bypass_actors": [
  {
    "actor_id": 1,
    "actor_type": "Integration",
    "bypass_mode": "always"
  }
]
```

## Unterschied zur Workflow Automation

| Feature | `.github/copilot-ruleset.json` | `.github/copilot/workflow-automation.json` |
|---------|-------------------------------|-------------------------------------------|
| **Typ** | GitHub Repository Ruleset | Custom Workflow Configuration |
| **Import** | Via Settings/API | Nicht importierbar |
| **Zweck** | Branch Protection | Issue→PR Automation Dokumentation |
| **Format** | GitHub API Standard | Custom Format |
| **Verwendung** | Settings → Rules → Rulesets | Dokumentation für Orchestrator |

## Weitere Informationen

- **GitHub Docs:** https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets
- **Ruleset Recipes:** https://github.com/github/ruleset-recipes
- **Copilot Code Review:** https://docs.github.com/en/copilot/using-github-copilot/code-review

---

**Datei:** `.github/copilot-ruleset.json`  
**Format:** GitHub Repository Ruleset v1  
**Kompatibel:** GitHub Enterprise Cloud, GitHub.com
