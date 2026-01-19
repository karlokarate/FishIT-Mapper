# Orchestrator TODO Queue

Diese Datei verwaltet die Task-Warteschlange für den GitHub Actions Orchestrator.

## Issue #48: Browser-Tab Traffic Capture + Click→Flow Website Mapping

### Task 3: MitmProxyServer Robust for Real Websites (Closed - Architecture Changed)

- [x] 3.1 Forward request bodies (POST/PUT)
- [x] 3.2 Keep `followRedirects(false)` for observable 30x responses
- [x] 3.3 Byte-safe stream handling - **N/A: MITM Proxy deprecated, using TrafficInterceptWebView**
- [x] 3.4 Keep-alive loops for HTTP and HTTPS/CONNECT - **N/A: MITM Proxy deprecated**
- [x] 3.5 Capture text-like response bodies only - **N/A: MITM Proxy deprecated**

> **Note**: MitmProxyServer wurde deprecated. Traffic-Capture erfolgt nun über:
> - `TrafficInterceptWebView` mit JavaScript-Hooks
> - Externe Tools wie HttpCanary für HTTPS

### Task 6: WebsiteMap per Session (Completed ✅)

- [x] 6.1 Define action window (until next action or 10s max)
- [x] 6.2 Collect ResourceRequestEvent + ResourceResponseEvent via requestId
- [x] 6.3 Produce WebsiteMap structure
- [x] 6.4 Persist to disk under `projects/<id>/maps/<sessionId>.json`
- [x] 6.5 Include in export bundles - **Implemented 2026-01-19**

---

## Completed Tasks Summary

| Task                | Status   | Note                                            |
| ------------------- | -------- | ----------------------------------------------- |
| 3.x MitmProxyServer | ✅ Closed | Architecture changed to TrafficInterceptWebView |
| 6.x WebsiteMap      | ✅ Done   | WebsiteMap now included in ZIP exports          |

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
