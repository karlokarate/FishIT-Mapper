# 📝 Verwendung der Issue-Dokumentation

Diese Dokumentation fasst alle **offenen Features aus dem Code-Review** zusammen und bietet zwei Formate für unterschiedliche Zwecke.

## 📄 Verfügbare Dokumente

### 1. `GITHUB_ISSUE_TEMPLATE.md` - Für GitHub Issues
**Zweck:** Kompakte Vorlage zum direkten Erstellen eines GitHub Issues

**Verwendung:**
1. Öffne [GITHUB_ISSUE_TEMPLATE.md](./GITHUB_ISSUE_TEMPLATE.md)
2. Kopiere den gesamten Inhalt
3. Gehe zu https://github.com/karlokarate/FishIT-Mapper/issues/new
4. Füge den Inhalt ein
5. Titel: "Vervollständigung offener Features aus Code-Review"
6. Labels hinzufügen: `enhancement`, `feature`, `priority: high`
7. Issue erstellen

**Inhalt:**
- ✅ Zusammenfassung aller Features
- ✅ Priorisierung (P1, P2, P3)
- ✅ Aufwandsschätzungen
- ✅ Roadmap mit Zeitrahmen
- ✅ Akzeptanzkriterien
- ✅ Verweis auf detaillierte Dokumentation

---

### 2. `OPEN_FEATURES_ISSUE.md` - Detaillierte Specs
**Zweck:** Vollständige Implementierungs-Spezifikation mit Code-Beispielen

**Verwendung:**
- Als Referenz während der Implementierung
- Für detaillierte Planung und Aufgabenverteilung
- Zum Verstehen der technischen Details

**Inhalt:**
- ✅ Ausführliche Problembeschreibungen
- ✅ Konkrete Lösungsvorschläge mit Code
- ✅ Betroffene Dateien + Zeilennummern
- ✅ Vollständige Akzeptanzkriterien pro Feature
- ✅ Implementierungshinweise
- ✅ Alternative Ansätze (z.B. für Graph-Visualisierung)

---

## 🎯 Feature-Übersicht

### Priorität 1: Quick Wins (~10 Stunden)
1. **WebChromeClient für Console-Logs** (1-2h)
2. **Chains-Tab im UI** (3-4h)
3. **Filter-Dropdown für NodeKind/EdgeKind** (2-3h)

### Priorität 2: MVP-Erweiterungen (~28 Stunden)
4. **Canvas-basierte Graph-Visualisierung** (10-15h) 🔥 WICHTIGSTE FEATURE
5. **JavaScript-Bridge für User-Actions** (6-8h)
6. **Import-Funktion für ZIP-Bundles** (6-8h)

### Priorität 3: Nice-to-Have (~30 Stunden)
7. **Hub-Detection Algorithmus** (8-10h)
8. **Form-Submit-Tracking** (4-6h)
9. **Redirect-Detection** (2-4h)
10. **Graph-Diff-Funktion** (8-10h)
11. **Node-Tagging & Filter** (4-5h)

**Gesamtaufwand:** ~68 Stunden über 2-3 Wochen

---

## 📊 Status-Übersicht

Aus dem Code-Review:

### ✅ Vollständig implementiert (100%)
- KotlinPoet Generator
- Projekt-Verwaltung
- WebView Recording
- Session-Speicherung
- MappingEngine
- Graph-Persistierung
- Export-Bundle
- Share-Funktion
- Multi-Tab UI

### ⚠️ Teilweise implementiert (10-60%)
- **Graph-Visualisierung** (60%) - nur Text-Liste
- **Chains-Funktionalität** (30%) - Daten vorhanden, kein UI
- **Console Messages** (10%) - Event-Typ existiert
- **User Actions** (10%) - Event-Typ existiert

### ❌ Nicht implementiert (0%)
- Graph-Filter & Tags
- Graph-Diff
- Import-Funktion
- Hub-Detection
- Form-Submit-Tracking
- Redirect-Detection

**Aktuelle MVP-Erfüllung:** ~80%  
**Ziel nach P1+P2:** ~95%

---

## 🚀 Empfohlene Vorgehensweise

### Option A: Schrittweise Umsetzung
```
Woche 1: Quick Wins (P1)
├─ Tag 1-2: Console-Logs + Filter
└─ Tag 3-5: Chains-Tab

Woche 2: Kern-Features (P2)
├─ Tag 1-3: Graph-Visualisierung
└─ Tag 4-5: User-Actions oder Import

Woche 3: Polish (P2 + P3)
├─ Tag 1-2: Restliche P2-Features
└─ Tag 3-5: Ausgewählte P3-Features
```

### Option B: Mit GitHub Actions Orchestrator
```bash
# 1. Issue erstellen mit GITHUB_ISSUE_TEMPLATE.md Inhalt

# 2. Labels hinzufügen:
- orchestrator:enabled
- orchestrator:run
- priority: high

# 3. Orchestrator übernimmt automatisch:
- ✅ Branch erstellen
- ✅ Features schrittweise implementieren
- ✅ Code-Reviews anfordern
- ✅ Iterationen durchführen
- ✅ Nach Approval mergen
```

---

## 📚 Weiterführende Ressourcen

- **Code-Review Basis:** [IMPLEMENTATION_SUMMARY.md](../IMPLEMENTATION_SUMMARY.md)
- **Projekt-Architektur:** [ARCHITECTURE.md](./ARCHITECTURE.md)
- **Roadmap:** [ROADMAP.md](./ROADMAP.md)
- **Contract Schema:** [contract.schema.json](../schema/contract.schema.json)

### Wichtige Code-Dateien

**UI Layer:**
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/GraphScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ProjectHomeScreen.kt`

**Engine:**
- `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/MappingEngine.kt`

**Contract Generation:**
- `tools/codegen-contract/src/main/kotlin/dev/fishit/mapper/codegen/ContractGenerator.kt`

---

## ❓ Häufige Fragen

### Q: Welches Feature soll ich zuerst implementieren?
**A:** Starte mit **P1.1 (Console-Logs)** - das ist ein "Good First Issue" mit hohem Impact und niedrigem Aufwand.

### Q: Muss ich alle Features implementieren?
**A:** Nein! Für einen vollständigen MVP sind nur **P1 + P2** erforderlich (~38 Stunden). P3-Features sind optional.

### Q: Kann ich die Reihenfolge ändern?
**A:** Ja, aber beachte Abhängigkeiten:
- Graph-Visualisierung (2.1) sollte vor Hub-Detection (3.1) kommen
- JavaScript-Bridge (2.2) sollte vor Form-Tracking (3.2) kommen

### Q: Wo finde ich Code-Beispiele?
**A:** Alle Code-Beispiele sind in `OPEN_FEATURES_ISSUE.md` mit konkreten Implementierungsvorschlägen.

### Q: Wie tracke ich meinen Fortschritt?
**A:** Nutze das GitHub Issue (aus GITHUB_ISSUE_TEMPLATE.md) mit Checklisten oder den Orchestrator für automatisches Tracking.

---

## 🤝 Beitragen

**Pull Requests willkommen!**

1. Fork das Repository
2. Wähle ein Feature aus der Liste
3. Implementiere gemäß den Specs in `OPEN_FEATURES_ISSUE.md`
4. Erstelle PR mit Verweis auf das entsprechende Issue
5. Code-Review abwarten

**Oder nutze den Orchestrator:**
1. Issue erstellen
2. Labels setzen: `orchestrator:enabled`, `orchestrator:run`
3. Lehne dich zurück - der Orchestrator arbeitet automatisch 🚀

---

## 📞 Support

Bei Fragen oder Problemen:
- 💬 Kommentiere im entsprechenden GitHub Issue
- 📧 Kontaktiere den Maintainer
- 📖 Lies die [vollständige Dokumentation](./OPEN_FEATURES_ISSUE.md)
