# Priority 3 Features - Implementation Guide

## Übersicht

Diese Dokumentation beschreibt die in Priority 3 implementierten Features des FishIT-Mapper Projekts. Alle Features sind optional und erweitern die Kernfunktionalität des MVP.

## 🎯 Implementierte Features

### 3.1 Hub-Detection Algorithmus ✅

**Zweck:** Automatische Erkennung wichtiger Nodes im Graph basierend auf verschiedenen Metriken.

**Implementierung:**
- **Datei:** `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/HubDetector.kt`
- **Metriken:**
  - InDegree: Anzahl eingehender Edges
  - OutDegree: Anzahl ausgehender Edges
  - Betweenness Centrality: Wie oft der Node auf kürzesten Pfaden liegt
  - Hub Score: Kombinierte Metrik basierend auf allen Faktoren

**Verwendung:**
```kotlin
// Manuell Hub-Detection durchführen
val metrics = HubDetector.analyzeGraph(graph)
val hubScore = metrics[nodeId]?.hubScore

// Automatisches Tagging von Hubs
val taggedGraph = HubDetector.tagHubs(graph, threshold = 5.0)
```

**Tags:**
- `hub:homepage` - Hoher OutDegree, niedriger InDegree
- `hub:navigation` - Hoher InDegree und OutDegree
- `hub:important` - Hoher InDegree
- `hub` - Generischer Hub

**UI Integration:**
- ViewModel-Methode: `ProjectViewModel.applyHubDetection(threshold: Double)`
- Kann manuell über UI-Button getriggert werden (TODO: Button hinzufügen)

### 3.2 Form-Submit-Tracking (Enhanced) ✅

**Zweck:** Erweiterte Analyse von Formular-Submissions mit Field-Typ-Erkennung.

**Implementierung:**
- **Datei:** `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/FormAnalyzer.kt`
- **Features:**
  - Field-Typ-Inferenz (EMAIL, PASSWORD, TEXT, NUMBER, etc.)
  - Form-Pattern-Detection (LOGIN, REGISTRATION, SEARCH, etc.)
  - Basis für erweiterte Validierungs-Tracking

**Field Types:**
```kotlin
enum class FieldType {
    TEXT, EMAIL, PASSWORD, NUMBER, DATE,
    CHECKBOX, RADIO, SELECT, TEXTAREA,
    FILE, HIDDEN, UNKNOWN
}
```

**Form Patterns:**
```kotlin
enum class FormPattern {
    LOGIN, REGISTRATION, SEARCH,
    COMMENT, UPLOAD, CHECKOUT,
    CONTACT, GENERIC
}
```

**Verwendung:**
```kotlin
// Parse Form-Submit-Event
val formInfo = FormAnalyzer.parseFormSubmit(event)

// Analyze Fields
val fields = FormAnalyzer.analyzeFormFields(fieldsData)

// Detect Pattern
val pattern = FormAnalyzer.detectFormPattern(fields)
```

### 3.3 Redirect-Detection (Improved) ✅

**Zweck:** Verbesserte Erkennung von Redirects mit intelligenten Heuristiken.

**Implementierung:**
- **Datei:** `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/RedirectDetector.kt`
- **Features:**
  - Timing-basierte Detection (< 800ms)
  - Same-domain Redirect-Erkennung
  - Redirect-Chain-Detection

**Heuristiken:**
- **Immediate Redirect:** < 300ms zwischen Navigationen
- **Fast Redirect:** < 800ms zwischen Navigationen
- **Same-domain Redirect:** Beide URLs auf gleicher Domain

**Verwendung:**
```kotlin
// Analyze Navigation Sequence
val redirects = RedirectDetector.analyzeNavigationSequence(events)

// Detect Redirect Chains
val chains = RedirectDetector.detectRedirectChains(graph)

// Längste Chain finden
val longestChain = chains.maxByOrNull { it.length }
```

**Data Models:**
```kotlin
data class RedirectInfo(
    val fromEvent: NavigationEvent,
    val toEvent: NavigationEvent,
    val timeDiffMs: Long,
    val reason: String
)

data class RedirectChain(
    val nodes: List<NodeId>,
    val length: Int
)
```

### 3.4 Graph-Diff-Funktion ✅

**Zweck:** Vergleich zwischen zwei Graph-Snapshots mit detaillierter Änderungs-Analyse.

**Implementierung:**
- **Engine:** `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/GraphDiff.kt`
- **UI:** `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/GraphDiffScreen.kt`

**Features:**
- Added/Removed/Modified Nodes
- Added/Removed/Modified Edges
- Detaillierte Change-Beschreibungen

**Verwendung:**
```kotlin
// Compare two graphs
val diffResult = GraphDiff.compare(beforeGraph, afterGraph)

// Check for changes
if (diffResult.hasChanges) {
    println("Added nodes: ${diffResult.addedNodes.size}")
    println("Removed nodes: ${diffResult.removedNodes.size}")
    println("Modified nodes: ${diffResult.modifiedNodes.size}")
}

// Inspect modifications
diffResult.modifiedNodes.forEach { mod ->
    println("Node ${mod.nodeId} changed:")
    mod.changes.forEach { change ->
        println("  - $change")
    }
}
```

**UI Features:**
- Session-Dropdown zur Auswahl von Before/After
- Farbcodierte Anzeige (Grün=Added, Rot=Removed, Orange=Modified)
- Detaillierte Change-Liste

**Navigation:**
- TODO: Integration in ProjectHomeScreen (neuer Tab oder Button)

### 3.5 Node-Tagging & Filter ✅

**Zweck:** Manuelle Kategorisierung von Nodes mit Tag-basierter Filterung.

**Implementierung:**
- **Dialog:** `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/NodeTaggingDialog.kt`
- **Integration:** `GraphScreen.kt` - Tag-Filter und Tagging-Button

**Features:**
- Tag-Management-Dialog mit Add/Remove
- Quick-Tag-Vorschläge (important, homepage, auth, etc.)
- Farbcodierte Tag-Anzeige
- Tag-basierte Filterung im GraphScreen

**Tag Colors:**
- `hub:*` → Blau
- `important` → Rot
- `auth` → Lila
- `api` → Grün
- `form` → Orange
- Standard → Grau

**Verwendung:**
```kotlin
// Im GraphScreen: "Tag" Button neben jedem Node
// → Öffnet NodeTaggingDialog
// → Tags werden über ViewModel gespeichert

// Filtern nach Tags
// → Tag-Dropdown in GraphScreen
// → Zeigt nur Nodes mit ausgewähltem Tag
```

**Quick Tags:**
- important
- homepage
- navigation
- auth
- api
- form
- admin
- public
- private

## 🔧 Integration in bestehende UI

### GraphScreen Updates

**Neue Features:**
1. Tag-Filter-Dropdown (erscheint wenn Tags existieren)
2. "Tag" Button bei jedem Node in der Liste
3. Tag-Anzeige unter Node-URL
4. Erweiterte Suche (inkludiert Tags)

**Callback:**
```kotlin
GraphScreen(
    graph = state.graph,
    onNodeTagsChanged = { nodeId, tags ->
        viewModel.updateNodeTags(nodeId, tags)
    }
)
```

### ProjectViewModel Updates

**Neue Methoden:**
```kotlin
// Tag-Update
fun updateNodeTags(nodeId: NodeId, tags: List<String>)

// Hub-Detection
fun applyHubDetection(threshold: Double = 5.0)
```

## 📊 Performance-Überlegungen

### Hub Detection
- **Komplexität:** O(V²) für Betweenness Centrality
- **Empfehlung:** Nur bei < 500 Nodes on-demand ausführen
- **Optimierung:** Caching von Metriken

### Graph Diff
- **Komplexität:** O(V + E)
- **Performant** auch bei großen Graphen
- **Memory:** Hält beide Graphen im Speicher

### Tag Filtering
- **Komplexität:** O(V)
- **Performant** bei allen Graph-Größen
- **UI:** Reactiv über Compose State

## 🧪 Testing

### Unit Tests (TODO)

```kotlin
// HubDetector Tests
@Test
fun `hub detection identifies high degree nodes`()
@Test
fun `betweenness centrality calculation`()
@Test
fun `hub tagging applies correct tags`()

// GraphDiff Tests
@Test
fun `diff detects added nodes`()
@Test
fun `diff detects removed nodes`()
@Test
fun `diff detects modified nodes`()

// RedirectDetector Tests
@Test
fun `detects fast redirects`()
@Test
fun `identifies redirect chains`()
@Test
fun `same domain detection works`()

// FormAnalyzer Tests
@Test
fun `field type inference`()
@Test
fun `form pattern detection`()
```

### Integration Tests (TODO)

```kotlin
@Test
fun `tagging persists across sessions`()
@Test
fun `hub detection can be applied multiple times`()
@Test
fun `graph diff shows correct changes`()
```

## 🚀 Zukünftige Erweiterungen

### Hub Detection
- [ ] PageRank-Algorithmus hinzufügen
- [ ] Clustering-Koeffizient berechnen
- [ ] Visualisierung von Metriken in UI
- [ ] Historisches Tracking von Hub-Changes

### Form Tracking
- [ ] Capture actual form field values (mit Privacy-Option)
- [ ] Validation-Error-Tracking
- [ ] Form-completion-Rate berechnen
- [ ] A/B-Testing-Support

### Redirect Detection
- [ ] HTTP Status Code Tracking (301, 302, etc.)
- [ ] Redirect-Performance-Metriken
- [ ] Redirect-Loop-Detection
- [ ] Visualisierung von Redirect-Chains

### Graph Diff
- [ ] Visualisierung von Diffs im Graph-Canvas
- [ ] Temporal Graph Analysis (Zeit-basiert)
- [ ] Diff-Export als Report
- [ ] Automated Regression Detection

### Node Tagging
- [ ] Tag-Hierarchien (z.B. auth/login, auth/signup)
- [ ] Tag-basierte Gruppenoperationen
- [ ] Auto-Tagging-Regeln (Pattern-basiert)
- [ ] Tag-Sharing zwischen Projekten

## 📝 Code-Beispiele

### Beispiel 1: Hub-Detection in Session-Processing

```kotlin
// Nach dem Anwenden einer Session
fun onSessionComplete(sessionId: SessionId) {
    viewModelScope.launch {
        val graph = store.loadGraph(projectId)
        
        // Apply hub detection
        val taggedGraph = HubDetector.tagHubs(graph)
        store.saveGraph(projectId, taggedGraph)
        
        refresh()
    }
}
```

### Beispiel 2: Diff zwischen zwei Sessions

```kotlin
// Im SessionsScreen
fun compareWithPrevious(currentSessionId: SessionId) {
    val current = sessions.find { it.id == currentSessionId }
    val previous = sessions.getOrNull(sessions.indexOf(current) + 1)
    
    if (current != null && previous != null) {
        val currentGraph = buildGraph(current)
        val previousGraph = buildGraph(previous)
        
        val diff = GraphDiff.compare(previousGraph, currentGraph)
        showDiffDialog(diff)
    }
}
```

### Beispiel 3: Automatisches Tagging basierend auf URL-Patterns

```kotlin
fun autoTagNodes(graph: MapGraph): MapGraph {
    val updatedNodes = graph.nodes.map { node ->
        val autoTags = mutableListOf<String>()
        
        when {
            node.url.contains("/admin") -> autoTags.add("admin")
            node.url.contains("/api/") -> autoTags.add("api")
            node.url.contains("/login") -> autoTags.add("auth")
            node.url.endsWith("/") && node.url.count { it == '/' } == 3 -> {
                autoTags.add("homepage")
            }
        }
        
        if (autoTags.isNotEmpty()) {
            node.copy(tags = (node.tags + autoTags).distinct())
        } else {
            node
        }
    }
    
    return graph.copy(nodes = updatedNodes)
}
```

## ✅ Akzeptanzkriterien

- [x] Funktionale Anforderungen erfüllt
- [ ] Tests vorhanden (>80% Coverage) - TODO
- [x] Dokumentation aktualisiert
- [x] Keine Breaking Changes
- [x] CodeQL Checks grün
- [x] Performance-Impact akzeptabel
- [x] Code kompiliert fehlerfrei
- [x] Minimal invasive Änderungen
- [x] Clean Architecture befolgt

## 🔗 Verwandte Dateien

### Engine (shared/engine)
- `HubDetector.kt`
- `GraphDiff.kt`
- `RedirectDetector.kt`
- `FormAnalyzer.kt`
- `MappingEngine.kt` (nicht geändert, kann Features nutzen)

### UI (androidApp)
- `NodeTaggingDialog.kt`
- `GraphDiffScreen.kt`
- `GraphScreen.kt` (erweitert)
- `ProjectViewModel.kt` (erweitert)
- `ProjectHomeScreen.kt` (erweitert)

### Contract (keine Änderungen nötig)
- `Graph.kt` - MapNode hat bereits `tags` Feld
- `Recorder.kt` - Events für Form/Redirect-Tracking

## 📚 Weitere Ressourcen

- [Issue #9](https://github.com/karlokarate/FishIT-Mapper/issues/9) - Original Feature-Request
- [FEATURE_STATUS.md](../FEATURE_STATUS.md) - Gesamtstatus aller Features
- [README.md](../README.md) - Projekt-Übersicht
