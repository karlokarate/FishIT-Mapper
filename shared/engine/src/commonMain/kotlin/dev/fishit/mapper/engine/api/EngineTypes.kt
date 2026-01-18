package dev.fishit.mapper.engine.api

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Plattform-neutrale Datentypen für die Engine-Analyse.
 *
 * Diese Typen werden im shared/engine module definiert, damit
 * sie sowohl von Android als auch von anderen Plattformen verwendet
 * werden können.
 *
 * Android-spezifische Implementierungen (HttpCanary, TrafficInterceptWebView)
 * konvertieren ihre Daten in diese Typen via Adapter.
 */

// ============================================================================
// HTTP Exchange Types
// ============================================================================

/**
 * Ein HTTP-Exchange (Request + Response) für die Engine-Analyse.
 *
 * Dies ist das plattform-neutrale Format, das von ApiBlueprintBuilder,
 * EndpointExtractor, AuthPatternDetector etc. verwendet wird.
 */
@Serializable
data class EngineExchange(
    /** Eindeutige ID für diesen Exchange */
    val exchangeId: String,

    /** Zeitpunkt des Request-Starts */
    val startedAt: Instant,

    /** Zeitpunkt der Response-Completion (optional) */
    val completedAt: Instant? = null,

    /** Der HTTP-Request */
    val request: EngineRequest,

    /** Die HTTP-Response (null wenn Request fehlgeschlagen) */
    val response: EngineResponse? = null,

    /** Protokoll-Typ: http, websocket, udp */
    val protocol: String = "http"
) {
    /** Helper für Content-Type des Requests */
    val requestContentType: String?
        get() = request.contentType ?: request.headers["Content-Type"] ?: request.headers["content-type"]

    /** Helper für Content-Type der Response */
    val responseContentType: String?
        get() = response?.contentType ?: response?.headers?.get("Content-Type") ?: response?.headers?.get("content-type")

    /** Convenience: Request Body */
    val requestBody: String?
        get() = request.body

    /** Convenience: Response Body */
    val responseBody: String?
        get() = response?.body
}

/**
 * Ein HTTP-Request für die Engine-Analyse.
 */
@Serializable
data class EngineRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null
) {
    /** Extrahiert den Host aus der URL */
    val host: String?
        get() = extractHost(url)

    /** Extrahiert den Pfad aus der URL */
    val path: String
        get() = extractPath(url)

    private fun extractHost(url: String): String? {
        return try {
            val withoutProtocol = url
                .removePrefix("https://")
                .removePrefix("http://")
            val endIndex = withoutProtocol.indexOfFirst { it == '/' || it == '?' || it == ':' }
            if (endIndex > 0) withoutProtocol.substring(0, endIndex) else withoutProtocol
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPath(url: String): String {
        return try {
            val withoutProtocol = url
                .removePrefix("https://")
                .removePrefix("http://")
            val hostEnd = withoutProtocol.indexOfFirst { it == '/' }
            if (hostEnd > 0) {
                val pathStart = withoutProtocol.substring(hostEnd)
                val queryStart = pathStart.indexOf('?')
                if (queryStart > 0) pathStart.substring(0, queryStart) else pathStart
            } else {
                "/"
            }
        } catch (e: Exception) {
            "/"
        }
    }
}

/**
 * Eine HTTP-Response für die Engine-Analyse.
 */
@Serializable
data class EngineResponse(
    val status: Int,
    val statusMessage: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
    val redirectLocation: String? = null
) {
    fun isRedirect(): Boolean = status in 300..399
    fun isSuccess(): Boolean = status in 200..299
    fun isClientError(): Boolean = status in 400..499
    fun isServerError(): Boolean = status in 500..599
}

// ============================================================================
// Correlated Action Types
// ============================================================================

/**
 * Referenz auf einen HTTP-Exchange.
 */
@Serializable
data class EngineExchangeReference(
    val exchangeId: String,
    val url: String,
    val method: String,
    val status: Int? = null,
    val isRedirect: Boolean = false
)

/**
 * Eine User-Aktion mit korrelierten HTTP-Exchanges.
 */
@Serializable
data class EngineCorrelatedAction(
    /** Eindeutige ID für diese Action */
    val actionId: String,

    /** Zeitpunkt der Action */
    val timestamp: Instant,

    /** Action-Typ (click, submit, navigation, etc.) */
    val actionType: String,

    /** Zusätzliche Payload-Daten */
    val payload: Map<String, String> = emptyMap(),

    /** Navigation-Ergebnis falls Seitenwechsel */
    val navigationOutcome: EngineNavigationOutcome? = null,

    /** Exchange-Referenzen */
    val exchanges: List<EngineExchangeReference> = emptyList()
) {
    /** Convenience: Liste der Exchange-IDs */
    val exchangeIds: List<String>
        get() = exchanges.map { it.exchangeId }
}

/**
 * Navigation-Ergebnis nach einer Action.
 */
@Serializable
data class EngineNavigationOutcome(
    val fromUrl: String?,
    val toUrl: String,
    val timestamp: Instant,
    val isRedirect: Boolean = false
)

// ============================================================================
// Website Map Types
// ============================================================================

/**
 * Eine vollständige WebsiteMap für die Engine-Analyse.
 */
@Serializable
data class EngineWebsiteMap(
    /** Session-ID */
    val sessionId: String,

    /** Generierungszeitpunkt */
    val generatedAt: Instant,

    /** Alle Exchanges */
    val exchanges: List<EngineExchange>,

    /** Korrelierte User-Actions */
    val actions: List<EngineCorrelatedAction>,

    /** Statistiken */
    val totalExchanges: Int,
    val correlatedExchanges: Int,
    val uncorrelatedExchanges: Int
)

// ============================================================================
// Type Aliases for backward compatibility
// ============================================================================

/**
 * Type Aliases zur Vereinfachung der Migration.
 * Diese können später entfernt werden, wenn alle Referenzen aktualisiert sind.
 */
typealias CapturedExchange = EngineExchange
typealias CapturedRequest = EngineRequest
typealias CapturedResponse = EngineResponse
typealias CorrelatedAction = EngineCorrelatedAction
typealias WebsiteMap = EngineWebsiteMap
typealias ExchangeReference = EngineExchangeReference
