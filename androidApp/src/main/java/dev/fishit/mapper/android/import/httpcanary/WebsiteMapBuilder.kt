package dev.fishit.mapper.android.import.httpcanary

import dev.fishit.mapper.contract.NavigationEvent
import dev.fishit.mapper.contract.RecorderEvent
import dev.fishit.mapper.contract.UserActionEvent
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Builds a WebsiteMap by correlating user actions with captured HTTP exchanges.
 *
 * The WebsiteMap provides a machine-readable representation of:
 * - Which user actions (clicks, form submits) triggered which network requests
 * - Redirect chains that occurred after each action
 * - The sequence of requests required to navigate the website
 *
 * ## Correlation Algorithm
 * For each UserActionEvent:
 * 1. Define a time window: [action.timestamp, nextAction.timestamp] or [action.timestamp, action.timestamp + 10s]
 * 2. Find all CapturedExchanges whose startedAt falls within this window
 * 3. Build redirect chains by following 30x responses
 * 4. Associate exchanges with the action
 *
 * ## Usage
 * ```kotlin
 * val builder = WebsiteMapBuilder()
 * val map = builder.build(
 *     sessionId = session.id.value,
 *     events = session.events,
 *     exchanges = importedExchanges
 * )
 * ```
 */
class WebsiteMapBuilder {

    companion object {
        /** Default correlation window duration when there's no next action */
        const val DEFAULT_WINDOW_MS = 10_000L // 10 seconds

        /** Maximum correlation window to prevent runaway windows */
        const val MAX_WINDOW_MS = 60_000L // 60 seconds
    }

    /**
     * Build a WebsiteMap from recorded events and imported exchanges.
     *
     * @param sessionId The recording session ID
     * @param events All recorded events from the session (UserActionEvent, NavigationEvent, etc.)
     * @param exchanges All imported HTTP exchanges from HttpCanary
     * @return WebsiteMap with actions correlated to exchanges
     */
    fun build(
        sessionId: String,
        events: List<RecorderEvent>,
        exchanges: List<CapturedExchange>
    ): WebsiteMap {
        // Filter and sort user actions
        val userActions = events
            .filterIsInstance<UserActionEvent>()
            .sortedBy { it.at }

        // Filter navigation events for context
        val navigations = events
            .filterIsInstance<NavigationEvent>()
            .sortedBy { it.at }

        // Sort exchanges by time
        val sortedExchanges = exchanges.sortedBy { it.startedAt }

        // Build correlated actions
        val correlatedActions = mutableListOf<CorrelatedAction>()

        for (i in userActions.indices) {
            val action = userActions[i]
            val nextAction = userActions.getOrNull(i + 1)

            // Determine correlation window
            val windowStart = action.at
            val windowEnd = calculateWindowEnd(action.at, nextAction?.at)

            // Find exchanges in this window
            val windowExchanges = findExchangesInWindow(sortedExchanges, windowStart, windowEnd)

            // Find navigation outcome (first NavigationEvent after this action)
            val navigationOutcome = findNavigationOutcome(navigations, action.at, windowEnd)

            // Build redirect chains
            val redirectChains = buildRedirectChains(windowExchanges)

            // Create correlated action
            correlatedActions.add(
                CorrelatedAction(
                    actionId = action.id.value,
                    timestamp = action.at,
                    actionType = action.action,
                    payload = action.payload,
                    navigationOutcome = navigationOutcome,
                    exchanges = windowExchanges.map { exchange ->
                        ExchangeReference(
                            exchangeId = exchange.exchangeId,
                            url = exchange.request.url,
                            method = exchange.request.method,
                            status = exchange.response?.status,
                            isRedirect = exchange.response?.isRedirect() ?: false,
                            redirectLocation = exchange.response?.redirectLocation
                        )
                    },
                    redirectChains = redirectChains
                )
            )
        }

        // Calculate summary statistics
        val totalExchanges = exchanges.size
        val correlatedExchangeIds = correlatedActions.flatMap { it.exchanges.map { e -> e.exchangeId } }.toSet()
        val uncorrelatedCount = totalExchanges - correlatedExchangeIds.size

        return WebsiteMap(
            sessionId = sessionId,
            generatedAt = kotlinx.datetime.Clock.System.now(),
            totalExchanges = totalExchanges,
            correlatedExchanges = correlatedExchangeIds.size,
            uncorrelatedExchanges = uncorrelatedCount,
            actions = correlatedActions
        )
    }

    /**
     * Calculate the end of the correlation window.
     */
    private fun calculateWindowEnd(actionTime: Instant, nextActionTime: Instant?): Instant {
        return if (nextActionTime != null) {
            // Use next action time, but cap at MAX_WINDOW
            val deltaMs = (nextActionTime - actionTime).inWholeMilliseconds
            if (deltaMs > MAX_WINDOW_MS) {
                Instant.fromEpochMilliseconds(actionTime.toEpochMilliseconds() + MAX_WINDOW_MS)
            } else {
                nextActionTime
            }
        } else {
            // Use default window
            Instant.fromEpochMilliseconds(actionTime.toEpochMilliseconds() + DEFAULT_WINDOW_MS)
        }
    }

    /**
     * Find all exchanges that started within the given time window.
     */
    private fun findExchangesInWindow(
        exchanges: List<CapturedExchange>,
        windowStart: Instant,
        windowEnd: Instant
    ): List<CapturedExchange> {
        return exchanges.filter { exchange ->
            exchange.startedAt >= windowStart && exchange.startedAt < windowEnd
        }
    }

    /**
     * Find the first navigation event that occurred after the action.
     */
    private fun findNavigationOutcome(
        navigations: List<NavigationEvent>,
        actionTime: Instant,
        windowEnd: Instant
    ): NavigationOutcome? {
        val nav = navigations.find { it.at > actionTime && it.at <= windowEnd }
        return nav?.let {
            NavigationOutcome(
                fromUrl = it.fromUrl,
                toUrl = it.url,
                timestamp = it.at,
                isRedirect = it.isRedirect
            )
        }
    }

    /**
     * Build redirect chains from a list of exchanges.
     * A redirect chain is a sequence of requests where each 30x response
     * leads to the next request.
     */
    private fun buildRedirectChains(exchanges: List<CapturedExchange>): List<RedirectChain> {
        val chains = mutableListOf<RedirectChain>()
        val usedExchangeIds = mutableSetOf<String>()

        // Find redirect responses that start chains
        val redirectExchanges = exchanges.filter {
            it.response?.isRedirect() == true && it.exchangeId !in usedExchangeIds
        }

        for (startExchange in redirectExchanges) {
            val chain = mutableListOf<RedirectStep>()
            var current = startExchange

            while (true) {
                usedExchangeIds.add(current.exchangeId)

                val redirectLocation = current.response?.redirectLocation
                if (redirectLocation == null || current.response?.isRedirect() != true) {
                    // End of chain (not a redirect or no location)
                    chain.add(
                        RedirectStep(
                            exchangeId = current.exchangeId,
                            url = current.request.url,
                            status = current.response?.status ?: 0,
                            location = null
                        )
                    )
                    break
                }

                chain.add(
                    RedirectStep(
                        exchangeId = current.exchangeId,
                        url = current.request.url,
                        status = current.response?.status ?: 0,
                        location = redirectLocation
                    )
                )

                // Find the next exchange that matches the redirect location
                val normalizedLocation = normalizeUrl(redirectLocation, current.request.url)
                val nextExchange = exchanges.find {
                    it.exchangeId !in usedExchangeIds &&
                    normalizeUrl(it.request.url, "") == normalizedLocation
                }

                if (nextExchange == null) {
                    // Redirect target not found in exchanges
                    break
                }

                current = nextExchange
            }

            if (chain.size > 1) {
                chains.add(
                    RedirectChain(
                        startUrl = startExchange.request.url,
                        finalUrl = chain.last().url,
                        steps = chain
                    )
                )
            }
        }

        return chains
    }

    /**
     * Normalize a URL for comparison, resolving relative URLs.
     */
    private fun normalizeUrl(url: String, baseUrl: String): String {
        return try {
            when {
                url.startsWith("http://") || url.startsWith("https://") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> {
                    // Absolute path - combine with base URL's origin
                    val base = java.net.URL(baseUrl)
                    "${base.protocol}://${base.host}${if (base.port != -1) ":${base.port}" else ""}$url"
                }
                else -> {
                    // Relative path
                    java.net.URL(java.net.URL(baseUrl), url).toString()
                }
            }
        } catch (e: Exception) {
            url
        }
    }
}

// ============================================================================
// WebsiteMap Data Models
// ============================================================================

/**
 * The complete WebsiteMap output - correlates user actions with HTTP exchanges.
 */
@Serializable
data class WebsiteMap(
    /** Recording session ID */
    val sessionId: String,

    /** When this map was generated */
    val generatedAt: Instant,

    /** Total number of exchanges imported */
    val totalExchanges: Int,

    /** Number of exchanges correlated with actions */
    val correlatedExchanges: Int,

    /** Number of exchanges not correlated (background requests, etc.) */
    val uncorrelatedExchanges: Int,

    /** User actions with correlated exchanges */
    val actions: List<CorrelatedAction>
)

/**
 * A user action with its correlated HTTP exchanges.
 */
@Serializable
data class CorrelatedAction(
    /** Original UserActionEvent ID */
    val actionId: String,

    /** When the action occurred */
    val timestamp: Instant,

    /** Action type (click, scroll, form_submit, etc.) */
    val actionType: String,

    /** Action payload (pageUrl, href, selector, text, etc.) */
    val payload: Map<String, String>,

    /** Navigation outcome if a page transition occurred */
    val navigationOutcome: NavigationOutcome?,

    /** HTTP exchanges triggered by this action */
    val exchanges: List<ExchangeReference>,

    /** Redirect chains within this action's exchanges */
    val redirectChains: List<RedirectChain>
)

/**
 * Navigation outcome after an action.
 */
@Serializable
data class NavigationOutcome(
    val fromUrl: String?,
    val toUrl: String,
    val timestamp: Instant,
    val isRedirect: Boolean
)

/**
 * Reference to a captured HTTP exchange.
 */
@Serializable
data class ExchangeReference(
    val exchangeId: String,
    val url: String,
    val method: String,
    val status: Int?,
    val isRedirect: Boolean,
    val redirectLocation: String?
)

/**
 * A chain of HTTP redirects.
 */
@Serializable
data class RedirectChain(
    /** URL where the redirect chain started */
    val startUrl: String,

    /** Final URL after all redirects */
    val finalUrl: String,

    /** Each step in the redirect chain */
    val steps: List<RedirectStep>
)

/**
 * A single step in a redirect chain.
 */
@Serializable
data class RedirectStep(
    val exchangeId: String,
    val url: String,
    val status: Int,
    val location: String?
)
