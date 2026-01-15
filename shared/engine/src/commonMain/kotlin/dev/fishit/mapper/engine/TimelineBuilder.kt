package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlinx.datetime.Instant

/**
 * Builds a unified timeline from a recording session.
 * 
 * Features:
 * - Correlates Request-Response pairs
 * - Builds parent-child tree structure (Start-URL as root)
 * - Maintains chronological event order
 * - Tracks navigation depth for hierarchical display
 */
object TimelineBuilder {
    
    // Configuration constants
    private const val CORRELATION_WINDOW_MS = 30_000L // 30 seconds
    
    /**
     * Builds a unified timeline from a recording session.
     */
    fun buildTimeline(session: RecordingSession, graph: MapGraph): UnifiedTimeline {
        val entries = buildTimelineEntries(session.events)
        val treeNodes = buildSessionTree(session, graph)
        val rootNodeId = findRootNode(treeNodes, session.initialUrl)
        
        return UnifiedTimeline(
            sessionId = session.id,
            entries = entries,
            treeNodes = treeNodes,
            rootNodeId = rootNodeId
        )
    }
    
    /**
     * Builds timeline entries with correlation metadata.
     */
    private fun buildTimelineEntries(events: List<RecorderEvent>): List<TimelineEntry> {
        val entries = mutableListOf<TimelineEntry>()
        val requestMap = mutableMapOf<EventId, ResourceRequestEvent>()
        val responseMap = mutableMapOf<EventId, ResourceResponseEvent>()
        
        // First pass: collect requests and responses with normalized URL index for fast lookup
        // Map: normalizedURL -> List of requests (sorted by time)
        val requestsByUrl = mutableMapOf<String, MutableList<ResourceRequestEvent>>()
        
        events.forEach { event ->
            when (event) {
                is ResourceRequestEvent -> {
                    requestMap[event.id] = event
                    val normalizedUrl = UrlNormalizer.normalize(event.url)
                    requestsByUrl.getOrPut(normalizedUrl) { mutableListOf() }.add(event)
                }
                is ResourceResponseEvent -> responseMap[event.id] = event
                else -> {}
            }
        }
        
        // Second pass: build entries with correlations
        val navigationStack = mutableListOf<EventId>()
        
        events.forEach { event ->
            val correlatedEventId: EventId?
            val parentEventId: EventId?
            val depth: Int
            
            when (event) {
                is NavigationEvent -> {
                    // Navigation is top-level or child of previous navigation
                    parentEventId = navigationStack.lastOrNull()
                    correlatedEventId = null
                    depth = navigationStack.size
                    
                    if (!event.isRedirect) {
                        // New navigation branch
                        navigationStack.add(event.id)
                    }
                }
                
                is ResourceRequestEvent -> {
                    // Request is child of current navigation
                    parentEventId = navigationStack.lastOrNull()
                    correlatedEventId = null
                    depth = navigationStack.size  // Direct child of navigation has same depth + 0 offset
                }
                
                is ResourceResponseEvent -> {
                    // Try to correlate with request using O(1) URL lookup + linear search within same URL
                    val normalizedUrl = UrlNormalizer.normalize(event.url)
                    val candidateRequests = requestsByUrl[normalizedUrl] ?: emptyList()
                    
                    val matchingRequest = candidateRequests.findLast { req ->
                        req.at <= event.at &&
                        (event.at.toEpochMilliseconds() - req.at.toEpochMilliseconds()) < CORRELATION_WINDOW_MS
                    }
                    
                    parentEventId = matchingRequest?.id ?: navigationStack.lastOrNull()
                    correlatedEventId = matchingRequest?.id
                    depth = navigationStack.size  // Same depth as requests
                }
                
                is UserActionEvent -> {
                    // User action at current navigation level
                    parentEventId = navigationStack.lastOrNull()
                    correlatedEventId = null
                    depth = navigationStack.size  // Same level as navigation
                }
                
                is ConsoleMessageEvent -> {
                    // Console message at current navigation level
                    parentEventId = navigationStack.lastOrNull()
                    correlatedEventId = null
                    depth = navigationStack.size  // Same level as navigation
                }
                
                else -> {
                    parentEventId = navigationStack.lastOrNull()
                    correlatedEventId = null
                    depth = navigationStack.size  // Same level as navigation
                }
            }
            
            entries.add(
                TimelineEntry(
                    event = event,
                    correlatedEventId = correlatedEventId,
                    parentEventId = parentEventId,
                    depth = depth
                )
            )
        }
        
        return entries
    }
    
    /**
     * Builds a tree structure of session nodes based on navigation flow.
     * Start URL becomes the root node, redirects and navigations form children.
     */
    private fun buildSessionTree(session: RecordingSession, graph: MapGraph): List<SessionTreeNode> {
        val treeNodes = mutableListOf<SessionTreeNode>()
        val nodeMap = graph.nodes.associateBy { UrlNormalizer.normalize(it.url) }
        val visitedUrls = mutableSetOf<String>()
        val urlToNodeId = mutableMapOf<String, NodeId>()
        val parentMap = mutableMapOf<String, String>()
        
        // Build parent-child relationships from navigation events
        var lastUrl: String? = null
        session.events.filterIsInstance<NavigationEvent>().forEach { navEvent ->
            val normalizedUrl = UrlNormalizer.normalize(navEvent.url)
            
            if (!visitedUrls.contains(normalizedUrl)) {
                visitedUrls.add(normalizedUrl)
                
                // If there's a fromUrl, it's the parent
                val fromUrl = navEvent.fromUrl
                if (fromUrl != null) {
                    val fromNormalized = UrlNormalizer.normalize(fromUrl)
                    parentMap[normalizedUrl] = fromNormalized
                } else {
                    val currentLastUrl = lastUrl
                    if (currentLastUrl != null && navEvent.isRedirect) {
                        // Redirect chains
                        parentMap[normalizedUrl] = currentLastUrl
                    }
                }
                
                lastUrl = normalizedUrl
            }
        }
        
        // Create tree nodes
        visitedUrls.forEach { url ->
            val node = nodeMap[url]
            if (node != null) {
                urlToNodeId[url] = node.id
            } else {
                // Generate consistent NodeId for nodes not in graph yet
                urlToNodeId[url] = IdGenerator.newNodeId()
            }
        }
        
        // Calculate children for each node
        val childrenMap = mutableMapOf<String, MutableList<String>>()
        parentMap.forEach { (child, parent) ->
            childrenMap.getOrPut(parent) { mutableListOf() }.add(child)
        }
        
        // Build tree nodes with depth calculation
        fun calculateDepth(url: String, visited: Set<String> = emptySet()): Int {
            if (url in visited) return 0 // Cycle detection
            val parent = parentMap[url] ?: return 0
            return 1 + calculateDepth(parent, visited + url)
        }
        
        visitedUrls.forEach { url ->
            val node = nodeMap[url]
            val nodeId = urlToNodeId[url]
            if (nodeId != null) {
                val parentUrl = parentMap[url]
                val parentNodeId = parentUrl?.let { urlToNodeId[it] }
                val children = childrenMap[url]?.mapNotNull { urlToNodeId[it] } ?: emptyList()
                val depth = calculateDepth(url)
                
                // Find all events related to this URL
                val eventIds = session.events.filter { event ->
                    when (event) {
                        is NavigationEvent -> UrlNormalizer.normalize(event.url) == url
                        is ResourceRequestEvent -> UrlNormalizer.normalize(event.url) == url
                        is ResourceResponseEvent -> UrlNormalizer.normalize(event.url) == url
                        else -> false
                    }
                }.map { it.id }
                
                treeNodes.add(
                    SessionTreeNode(
                        nodeId = nodeId,
                        url = url,
                        title = node?.title,
                        parentNodeId = parentNodeId,
                        children = children,
                        depth = depth,
                        eventIds = eventIds
                    )
                )
            }
        }
        
        return treeNodes.sortedBy { it.depth }
    }
    
    /**
     * Finds the root node (initial URL).
     */
    private fun findRootNode(treeNodes: List<SessionTreeNode>, initialUrl: String): NodeId? {
        val normalized = UrlNormalizer.normalize(initialUrl)
        return treeNodes.find { 
            UrlNormalizer.normalize(it.url) == normalized && it.parentNodeId == null 
        }?.nodeId
    }
}
