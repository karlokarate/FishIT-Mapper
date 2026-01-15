package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlinx.datetime.Instant

/**
 * Applies recorder events to a [MapGraph].
 *
 * This is intentionally simple for MVP:
 * - it focuses on building a useful graph fast
 * - it does NOT attempt deep heuristics (hub detection, semantic clustering, etc.) yet
 */
class MappingEngine {

    fun applySession(graph: MapGraph, session: RecordingSession): MapGraph {
        var g = graph
        session.events.forEach { event ->
            g = applyEvent(g, event)
        }
        return g.copy(updatedAt = session.endedAt ?: session.startedAt)
    }

    fun applyEvent(graph: MapGraph, event: RecorderEvent): MapGraph {
        return when (event) {
            is NavigationEvent -> applyNavigation(graph, event)
            is ResourceRequestEvent -> applyResourceRequest(graph, event)
            is ResourceResponseEvent -> applyResourceResponse(graph, event)
            else -> graph
        }
    }

    private fun applyNavigation(graph: MapGraph, e: NavigationEvent): MapGraph {
        val toUrl = UrlNormalizer.normalize(e.url)
        val fromUrl = e.fromUrl?.let(UrlNormalizer::normalize)

        val (g1, toId) = upsertNode(
            graph = graph,
            url = toUrl,
            kind = NodeKind.Page,
            title = e.title,
            at = e.at
        )

        if (fromUrl == null) return g1

        val (g2, fromId) = upsertNode(
            graph = g1,
            url = fromUrl,
            kind = NodeKind.Page,
            title = null,
            at = e.at
        )

        val edgeKind = if (e.isRedirect) EdgeKind.Redirect else EdgeKind.Link

        return upsertEdge(
            graph = g2,
            from = fromId,
            to = toId,
            kind = edgeKind,
            label = null,
            at = e.at
        ).first
    }

    private fun applyResourceRequest(graph: MapGraph, e: ResourceRequestEvent): MapGraph {
        val resUrl = UrlNormalizer.normalize(e.url)
        val initUrl = e.initiatorUrl?.let(UrlNormalizer::normalize)

        val inferredNodeKind = when (e.resourceKind) {
            ResourceKind.Document -> NodeKind.Page
            ResourceKind.Fetch, ResourceKind.Xhr -> NodeKind.ApiEndpoint
            else -> NodeKind.Asset
        }

        val (g1, resId) = upsertNode(
            graph = graph,
            url = resUrl,
            kind = inferredNodeKind,
            title = null,
            at = e.at
        )

        if (initUrl == null) return g1

        val (g2, initId) = upsertNode(
            graph = g1,
            url = initUrl,
            kind = NodeKind.Page,
            title = null,
            at = e.at
        )

        val edgeKind = when (e.resourceKind) {
            ResourceKind.Xhr -> EdgeKind.Xhr
            ResourceKind.Fetch -> EdgeKind.Fetch
            else -> EdgeKind.AssetLoad
        }

        return upsertEdge(
            graph = g2,
            from = initId,
            to = resId,
            kind = edgeKind,
            label = e.method,
            at = e.at
        ).first
    }

    private fun applyResourceResponse(graph: MapGraph, e: ResourceResponseEvent): MapGraph {
        val url = UrlNormalizer.normalize(e.url)
        
        // Finde existierenden Node
        val existingNode = graph.nodes.find { it.url == url }
        
        if (existingNode != null) {
            // Enriche Node mit HTTP-Response-Daten
            val updatedAttributes = existingNode.attributes.toMutableMap().apply {
                put("httpStatusCode", e.statusCode.toString())
                put("contentType", e.contentType ?: "unknown")
                put("lastResponseTimeMs", e.responseTimeMs.toString())
                
                // Markiere Error-Pages
                if (e.statusCode >= 400) {
                    put("isErrorPage", "true")
                }
                
                // Markiere erfolgreiche Redirects
                if (e.isRedirect) {
                    put("isRedirect", "true")
                    e.redirectLocation?.let { put("redirectLocation", it) }
                }
            }
            
            // Ändere NodeKind basierend auf Status Code
            val newKind = when {
                e.statusCode >= 400 -> NodeKind.Error
                else -> existingNode.kind
            }
            
            val updatedNode = existingNode.copy(
                kind = newKind,
                attributes = updatedAttributes,
                lastSeenAt = e.at
            )
            
            val updatedNodes = graph.nodes.map { 
                if (it.id == existingNode.id) updatedNode else it 
            }
            
            var updatedGraph = graph.copy(nodes = updatedNodes)
            
            // Erstelle Redirect-Edge wenn Location Header vorhanden
            val redirectLoc = e.redirectLocation
            if (e.isRedirect && redirectLoc != null) {
                val targetUrl = UrlNormalizer.normalize(redirectLoc)
                val (g2, targetId) = upsertNode(updatedGraph, targetUrl, NodeKind.Page, null, e.at)
                
                updatedGraph = upsertEdge(
                    graph = g2,
                    from = existingNode.id,
                    to = targetId,
                    kind = EdgeKind.Redirect,
                    label = e.statusCode.toString(),  // "301", "302", etc.
                    at = e.at
                ).first
            }
            
            return updatedGraph.copy(updatedAt = e.at)
        }
        
        // Falls Node nicht existiert, erstelle einen neuen
        val kind = if (e.statusCode >= 400) NodeKind.Error else NodeKind.Page
        val attributes = mutableMapOf(
            "httpStatusCode" to e.statusCode.toString(),
            "contentType" to (e.contentType ?: "unknown"),
            "lastResponseTimeMs" to e.responseTimeMs.toString()
        )
        
        if (e.statusCode >= 400) {
            attributes["isErrorPage"] = "true"
        }
        
        var (g1, nodeId) = upsertNode(graph, url, kind, null, e.at)
        
        // Update attributes
        val node = g1.nodes.find { it.id == nodeId }
        if (node != null) {
            val updatedNode = node.copy(attributes = attributes)
            val updatedNodes = g1.nodes.map { if (it.id == nodeId) updatedNode else it }
            g1 = g1.copy(nodes = updatedNodes)
        }
        
        // Erstelle Redirect-Edge falls erforderlich
        val redirectLoc = e.redirectLocation
        if (e.isRedirect && redirectLoc != null) {
            val targetUrl = UrlNormalizer.normalize(redirectLoc)
            val (g2, targetId) = upsertNode(g1, targetUrl, NodeKind.Page, null, e.at)
            
            return upsertEdge(
                graph = g2,
                from = nodeId,
                to = targetId,
                kind = EdgeKind.Redirect,
                label = e.statusCode.toString(),
                at = e.at
            ).first.copy(updatedAt = e.at)
        }
        
        return g1.copy(updatedAt = e.at)
    }

    private fun upsertNode(
        graph: MapGraph,
        url: String,
        kind: NodeKind,
        title: String?,
        at: Instant
    ): Pair<MapGraph, NodeId> {
        val existing = graph.nodes.firstOrNull { it.url == url }
        return if (existing != null) {
            val updated = existing.copy(
                kind = mergeKind(existing.kind, kind),
                title = existing.title ?: title,
                firstSeenAt = existing.firstSeenAt ?: at,
                lastSeenAt = at
            )
            val newNodes = graph.nodes.map { if (it.id == existing.id) updated else it }
            graph.copy(nodes = newNodes) to existing.id
        } else {
            val id = IdGenerator.newNodeId()
            val node = MapNode(
                id = id,
                kind = kind,
                url = url,
                title = title,
                firstSeenAt = at,
                lastSeenAt = at
            )
            graph.copy(nodes = graph.nodes + node) to id
        }
    }

    private fun upsertEdge(
        graph: MapGraph,
        from: NodeId,
        to: NodeId,
        kind: EdgeKind,
        label: String?,
        at: Instant
    ): Pair<MapGraph, EdgeId> {
        val existing = graph.edges.firstOrNull { it.from == from && it.to == to && it.kind == kind && it.label == label }
        return if (existing != null) {
            val updated = existing.copy(
                firstSeenAt = existing.firstSeenAt ?: at,
                lastSeenAt = at
            )
            val newEdges = graph.edges.map { if (it.id == existing.id) updated else it }
            graph.copy(edges = newEdges) to existing.id
        } else {
            val id = IdGenerator.newEdgeId()
            val edge = MapEdge(
                id = id,
                kind = kind,
                from = from,
                to = to,
                label = label,
                firstSeenAt = at,
                lastSeenAt = at
            )
            graph.copy(edges = graph.edges + edge) to id
        }
    }

    private fun mergeKind(existing: NodeKind, incoming: NodeKind): NodeKind {
        // Heuristic: ApiEndpoint beats Asset beats Page, but never overwrite Error with something else.
        if (existing == NodeKind.Error) return existing
        if (incoming == NodeKind.Error) return incoming

        return when {
            existing == incoming -> existing
            existing == NodeKind.ApiEndpoint || incoming == NodeKind.ApiEndpoint -> NodeKind.ApiEndpoint
            existing == NodeKind.Asset || incoming == NodeKind.Asset -> NodeKind.Asset
            else -> existing
        }
    }
}
