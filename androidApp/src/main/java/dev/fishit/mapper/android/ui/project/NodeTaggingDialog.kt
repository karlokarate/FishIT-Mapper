package dev.fishit.mapper.android.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.fishit.mapper.contract.MapNode
import dev.fishit.mapper.contract.NodeId

/**
 * Dialog for managing tags on a node.
 */
@Composable
fun NodeTaggingDialog(
    node: MapNode,
    onDismiss: () -> Unit,
    onTagsChanged: (NodeId, List<String>) -> Unit
) {
    var currentTags by remember { mutableStateOf(node.tags) }
    var newTagText by remember { mutableStateOf("") }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Manage Tags",
                    style = MaterialTheme.typography.titleLarge
                )
                
                Text(
                    text = node.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Existing tags
                if (currentTags.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(currentTags) { tag ->
                            TagChip(
                                tag = tag,
                                onRemove = {
                                    currentTags = currentTags - tag
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "No tags yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Add new tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newTagText,
                        onValueChange = { newTagText = it },
                        label = { Text("New Tag") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    
                    IconButton(
                        onClick = {
                            val tag = newTagText.trim()
                            if (tag.isNotBlank() && tag !in currentTags) {
                                currentTags = currentTags + tag
                                newTagText = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, "Add tag")
                    }
                }
                
                // Common tags suggestions
                Text(
                    text = "Quick tags:",
                    style = MaterialTheme.typography.labelMedium
                )
                
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(commonTags) { suggestedTag ->
                        SuggestionChip(
                            onClick = {
                                if (suggestedTag !in currentTags) {
                                    currentTags = currentTags + suggestedTag
                                }
                            },
                            label = { Text(suggestedTag) }
                        )
                    }
                }
                
                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    
                    Spacer(Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            onTagsChanged(node.id, currentTags)
                            onDismiss()
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun TagChip(
    tag: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = getTagColor(tag),
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
            
            // Use a clickable Icon instead of IconButton for better space efficiency
            // while maintaining accessibility
            Box(
                modifier = Modifier
                    .size(24.dp) // Minimum touch target size
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove tag",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

private fun getTagColor(tag: String): Color {
    return when {
        tag.startsWith("hub:") -> Color(0xFF2196F3) // Blue
        tag.startsWith("important") -> Color(0xFFF44336) // Red
        tag.startsWith("auth") -> Color(0xFF9C27B0) // Purple
        tag.startsWith("api") -> Color(0xFF4CAF50) // Green
        tag.startsWith("form") -> Color(0xFFFF9800) // Orange
        else -> Color(0xFF607D8B) // Blue Grey
    }
}

private val commonTags = listOf(
    "important",
    "homepage",
    "navigation",
    "auth",
    "api",
    "form",
    "admin",
    "public",
    "private"
)
