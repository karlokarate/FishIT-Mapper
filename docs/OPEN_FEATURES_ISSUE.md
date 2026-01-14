# 🚀 FishIT-Mapper: Vervollständigung offener Features

## 📋 Zusammenfassung

Basierend auf dem aktuellen Code-Review sind **80% der MVP-Features** vollständig implementiert, aber wichtige Visualisierungs- und Tracking-Features fehlen noch. Dieses Issue fasst alle offenen Punkte zusammen und priorisiert sie nach Wichtigkeit.

## 🎯 Priorität 1: Schnelle Verbesserungen (Quick Wins)

### 1.1 WebChromeClient für Console-Logs hinzufügen
**Status:** ❌ 0% (Event-Typ existiert, aber keine Erfassung)  
**Aufwand:** 🟢 Niedrig (1-2 Stunden)  
**Auswirkung:** 🔥 Hoch (wichtig für Debugging)

#### Problem
`BrowserScreen.kt` nutzt nur `WebViewClient`, erfasst aber keine Console-Logs.

#### Lösung
```kotlin
// In BrowserScreen.kt nach Zeile 154 hinzufügen:
webChromeClient = object : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (!recordingState) return super.onConsoleMessage(consoleMessage)
        val msg = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
        
        val level = when (msg.messageLevel()) {
            ConsoleMessage.MessageLevel.LOG -> ConsoleLevel.Log
            ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.Warn
            ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.Error
            else -> ConsoleLevel.Info
        }
        
        val event = ConsoleMessageEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            level = level,
            message = msg.message(),
            sourceUrl = msg.sourceId(),
            lineNumber = msg.lineNumber()
        )
        
        mainHandler.post { onRecorderEventState(event) }
        return true
    }
}
```

#### Betroffene Dateien
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt` (Zeile 106-154)

#### Akzeptanzkriterien
- ✅ Console-Logs (log, info, warn, error) werden erfasst
- ✅ Events erscheinen in der Session-Detail-Ansicht
- ✅ Source URL und Zeilennummer werden mitprotokolliert

---

### 1.2 Chains-Tab im UI erstellen
**Status:** ⚠️ 30% (Datenstrukturen vorhanden, aber kein UI)  
**Aufwand:** 🟡 Mittel (3-4 Stunden)  
**Auswirkung:** 🔥 Mittel-Hoch (bessere Session-Übersicht)

#### Problem
`chains.json` wird gespeichert, aber es gibt keinen Tab zur Visualisierung.

#### Lösung
1. **Neues Tab in `ProjectHomeScreen.kt`:**
```kotlin
private enum class ProjectTab(val label: String) {
    Browser("Browser"),
    Graph("Graph"),
    Sessions("Sessions"),
    Chains("Chains")  // NEU
}
```

2. **Neue Datei: `ChainsScreen.kt`:**
```kotlin
@Composable
fun ChainsScreen(chains: List<RecordChain>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(chains) { chain ->
            ChainCard(chain)
        }
    }
}

@Composable
private fun ChainCard(chain: RecordChain) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Chain ID: ${chain.id.value}", fontWeight = Bold)
            Text("Session: ${chain.sessionId.value}")
            Text("Points: ${chain.points.size}")
            
            // Points anzeigen
            chain.points.forEach { point ->
                Row {
                    Text("${point.at} → ${point.url}")
                }
            }
        }
    }
}
```

3. **ViewModel erweitern:**
```kotlin
// In ProjectViewModel.kt State erweitern:
data class ProjectState(
    // ... bestehend
    val chains: List<RecordChain> = emptyList()
)
```

#### Betroffene Dateien
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ProjectHomeScreen.kt` (Zeile 31-35, 75-95)
- NEU: `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ChainsScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ProjectViewModel.kt`

#### Akzeptanzkriterien
- ✅ Chains-Tab ist sichtbar und auswählbar
- ✅ Liste aller Chains wird angezeigt
- ✅ Details pro Chain (ID, Session, Points) sind sichtbar
- ✅ Chains werden aus `chains.json` geladen

---

### 1.3 Filter-Dropdown für NodeKind/EdgeKind
**Status:** ❌ 0%  
**Aufwand:** 🟡 Mittel (2-3 Stunden)  
**Auswirkung:** 🔥 Mittel (bessere Filterung)

#### Problem
`GraphScreen.kt` hat nur Textsuche, keine Filter nach Typ.

#### Lösung
```kotlin
@Composable
fun GraphScreen(graph: MapGraph) {
    var query by remember { mutableStateOf("") }
    var selectedNodeKind by remember { mutableStateOf<NodeKind?>(null) }
    var selectedEdgeKind by remember { mutableStateOf<EdgeKind?>(null) }
    
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Textsuche (bestehend)
        OutlinedTextField(...)
        
        // NEU: Filter-Dropdowns
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Node Kind Filter
            var nodeKindExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { nodeKindExpanded = true }) {
                    Text(selectedNodeKind?.name ?: "All Nodes")
                }
                DropdownMenu(
                    expanded = nodeKindExpanded,
                    onDismissRequest = { nodeKindExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All") },
                        onClick = {
                            selectedNodeKind = null
                            nodeKindExpanded = false
                        }
                    )
                    NodeKind.values().forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.name) },
                            onClick = {
                                selectedNodeKind = kind
                                nodeKindExpanded = false
                            }
                        )
                    }
                }
            }
            
            // Edge Kind Filter (analog)
        }
        
        // Gefilterte Nodes
        val filteredNodes = graph.nodes
            .filter { selectedNodeKind == null || it.kind == selectedNodeKind }
            .filter { query.isBlank() || it.url.contains(query, ignoreCase = true) }
        
        LazyColumn { ... }
    }
}
```

#### Betroffene Dateien
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/GraphScreen.kt` (Zeile 26-82)

#### Akzeptanzkriterien
- ✅ Dropdown für NodeKind-Filter
- ✅ Dropdown für EdgeKind-Filter
- ✅ Filter kombinierbar mit Textsuche
- ✅ "All" Option zum Zurücksetzen

---

## 🎯 Priorität 2: Wichtige Features (MVP-Erweiterungen)

### 2.1 Canvas-basierte Graph-Visualisierung
**Status:** ⚠️ 60% (nur Text-Liste, keine visuelle Darstellung)  
**Aufwand:** 🔴 Hoch (10-15 Stunden)  
**Auswirkung:** 🔥🔥🔥 Sehr Hoch (Haupt-Feature!)

#### Problem
`GraphScreen.kt` zeigt nur eine Text-Liste, keine visuelle Graph-Darstellung mit Force Layout.

#### Lösungsoptionen

**Option A: Jetpack Compose Canvas (empfohlen)**
```kotlin
@Composable
fun GraphVisualization(graph: MapGraph) {
    var nodePositions by remember { mutableStateOf<Map<NodeId, Offset>>(emptyMap()) }
    
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Nodes zeichnen
        graph.nodes.forEach { node ->
            val pos = nodePositions[node.id] ?: Offset.Zero
            drawCircle(
                color = getNodeColor(node.kind),
                radius = 20f,
                center = pos
            )
            drawContext.canvas.nativeCanvas.drawText(
                node.title ?: "Node",
                pos.x,
                pos.y - 30f,
                Paint().apply { textSize = 24f }
            )
        }
        
        // Edges zeichnen
        graph.edges.forEach { edge ->
            val from = nodePositions[edge.from] ?: return@forEach
            val to = nodePositions[edge.to] ?: return@forEach
            drawLine(
                color = Color.Gray,
                start = from,
                end = to,
                strokeWidth = 2f
            )
        }
    }
    
    // Force-directed Layout
    LaunchedEffect(graph) {
        while (true) {
            delay(16) // ~60 FPS
            nodePositions = calculateForceLayout(nodePositions, graph)
        }
    }
}

private fun calculateForceLayout(
    positions: Map<NodeId, Offset>,
    graph: MapGraph
): Map<NodeId, Offset> {
    // Implementierung: Force-directed Layout Algorithmus
    // - Abstoßung zwischen allen Nodes
    // - Anziehung zwischen verbundenen Nodes
    // - Dämpfung für Stabilität
}
```

**Option B: Integration einer Bibliothek**
- **Graphviz-Android:** Native Graphviz-Integration (sehr leistungsstark)
- **JGraphT:** Java Graph Library mit Visualisierung
- **vis.js WebView:** JavaScript-Library über WebView einbinden

#### Betroffene Dateien
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/GraphScreen.kt` (komplett überarbeiten)
- NEU: `androidApp/src/main/java/dev/fishit/mapper/android/ui/graph/GraphVisualization.kt`
- NEU: `androidApp/src/main/java/dev/fishit/mapper/android/ui/graph/ForceLayout.kt`

#### Akzeptanzkriterien
- ✅ Visuelle Darstellung von Nodes als Kreise/Rechtecke
- ✅ Edges als Linien/Pfeile zwischen Nodes
- ✅ Force-directed Layout für automatische Positionierung
- ✅ Zoom & Pan Funktionalität
- ✅ Klick auf Node zeigt Details
- ✅ Farbcodierung nach NodeKind
- ✅ Performant auch bei 100+ Nodes

---

### 2.2 JavaScript-Bridge für User-Action-Tracking
**Status:** ⚠️ 10% (Event-Typ existiert, keine Erfassung)  
**Aufwand:** 🔴 Mittel-Hoch (6-8 Stunden)  
**Auswirkung:** 🔥🔥 Hoch (wichtig für User-Flow)

#### Problem
Klicks, Scrolls und andere User-Aktionen werden nicht erfasst.

#### Lösung
1. **JavaScript-Bridge erstellen:**
```kotlin
// In BrowserScreen.kt
class JavaScriptBridge(
    private val onUserAction: (UserActionEvent) -> Unit
) {
    @JavascriptInterface
    fun recordClick(targetSelector: String, x: Int, y: Int) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            actionType = UserActionType.Click,
            targetSelector = targetSelector,
            targetUrl = null,
            metadata = mapOf("x" to x, "y" to y)
        )
        onUserAction(event)
    }
    
    @JavascriptInterface
    fun recordScroll(scrollY: Int) {
        val event = UserActionEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            actionType = UserActionType.Scroll,
            targetSelector = null,
            targetUrl = null,
            metadata = mapOf("scrollY" to scrollY)
        )
        onUserAction(event)
    }
}
```

2. **JavaScript injizieren:**
```kotlin
webView.apply {
    addJavascriptInterface(
        JavaScriptBridge { event -> mainHandler.post { onRecorderEventState(event) } },
        "FishITMapper"
    )
    
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // Tracking-Script injizieren
            view?.evaluateJavascript("""
                (function() {
                    document.addEventListener('click', function(e) {
                        const selector = getSelector(e.target);
                        FishITMapper.recordClick(selector, e.clientX, e.clientY);
                    });
                    
                    let scrollTimeout;
                    window.addEventListener('scroll', function() {
                        clearTimeout(scrollTimeout);
                        scrollTimeout = setTimeout(() => {
                            FishITMapper.recordScroll(window.scrollY);
                        }, 100);
                    });
                    
                    function getSelector(element) {
                        if (element.id) return '#' + element.id;
                        if (element.className) return '.' + element.className.split(' ')[0];
                        return element.tagName.toLowerCase();
                    }
                })();
            """.trimIndent(), null)
        }
    }
}
```

#### Betroffene Dateien
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt`
- NEU: `androidApp/src/main/java/dev/fishit/mapper/android/webview/JavaScriptBridge.kt`
- NEU: `androidApp/src/main/java/dev/fishit/mapper/android/webview/tracking.js`

#### Akzeptanzkriterien
- ✅ Klicks werden mit Target-Selector erfasst
- ✅ Scroll-Events werden erfasst
- ✅ Form-Submits werden erkannt
- ✅ Events erscheinen in Session-Details
- ✅ Keine Performance-Probleme durch Tracking

---

### 2.3 Import-Funktion für ZIP-Bundles
**Status:** ❌ 0%  
**Aufwand:** 🔴 Mittel-Hoch (6-8 Stunden)  
**Auswirkung:** 🔥 Mittel-Hoch (Datenaustausch)

#### Problem
Exportierte ZIP-Bundles können nicht wieder importiert werden.

#### Lösung
1. **Import-Button in ProjectsScreen:**
```kotlin
// In ProjectsScreen.kt TopBar
actions = {
    IconButton(onClick = { showImportDialog = true }) {
        Icon(Icons.Default.Upload, "Import")
    }
}
```

2. **File Picker für ZIP:**
```kotlin
val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri ->
    uri?.let { vm.importBundle(it) }
}

if (showImportDialog) {
    importLauncher.launch("application/zip")
}
```

3. **Import-Logik in ViewModel:**
```kotlin
suspend fun importBundle(zipUri: Uri) {
    val tempDir = File(context.cacheDir, "import_${System.currentTimeMillis()}")
    try {
        // ZIP entpacken
        unzipBundle(zipUri, tempDir)
        
        // Manifest lesen
        val manifest = Json.decodeFromString<ExportManifest>(
            File(tempDir, "manifest.json").readText()
        )
        
        // Projekt erstellen/aktualisieren
        val projectId = manifest.projectId
        store.ensureProjectExists(projectId)
        
        // Graph mergen
        val importedGraph = Json.decodeFromString<MapGraph>(
            File(tempDir, "graph.json").readText()
        )
        val existingGraph = store.loadGraph(projectId)
        val mergedGraph = mergeGraphs(existingGraph, importedGraph)
        store.saveGraph(projectId, mergedGraph)
        
        // Sessions importieren
        // Chains importieren
        
    } finally {
        tempDir.deleteRecursively()
    }
}
```

4. **Graph-Merge-Strategie:**
```kotlin
private fun mergeGraphs(existing: MapGraph, imported: MapGraph): MapGraph {
    val nodesById = (existing.nodes + imported.nodes)
        .groupBy { it.id }
        .mapValues { (_, nodes) -> nodes.maxByOrNull { it.lastSeenAt } ?: nodes.first() }
    
    val uniqueEdges = (existing.edges + imported.edges)
        .distinctBy { Triple(it.from, it.to, it.kind) }
    
    return MapGraph(
        nodes = nodesById.values.toList(),
        edges = uniqueEdges
    )
}
```

#### Betroffene Dateien
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/projects/ProjectsScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/projects/ProjectsViewModel.kt`
- NEU: `androidApp/src/main/java/dev/fishit/mapper/android/import/ImportManager.kt`

#### Akzeptanzkriterien
- ✅ Import-Button in ProjectsScreen
- ✅ File Picker öffnet sich für ZIP-Dateien
- ✅ ZIP wird entpackt und validiert
- ✅ Projekt wird erstellt/aktualisiert
- ✅ Graph wird intelligent gemerged (keine Duplikate)
- ✅ Sessions und Chains werden importiert
- ✅ Fehlerbehandlung bei ungültigem ZIP

---

## 🎯 Priorität 3: Erweiterte Features (Nice-to-Have)

### 3.1 Hub-Detection Algorithmus
**Status:** ❌ 0%  
**Aufwand:** 🔴 Hoch (8-10 Stunden)  
**Auswirkung:** 🔥 Mittel (bessere Graph-Analyse)

#### Problem
Wichtige Nodes (z.B. Homepage, Haupt-Navigation) werden nicht automatisch erkannt.

#### Lösung
```kotlin
data class HubMetrics(
    val inDegree: Int,      // Anzahl eingehender Edges
    val outDegree: Int,     // Anzahl ausgehender Edges
    val betweenness: Double, // Betweenness Centrality
    val pageRank: Double,    // PageRank Score
    val isHub: Boolean
)

fun detectHubs(graph: MapGraph): List<NodeId> {
    val metrics = graph.nodes.associate { node ->
        node.id to calculateMetrics(node, graph)
    }
    
    return metrics
        .filter { it.value.isHub }
        .keys
        .toList()
}

private fun calculateMetrics(node: MapNode, graph: MapGraph): HubMetrics {
    val inDegree = graph.edges.count { it.to == node.id }
    val outDegree = graph.edges.count { it.from == node.id }
    val betweenness = calculateBetweenness(node.id, graph)
    val pageRank = calculatePageRank(node.id, graph)
    
    val isHub = (inDegree + outDegree) > 5 || pageRank > 0.1
    
    return HubMetrics(inDegree, outDegree, betweenness, pageRank, isHub)
}
```

#### Betroffene Dateien
- NEU: `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/analysis/HubDetector.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/GraphScreen.kt` (Hubs hervorheben)

#### Akzeptanzkriterien
- ✅ Hubs werden automatisch erkannt
- ✅ Visuelle Hervorhebung im Graph (größer, andere Farbe)
- ✅ Liste der Top-10 Hubs
- ✅ Metriken pro Node anzeigbar

---

### 3.2 Form-Submit-Tracking
**Status:** ❌ 0%  
**Aufwand:** 🔴 Mittel (4-6 Stunden)  
**Auswirkung:** 🔥 Mittel (besseres Tracking)

#### Problem
Formular-Submits werden nicht speziell erfasst.

#### Lösung
Erweiterung der JavaScript-Bridge (siehe 2.2):
```javascript
document.addEventListener('submit', function(e) {
    const form = e.target;
    const formData = {};
    
    // Form-Felder sammeln (OHNE Werte aus Sicherheitsgründen)
    const inputs = form.querySelectorAll('input, select, textarea');
    inputs.forEach(input => {
        formData[input.name || input.id] = {
            type: input.type,
            required: input.required
        };
    });
    
    FishITMapper.recordFormSubmit(
        form.action || window.location.href,
        form.method || 'GET',
        JSON.stringify(formData)
    );
}, true);
```

#### Akzeptanzkriterien
- ✅ Form-Submits werden erfasst
- ✅ Action-URL und Method werden gespeichert
- ✅ Feld-Namen (NICHT Werte!) werden protokolliert
- ✅ Events erscheinen in Session-Details

---

### 3.3 Redirect-Detection
**Status:** ❌ 0%  
**Aufwand:** 🟡 Niedrig-Mittel (2-4 Stunden)  
**Auswirkung:** 🔥 Niedrig-Mittel (bessere Navigation-Analyse)

#### Problem
Redirects (301, 302, 303) werden als normale Navigation behandelt.

#### Lösung
```kotlin
// In BrowserScreen.kt WebViewClient erweitern:
override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
    super.onPageStarted(view, url, favicon)
    val u = url ?: return
    if (!recordingState) {
        lastUrl = u
        return
    }
    
    // Redirect erkennen durch schnelle Aufeinanderfolge
    val now = System.currentTimeMillis()
    val isRedirect = lastUrl != null && 
                     (now - lastNavigationTime) < 500 && 
                     u != lastUrl
    
    val event = NavigationEvent(
        id = IdGenerator.newEventId(),
        at = Clock.System.now(),
        url = u,
        fromUrl = lastUrl,
        title = null,
        isRedirect = isRedirect  // ✅ Jetzt korrekt gesetzt
    )
    
    lastUrl = u
    lastNavigationTime = now
    mainHandler.post { onRecorderEventState(event) }
}
```

#### Akzeptanzkriterien
- ✅ Redirects werden korrekt markiert (isRedirect = true)
- ✅ Redirect-Chain wird sichtbar in Sessions
- ✅ Graph-Edges haben EdgeKind.Redirect für Redirects

---

### 3.4 Graph-Diff-Funktion
**Status:** ❌ 0%  
**Aufwand:** 🔴 Hoch (8-10 Stunden)  
**Auswirkung:** 🔥 Niedrig-Mittel (Analyse-Tool)

#### Problem
Keine Möglichkeit, zwei Sessions/Graphs zu vergleichen.

#### Lösung
```kotlin
data class GraphDiff(
    val addedNodes: List<MapNode>,
    val removedNodes: List<MapNode>,
    val modifiedNodes: List<Pair<MapNode, MapNode>>, // (old, new)
    val addedEdges: List<MapEdge>,
    val removedEdges: List<MapEdge>
)

fun compareGraphs(old: MapGraph, new: MapGraph): GraphDiff {
    val oldNodesById = old.nodes.associateBy { it.id }
    val newNodesById = new.nodes.associateBy { it.id }
    
    val addedNodes = newNodesById.keys.subtract(oldNodesById.keys)
        .mapNotNull { newNodesById[it] }
    val removedNodes = oldNodesById.keys.subtract(newNodesById.keys)
        .mapNotNull { oldNodesById[it] }
    val modifiedNodes = oldNodesById.keys.intersect(newNodesById.keys)
        .mapNotNull { id ->
            val oldNode = oldNodesById[id]!!
            val newNode = newNodesById[id]!!
            if (oldNode != newNode) oldNode to newNode else null
        }
    
    // Analog für Edges
    
    return GraphDiff(addedNodes, removedNodes, modifiedNodes, ...)
}
```

#### UI für Diff-Ansicht:
```kotlin
@Composable
fun GraphDiffScreen(diff: GraphDiff) {
    LazyColumn {
        item { Text("Added Nodes: ${diff.addedNodes.size}", color = Color.Green) }
        items(diff.addedNodes) { node -> NodeRow(node, background = Color.Green.copy(alpha = 0.1f)) }
        
        item { Text("Removed Nodes: ${diff.removedNodes.size}", color = Color.Red) }
        items(diff.removedNodes) { node -> NodeRow(node, background = Color.Red.copy(alpha = 0.1f)) }
        
        // Modified, Added/Removed Edges analog
    }
}
```

#### Akzeptanzkriterien
- ✅ Zwei Sessions auswählbar für Vergleich
- ✅ Diff-Ansicht zeigt Added/Removed/Modified Nodes
- ✅ Diff-Ansicht zeigt Added/Removed Edges
- ✅ Farbcodierung (Grün = neu, Rot = entfernt, Gelb = geändert)

---

### 3.5 Node-Tagging & Filter
**Status:** ❌ 0%  
**Aufwand:** 🟡 Mittel (4-5 Stunden)  
**Auswirkung:** 🔥 Niedrig-Mittel (Organisation)

#### Problem
Keine Möglichkeit, Nodes manuell zu taggen/kategorisieren.

#### Lösung
1. **Contract erweitern:**
```kotlin
data class MapNode(
    // ... bestehend
    val tags: List<String> = emptyList()  // NEU
)
```

2. **Tag-Dialog:**
```kotlin
@Composable
fun TagDialog(node: MapNode, onSave: (List<String>) -> Unit) {
    var tags by remember { mutableStateOf(node.tags.joinToString(", ")) }
    
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Edit Tags") },
        text = {
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text("Tags (comma-separated)") }
            )
        },
        confirmButton = {
            Button(onClick = {
                onSave(tags.split(",").map { it.trim() })
            }) {
                Text("Save")
            }
        }
    )
}
```

#### Akzeptanzkriterien
- ✅ Click auf Node öffnet Tag-Dialog
- ✅ Tags werden im MapNode gespeichert
- ✅ Filter nach Tags in GraphScreen
- ✅ Tags werden in Export/Import übernommen

---

## 📊 Zusammenfassung & Roadmap

### Aufwandsschätzung Gesamt

| Priorität | Features | Aufwand | Zeitrahmen |
|-----------|----------|---------|------------|
| **P1: Quick Wins** | 3 Features | ~10 Std | 1-2 Tage |
| **P2: MVP-Erweiterung** | 3 Features | ~28 Std | 1 Woche |
| **P3: Nice-to-Have** | 5 Features | ~30 Std | 1 Woche |
| **GESAMT** | **11 Features** | **~68 Std** | **2-3 Wochen** |

### Empfohlene Reihenfolge

#### Phase 1: Quick Wins (1-2 Tage)
1. ✅ WebChromeClient für Console-Logs
2. ✅ Chains-Tab erstellen
3. ✅ Filter-Dropdowns in Graph

→ **Ergebnis:** App wird von 80% auf 85% MVP-Erfüllung gebracht

#### Phase 2: Kern-Features (1 Woche)
4. ✅ Graph-Visualisierung (wichtigstes Feature!)
5. ✅ JavaScript-Bridge für User-Actions
6. ✅ Import-Funktion

→ **Ergebnis:** App wird auf 95% MVP-Erfüllung gebracht + wichtigstes visuelles Feature

#### Phase 3: Polish & Erweitert (1 Woche)
7. ✅ Hub-Detection
8. ✅ Form-Submit-Tracking
9. ✅ Redirect-Detection
10. ✅ Graph-Diff
11. ✅ Node-Tagging

→ **Ergebnis:** Feature-vollständige App mit erweiterten Analyse-Tools

---

## 🔗 Verweise

### Betroffene Dateien (Übersicht)

#### Zu bearbeiten:
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/GraphScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ProjectHomeScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ProjectViewModel.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/projects/ProjectsScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/projects/ProjectsViewModel.kt`

#### Neu zu erstellen:
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/ChainsScreen.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/graph/GraphVisualization.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/ui/graph/ForceLayout.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/webview/JavaScriptBridge.kt`
- `androidApp/src/main/java/dev/fishit/mapper/android/import/ImportManager.kt`
- `shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/analysis/HubDetector.kt`

### Weitere Ressourcen
- [Code-Review Dokument](../IMPLEMENTATION_SUMMARY.md)
- [Contract Schema](../schema/contract.schema.json)
- [MappingEngine](../shared/engine/src/commonMain/kotlin/dev/fishit/mapper/engine/MappingEngine.kt)

---

## ✅ Akzeptanzkriterien (Gesamt)

**Die App gilt als feature-vollständig, wenn:**
- ✅ Alle P1-Features (Quick Wins) implementiert sind
- ✅ Graph-Visualisierung funktioniert (P2)
- ✅ User-Action-Tracking funktioniert (P2)
- ✅ Import-Funktion funktioniert (P2)
- ✅ Mindestens 3 von 5 P3-Features implementiert sind

**Qualitätskriterien:**
- ✅ Keine neuen Crashes/Bugs
- ✅ Performance bleibt gut (< 1s für Graph-Render bei 100 Nodes)
- ✅ Tests für neue Features existieren
- ✅ Code-Review ohne kritische Findings

---

## 🏷️ Labels

Für GitHub Issue:
- `enhancement`
- `feature`
- `priority: high` (für P1/P2)
- `priority: medium` (für P3)
- `good first issue` (für P1.1, P1.3)
- `help wanted`
