package dev.fishit.mapper.android.capture

import dev.fishit.mapper.engine.api.*
import kotlinx.datetime.Instant

/**
 * Adapter um WebView Capture Sessions in die Engine zu integrieren.
 *
 * Konvertiert das WebView-spezifische Format in das Engine-Format
 * für API Blueprint Analyse und Export.
 *
 * ## Verwendung
 * ```kotlin
 * val session = sessionManager.stopSession()
 * val adapter = SessionToEngineAdapter()
 *
 * // Zu Engine-Format konvertieren
 * val exchanges = adapter.toEngineExchanges(session)
 * val actions = adapter.toEngineActions(session)
 *
 * // API Blueprint bauen
 * val blueprint = ApiBlueprintBuilder().build(
 *     projectId = session.id,
 *     projectName = session.name,
 *     exchanges = exchanges
 * )
 *
 * // Exportieren
 * val har = ExportOrchestrator.exportToHar(exchanges)
 * ```
 */
class SessionToEngineAdapter {

    /**
     * Konvertiert WebView CapturedExchanges zu Engine EngineExchanges.
     */
    fun toEngineExchanges(session: CaptureSessionManager.CaptureSession): List<EngineExchange> {
        return session.exchanges.mapNotNull { exchange ->
            try {
                toEngineExchange(exchange)
            } catch (e: Exception) {
                // Ungültige Exchanges überspringen
                null
            }
        }
    }

    /**
     * Konvertiert einen einzelnen WebView Exchange zu Engine Exchange.
     */
    fun toEngineExchange(exchange: TrafficInterceptWebView.CapturedExchange): EngineExchange {
        return EngineExchange(
            exchangeId = exchange.id,
            startedAt = exchange.startedAt,
            completedAt = exchange.completedAt,
            request = EngineRequest(
                method = exchange.method,
                url = exchange.url,
                headers = exchange.requestHeaders,
                body = exchange.requestBody,
                contentType = exchange.requestHeaders["Content-Type"]
                    ?: exchange.requestHeaders["content-type"]
            ),
            response = if (exchange.responseStatus != null) {
                EngineResponse(
                    status = exchange.responseStatus,
                    statusMessage = httpStatusText(exchange.responseStatus),
                    headers = exchange.responseHeaders ?: emptyMap(),
                    body = exchange.responseBody,
                    contentType = exchange.responseHeaders?.get("Content-Type")
                        ?: exchange.responseHeaders?.get("content-type"),
                    redirectLocation = exchange.responseHeaders?.get("Location")
                )
            } else null,
            protocol = "http"
        )
    }

    /**
     * Konvertiert WebView UserActions zu Engine CorrelatedActions.
     */
    fun toEngineActions(session: CaptureSessionManager.CaptureSession): List<EngineCorrelatedAction> {
        return session.userActions.map { action ->
            val relatedExchanges = session.correlate(action)

            EngineCorrelatedAction(
                actionId = action.id,
                timestamp = action.timestamp,
                actionType = action.type.name.lowercase(),
                payload = buildMap {
                    put("target", action.target)
                    action.value?.let { put("value", it) }
                    action.pageUrl?.let { put("pageUrl", it) }
                },
                navigationOutcome = null, // Could be enhanced
                exchanges = relatedExchanges.map { ex ->
                    EngineExchangeReference(
                        exchangeId = ex.id,
                        url = ex.url,
                        method = ex.method,
                        status = ex.responseStatus,
                        isRedirect = ex.responseStatus in 300..399
                    )
                }
            )
        }
    }

    /**
     * Konvertiert eine vollständige Session für die Blueprint-Analyse.
     */
    fun toAnalysisInput(session: CaptureSessionManager.CaptureSession): AnalysisInput {
        return AnalysisInput(
            sessionId = session.id,
            sessionName = session.name,
            targetUrl = session.targetUrl,
            exchanges = toEngineExchanges(session),
            correlatedActions = toEngineActions(session),
            startedAt = session.startedAt,
            stoppedAt = session.stoppedAt
        )
    }

    /**
     * Konvertiert Session zu EngineWebsiteMap.
     */
    fun toEngineWebsiteMap(session: CaptureSessionManager.CaptureSession): EngineWebsiteMap {
        val exchanges = toEngineExchanges(session)
        val actions = toEngineActions(session)
        val correlatedIds = actions.flatMap { it.exchangeIds }.toSet()

        return EngineWebsiteMap(
            sessionId = session.id,
            generatedAt = session.stoppedAt ?: session.startedAt,
            exchanges = exchanges,
            actions = actions,
            totalExchanges = exchanges.size,
            correlatedExchanges = correlatedIds.size,
            uncorrelatedExchanges = exchanges.size - correlatedIds.size
        )
    }

    /**
     * Input-Format für die Blueprint-Analyse.
     */
    data class AnalysisInput(
        val sessionId: String,
        val sessionName: String,
        val targetUrl: String?,
        val exchanges: List<EngineExchange>,
        val correlatedActions: List<EngineCorrelatedAction>,
        val startedAt: Instant,
        val stoppedAt: Instant?
    )

    private fun httpStatusText(code: Int): String {
        return when (code) {
            100 -> "Continue"
            101 -> "Switching Protocols"
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            303 -> "See Other"
            304 -> "Not Modified"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            410 -> "Gone"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Unknown"
        }
    }
}
