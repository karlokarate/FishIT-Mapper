package dev.fishit.mapper.android.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.fishit.mapper.contract.MapGraph
import dev.fishit.mapper.contract.SessionId
import dev.fishit.mapper.engine.GraphDiff

/**
 * Screen to compare two graph snapshots and show differences.
 */
@Composable
fun GraphDiffScreen(
    sessions: List<Pair<SessionId, MapGraph>>,
    onClose: () -> Unit
) {
    var selectedBefore by remember { mutableStateOf<SessionId?>(null) }
    var selectedAfter by remember { mutableStateOf<SessionId?>(null) }
    
    val diffResult = remember(selectedBefore, selectedAfter) {
        if (selectedBefore != null && selectedAfter != null) {
            val beforeGraph = sessions.find { it.first == selectedBefore }?.second
            val afterGraph = sessions.find { it.first == selectedAfter }?.second
            
            if (beforeGraph != null && afterGraph != null) {
                GraphDiff.compare(beforeGraph, afterGraph)
            } else {
                null
            }
        } else {
            null
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Compare Sessions",
            style = MaterialTheme.typography.titleLarge
        )
        
        // Session selectors
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Before selector
            SessionDropdown(
                label = "Before (Baseline)",
                sessions = sessions,
                selectedSession = selectedBefore,
                onSessionSelected = { selectedBefore = it },
                modifier = Modifier.weight(1f)
            )
            
            // After selector
            SessionDropdown(
                label = "After (Compare)",
                sessions = sessions,
                selectedSession = selectedAfter,
                onSessionSelected = { selectedAfter = it },
                modifier = Modifier.weight(1f)
            )
        }
        
        Divider()
        
        // Show diff results
        if (diffResult != null) {
            if (diffResult.hasChanges) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Added nodes
                    if (diffResult.addedNodes.isNotEmpty()) {
                        item {
                            DiffSection(
                                title = "Added Nodes (${diffResult.addedNodes.size})",
                                color = Color(0xFF4CAF50)
                            )
                        }
                        items(diffResult.addedNodes) { node ->
                            DiffNodeItem(
                                label = "+ ${node.kind}: ${node.url}",
                                backgroundColor = Color(0xFFE8F5E9)
                            )
                        }
                    }
                    
                    // Removed nodes
                    if (diffResult.removedNodes.isNotEmpty()) {
                        item {
                            DiffSection(
                                title = "Removed Nodes (${diffResult.removedNodes.size})",
                                color = Color(0xFFF44336)
                            )
                        }
                        items(diffResult.removedNodes) { node ->
                            DiffNodeItem(
                                label = "- ${node.kind}: ${node.url}",
                                backgroundColor = Color(0xFFFFEBEE)
                            )
                        }
                    }
                    
                    // Modified nodes
                    if (diffResult.modifiedNodes.isNotEmpty()) {
                        item {
                            DiffSection(
                                title = "Modified Nodes (${diffResult.modifiedNodes.size})",
                                color = Color(0xFFFF9800)
                            )
                        }
                        items(diffResult.modifiedNodes) { modification ->
                            DiffModificationItem(
                                label = "~ ${modification.after.url}",
                                changes = modification.changes,
                                backgroundColor = Color(0xFFFFF3E0)
                            )
                        }
                    }
                    
                    // Added edges
                    if (diffResult.addedEdges.isNotEmpty()) {
                        item {
                            DiffSection(
                                title = "Added Edges (${diffResult.addedEdges.size})",
                                color = Color(0xFF2196F3)
                            )
                        }
                        items(diffResult.addedEdges) { edge ->
                            DiffNodeItem(
                                label = "+ ${edge.kind}: ${edge.from.value} → ${edge.to.value}",
                                backgroundColor = Color(0xFFE3F2FD)
                            )
                        }
                    }
                    
                    // Removed edges
                    if (diffResult.removedEdges.isNotEmpty()) {
                        item {
                            DiffSection(
                                title = "Removed Edges (${diffResult.removedEdges.size})",
                                color = Color(0xFF9C27B0)
                            )
                        }
                        items(diffResult.removedEdges) { edge ->
                            DiffNodeItem(
                                label = "- ${edge.kind}: ${edge.from.value} → ${edge.to.value}",
                                backgroundColor = Color(0xFFF3E5F5)
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = "No differences found between these sessions",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    text = "Select two sessions to compare",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SessionDropdown(
    label: String,
    sessions: List<Pair<SessionId, MapGraph>>,
    selectedSession: SessionId?,
    onSessionSelected: (SessionId) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selectedSession?.value ?: label)
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            sessions.forEach { (sessionId, _) ->
                DropdownMenuItem(
                    text = { Text(sessionId.value) },
                    onClick = {
                        onSessionSelected(sessionId)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DiffSection(
    title: String,
    color: Color
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun DiffNodeItem(
    label: String,
    backgroundColor: Color
) {
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun DiffModificationItem(
    label: String,
    changes: List<String>,
    backgroundColor: Color
) {
    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            
            changes.forEach { change ->
                Text(
                    text = "  • $change",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
