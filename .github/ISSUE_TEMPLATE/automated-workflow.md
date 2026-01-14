---
name: Automated Workflow Issue
about: Issue template für den vollautomatischen Copilot Workflow
title: '[AUTO] '
labels: orchestrator:enabled, orchestrator:run
assignees: ''
---

# Beschreibung

<!-- Klare, präzise Beschreibung was implementiert werden soll -->

## Anforderungen

<!-- Liste der funktionalen Anforderungen -->
- 
- 
- 

## Akzeptanzkriterien

- [ ] Funktionale Anforderungen erfüllt
- [ ] Tests vorhanden (>80% Coverage)
- [ ] Dokumentation aktualisiert
- [ ] Keine Breaking Changes
- [ ] CodeQL Checks grün
- [ ] Performance-Impact akzeptabel

## Technische Details

<!-- Optional: Spezifische technische Vorgaben -->

**Betroffene Module:**
- 

**Architektur-Entscheidungen:**
- 

**Dependencies:**
- 

## Kontext

<!-- Hintergrund, Links, verwandte Issues -->

Verwandte Issues: #
Dokumentation: 

---

<!-- 
🤖 Dieser Workflow läuft vollautomatisch mit dem Copilot Ruleset:

1. ✅ Tasklist wird automatisch generiert aus dieser Beschreibung
2. ✅ Tasks werden sequentiell abgearbeitet
3. ✅ PRs werden automatisch erstellt und reviewed
4. ✅ Review Findings werden automatisch behoben
5. ✅ Automatischer Merge bei erfolgreichen Reviews
6. ✅ Issue wird geschlossen wenn alle Tasks fertig
7. ✅ Dokumentation wird automatisch aktualisiert

Workflow-Status wird in den Issue-Kommentaren gepostet.
Fortschritt kann in codex/CHECKPOINT.md und codex/TODO_QUEUE.md verfolgt werden.

Mehr Info: docs/COPILOT_RULESET_QUICKSTART.md
-->
