package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*

/**
 * Compares two MapGraphs and produces a diff showing what changed.
 */
object GraphDiff {
    
    data class DiffResult(
        val addedNodes: List<MapNode>,
        val removedNodes: List<MapNode>,
        val modifiedNodes: List<NodeModification>,
        val addedEdges: List<MapEdge>,
        val removedEdges: List<MapEdge>,
        val modifiedEdges: List<EdgeModification>
    ) {
        val hasChanges: Boolean
            get() = addedNodes.isNotEmpty() || removedNodes.isNotEmpty() || 
                    modifiedNodes.isNotEmpty() || addedEdges.isNotEmpty() || 
                    removedEdges.isNotEmpty() || modifiedEdges.isNotEmpty()
    }
    
    data class NodeModification(
        val nodeId: NodeId,
        val before: MapNode,
        val after: MapNode,
        val changes: List<String>
    )
    
    data class EdgeModification(
        val edgeId: EdgeId,
        val before: MapEdge,
        val after: MapEdge,
        val changes: List<String>
    )
    
    /**
     * Compare two graphs and return a detailed diff.
     * 
     * @param before The older/baseline graph
     * @param after The newer graph to compare against
     */
    fun compare(before: MapGraph, after: MapGraph): DiffResult {
        val beforeNodesById = before.nodes.associateBy { it.id }
        val afterNodesById = after.nodes.associateBy { it.id }
        
        val beforeEdgesById = before.edges.associateBy { it.id }
        val afterEdgesById = after.edges.associateBy { it.id }
        
        // Nodes
        val addedNodes = after.nodes.filter { it.id !in beforeNodesById }
        val removedNodes = before.nodes.filter { it.id !in afterNodesById }
        val modifiedNodes = mutableListOf<NodeModification>()
        
        // Check for modified nodes
        beforeNodesById.forEach { (id, beforeNode) ->
            val afterNode = afterNodesById[id]
            if (afterNode != null && beforeNode != afterNode) {
                val changes = detectNodeChanges(beforeNode, afterNode)
                if (changes.isNotEmpty()) {
                    modifiedNodes.add(
                        NodeModification(
                            nodeId = id,
                            before = beforeNode,
                            after = afterNode,
                            changes = changes
                        )
                    )
                }
            }
        }
        
        // Edges
        val addedEdges = after.edges.filter { it.id !in beforeEdgesById }
        val removedEdges = before.edges.filter { it.id !in afterEdgesById }
        val modifiedEdges = mutableListOf<EdgeModification>()
        
        // Check for modified edges
        beforeEdgesById.forEach { (id, beforeEdge) ->
            val afterEdge = afterEdgesById[id]
            if (afterEdge != null && beforeEdge != afterEdge) {
                val changes = detectEdgeChanges(beforeEdge, afterEdge)
                if (changes.isNotEmpty()) {
                    modifiedEdges.add(
                        EdgeModification(
                            edgeId = id,
                            before = beforeEdge,
                            after = afterEdge,
                            changes = changes
                        )
                    )
                }
            }
        }
        
        return DiffResult(
            addedNodes = addedNodes,
            removedNodes = removedNodes,
            modifiedNodes = modifiedNodes,
            addedEdges = addedEdges,
            removedEdges = removedEdges,
            modifiedEdges = modifiedEdges
        )
    }
    
    private fun detectNodeChanges(before: MapNode, after: MapNode): List<String> {
        val changes = mutableListOf<String>()
        
        if (before.kind != after.kind) {
            changes.add("kind: ${before.kind} → ${after.kind}")
        }
        if (before.url != after.url) {
            changes.add("url: ${before.url} → ${after.url}")
        }
        if (before.title != after.title) {
            changes.add("title: ${before.title} → ${after.title}")
        }
        if (before.tags != after.tags) {
            val added = after.tags - before.tags.toSet()
            val removed = before.tags - after.tags.toSet()
            if (added.isNotEmpty()) changes.add("tags added: ${added.joinToString()}")
            if (removed.isNotEmpty()) changes.add("tags removed: ${removed.joinToString()}")
        }
        if (before.attributes != after.attributes) {
            changes.add("attributes changed")
        }
        
        return changes
    }
    
    private fun detectEdgeChanges(before: MapEdge, after: MapEdge): List<String> {
        val changes = mutableListOf<String>()
        
        if (before.kind != after.kind) {
            changes.add("kind: ${before.kind} → ${after.kind}")
        }
        if (before.from != after.from) {
            changes.add("from: ${before.from} → ${after.from}")
        }
        if (before.to != after.to) {
            changes.add("to: ${before.to} → ${after.to}")
        }
        if (before.label != after.label) {
            changes.add("label: ${before.label} → ${after.label}")
        }
        if (before.attributes != after.attributes) {
            changes.add("attributes changed")
        }
        
        return changes
    }
}
