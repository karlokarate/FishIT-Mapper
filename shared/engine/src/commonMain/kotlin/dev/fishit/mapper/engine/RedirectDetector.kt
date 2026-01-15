package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Improved redirect detection with better heuristics.
 * 
 * Detects redirects based on:
 * - Timing between navigations (fast succession)
 * - URL patterns (same domain redirects)
 * - HTTP status codes (if available in attributes)
 */
object RedirectDetector {
    
    // Threshold for considering a navigation as a redirect
    private const val REDIRECT_THRESHOLD_MS = 800L
    
    // Threshold for same-domain redirects (common for auth flows)
    private const val SAME_DOMAIN_REDIRECT_THRESHOLD_MS = 2000L
    
    /**
     * Analyzes a sequence of navigation events to detect likely redirects.
     */
    fun analyzeNavigationSequence(events: List<NavigationEvent>): List<RedirectInfo> {
        val redirects = mutableListOf<RedirectInfo>()
        
        for (i in 0 until events.size - 1) {
            val current = events[i]
            val next = events[i + 1]
            
            val timeDiff = (next.at.toEpochMilliseconds() - current.at.toEpochMilliseconds())
            
            if (isLikelyRedirect(current, next, timeDiff)) {
                redirects.add(
                    RedirectInfo(
                        fromEvent = current,
                        toEvent = next,
                        timeDiffMs = timeDiff,
                        reason = getRedirectReason(current, next, timeDiff)
                    )
                )
            }
        }
        
        return redirects
    }
    
    /**
     * Detects redirect chains in the graph.
     */
    fun detectRedirectChains(graph: MapGraph): List<RedirectChain> {
        val chains = mutableListOf<RedirectChain>()
        val redirectEdges = graph.edges.filter { it.kind == EdgeKind.Redirect }
        
        // Build adjacency map for redirects
        val redirectMap = mutableMapOf<NodeId, NodeId>()
        redirectEdges.forEach { edge ->
            redirectMap[edge.from] = edge.to
        }
        
        val visited = mutableSetOf<NodeId>()
        
        // Find all redirect chains
        graph.nodes.forEach { startNode ->
            if (startNode.id !in visited && startNode.id in redirectMap) {
                val chain = mutableListOf<NodeId>()
                val seenInChain = mutableSetOf<NodeId>()
                var current: NodeId? = startNode.id
                
                // Follow redirect chain, stop on cycle or dead end
                while (current != null && current in redirectMap && current !in seenInChain) {
                    chain.add(current)
                    seenInChain.add(current)
                    visited.add(current)
                    current = redirectMap[current]
                }
                
                // Add final destination if it's not already in chain (not a cycle)
                if (current != null && current !in seenInChain) {
                    chain.add(current)
                    visited.add(current)
                }
                
                if (chain.size >= 2) {
                    chains.add(
                        RedirectChain(
                            nodes = chain,
                            length = chain.size
                        )
                    )
                }
            }
        }
        
        return chains.sortedByDescending { it.length }
    }
    
    private fun isLikelyRedirect(
        current: NavigationEvent,
        next: NavigationEvent,
        timeDiffMs: Long
    ): Boolean {
        // Already marked as redirect
        if (current.isRedirect) return true
        
        // Fast succession navigation
        if (timeDiffMs <= REDIRECT_THRESHOLD_MS) {
            return true
        }
        
        // Same domain redirect pattern (common for auth flows)
        if (isSameDomainRedirect(current.url, next.url) && timeDiffMs <= SAME_DOMAIN_REDIRECT_THRESHOLD_MS) {
            return true
        }
        
        return false
    }
    
    private fun getRedirectReason(
        current: NavigationEvent,
        next: NavigationEvent,
        timeDiffMs: Long
    ): String {
        return when {
            current.isRedirect -> "Marked as redirect"
            timeDiffMs <= 300 -> "Immediate redirect (${timeDiffMs}ms)"
            timeDiffMs <= REDIRECT_THRESHOLD_MS -> "Fast redirect (${timeDiffMs}ms)"
            isSameDomainRedirect(current.url, next.url) -> "Same-domain redirect"
            else -> "Likely redirect"
        }
    }
    
    private fun isSameDomainRedirect(url1: String, url2: String): Boolean {
        val domain1 = extractDomain(url1)
        val domain2 = extractDomain(url2)
        return domain1 != null && domain1 == domain2
    }
    
    private fun extractDomain(url: String): String? {
        return try {
            val cleanUrl = url.substringAfter("://")
            val domain = cleanUrl.substringBefore("/").substringBefore(":")
            domain.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Kombiniert HTTP-basierte und Timing-basierte Redirect-Erkennung.
     * HTTP-Status hat Priorität wenn verfügbar.
     */
    fun analyzeWithHttpStatus(
        navigationEvents: List<NavigationEvent>,
        responseEvents: List<ResourceResponseEvent>
    ): List<EnhancedRedirectInfo> {
        val results = mutableListOf<EnhancedRedirectInfo>()
        
        // Erstelle URL -> Response-Event Map für schnellen Lookup
        val responseByUrl = responseEvents.filter { it.isRedirect }.associateBy { UrlNormalizer.normalize(it.url) }
        
        for (i in 0 until navigationEvents.size - 1) {
            val current = navigationEvents[i]
            val next = navigationEvents[i + 1]
            val timeDiff = next.at.toEpochMilliseconds() - current.at.toEpochMilliseconds()
            
            val normalizedUrl = UrlNormalizer.normalize(current.url)
            val httpRedirect = responseByUrl[normalizedUrl]
            
            val isRedirect: Boolean
            val reason: String
            val method: RedirectDetectionMethod
            
            when {
                // Priorität 1: HTTP Status Code
                httpRedirect != null -> {
                    isRedirect = true
                    reason = "HTTP ${httpRedirect.statusCode} redirect to ${httpRedirect.redirectLocation}"
                    method = RedirectDetectionMethod.HTTP_STATUS
                }
                // Priorität 2: Manuell markiert
                current.isRedirect -> {
                    isRedirect = true
                    reason = "Marked as redirect"
                    method = RedirectDetectionMethod.MANUAL
                }
                // Priorität 3: Timing-Heuristik
                timeDiff <= REDIRECT_THRESHOLD_MS -> {
                    isRedirect = true
                    reason = "Fast redirect (${timeDiff}ms)"
                    method = RedirectDetectionMethod.TIMING
                }
                // Priorität 4: Same-Domain-Heuristik
                isSameDomainRedirect(current.url, next.url) && timeDiff <= SAME_DOMAIN_REDIRECT_THRESHOLD_MS -> {
                    isRedirect = true
                    reason = "Same-domain redirect"
                    method = RedirectDetectionMethod.SAME_DOMAIN
                }
                else -> {
                    isRedirect = false
                    reason = "Not a redirect"
                    method = RedirectDetectionMethod.NONE
                }
            }
            
            if (isRedirect) {
                results.add(
                    EnhancedRedirectInfo(
                        fromEvent = current,
                        toEvent = next,
                        timeDiffMs = timeDiff,
                        reason = reason,
                        method = method,
                        httpStatusCode = httpRedirect?.statusCode,
                        responseEvent = httpRedirect
                    )
                )
            }
        }
        
        return results
    }
    
    enum class RedirectDetectionMethod {
        HTTP_STATUS,      // Zuverlässig: HTTP 3xx Status Code
        MANUAL,           // Zuverlässig: Manuell markiert
        TIMING,           // Heuristik: Schnelle Navigation
        SAME_DOMAIN,      // Heuristik: Same-Domain Navigation
        NONE              // Kein Redirect
    }
    
    data class EnhancedRedirectInfo(
        val fromEvent: NavigationEvent,
        val toEvent: NavigationEvent,
        val timeDiffMs: Long,
        val reason: String,
        val method: RedirectDetectionMethod,
        val httpStatusCode: Int? = null,
        val responseEvent: ResourceResponseEvent? = null
    )
    
    data class RedirectInfo(
        val fromEvent: NavigationEvent,
        val toEvent: NavigationEvent,
        val timeDiffMs: Long,
        val reason: String
    )
    
    data class RedirectChain(
        val nodes: List<NodeId>,
        val length: Int
    )
}
