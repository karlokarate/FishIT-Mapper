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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.fishit.mapper.contract.EdgeKind
import dev.fishit.mapper.contract.MapGraph
import dev.fishit.mapper.contract.MapNode
import dev.fishit.mapper.contract.NodeKind

@Composable
fun GraphScreen(graph: MapGraph) {
    var query by remember { mutableStateOf("") }
    var selectedNodeKind by remember { mutableStateOf<NodeKind?>(null) }
    var selectedEdgeKind by remember { mutableStateOf<EdgeKind?>(null) }
    
    val q = query.trim().lowercase()

    // Filter nodes
    val nodes = graph.nodes.filter {
        val matchesQuery = q.isBlank() || 
            it.url.lowercase().contains(q) || 
            (it.title?.lowercase()?.contains(q) == true) || 
            it.kind.name.lowercase().contains(q)
        val matchesNodeKind = selectedNodeKind == null || it.kind == selectedNodeKind
        matchesQuery && matchesNodeKind
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
        Text("Nodes: ${'$'}{graph.nodes.size}   Edges: ${'$'}{graph.edges.size}")

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search (url/title/kind)") },
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

        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Nodes (filtered: ${nodes.size})")
            }
            items(nodes) { node ->
                NodeRow(node)
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
}

@Composable
private fun NodeRow(node: MapNode) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("${'$'}{node.kind}  ${'$'}{node.title ?: ""}")
        Text(node.url)
    }
}
