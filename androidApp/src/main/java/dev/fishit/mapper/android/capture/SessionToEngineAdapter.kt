package dev.fishit.mapper.android.capture

import dev.fishit.mapper.engine.api.*
import dev.fishit.mapper.engine.bundle.HttpExchange
import dev.fishit.mapper.engine.bundle.HttpHeaders
import dev.fishit.mapper.engine.bundle.HttpRequest
import dev.fishit.mapper.engine.bundle.HttpResponse

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
 * val exchanges = adapter.toHttpExchanges(session)
 * val actions = adapter.toCorrelatedActions(session)
 *
 * // API Blueprint bauen
 * val blueprint = ApiBlueprintBuilder().build(
 *     exchanges = exchanges,
 *     correlatedActions = actions,
 *     name = session.name
 * )
 *
 * // Exportieren
 * val har = ExportOrchestrator.exportToHar(exchanges)
 * ```
 */
class SessionToEngineAdapter {

    /**
     * Konvertiert WebView CapturedExchanges zu Engine HttpExchanges.
     */
    fun toHttpExchanges(session: CaptureSessionManager.CaptureSession): List<HttpExchange> {
        return session.exchanges.mapNotNull { exchange ->
            try {
                toHttpExchange(exchange)
            } catch (e: Exception) {
                // Ungültige Exchanges überspringen
                null
            }
        }
    }

    /**
     * Konvertiert einen einzelnen WebView Exchange zu Engine Exchange.
     */
    fun toHttpExchange(exchange: TrafficInterceptWebView.CapturedExchange): HttpExchange {
        val parsedUrl = java.net.URL(exchange.url)

        return HttpExchange(
            id = exchange.id,
            startedAt = exchange.startedAt.toEpochMilliseconds(),
            completedAt = exchange.completedAt?.toEpochMilliseconds()
                ?: exchange.startedAt.toEpochMilliseconds(),
            request = HttpRequest(
                method = exchange.method,
                url = exchange.url,
                path = parsedUrl.path.ifEmpty { "/" },
                query = parsedUrl.query,
                headers = HttpHeaders(
                    entries = exchange.requestHeaders.map { (k, v) ->
                        HttpHeaders.Header(k, v)
                    }
                ),
                body = exchange.requestBody,
                contentType = exchange.requestHeaders["Content-Type"]
                    ?: exchange.requestHeaders["content-type"]
            ),
            response = if (exchange.responseStatus != null) {
                HttpResponse(
                    statusCode = exchange.responseStatus,
                    statusText = httpStatusText(exchange.responseStatus),
                    headers = HttpHeaders(
                        entries = exchange.responseHeaders?.map { (k, v) ->
                            HttpHeaders.Header(k, v)
                        } ?: emptyList()
                    ),
                    body = exchange.responseBody,
                    contentType = exchange.responseHeaders?.get("Content-Type")
                        ?: exchange.responseHeaders?.get("content-type")
                )
            } else null,
            serverIp = null,
            connectionId = null
        )
    }

    /**
     * Konvertiert WebView UserActions zu Engine CorrelatedActions.
     */
    fun toCorrelatedActions(session: CaptureSessionManager.CaptureSession): List<CorrelatedAction> {
        return session.userActions.map { action ->
            val relatedExchanges = session.correlate(action)

            CorrelatedAction(
                id = action.id,
                timestamp = action.timestamp,
                type = when (action.type) {
                    TrafficInterceptWebView.ActionType.CLICK -> ActionType.CLICK
                    TrafficInterceptWebView.ActionType.SUBMIT -> ActionType.SUBMIT
                    TrafficInterceptWebView.ActionType.INPUT -> ActionType.INPUT
                    TrafficInterceptWebView.ActionType.NAVIGATION -> ActionType.NAVIGATION
                },
                target = action.target,
                value = action.value,
                pageUrl = action.pageUrl,
                exchangeIds = relatedExchanges.map { it.id }
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
            exchanges = toHttpExchanges(session),
            correlatedActions = toCorrelatedActions(session),
            startedAt = session.startedAt,
            stoppedAt = session.stoppedAt
        )
    }

    /**
     * Input-Format für die Blueprint-Analyse.
     */
    data class AnalysisInput(
        val sessionId: String,
        val sessionName: String,
        val targetUrl: String?,
        val exchanges: List<HttpExchange>,
        val correlatedActions: List<CorrelatedAction>,
        val startedAt: kotlinx.datetime.Instant,
        val stoppedAt: kotlinx.datetime.Instant?
    )

    /**
     * Action-Typ für die Engine.
     */
    enum class ActionType {
        CLICK,
        SUBMIT,
        INPUT,
        NAVIGATION
    }

    /**
     * Korrelierte Action im Engine-Format.
     */
    data class CorrelatedAction(
        val id: String,
        val timestamp: kotlinx.datetime.Instant,
        val type: ActionType,
        val target: String,
        val value: String?,
        val pageUrl: String?,
        val exchangeIds: List<String>
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
