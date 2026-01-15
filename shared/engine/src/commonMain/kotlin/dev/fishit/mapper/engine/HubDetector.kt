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
    
    // Hub score calculation weights
    private const val CONNECTIVITY_WEIGHT = 0.4
    private const val BETWEENNESS_WEIGHT = 0.3
    private const val BALANCE_WEIGHT = 0.3
    
    // Hub detection thresholds
    private const val DEFAULT_HUB_THRESHOLD = 5.0
    private const val NAVIGATION_HUB_MIN_DEGREE = 5
    private const val DEGREE_RATIO_THRESHOLD = 2
    
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
        val connectivityScore = (inDegree + outDegree) * CONNECTIVITY_WEIGHT
        
        // Betweenness contribution (nodes that are on many shortest paths)
        val betweennessScore = betweenness * BETWEENNESS_WEIGHT
        
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
        val balanceScore = balance * BALANCE_WEIGHT
        
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
            val queue = ArrayDeque<NodeId>()
            
            distance[source.id] = 0
            paths[source.id] = 1
            queue.addLast(source.id)
            
            val stack = mutableListOf<NodeId>()
            
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
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
                    // In Brandes' algorithm, paths[w] and paths[v] should be > 0
                    // for all predecessor relationships, as they were reached via BFS.
                    // We check this defensively to avoid division by zero and
                    // mathematically implausible states.
                    val pathsWInt = paths[w] ?: 0
                    val pathsVInt = paths[v] ?: 0
                    if (pathsWInt <= 0 || pathsVInt <= 0) {
                        // Inconsistent state: skip this contribution
                        return@forEach
                    }
                    val factor = pathsVInt.toDouble() / pathsWInt.toDouble()
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
    fun tagHubs(graph: MapGraph, threshold: Double = DEFAULT_HUB_THRESHOLD): MapGraph {
        val metrics = analyzeGraph(graph)
        
        val updatedNodes = graph.nodes.map { node ->
            val metric = metrics[node.id]
            if (metric != null && metric.hubScore >= threshold) {
                val hubTag = when {
                    metric.outDegree > metric.inDegree * DEGREE_RATIO_THRESHOLD -> "hub:homepage"
                    metric.inDegree > NAVIGATION_HUB_MIN_DEGREE && metric.outDegree > NAVIGATION_HUB_MIN_DEGREE -> "hub:navigation"
                    metric.inDegree > metric.outDegree * DEGREE_RATIO_THRESHOLD -> "hub:important"
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
