package dev.fishit.mapper.engine.export

import dev.fishit.mapper.engine.api.EngineExchange
import dev.fishit.mapper.engine.api.EngineRequest
import dev.fishit.mapper.engine.api.EngineResponse
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Normalisierte Timeline-Struktur für HAR → Diagramm Pipeline.
 *
 * ## Pipeline
 * ```
 * HAR/Exchanges → NormalizedTimeline → Sequenzdiagramm (Mermaid)
 *                                   → State-Graph (Mermaid)
 *                                   → Flow-Diagramm
 * ```
 *
 * ## Features
 * - Einheitliche Event-Struktur für alle Quellen
 * - Korrelation zwischen User-Actions und HTTP-Requests
 * - State-Tracking über die gesamte Session
 * - Serialisierbar für Export/Import
 */
@Serializable
data class NormalizedTimeline(
    val sessionId: String,
    val sessionName: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val events: List<TimelineEvent>,
    val participants: List<Participant>,
    val stateTransitions: List<StateTransition>,
    val metadata: TimelineMetadata
) {
    /**
     * Gibt Events gefiltert nach Typ zurück.
     */
    fun getEventsByType(type: EventType): List<TimelineEvent> =
        events.filter { it.type == type }

    /**
     * Gibt alle User-Actions zurück.
     */
    fun getUserActions(): List<TimelineEvent> =
        events.filter { it.type == EventType.USER_ACTION }

    /**
     * Gibt alle HTTP-Requests zurück.
     */
    fun getHttpRequests(): List<TimelineEvent> =
        events.filter { it.type == EventType.HTTP_REQUEST }

    /**
     * Gibt Events in einem Zeitfenster zurück.
     */
    fun getEventsInWindow(start: Instant, end: Instant): List<TimelineEvent> =
        events.filter { it.timestamp >= start && it.timestamp <= end }

    /**
     * Korreliert eine User-Action mit den darauf folgenden HTTP-Requests.
     */
    fun getCorrelatedRequests(actionId: String, windowMs: Long = 2000): List<TimelineEvent> {
        val action = events.find { it.id == actionId } ?: return emptyList()
        val actionTime = action.timestamp.toEpochMilliseconds()

        return events.filter { event ->
            event.type == EventType.HTTP_REQUEST &&
            event.timestamp.toEpochMilliseconds() in actionTime..(actionTime + windowMs)
        }
    }
}

/**
 * Event-Typen in der Timeline.
 */
@Serializable
enum class EventType {
    USER_ACTION,      // Click, Input, Submit, Navigation
    HTTP_REQUEST,     // Ausgehender Request
    HTTP_RESPONSE,    // Eingehender Response
    PAGE_LOAD,        // Seite geladen
    STATE_CHANGE,     // Zustandsänderung
    ERROR,            // Fehler
    WEBSOCKET,        // WebSocket Message
    COOKIE_CHANGE     // Cookie gesetzt/geändert
}

/**
 * Ein einzelnes Event in der Timeline.
 */
@Serializable
data class TimelineEvent(
    val id: String,
    val type: EventType,
    val timestamp: Instant,
    val source: String,           // Participant-ID der Quelle
    val target: String?,          // Participant-ID des Ziels (bei Request/Response)
    val label: String,            // Kurze Beschreibung
    val details: EventDetails,
    val correlatedActionId: String? = null,  // ID der auslösenden User-Action
    val stateAfter: String? = null           // State nach diesem Event
)

/**
 * Details eines Events, abhängig vom Typ.
 */
@Serializable
data class EventDetails(
    // Für HTTP
    val method: String? = null,
    val url: String? = null,
    val path: String? = null,
    val host: String? = null,
    val statusCode: Int? = null,
    val contentType: String? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
    val headers: Map<String, String>? = null,
    val durationMs: Long? = null,

    // Für User-Action
    val actionType: String? = null,     // click, input, submit, navigation
    val selector: String? = null,        // CSS-Selector oder Element-Beschreibung
    val value: String? = null,           // Eingegebener Wert (ohne Passwörter)
    val elementText: String? = null,     // Text des geklickten Elements

    // Für State
    val stateName: String? = null,
    val stateData: Map<String, String>? = null,

    // Für Fehler
    val errorMessage: String? = null,
    val errorCode: String? = null
)

/**
 * Ein Participant (Akteur) im Sequenzdiagramm.
 */
@Serializable
data class Participant(
    val id: String,
    val name: String,
    val type: ParticipantType,
    val emoji: String,
    val host: String? = null
)

@Serializable
enum class ParticipantType {
    USER,
    BROWSER,
    API_SERVER,
    AUTH_SERVER,
    CDN,
    ANALYTICS,
    WEBSOCKET,
    OTHER
}

/**
 * Eine Zustandsänderung in der Session.
 */
@Serializable
data class StateTransition(
    val id: String,
    val timestamp: Instant,
    val fromState: String,
    val toState: String,
    val triggeredBy: String,      // Event-ID das die Änderung ausgelöst hat
    val description: String
)

/**
 * Metadaten über die Timeline.
 */
@Serializable
data class TimelineMetadata(
    val totalEvents: Int,
    val userActionCount: Int,
    val httpRequestCount: Int,
    val errorCount: Int,
    val uniqueHosts: List<String>,
    val stateCount: Int,
    val correlatedPairs: Int,     // Anzahl der korrelierten Action-Request Paare
    val avgResponseTimeMs: Long?,
    val generatedAt: Instant
)

/**
 * Konvertiert HAR/Exchanges in eine normalisierte Timeline.
 */
class TimelineNormalizer {

    private var eventCounter = 0
    private var stateCounter = 0

    /**
     * Erstellt eine normalisierte Timeline aus Exchanges und User-Actions.
     */
    fun normalize(
        sessionId: String,
        sessionName: String,
        exchanges: List<EngineExchange>,
        userActions: List<MermaidSequenceExporter.UserActionEvent>,
        startedAt: Instant,
        endedAt: Instant? = null
    ): NormalizedTimeline {
        eventCounter = 0
        stateCounter = 0

        // Participants aus Hosts extrahieren
        val participants = buildParticipants(exchanges)

        // Events erstellen und sortieren
        val events = mutableListOf<TimelineEvent>()

        // User-Actions zu Events
        userActions.forEach { action ->
            events.add(userActionToEvent(action))
        }

        // HTTP-Exchanges zu Events
        exchanges.forEach { exchange ->
            events.addAll(exchangeToEvents(exchange, participants))
        }

        // Nach Zeit sortieren
        events.sortBy { it.timestamp }

        // Korrelation durchführen
        val correlatedEvents = correlateEvents(events, userActions)

        // State-Transitions aus User-Actions ableiten
        val stateTransitions = buildStateTransitions(correlatedEvents)

        // Metadata berechnen
        val metadata = calculateMetadata(correlatedEvents, exchanges, stateTransitions)

        return NormalizedTimeline(
            sessionId = sessionId,
            sessionName = sessionName,
            startedAt = startedAt,
            endedAt = endedAt,
            events = correlatedEvents,
            participants = participants,
            stateTransitions = stateTransitions,
            metadata = metadata
        )
    }

    private fun buildParticipants(exchanges: List<EngineExchange>): List<Participant> {
        val participants = mutableListOf<Participant>()

        // User und Browser sind immer dabei
        participants.add(Participant(
            id = "user",
            name = "User",
            type = ParticipantType.USER,
            emoji = "👤"
        ))

        participants.add(Participant(
            id = "browser",
            name = "Browser",
            type = ParticipantType.BROWSER,
            emoji = "🌐"
        ))

        // Hosts aus Exchanges extrahieren
        val hosts = exchanges
            .mapNotNull { it.request.host }
            .distinct()
            .take(5)

        hosts.forEachIndexed { index, host ->
            val type = classifyHost(host)
            participants.add(Participant(
                id = "server_$index",
                name = getShortHostName(host),
                type = type,
                emoji = getHostEmoji(type, host),
                host = host
            ))
        }

        return participants
    }

    private fun classifyHost(host: String): ParticipantType {
        return when {
            host.contains("auth") || host.contains("login") || host.contains("oauth") -> ParticipantType.AUTH_SERVER
            host.contains("cdn") || host.contains("static") || host.contains("assets") -> ParticipantType.CDN
            host.contains("analytics") || host.contains("tracking") || host.contains("gtm") -> ParticipantType.ANALYTICS
            host.contains("ws") || host.contains("socket") -> ParticipantType.WEBSOCKET
            host.contains("api") -> ParticipantType.API_SERVER
            else -> ParticipantType.OTHER
        }
    }

    private fun getHostEmoji(type: ParticipantType, host: String): String {
        return when (type) {
            ParticipantType.AUTH_SERVER -> "🔐"
            ParticipantType.CDN -> "📦"
            ParticipantType.ANALYTICS -> "📊"
            ParticipantType.WEBSOCKET -> "🔌"
            ParticipantType.API_SERVER -> "🔧"
            else -> "🖥️"
        }
    }

    private fun getShortHostName(host: String): String {
        return host
            .removePrefix("www.")
            .split(".")
            .firstOrNull()
            ?.take(15)
            ?: host.take(15)
    }

    private fun userActionToEvent(action: MermaidSequenceExporter.UserActionEvent): TimelineEvent {
        val actionEmoji = when (action.type.lowercase()) {
            "click" -> "🖱️"
            "submit" -> "📤"
            "input" -> "⌨️"
            "navigation" -> "🔗"
            else -> "👆"
        }

        return TimelineEvent(
            id = "event_${++eventCounter}",
            type = EventType.USER_ACTION,
            timestamp = action.timestamp,
            source = "user",
            target = "browser",
            label = "$actionEmoji ${action.type}: ${action.target.take(30)}",
            details = EventDetails(
                actionType = action.type,
                selector = action.target,
                value = action.value,
                elementText = action.value?.take(50)
            )
        )
    }

    private fun exchangeToEvents(
        exchange: EngineExchange,
        participants: List<Participant>
    ): List<TimelineEvent> {
        val events = mutableListOf<TimelineEvent>()

        val host = exchange.request.host ?: "unknown"
        val serverParticipant = participants.find { it.host == host }
            ?: participants.find { it.type == ParticipantType.API_SERVER }
            ?: participants.last()

        // Request Event
        events.add(TimelineEvent(
            id = "event_${++eventCounter}",
            type = EventType.HTTP_REQUEST,
            timestamp = exchange.startedAt,
            source = "browser",
            target = serverParticipant.id,
            label = "${exchange.request.method} ${exchange.request.path.take(35)}",
            details = EventDetails(
                method = exchange.request.method,
                url = exchange.request.url,
                path = exchange.request.path,
                host = host,
                requestBody = exchange.request.body?.take(500),
                headers = exchange.request.headers,
                contentType = exchange.request.contentType
            )
        ))

        // Response Event (wenn vorhanden)
        exchange.response?.let { response ->
            val statusEmoji = when {
                response.status in 200..299 -> "✅"
                response.status in 300..399 -> "↪️"
                response.status in 400..499 -> "⚠️"
                response.status >= 500 -> "❌"
                else -> "❓"
            }

            val duration = exchange.completedAt?.let {
                it.toEpochMilliseconds() - exchange.startedAt.toEpochMilliseconds()
            }

            events.add(TimelineEvent(
                id = "event_${++eventCounter}",
                type = EventType.HTTP_RESPONSE,
                timestamp = exchange.completedAt ?: exchange.startedAt,
                source = serverParticipant.id,
                target = "browser",
                label = "$statusEmoji ${response.status}",
                details = EventDetails(
                    statusCode = response.status,
                    responseBody = response.body?.take(500),
                    headers = response.headers,
                    contentType = response.headers?.get("Content-Type"),
                    durationMs = duration
                )
            ))
        }

        return events
    }

    private fun correlateEvents(
        events: List<TimelineEvent>,
        userActions: List<MermaidSequenceExporter.UserActionEvent>,
        windowMs: Long = 2000
    ): List<TimelineEvent> {
        val correlatedEvents = mutableListOf<TimelineEvent>()
        var currentActionId: String? = null
        var currentActionTime: Long? = null

        events.forEach { event ->
            when (event.type) {
                EventType.USER_ACTION -> {
                    currentActionId = event.id
                    currentActionTime = event.timestamp.toEpochMilliseconds()
                    correlatedEvents.add(event)
                }
                EventType.HTTP_REQUEST -> {
                    val eventTime = event.timestamp.toEpochMilliseconds()
                    val correlatedId = if (currentActionTime != null &&
                        eventTime >= currentActionTime!! &&
                        eventTime <= currentActionTime!! + windowMs) {
                        currentActionId
                    } else {
                        null
                    }
                    correlatedEvents.add(event.copy(correlatedActionId = correlatedId))
                }
                else -> {
                    correlatedEvents.add(event)
                }
            }
        }

        return correlatedEvents
    }

    private fun buildStateTransitions(events: List<TimelineEvent>): List<StateTransition> {
        val transitions = mutableListOf<StateTransition>()
        var currentState = "initial"

        events.filter { it.type == EventType.USER_ACTION }.forEach { event ->
            val newState = deriveState(event)
            if (newState != currentState) {
                transitions.add(StateTransition(
                    id = "state_${++stateCounter}",
                    timestamp = event.timestamp,
                    fromState = currentState,
                    toState = newState,
                    triggeredBy = event.id,
                    description = "${event.details.actionType}: ${event.details.selector?.take(30)}"
                ))
                currentState = newState
            }
        }

        return transitions
    }

    private fun deriveState(event: TimelineEvent): String {
        val action = event.details.actionType?.lowercase() ?: ""
        val target = event.details.selector?.lowercase() ?: ""

        return when {
            target.contains("login") || target.contains("signin") -> "authenticating"
            target.contains("logout") || target.contains("signout") -> "logged_out"
            target.contains("submit") || action == "submit" -> "submitting"
            target.contains("search") -> "searching"
            target.contains("next") || target.contains("continue") -> "navigating"
            target.contains("save") -> "saving"
            target.contains("delete") || target.contains("remove") -> "deleting"
            target.contains("edit") || target.contains("modify") -> "editing"
            action == "navigation" -> "browsing"
            action == "input" -> "entering_data"
            else -> "interacting"
        }
    }

    private fun calculateMetadata(
        events: List<TimelineEvent>,
        exchanges: List<EngineExchange>,
        stateTransitions: List<StateTransition>
    ): TimelineMetadata {
        val userActions = events.count { it.type == EventType.USER_ACTION }
        val httpRequests = events.count { it.type == EventType.HTTP_REQUEST }
        val errors = events.count {
            it.type == EventType.HTTP_RESPONSE &&
            (it.details.statusCode ?: 0) >= 400
        }

        val correlatedPairs = events.count {
            it.type == EventType.HTTP_REQUEST && it.correlatedActionId != null
        }

        val responseTimes = exchanges.mapNotNull { exchange ->
            exchange.completedAt?.let {
                it.toEpochMilliseconds() - exchange.startedAt.toEpochMilliseconds()
            }
        }

        val avgResponseTime = if (responseTimes.isNotEmpty()) {
            responseTimes.average().toLong()
        } else null

        return TimelineMetadata(
            totalEvents = events.size,
            userActionCount = userActions,
            httpRequestCount = httpRequests,
            errorCount = errors,
            uniqueHosts = exchanges.mapNotNull { it.request.host }.distinct(),
            stateCount = stateTransitions.size,
            correlatedPairs = correlatedPairs,
            avgResponseTimeMs = avgResponseTime,
            generatedAt = kotlinx.datetime.Clock.System.now()
        )
    }
}

/**
 * Extension: Timeline als JSON exportieren.
 */
fun NormalizedTimeline.toJson(): String {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    return json.encodeToString(NormalizedTimeline.serializer(), this)
}
