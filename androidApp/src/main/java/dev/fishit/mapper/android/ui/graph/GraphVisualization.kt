package dev.fishit.mapper.android.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.fishit.mapper.contract.*
import kotlinx.coroutines.delay
import kotlin.math.sqrt

// Constants
private const val MAX_LABEL_LENGTH = 20
private const val REPULSION_STRENGTH = 5000f
private const val INTERACTION_RANGE = 500f
private const val ATTRACTION_STRENGTH = 0.1f
private const val INITIAL_DAMPING = 0.9f
private const val DAMPING_DECAY = 0.005f

/**
 * Visual graph representation using Compose Canvas with force-directed layout.
 * 
 * Features:
 * - Force-directed layout for automatic node positioning
 * - Zoom and pan for navigation
 * - Color-coded nodes by NodeKind
 * - Spatial grid optimization for better performance with large graphs
 */
@Composable
fun GraphVisualization(
    graph: MapGraph,
    modifier: Modifier = Modifier
) {
    var nodePositions by remember(graph) { 
        mutableStateOf(initializeNodePositions(graph.nodes)) 
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    // Run force-directed layout simulation
    LaunchedEffect(graph) {
        var iterations = 0
        val maxIterations = 100 // Limit iterations for performance
        
        while (iterations < maxIterations) {
            delay(16) // ~60 FPS
            nodePositions = calculateForceLayout(
                nodePositions,
                graph.nodes,
                graph.edges,
                iteration = iterations
            )
            iterations++
        }
    }
    
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "Graph visualization with ${graph.nodes.size} nodes and ${graph.edges.size} edges"
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 3f)
                    offset += pan
                }
            }
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        
        // Draw edges first (so they appear behind nodes)
        graph.edges.forEach { edge ->
            val fromPos = nodePositions[edge.from] ?: return@forEach
            val toPos = nodePositions[edge.to] ?: return@forEach
            
            val start = transformPosition(fromPos, centerX, centerY, scale, offset)
            val end = transformPosition(toPos, centerX, centerY, scale, offset)
            
            drawLine(
                color = getEdgeColor(edge.kind),
                start = start,
                end = end,
                strokeWidth = 2f * scale
            )
        }
        
        // Draw nodes
        val nodesById = graph.nodes.associateBy { it.id }
        nodePositions.forEach { (nodeId, position) ->
            val node = nodesById[nodeId] ?: return@forEach
            val transformedPos = transformPosition(position, centerX, centerY, scale, offset)
            
            // Draw node circle
            val radius = 20f * scale
            drawCircle(
                color = getNodeColor(node.kind),
                radius = radius,
                center = transformedPos
            )
            
            // Draw node label
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = Color.Black.toArgb()
                    textSize = 12f * scale
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                
                val label = node.title?.take(MAX_LABEL_LENGTH) ?: node.url.takeLast(MAX_LABEL_LENGTH)
                canvas.nativeCanvas.drawText(
                    label,
                    transformedPos.x,
                    transformedPos.y - radius - 5f,
                    paint
                )
            }
        }
    }
}

/**
 * Initialize node positions randomly in a circle layout
 */
private fun initializeNodePositions(nodes: List<MapNode>): Map<NodeId, Offset> {
    val radius = 300f
    return nodes.mapIndexed { index, node ->
        val angle = (index.toFloat() / nodes.size) * 2 * Math.PI
        val x = (radius * Math.cos(angle)).toFloat()
        val y = (radius * Math.sin(angle)).toFloat()
        node.id to Offset(x, y)
    }.toMap()
}

/**
 * Calculate one iteration of force-directed layout with spatial grid optimization.
 * 
 * Forces:
 * - Repulsion: All nodes repel each other (prevents overlapping)
 * - Attraction: Connected nodes attract each other (groups related nodes)
 * - Damping: Reduces velocity over time (stabilizes layout)
 * 
 * Uses spatial grid to reduce repulsion calculation from O(n²) to approximately O(n).
 */
private fun calculateForceLayout(
    positions: Map<NodeId, Offset>,
    nodes: List<MapNode>,
    edges: List<MapEdge>,
    iteration: Int
): Map<NodeId, Offset> {
    val newPositions = mutableMapOf<NodeId, Offset>()
    val forces = mutableMapOf<NodeId, Offset>()
    
    // Initialize forces to zero
    nodes.forEach { node ->
        forces[node.id] = Offset.Zero
    }
    
    // Build spatial grid for efficient neighbor queries
    val cellSize = INTERACTION_RANGE
    val spatialGrid = mutableMapOf<Pair<Int, Int>, MutableList<MapNode>>()
    nodes.forEach { node ->
        val pos = positions[node.id] ?: Offset.Zero
        val cellX = (pos.x / cellSize).toInt()
        val cellY = (pos.y / cellSize).toInt()
        val key = cellX to cellY
        spatialGrid.getOrPut(key) { mutableListOf() }.add(node)
    }
    
    // Repulsion force - only check nodes in nearby cells
    nodes.forEach { nodeA ->
        val posA = positions[nodeA.id] ?: Offset.Zero
        val cellX = (posA.x / cellSize).toInt()
        val cellY = (posA.y / cellSize).toInt()
        
        // Check node and neighboring cells (9 cells total)
        for (dx in -1..1) {
            for (dy in -1..1) {
                val neighborKey = (cellX + dx) to (cellY + dy)
                val neighborNodes = spatialGrid[neighborKey] ?: continue
                
                neighborNodes.forEach { nodeB ->
                    if (nodeA.id != nodeB.id) {
                        val posB = positions[nodeB.id] ?: Offset.Zero
                        val delta = posA - posB
                        val distance = sqrt(delta.x * delta.x + delta.y * delta.y).coerceAtLeast(1f)
                        
                        if (distance < INTERACTION_RANGE) {
                            val force = REPULSION_STRENGTH / (distance * distance)
                            val direction = delta / distance
                            forces[nodeA.id] = forces[nodeA.id]!! + (direction * force)
                        }
                    }
                }
            }
        }
    }
    
    // Attraction force between connected nodes
    edges.forEach { edge ->
        val posFrom = positions[edge.from] ?: return@forEach
        val posTo = positions[edge.to] ?: return@forEach
        val delta = posTo - posFrom
        val distance = sqrt(delta.x * delta.x + delta.y * delta.y).coerceAtLeast(1f)
        
        val force = ATTRACTION_STRENGTH * distance
        val direction = delta / distance
        
        forces[edge.from] = forces[edge.from]!! + (direction * force)
        forces[edge.to] = forces[edge.to]!! - (direction * force)
    }
    
    // Apply forces with damping
    val damping = INITIAL_DAMPING - (iteration * DAMPING_DECAY).coerceAtMost(0.5f)
    nodes.forEach { node ->
        val pos = positions[node.id] ?: Offset.Zero
        val force = forces[node.id] ?: Offset.Zero
        newPositions[node.id] = pos + (force * damping)
    }
    
    return newPositions
}

/**
 * Transform position from graph space to screen space
 */
private fun transformPosition(
    position: Offset,
    centerX: Float,
    centerY: Float,
    scale: Float,
    offset: Offset
): Offset {
    return Offset(
        centerX + position.x * scale + offset.x,
        centerY + position.y * scale + offset.y
    )
}

/**
 * Get color for node based on its kind
 */
private fun getNodeColor(kind: NodeKind): Color {
    return when (kind) {
        NodeKind.Page -> Color(0xFF4CAF50) // Green
        NodeKind.ApiEndpoint -> Color(0xFFFF9800) // Orange
        NodeKind.Asset -> Color(0xFF2196F3) // Blue
        NodeKind.Document -> Color(0xFF9C27B0) // Purple
        NodeKind.Form -> Color(0xFF03A9F4) // Light Blue
        NodeKind.Error -> Color(0xFFFF5722) // Red
        NodeKind.Unknown -> Color(0xFF757575) // Gray
    }
}

/**
 * Get color for edge based on its kind
 */
private fun getEdgeColor(kind: EdgeKind): Color {
    return when (kind) {
        EdgeKind.Link -> Color(0xFF666666) // Dark Gray
        EdgeKind.Redirect -> Color(0xFFFF5722) // Red
        EdgeKind.Fetch -> Color(0xFF03A9F4) // Light Blue
        EdgeKind.Xhr -> Color(0xFF0288D1) // Darker Blue
        EdgeKind.FormSubmit -> Color(0xFF000000) // Black
        EdgeKind.AssetLoad -> Color(0xFF8BC34A) // Light Green
        EdgeKind.Embed -> Color(0xFFCDDC39) // Lime
        EdgeKind.Unknown -> Color(0xFF9E9E9E) // Gray
    }
}
