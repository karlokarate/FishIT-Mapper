package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*

/**
 * Detects important "hub" nodes in the graph based on various metrics.
 * 
 * Hubs are typically:
 * - Homepage (high out-degree, low in-degree)
 * - Navigation pages (high in-degree and out-degree)
 * - Important API endpoints (high in-degree)
 */
object HubDetector {
    
    data class NodeMetrics(
        val nodeId: NodeId,
        val inDegree: Int,
        val outDegree: Int,
        val betweenness: Double,
        val hubScore: Double
    )
    
    /**
     * Analyzes the graph and returns metrics for all nodes.
     */
    fun analyzeGraph(graph: MapGraph): Map<NodeId, NodeMetrics> {
        val inDegreeMap = mutableMapOf<NodeId, Int>()
        val outDegreeMap = mutableMapOf<NodeId, Int>()
        
        // Calculate in-degree and out-degree
        graph.nodes.forEach { node ->
            inDegreeMap[node.id] = 0
            outDegreeMap[node.id] = 0
        }
        
        graph.edges.forEach { edge ->
            outDegreeMap[edge.from] = (outDegreeMap[edge.from] ?: 0) + 1
            inDegreeMap[edge.to] = (inDegreeMap[edge.to] ?: 0) + 1
        }
        
        // Calculate betweenness centrality (simplified version)
        val betweennessMap = calculateBetweenness(graph)
        
        // Calculate hub score
        return graph.nodes.associate { node ->
            val inDegree = inDegreeMap[node.id] ?: 0
            val outDegree = outDegreeMap[node.id] ?: 0
            val betweenness = betweennessMap[node.id] ?: 0.0
            
            val hubScore = calculateHubScore(
                nodeKind = node.kind,
                inDegree = inDegree,
                outDegree = outDegree,
                betweenness = betweenness
            )
            
            node.id to NodeMetrics(
                nodeId = node.id,
                inDegree = inDegree,
                outDegree = outDegree,
                betweenness = betweenness,
                hubScore = hubScore
            )
        }
    }
    
    /**
     * Calculates a hub score based on multiple factors.
     * Higher score = more likely to be a hub.
     */
    private fun calculateHubScore(
        nodeKind: NodeKind,
        inDegree: Int,
        outDegree: Int,
        betweenness: Double
    ): Double {
        // Base score from connectivity
        val connectivityScore = (inDegree + outDegree) * 0.4
        
        // Betweenness contribution (nodes that are on many shortest paths)
        val betweennessScore = betweenness * 0.3
        
        // Kind-based weighting
        val kindWeight = when (nodeKind) {
            NodeKind.Page -> 1.0
            NodeKind.ApiEndpoint -> 0.8
            NodeKind.Document -> 0.6
            NodeKind.Form -> 0.7
            else -> 0.3
        }
        
        // Bonus for balanced in/out (navigation hubs)
        val balance = if (inDegree > 0 && outDegree > 0) {
            1.0 - kotlin.math.abs(inDegree - outDegree).toDouble() / (inDegree + outDegree)
        } else {
            0.0
        }
        val balanceScore = balance * 0.3
        
        return (connectivityScore + betweennessScore + balanceScore) * kindWeight
    }
    
    /**
     * Simplified betweenness centrality calculation.
     * Uses BFS to find shortest paths between all node pairs.
     */
    private fun calculateBetweenness(graph: MapGraph): Map<NodeId, Double> {
        val betweenness = mutableMapOf<NodeId, Double>()
        graph.nodes.forEach { betweenness[it.id] = 0.0 }
        
        // Build adjacency list
        val adjacency = mutableMapOf<NodeId, MutableList<NodeId>>()
        graph.edges.forEach { edge ->
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
        }
        
        // For each node as source
        graph.nodes.forEach { source ->
            // BFS to find shortest paths
            val distance = mutableMapOf<NodeId, Int>()
            val paths = mutableMapOf<NodeId, Int>()
            val predecessors = mutableMapOf<NodeId, MutableList<NodeId>>()
            val queue = mutableListOf<NodeId>()
            
            distance[source.id] = 0
            paths[source.id] = 1
            queue.add(source.id)
            
            val stack = mutableListOf<NodeId>()
            
            while (queue.isNotEmpty()) {
                val v = queue.removeAt(0)
                stack.add(v)
                
                adjacency[v]?.forEach { w ->
                    // First visit to w?
                    if (w !in distance) {
                        distance[w] = distance[v]!! + 1
                        queue.add(w)
                    }
                    // Shortest path to w via v?
                    if (distance[w] == distance[v]!! + 1) {
                        paths[w] = (paths[w] ?: 0) + (paths[v] ?: 0)
                        predecessors.getOrPut(w) { mutableListOf() }.add(v)
                    }
                }
            }
            
            // Accumulate betweenness
            val delta = mutableMapOf<NodeId, Double>()
            while (stack.isNotEmpty()) {
                val w = stack.removeLast()
                predecessors[w]?.forEach { v ->
                    val factor = (paths[v]?.toDouble() ?: 0.0) / (paths[w]?.toDouble() ?: 1.0)
                    delta[v] = (delta[v] ?: 0.0) + factor * (1.0 + (delta[w] ?: 0.0))
                }
                if (w != source.id) {
                    betweenness[w] = (betweenness[w] ?: 0.0) + (delta[w] ?: 0.0)
                }
            }
        }
        
        return betweenness
    }
    
    /**
     * Tags nodes that are identified as hubs.
     * Returns a new graph with hub tags applied.
     */
    fun tagHubs(graph: MapGraph, threshold: Double = 5.0): MapGraph {
        val metrics = analyzeGraph(graph)
        
        val updatedNodes = graph.nodes.map { node ->
            val metric = metrics[node.id]
            if (metric != null && metric.hubScore >= threshold) {
                val hubTag = when {
                    metric.outDegree > metric.inDegree * 2 -> "hub:homepage"
                    metric.inDegree > 5 && metric.outDegree > 5 -> "hub:navigation"
                    metric.inDegree > metric.outDegree * 2 -> "hub:important"
                    else -> "hub"
                }
                
                // Add tag if not already present
                if (hubTag !in node.tags) {
                    node.copy(tags = node.tags + hubTag)
                } else {
                    node
                }
            } else {
                node
            }
        }
        
        return graph.copy(nodes = updatedNodes)
    }
}
