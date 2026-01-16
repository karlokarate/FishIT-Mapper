package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/**
 * Builds a WebsiteMap from a recording session.
 *
 * A WebsiteMap correlates user actions (clicks, form submits) with the resulting HTTP
 * request/response chains, including redirects.
 *
 * This enables:
 * - Understanding which actions trigger which network requests
 * - Mapping redirect chains (e.g., login → redirect → dashboard)
 * - Generating machine-readable "interaction maps" for automation
 */
object WebsiteMapBuilder {

    // Configuration
    private val DEFAULT_ACTION_WINDOW = 10.seconds

    /**
     * Builds a WebsiteMap from a recording session.
     *
     * @param session The recording session with events
     * @return A WebsiteMap with correlated action→request mappings
     */
    fun buildWebsiteMap(session: RecordingSession): WebsiteMap {
        val actions = correlateActionsToRequests(session.events)

        return WebsiteMap(
                sessionId = session.id,
                initialUrl = session.initialUrl,
                finalUrl = session.finalUrl,
                actions = actions,
                generatedAt = kotlinx.datetime.Clock.System.now()
        )
    }

    /** Correlates user actions to HTTP request/response chains. */
    private fun correlateActionsToRequests(events: List<RecorderEvent>): List<ActionMapping> {
        val mappings = mutableListOf<ActionMapping>()

        // Index events by type
        val userActions = events.filterIsInstance<UserActionEvent>()
        val requests = events.filterIsInstance<ResourceRequestEvent>()
        val responses = events.filterIsInstance<ResourceResponseEvent>()
        val navigations = events.filterIsInstance<NavigationEvent>()

        // Build response lookup by requestId
        val responseByRequestId = responses.associateBy { it.requestId }

        // For each user action, find correlated events
        userActions.forEachIndexed { index, action ->
            val actionTime = action.at

            // Determine action window end (next action or default timeout)
            val nextActionTime = userActions.getOrNull(index + 1)?.at
            val windowEnd =
                    if (nextActionTime != null &&
                                    (nextActionTime.toEpochMilliseconds() -
                                            actionTime.toEpochMilliseconds()) <
                                            DEFAULT_ACTION_WINDOW.inWholeMilliseconds
                    ) {
                        nextActionTime
                    } else {
                        Instant.fromEpochMilliseconds(
                                actionTime.toEpochMilliseconds() +
                                        DEFAULT_ACTION_WINDOW.inWholeMilliseconds
                        )
                    }

            // Find requests within the action window
            val windowRequests =
                    requests.filter { req -> req.at >= actionTime && req.at <= windowEnd }

            // Find navigations within the action window
            val windowNavigations =
                    navigations.filter { nav -> nav.at >= actionTime && nav.at <= windowEnd }

            // Build HTTP exchanges (request + response pairs)
            val httpExchanges =
                    windowRequests.map { req ->
                        val response = responseByRequestId[req.id]
                        HttpExchange(
                                requestId = req.id,
                                url = req.url,
                                method = req.method ?: "GET",
                                requestAt = req.at,
                                responseAt = response?.at,
                                statusCode = response?.statusCode,
                                isRedirect = response?.isRedirect ?: false,
                                redirectLocation = response?.redirectLocation,
                                contentType = response?.contentType
                        )
                    }

            // Build navigation outcome (first navigation after action)
            val navigationOutcome =
                    if (windowNavigations.isNotEmpty()) {
                        val firstNav = windowNavigations.first()
                        NavigationOutcome(
                                url = firstNav.url,
                                title = firstNav.title,
                                isRedirect = firstNav.isRedirect,
                                redirectChain = buildRedirectChain(firstNav, windowNavigations)
                        )
                    } else {
                        null
                    }

            mappings.add(
                    ActionMapping(
                            actionId = action.id,
                            actionType = action.action,
                            actionAt = action.at,
                            payload = action.payload,
                            navigationOutcome = navigationOutcome,
                            httpExchanges = httpExchanges
                    )
            )
        }

        return mappings
    }

    /** Builds a redirect chain from navigation events. */
    private fun buildRedirectChain(
            firstNav: NavigationEvent,
            navigations: List<NavigationEvent>
    ): List<String> {
        val chain = mutableListOf<String>()
        var current = firstNav

        // Follow redirects
        for (nav in navigations) {
            if (nav.at > current.at && nav.isRedirect) {
                chain.add(nav.url)
                current = nav
            }
        }

        return chain
    }
}

// ============================================================================
// Data Classes for WebsiteMap (to be generated via ContractGenerator)
// ============================================================================

/** A complete website map for a recording session. */
data class WebsiteMap(
        val sessionId: SessionId,
        val initialUrl: String,
        val finalUrl: String?,
        val actions: List<ActionMapping>,
        val generatedAt: Instant
)

/** Maps a user action to its resulting network activity. */
data class ActionMapping(
        val actionId: EventId,
        val actionType: String,
        val actionAt: Instant,
        val payload: Map<String, String>,
        val navigationOutcome: NavigationOutcome?,
        val httpExchanges: List<HttpExchange>
)

/** The navigation result of a user action. */
data class NavigationOutcome(
        val url: String,
        val title: String?,
        val isRedirect: Boolean,
        val redirectChain: List<String>
)

/** A single HTTP request/response exchange. */
data class HttpExchange(
        val requestId: EventId,
        val url: String,
        val method: String,
        val requestAt: Instant,
        val responseAt: Instant?,
        val statusCode: Int?,
        val isRedirect: Boolean,
        val redirectLocation: String?,
        val contentType: String?
)
