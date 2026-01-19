package dev.fishit.mapper.android.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.android.ui.graph.GraphVisualization
import dev.fishit.mapper.contract.EdgeKind
import dev.fishit.mapper.contract.MapGraph
import dev.fishit.mapper.contract.MapNode
import dev.fishit.mapper.contract.NodeKind

private enum class ViewMode {
    List, Visualization
}

@Composable
fun GraphScreen(
    graph: MapGraph,
    onNodeTagsChanged: ((dev.fishit.mapper.contract.NodeId, List<String>) -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    var selectedNodeKind by remember { mutableStateOf<NodeKind?>(null) }
    var selectedEdgeKind by remember { mutableStateOf<EdgeKind?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(ViewMode.List) }
    var nodeToTag by remember { mutableStateOf<MapNode?>(null) }
    
    val q = query.trim().lowercase()

    // Collect all unique tags from the graph
    val allTags = remember(graph) {
        graph.nodes.flatMap { it.tags }.distinct().sorted()
    }

    // Filter nodes
    val nodes = graph.nodes.filter {
        val matchesQuery = q.isBlank() || 
            it.url.lowercase().contains(q) || 
            (it.title?.lowercase()?.contains(q) == true) || 
            it.kind.name.lowercase().contains(q) ||
            it.tags.any { tag -> tag.lowercase().contains(q) }
        val matchesNodeKind = selectedNodeKind == null || it.kind == selectedNodeKind
        val matchesTag = selectedTag == null || selectedTag in it.tags
        matchesQuery && matchesNodeKind && matchesTag
    }

    // Filter edges - only show edges between visible nodes
    val visibleNodeIds = nodes.map { it.id }.toSet()
    val edges = graph.edges.filter {
        val matchesEdgeKind = selectedEdgeKind == null || it.kind == selectedEdgeKind
        val bothNodesVisible = it.from in visibleNodeIds && it.to in visibleNodeIds
        matchesEdgeKind && bothNodesVisible
    }

    val nodesById = graph.nodes.associateBy { it.id }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with stats and view mode toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Nodes: ${'$'}{graph.nodes.size}   Edges: ${'$'}{graph.edges.size}")
            
            IconButton(onClick = { 
                viewMode = when (viewMode) {
                    ViewMode.List -> ViewMode.Visualization
                    ViewMode.Visualization -> ViewMode.List
                }
            }) {
                Icon(
                    imageVector = when (viewMode) {
                        ViewMode.List -> Icons.Default.BubbleChart
                        ViewMode.Visualization -> Icons.AutoMirrored.Filled.List
                    },
                    contentDescription = when (viewMode) {
                        ViewMode.List -> "Switch to visualization"
                        ViewMode.Visualization -> "Switch to list"
                    }
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search (url/title/kind/tags)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Filter dropdowns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Node Kind Filter
            var nodeKindExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { nodeKindExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedNodeKind?.name ?: "All Nodes")
                }
                DropdownMenu(
                    expanded = nodeKindExpanded,
                    onDismissRequest = { nodeKindExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All Nodes") },
                        onClick = {
                            selectedNodeKind = null
                            nodeKindExpanded = false
                        }
                    )
                    NodeKind.entries.forEach { kind ->
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

            // Edge Kind Filter
            var edgeKindExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { edgeKindExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedEdgeKind?.name ?: "All Edges")
                }
                DropdownMenu(
                    expanded = edgeKindExpanded,
                    onDismissRequest = { edgeKindExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All Edges") },
                        onClick = {
                            selectedEdgeKind = null
                            edgeKindExpanded = false
                        }
                    )
                    EdgeKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.name) },
                            onClick = {
                                selectedEdgeKind = kind
                                edgeKindExpanded = false
                            }
                        )
                    }
                }
            }
        }

        // Tag Filter (if tags exist)
        if (allTags.isNotEmpty()) {
            var tagExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { tagExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedTag ?: "All Tags (${allTags.size})")
                }
                DropdownMenu(
                    expanded = tagExpanded,
                    onDismissRequest = { tagExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All Tags") },
                        onClick = {
                            selectedTag = null
                            tagExpanded = false
                        }
                    )
                    allTags.forEach { tag ->
                        DropdownMenuItem(
                            text = { Text(tag) },
                            onClick = {
                                selectedTag = tag
                                tagExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Show either list view or visualization based on view mode
        when (viewMode) {
            ViewMode.List -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("Nodes (filtered: ${nodes.size})")
                    }
                    items(nodes) { node ->
                        NodeRow(
                            node = node,
                            onTagClick = if (onNodeTagsChanged != null) {
                                { nodeToTag = node }
                            } else null
                        )
                    }

                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("Edges (filtered: ${edges.size})")
                    }

                    items(edges) { edge ->
                        val from = nodesById[edge.from]?.url ?: edge.from.value
                        val to = nodesById[edge.to]?.url ?: edge.to.value
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("${'$'}{edge.kind}: ")
                            Text(from, modifier = Modifier.weight(1f))
                            Text(" → ")
                            Text(to, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            
            ViewMode.Visualization -> {
                // Show visual graph with filters applied
                val filteredGraph = MapGraph(
                    nodes = nodes,
                    edges = edges
                )
                GraphVisualization(
                    graph = filteredGraph,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Show tagging dialog if node is selected
        nodeToTag?.let { node ->
            NodeTaggingDialog(
                node = node,
                onDismiss = { nodeToTag = null },
                onTagsChanged = { nodeId, tags ->
                    onNodeTagsChanged?.invoke(nodeId, tags)
                }
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: MapNode,
    onTagClick: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${node.kind}  ${node.title ?: ""}")
                Text(node.url)
                
                // Show tags if any
                if (node.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tags: ${node.tags.joinToString(", ")}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Tag button
            if (onTagClick != null) {
                androidx.compose.material3.TextButton(onClick = onTagClick) {
                    Text("Tag")
                }
            }
        }
    }
}
