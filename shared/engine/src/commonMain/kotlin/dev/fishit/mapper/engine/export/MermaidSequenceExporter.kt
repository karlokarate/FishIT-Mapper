package dev.fishit.mapper.engine.export

import dev.fishit.mapper.engine.api.CapturedExchange
import dev.fishit.mapper.engine.api.EngineExchange
import kotlinx.datetime.Instant

/**
 * Generiert Mermaid-Sequenzdiagramme aus Traffic-Daten.
 *
 * Das Diagramm zeigt die Interaktion zwischen:
 * - User (Browser)
 * - Frontend (Web-App)
 * - Backend (API Server)
 *
 * ## Features
 * - Korrelation von User-Actions mit HTTP-Requests
 * - Gruppierung nach Hosts/Services
 * - Zeitliche Sortierung
 * - Fehler-Markierung (4xx, 5xx)
 * - Auth-Flow-Erkennung
 *
 * ## Verwendung
 * ```kotlin
 * val exporter = MermaidSequenceExporter()
 * val mermaidCode = exporter.generate(exchanges, userActions)
 * ```
 *
 * ## Output Format
 * ```mermaid
 * sequenceDiagram
 *     participant U as User
 *     participant B as Browser
 *     participant A as API Server
 *
 *     U->>B: Click "Login"
 *     B->>A: POST /auth/login
 *     A-->>B: 200 OK (token)
 * ```
 */
class MermaidSequenceExporter {

    /**
     * User-Action für Korrelation.
     */
    data class UserActionEvent(
        val type: String,
        val target: String,
        val value: String?,
        val timestamp: Instant
    )

    /**
     * Generiert ein Mermaid-Sequenzdiagramm.
     *
     * @param exchanges Liste der HTTP-Exchanges
     * @param userActions Liste der User-Actions (optional)
     * @param title Optionaler Titel für das Diagramm
     * @param maxEntries Maximale Anzahl von Einträgen (default: 50)
     */
    fun generate(
        exchanges: List<EngineExchange>,
        userActions: List<UserActionEvent> = emptyList(),
        title: String? = null,
        maxEntries: Int = 50
    ): String {
        if (exchanges.isEmpty() && userActions.isEmpty()) {
            return buildEmptyDiagram(title)
        }

        // Sammle alle einzigartigen Hosts (mit Fallback für null)
        val hosts = exchanges
            .mapNotNull { it.request.host }
            .distinct()
            .take(5) // Maximal 5 verschiedene Hosts

        // Erstelle Timeline mit allen Events
        val timeline = buildTimeline(exchanges, userActions)
            .take(maxEntries)

        return buildString {
            // Header
            if (title != null) {
                appendLine("---")
                appendLine("title: $title")
                appendLine("---")
            }
            appendLine("sequenceDiagram")
            appendLine("    autonumber")
            appendLine()

            // Participants
            appendLine("    participant U as 👤 User")
            appendLine("    participant B as 🌐 Browser")
            hosts.forEachIndexed { index, host ->
                val shortName = getShortHostName(host)
                val emoji = getHostEmoji(host)
                appendLine("    participant S$index as $emoji $shortName")
            }
            appendLine()

            // Events
            var lastActionTime: Instant? = null
            timeline.forEach { event ->
                when (event) {
                    is TimelineEvent.Action -> {
                        lastActionTime = event.timestamp
                        appendLine("    ${formatUserAction(event)}")
                    }
                    is TimelineEvent.Exchange -> {
                        val host = event.exchange.request.host ?: "unknown"
                        val hostIndex = hosts.indexOf(host).coerceAtLeast(0)
                        appendLine("    ${formatExchange(event.exchange, hostIndex, lastActionTime)}")
                    }
                }
            }

            // Legende als Note
            appendLine()
            appendLine("    Note over U,B: 📊 ${exchanges.size} Requests | ${userActions.size} Actions")
        }
    }

    /**
     * Generiert ein Korrelations-Diagramm das User-Actions mit ausgelösten Requests verbindet.
     */
    fun generateCorrelationDiagram(
        exchanges: List<EngineExchange>,
        userActions: List<UserActionEvent>,
        correlationWindowMs: Long = 2000,
        title: String? = null
    ): String {
        if (exchanges.isEmpty()) {
            return buildEmptyDiagram(title)
        }

        // Korreliere Actions mit Exchanges
        val correlations = correlateActionsWithExchanges(exchanges, userActions, correlationWindowMs)

        // Sammle Hosts (mit Fallback für null)
        val hosts = exchanges
            .mapNotNull { it.request.host }
            .distinct()
            .take(5)

        return buildString {
            if (title != null) {
                appendLine("---")
                appendLine("title: $title - Korrelation")
                appendLine("---")
            }
            appendLine("sequenceDiagram")
            appendLine("    autonumber")
            appendLine()

            // Participants
            appendLine("    participant U as 👤 User")
            appendLine("    participant B as 🌐 Browser")
            hosts.forEachIndexed { index, host ->
                val shortName = getShortHostName(host)
                appendLine("    participant S$index as 🔧 $shortName")
            }
            appendLine()

            // Korrelierte Flows
            correlations.forEach { correlation ->
                // User Action
                if (correlation.action != null) {
                    appendLine("    ${formatUserAction(TimelineEvent.Action(correlation.action.type, correlation.action.target, correlation.action.value, correlation.action.timestamp))}")
                    appendLine()
                    appendLine("    rect rgb(240, 248, 255)")
                    appendLine("    Note right of B: Ausgelöste Requests")
                }

                // Zugehörige Exchanges
                correlation.exchanges.forEach { exchange ->
                    val host = exchange.request.host ?: "unknown"
                    val hostIndex = hosts.indexOf(host).coerceAtLeast(0)
                    appendLine("    ${formatExchange(exchange, hostIndex, correlation.action?.timestamp)}")
                }

                if (correlation.action != null) {
                    appendLine("    end")
                    appendLine()
                }
            }

            // Stats
            val correlatedCount = correlations.count { it.action != null }
            val uncorrelatedCount = correlations.count { it.action == null }
            appendLine()
            appendLine("    Note over U,B: ✅ $correlatedCount korreliert | ❓ $uncorrelatedCount ohne Action")
        }
    }

    /**
     * Generiert ein kompaktes Flow-Diagramm (weniger Details).
     */
    fun generateFlowDiagram(
        exchanges: List<EngineExchange>,
        title: String? = null
    ): String {
        if (exchanges.isEmpty()) {
            return buildEmptyDiagram(title)
        }

        // Gruppiere nach Endpoint
        val grouped = exchanges.groupBy { "${it.request.method} ${it.request.path}" }
        val hosts = exchanges.mapNotNull { it.request.host }.distinct().take(3)

        return buildString {
            if (title != null) {
                appendLine("---")
                appendLine("title: $title - API Flow")
                appendLine("---")
            }
            appendLine("sequenceDiagram")
            appendLine()

            // Participants (kompakt)
            appendLine("    participant C as 📱 Client")
            hosts.forEachIndexed { index, host ->
                appendLine("    participant S$index as ${getShortHostName(host)}")
            }
            appendLine()

            // Gruppierte Requests
            grouped.entries.take(30).forEach { (endpoint, exs) ->
                val first = exs.first()
                val host = first.request.host ?: "unknown"
                val hostIndex = hosts.indexOf(host).coerceAtLeast(0)
                val status = first.response?.status ?: 0
                val count = if (exs.size > 1) " (${exs.size}x)" else ""

                val arrow = if (status >= 400) "C-x" else "C->>"
                val returnArrow = if (status >= 400) "S$hostIndex--x" else "S$hostIndex-->>"

                appendLine("    $arrow S$hostIndex: ${first.request.method} ${first.request.path.take(40)}$count")
                appendLine("    $returnArrow C: $status")
            }

            appendLine()
            appendLine("    Note over C: ${grouped.size} unique endpoints")
        }
    }

    // ==================== Private Helpers ====================

    private sealed class TimelineEvent {
        abstract val timestamp: Instant

        data class Action(
            val type: String,
            val target: String,
            val value: String?,
            override val timestamp: Instant
        ) : TimelineEvent()

        data class Exchange(
            val exchange: EngineExchange,
            override val timestamp: Instant
        ) : TimelineEvent()
    }

    private data class Correlation(
        val action: UserActionEvent?,
        val exchanges: List<EngineExchange>
    )

    private fun buildTimeline(
        exchanges: List<EngineExchange>,
        userActions: List<UserActionEvent>
    ): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()

        // User Actions hinzufügen
        userActions.forEach { action ->
            events.add(TimelineEvent.Action(
                type = action.type,
                target = action.target,
                value = action.value,
                timestamp = action.timestamp
            ))
        }

        // Exchanges hinzufügen
        exchanges.forEach { exchange ->
            events.add(TimelineEvent.Exchange(
                exchange = exchange,
                timestamp = exchange.startedAt
            ))
        }

        // Nach Zeit sortieren
        return events.sortedBy { it.timestamp }
    }

    private fun correlateActionsWithExchanges(
        exchanges: List<EngineExchange>,
        userActions: List<UserActionEvent>,
        windowMs: Long
    ): List<Correlation> {
        val correlations = mutableListOf<Correlation>()
        val usedExchanges = mutableSetOf<String>()

        // Für jede Action, finde zugehörige Exchanges
        userActions.sortedBy { it.timestamp }.forEach { action ->
            val actionTime = action.timestamp.toEpochMilliseconds()
            val relatedExchanges = exchanges.filter { exchange ->
                val exchangeTime = exchange.startedAt.toEpochMilliseconds()
                !usedExchanges.contains(exchange.exchangeId) &&
                exchangeTime >= actionTime &&
                exchangeTime <= actionTime + windowMs
            }

            if (relatedExchanges.isNotEmpty()) {
                relatedExchanges.forEach { usedExchanges.add(it.exchangeId) }
                correlations.add(Correlation(action, relatedExchanges))
            }
        }

        // Nicht-korrelierte Exchanges
        val uncorrelated = exchanges.filter { !usedExchanges.contains(it.exchangeId) }
        if (uncorrelated.isNotEmpty()) {
            correlations.add(Correlation(null, uncorrelated))
        }

        return correlations
    }

    private fun formatUserAction(action: TimelineEvent.Action): String {
        val emoji = when (action.type.lowercase()) {
            "click" -> "🖱️"
            "submit" -> "📤"
            "input" -> "⌨️"
            "navigation" -> "🔗"
            else -> "👆"
        }

        val target = action.target
            .replace("\"", "'")
            .take(40)
            .let { if (it.length == 40) "$it..." else it }

        return "U->>B: $emoji ${action.type}: $target"
    }

    private fun formatExchange(
        exchange: EngineExchange,
        hostIndex: Int,
        lastActionTime: Instant?
    ): String {
        val method = exchange.request.method
        val path = exchange.request.path.take(35).let {
            if (it.length == 35) "$it..." else it
        }
        val status = exchange.response?.status ?: 0

        // Request Arrow
        val requestArrow = "B->>S$hostIndex"

        // Response Arrow basierend auf Status
        val responseArrow = when {
            status == 0 -> "S$hostIndex--xB"  // Kein Response
            status >= 500 -> "S$hostIndex--xB" // Server Error
            status >= 400 -> "S$hostIndex--xB" // Client Error
            status >= 300 -> "S$hostIndex-->>B" // Redirect
            else -> "S$hostIndex-->>B"         // Success
        }

        // Status Text
        val statusText = when {
            status == 0 -> "❌ No Response"
            status >= 500 -> "❌ $status"
            status >= 400 -> "⚠️ $status"
            status >= 300 -> "↪️ $status"
            status >= 200 -> "✅ $status"
            else -> "$status"
        }

        return buildString {
            appendLine("$requestArrow: $method $path")
            append("    $responseArrow: $statusText")
        }
    }

    private fun getShortHostName(host: String): String {
        // Entferne www. und nimm nur den ersten Teil
        return host
            .removePrefix("www.")
            .split(".")
            .firstOrNull()
            ?.take(15)
            ?: host.take(15)
    }

    private fun getHostEmoji(host: String): String {
        return when {
            host.contains("auth") || host.contains("login") || host.contains("oauth") -> "🔐"
            host.contains("api") -> "🔧"
            host.contains("cdn") || host.contains("static") -> "📦"
            host.contains("analytics") || host.contains("tracking") -> "📊"
            else -> "🖥️"
        }
    }

    private fun buildEmptyDiagram(title: String?): String {
        return buildString {
            if (title != null) {
                appendLine("---")
                appendLine("title: $title")
                appendLine("---")
            }
            appendLine("sequenceDiagram")
            appendLine("    participant U as User")
            appendLine("    participant B as Browser")
            appendLine("    Note over U,B: Keine Daten verfügbar")
        }
    }
}
