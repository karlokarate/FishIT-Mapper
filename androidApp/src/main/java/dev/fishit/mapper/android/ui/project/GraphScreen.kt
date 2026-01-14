package dev.fishit.mapper.android.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.contract.MapGraph
import dev.fishit.mapper.contract.MapNode

@Composable
fun GraphScreen(graph: MapGraph) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()

    val nodes = if (q.isBlank()) graph.nodes else graph.nodes.filter {
        it.url.lowercase().contains(q) || (it.title?.lowercase()?.contains(q) == true) || it.kind.name.lowercase().contains(q)
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

        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Nodes")
            }
            items(nodes) { node ->
                NodeRow(node)
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text("Edges")
            }

            items(graph.edges) { edge ->
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
