# 🚀 Vervollständigung offener Features

## 📋 Zusammenfassung

Basierend auf dem Code-Review sind **80% der MVP-Features** vollständig implementiert. Dieses Issue fasst alle offenen Punkte zusammen und priorisiert sie nach Wichtigkeit.

**Vollständige Dokumentation:** Siehe [OPEN_FEATURES_ISSUE.md](./OPEN_FEATURES_ISSUE.md) für detaillierte Implementierungshinweise.

---

## 🎯 Priorität 1: Quick Wins (1-2 Tage)

### 1.1 WebChromeClient für Console-Logs ✨
- **Status:** ❌ 0% | **Aufwand:** 🟢 1-2h | **Impact:** 🔥 Hoch
- **Problem:** Console-Logs werden nicht erfasst
- **Lösung:** `WebChromeClient` mit `onConsoleMessage()` in `BrowserScreen.kt` hinzufügen
- **Dateien:** `BrowserScreen.kt` (Zeile 106-154)

### 1.2 Chains-Tab im UI ✨
- **Status:** ⚠️ 30% | **Aufwand:** 🟡 3-4h | **Impact:** 🔥 Mittel-Hoch
- **Problem:** `chains.json` wird gespeichert, aber kein UI vorhanden
- **Lösung:** Neuen Tab + `ChainsScreen.kt` erstellen
- **Dateien:** `ProjectHomeScreen.kt`, NEU: `ChainsScreen.kt`

### 1.3 Filter-Dropdown für NodeKind/EdgeKind ✨
- **Status:** ❌ 0% | **Aufwand:** 🟡 2-3h | **Impact:** 🔥 Mittel
- **Problem:** Nur Textsuche, keine Typ-Filter
- **Lösung:** Dropdown-Menüs für NodeKind/EdgeKind in `GraphScreen.kt`
- **Dateien:** `GraphScreen.kt`

---

## 🎯 Priorität 2: MVP-Erweiterungen (1 Woche)

### 2.1 Canvas-basierte Graph-Visualisierung 🔥🔥🔥
- **Status:** ⚠️ 60% | **Aufwand:** 🔴 10-15h | **Impact:** 🔥🔥🔥 SEHR HOCH
- **Problem:** Nur Text-Liste, keine visuelle Darstellung
- **Lösung:** Jetpack Compose Canvas + Force-directed Layout
- **Dateien:** `GraphScreen.kt` (komplett überarbeiten), NEU: `GraphVisualization.kt`, `ForceLayout.kt`

### 2.2 JavaScript-Bridge für User-Actions 🔥🔥
- **Status:** ⚠️ 10% | **Aufwand:** 🔴 6-8h | **Impact:** 🔥🔥 Hoch
- **Problem:** Klicks, Scrolls werden nicht erfasst
- **Lösung:** JS-Bridge mit `@JavascriptInterface` + Tracking-Script
- **Dateien:** `BrowserScreen.kt`, NEU: `JavaScriptBridge.kt`

### 2.3 Import-Funktion für ZIP-Bundles 🔥
- **Status:** ❌ 0% | **Aufwand:** 🔴 6-8h | **Impact:** 🔥 Mittel-Hoch
- **Problem:** Keine Import-Möglichkeit für exportierte Bundles
- **Lösung:** File Picker + ZIP-Entpacken + Graph-Merge-Strategie
- **Dateien:** `ProjectsScreen.kt`, NEU: `ImportManager.kt`

---

## 🎯 Priorität 3: Nice-to-Have (1 Woche)

### 3.1 Hub-Detection Algorithmus
- **Aufwand:** 🔴 8-10h | **Impact:** 🔥 Mittel
- Automatische Erkennung wichtiger Nodes (Homepage, Navigation)

### 3.2 Form-Submit-Tracking
- **Aufwand:** 🔴 4-6h | **Impact:** 🔥 Mittel
- Spezielle Erfassung von Formular-Submits

### 3.3 Redirect-Detection
- **Aufwand:** 🟡 2-4h | **Impact:** 🔥 Niedrig-Mittel
- Korrekte Markierung von Redirects (301, 302)

### 3.4 Graph-Diff-Funktion
- **Aufwand:** 🔴 8-10h | **Impact:** 🔥 Niedrig-Mittel
- Vergleich zwischen zwei Sessions

### 3.5 Node-Tagging & Filter
- **Aufwand:** 🟡 4-5h | **Impact:** 🔥 Niedrig-Mittel
- Manuelle Kategorisierung von Nodes

---

## 📊 Roadmap & Aufwand

| Phase | Features | Aufwand | Zeitrahmen |
|-------|----------|---------|------------|
| **Phase 1: Quick Wins** | 3 Features | ~10 Std | 1-2 Tage |
| **Phase 2: MVP-Erweiterung** | 3 Features | ~28 Std | 1 Woche |
| **Phase 3: Nice-to-Have** | 5 Features | ~30 Std | 1 Woche |
| **GESAMT** | **11 Features** | **~68 Std** | **2-3 Wochen** |

---

## ✅ Akzeptanzkriterien (Gesamt)

**MVP als vollständig definiert, wenn:**
- ✅ Alle P1-Features implementiert
- ✅ Graph-Visualisierung funktioniert (P2.1)
- ✅ User-Action-Tracking funktioniert (P2.2)
- ✅ Import-Funktion funktioniert (P2.3)
- ✅ Mind. 3 von 5 P3-Features

**Qualität:**
- ✅ Keine neuen Crashes
- ✅ Performance < 1s für Graph-Render (100 Nodes)
- ✅ Code-Review bestanden

---

## 🏷️ Labels
`enhancement` `feature` `priority: high` `good first issue` `help wanted`

---

## 📚 Ressourcen
- **Detaillierte Specs:** [OPEN_FEATURES_ISSUE.md](./OPEN_FEATURES_ISSUE.md)
- **Code-Review:** [IMPLEMENTATION_SUMMARY.md](../IMPLEMENTATION_SUMMARY.md)
- **Contract Schema:** [contract.schema.json](../schema/contract.schema.json)
