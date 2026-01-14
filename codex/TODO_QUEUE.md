# Orchestrator TODO Queue

Diese Datei verwaltet die Task-Warteschlange für den GitHub Actions Orchestrator.

## Issue #9: Vervollständigung offener Features

### Priorität 1: Quick Wins (1-2 Tage)

- [ ] 1.1 WebChromeClient für Console-Logs hinzufügen
- [ ] 1.2 Chains-Tab im UI erstellen
- [ ] 1.3 Filter-Dropdown für NodeKind/EdgeKind

---

## Nutzung

### Task-Format

```markdown
## Issue #<number>: <title>

- [ ] Task 1 - Kurzbeschreibung
- [ ] Task 2 - Kurzbeschreibung
- [x] Task 3 - Abgeschlossen
```

### Workflow

1. **Neues Issue**: Kopiere Tasks aus dem Issue-Body hierher
2. **Task abgeschlossen**: Markiere mit `[x]`
3. **Alle Tasks done**: Orchestrator erstellt Follow-up Issue mit verbleibenden Tasks

### Beispiel

```markdown
## Issue #123: Implement User Authentication

- [x] Create User model and contract
- [x] Implement login endpoint
- [ ] Add JWT token validation
- [ ] Implement logout functionality
- [ ] Add session management
- [ ] Write unit tests
- [ ] Update API documentation

## Future Tasks (Follow-up)

Diese Tasks werden in einem neuen Issue fortgesetzt, nachdem die aktuellen Tasks abgeschlossen sind:

- [ ] Implement password reset
- [ ] Add 2FA support
- [ ] Implement OAuth providers
```

---

## Anleitung für den Orchestrator

Der Orchestrator:
1. Liest den ersten `[ ]` (nicht erledigten) Task
2. Instruiert den Agent, NUR diesen Task zu erledigen
3. Markiert Task als `[x]` nach erfolgreichem Merge
4. Wiederholt für nächsten Task
5. Erstellt Follow-up Issue wenn alle Tasks erledigt sind

**Time-safe**: Jeder Task ist ein separater PR-Cycle. Bei Timeout wird beim nächsten Run einfach fortgesetzt.
