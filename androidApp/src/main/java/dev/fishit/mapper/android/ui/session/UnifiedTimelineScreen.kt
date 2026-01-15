package dev.fishit.mapper.android.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fishit.mapper.android.di.LocalAppContainer
import dev.fishit.mapper.contract.*
import dev.fishit.mapper.engine.TimelineBuilder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedTimelineScreen(
    projectId: String,
    sessionId: String,
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    
    var session by remember { mutableStateOf<RecordingSession?>(null) }
    var timeline by remember { mutableStateOf<UnifiedTimeline?>(null) }
    var graph by remember { mutableStateOf<MapGraph?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    
    LaunchedEffect(projectId, sessionId) {
        loading = true
        error = null
        scope.launch {
            runCatching {
                val loadedSession = container.store.loadSession(ProjectId(projectId), SessionId(sessionId))
                val loadedGraph = container.store.loadGraph(ProjectId(projectId))
                
                if (loadedSession != null) {
                    session = loadedSession
                    graph = loadedGraph
                    
                    // Try to load cached timeline, or build a new one
                    val cachedTimeline = container.store.loadTimeline(ProjectId(projectId), SessionId(sessionId))
                    if (cachedTimeline != null) {
                        timeline = cachedTimeline
                    } else {
                        // Build and cache timeline
                        val newTimeline = TimelineBuilder.buildTimeline(loadedSession, loadedGraph)
                        timeline = newTimeline
                        container.store.saveTimeline(ProjectId(projectId), newTimeline)
                    }
                }
            }.onFailure { t ->
                error = t.message ?: "Unknown error"
            }
            loading = false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unified Timeline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                error != null -> {
                    Text(
                        text = "Error: $error",
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.Center),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                timeline != null && session != null -> {
                    TimelineContent(
                        timeline = timeline!!,
                        session = session!!,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {
                    Text(
                        text = "No timeline data available",
                        modifier = Modifier
                            .padding(16.dp)
                            .align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineContent(
    timeline: UnifiedTimeline,
    session: RecordingSession,
    modifier: Modifier = Modifier
) {
    var showTreeView by remember { mutableStateOf(false) }
    
    Column(modifier = modifier) {
        // Toggle between timeline and tree view
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Session: ${session.id.value.take(8)}",
                style = MaterialTheme.typography.titleMedium
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showTreeView,
                    onClick = { showTreeView = false },
                    label = { Text("Timeline") }
                )
                FilterChip(
                    selected = showTreeView,
                    onClick = { showTreeView = true },
                    label = { Text("Tree") }
                )
            }
        }
        
        Divider()
        
        if (showTreeView) {
            TreeView(
                timeline = timeline,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        } else {
            TimelineView(
                timeline = timeline,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun TimelineView(
    timeline: UnifiedTimeline,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(timeline.entries) { entry ->
            TimelineEntryCard(entry = entry)
        }
    }
}

@Composable
private fun TimelineEntryCard(entry: TimelineEntry) {
    val indentPadding = (entry.depth * 16).dp
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentPadding),
        colors = CardDefaults.cardColors(
            containerColor = when (entry.event) {
                is NavigationEvent -> MaterialTheme.colorScheme.primaryContainer
                is ResourceResponseEvent -> MaterialTheme.colorScheme.secondaryContainer
                is ResourceRequestEvent -> MaterialTheme.colorScheme.tertiaryContainer
                is UserActionEvent -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = getEventTypeLabel(entry.event),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = formatTimestamp(entry.event.at),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Spacer(Modifier.height(4.dp))
            
            EventDetails(event = entry.event)
            
            entry.correlatedEventId?.let { correlatedId ->
                Text(
                    text = "↔ Correlated with: ${correlatedId.value.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            entry.parentEventId?.let { parentId ->
                Text(
                    text = "↳ Parent: ${parentId.value.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun EventDetails(event: RecorderEvent) {
    when (event) {
        is NavigationEvent -> {
            Text(
                text = event.url,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            if (event.title != null) {
                Text(
                    text = "Title: ${event.title}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (event.isRedirect) {
                Text(
                    text = "🔄 Redirect",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF9800)
                )
            }
        }
        is ResourceRequestEvent -> {
            Text(
                text = "${event.method ?: "GET"} ${event.url}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            if (event.resourceKind != null) {
                Text(
                    text = "Type: ${event.resourceKind}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        is ResourceResponseEvent -> {
            Text(
                text = "${event.statusCode} ${event.url}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )
            if (event.contentType != null) {
                Text(
                    text = "Content-Type: ${event.contentType}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (event.isRedirect && event.redirectLocation != null) {
                Text(
                    text = "→ ${event.redirectLocation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF9800)
                )
            }
        }
        is UserActionEvent -> {
            Text(
                text = "Action: ${event.action}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (event.target != null) {
                Text(
                    text = "Target: ${event.target}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        is ConsoleMessageEvent -> {
            Text(
                text = "${event.level}: ${event.message}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        else -> {
            Text(
                text = "Event: ${event::class.simpleName}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TreeView(
    timeline: UnifiedTimeline,
    modifier: Modifier = Modifier
) {
    val rootNode = timeline.treeNodes.find { it.nodeId == timeline.rootNodeId }
    
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (rootNode != null) {
            item {
                Text(
                    text = "Session Tree Structure",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            items(timeline.treeNodes.sortedBy { it.depth }) { node ->
                TreeNodeCard(node = node, timeline = timeline)
            }
        } else {
            item {
                Text(
                    text = "No tree structure available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun TreeNodeCard(
    node: SessionTreeNode,
    timeline: UnifiedTimeline
) {
    var expanded by remember { mutableStateOf(false) }
    val hasChildren = node.children.isNotEmpty()
    val indentPadding = (node.depth * 20).dp
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentPadding)
            .clickable { if (hasChildren) expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (node.parentNodeId == null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasChildren) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.title ?: "Untitled",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = node.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    text = "${node.children.size} children",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            if (node.eventIds.isNotEmpty()) {
                Text(
                    text = "${node.eventIds.size} events",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun getEventTypeLabel(event: RecorderEvent): String = when (event) {
    is NavigationEvent -> "NAVIGATION"
    is ResourceRequestEvent -> "REQUEST"
    is ResourceResponseEvent -> "RESPONSE"
    is UserActionEvent -> "USER ACTION"
    is ConsoleMessageEvent -> "CONSOLE"
    is CustomEvent -> "CUSTOM"
    else -> "EVENT"
}

private fun formatTimestamp(instant: kotlinx.datetime.Instant): String {
    val seconds = instant.epochSeconds
    val millis = instant.nanosecondsOfSecond / 1_000_000
    return String.format("%d.%03d", seconds % 1000, millis)
}
