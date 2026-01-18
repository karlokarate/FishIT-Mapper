package dev.fishit.mapper.engine.export

import kotlinx.datetime.Instant

/**
 * Generiert State-Graph Diagramme (Mermaid stateDiagram) aus einer NormalizedTimeline.
 *
 * ## Output Format
 * ```mermaid
 * stateDiagram-v2
 *     [*] --> initial
 *     initial --> authenticating: Login clicked
 *     authenticating --> browsing: Auth success
 *     browsing --> editing: Edit button
 *     editing --> saving: Save clicked
 *     saving --> browsing: Save success
 * ```
 *
 * ## Features
 * - Ein State-Graph pro User-Action Gruppe
 * - Globaler Session-Flow
 * - Fehler-States visualisiert
 * - Annotationen mit Timestamps
 */
class StateGraphExporter {

    /**
     * Generiert den vollständigen Session State-Graph.
     */
    fun generateSessionStateGraph(timeline: NormalizedTimeline): String {
        val sb = StringBuilder()

        sb.appendLine("# Session State-Graph")
        sb.appendLine()
        sb.appendLine("**Session:** ${timeline.sessionName}")
        sb.appendLine("**Start:** ${formatTimestamp(timeline.startedAt)}")
        sb.appendLine("**Events:** ${timeline.metadata.totalEvents}")
        sb.appendLine("**User-Actions:** ${timeline.metadata.userActionCount}")
        sb.appendLine("**States:** ${timeline.metadata.stateCount}")
        sb.appendLine()

        // Haupt State-Graph
        sb.appendLine("## Vollständiger Session-Flow")
        sb.appendLine()
        sb.appendLine("```mermaid")
        sb.appendLine("stateDiagram-v2")

        if (timeline.stateTransitions.isEmpty()) {
            sb.appendLine("    [*] --> no_state_changes")
            sb.appendLine("    note right of no_state_changes: Keine State-Änderungen erkannt")
        } else {
            // Initial State
            sb.appendLine("    [*] --> initial")

            // Alle Transitions
            val uniqueStates = mutableSetOf<String>()
            timeline.stateTransitions.forEach { transition ->
                uniqueStates.add(transition.fromState)
                uniqueStates.add(transition.toState)

                val label = sanitizeLabel(transition.description).take(30)
                sb.appendLine("    ${sanitizeStateName(transition.fromState)} --> ${sanitizeStateName(transition.toState)}: $label")
            }

            // State Notes mit Timestamps
            timeline.stateTransitions
                .groupBy { it.toState }
                .forEach { (state, transitions) ->
                    val firstOccurrence = transitions.minByOrNull { it.timestamp }
                    if (firstOccurrence != null) {
                        val time = formatTime(firstOccurrence.timestamp)
                        sb.appendLine("    note right of ${sanitizeStateName(state)}: Erste Occurrence: $time")
                    }
                }

            // Final State (letzter State)
            val lastState = timeline.stateTransitions.lastOrNull()?.toState
            if (lastState != null) {
                sb.appendLine("    ${sanitizeStateName(lastState)} --> [*]")
            }
        }

        sb.appendLine("```")
        sb.appendLine()

        return sb.toString()
    }

    /**
     * Generiert State-Graphs gruppiert nach User-Actions.
     */
    fun generatePerActionStateGraphs(timeline: NormalizedTimeline): String {
        val sb = StringBuilder()

        sb.appendLine("# State-Graphs pro User-Action")
        sb.appendLine()
        sb.appendLine("Jeder Abschnitt zeigt den State-Übergang für eine einzelne User-Aktion.")
        sb.appendLine()

        // User-Actions mit ihren State-Transitions
        val userActions = timeline.getUserActions()
        val transitionsByAction = timeline.stateTransitions
            .groupBy { it.triggeredBy }

        if (userActions.isEmpty()) {
            sb.appendLine("*Keine User-Actions in dieser Session.*")
            return sb.toString()
        }

        userActions.forEachIndexed { index, action ->
            val transitions = transitionsByAction[action.id] ?: emptyList()
            val correlatedRequests = timeline.getCorrelatedRequests(action.id)

            sb.appendLine("## ${index + 1}. ${action.label}")
            sb.appendLine()
            sb.appendLine("**Zeit:** ${formatTimestamp(action.timestamp)}")
            sb.appendLine("**Action:** ${action.details.actionType ?: "unknown"}")
            action.details.selector?.let { sb.appendLine("**Element:** `$it`") }
            sb.appendLine("**Korrelierte Requests:** ${correlatedRequests.size}")
            sb.appendLine()

            // Mini State-Graph für diese Action
            sb.appendLine("```mermaid")
            sb.appendLine("stateDiagram-v2")

            if (transitions.isEmpty()) {
                sb.appendLine("    [*] --> no_change: ${sanitizeLabel(action.label.take(20))}")
                sb.appendLine("    no_change --> [*]")
            } else {
                transitions.forEach { transition ->
                    sb.appendLine("    ${sanitizeStateName(transition.fromState)} --> ${sanitizeStateName(transition.toState)}")
                }
            }

            sb.appendLine("```")
            sb.appendLine()

            // Korrelierte Requests auflisten
            if (correlatedRequests.isNotEmpty()) {
                sb.appendLine("### Ausgelöste HTTP-Requests")
                sb.appendLine()
                correlatedRequests.take(5).forEach { request ->
                    val method = request.details.method ?: "?"
                    val path = request.details.path?.take(40) ?: "?"
                    val status = timeline.events
                        .find {
                            it.type == EventType.HTTP_RESPONSE &&
                            it.timestamp > request.timestamp &&
                            it.details.host == request.details.host
                        }
                        ?.details?.statusCode

                    val statusIcon = when {
                        status == null -> "⏳"
                        status in 200..299 -> "✅"
                        status in 300..399 -> "↪️"
                        status in 400..499 -> "⚠️"
                        else -> "❌"
                    }

                    sb.appendLine("- $statusIcon `$method` $path ${status?.let { "($it)" } ?: ""}")
                }
                if (correlatedRequests.size > 5) {
                    sb.appendLine("- ... und ${correlatedRequests.size - 5} weitere")
                }
                sb.appendLine()
            }

            sb.appendLine("---")
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Generiert einen kombinierten Flow mit States und HTTP-Requests.
     */
    fun generateCombinedFlowGraph(timeline: NormalizedTimeline): String {
        val sb = StringBuilder()

        sb.appendLine("# Kombinierter Session-Flow")
        sb.appendLine()
        sb.appendLine("Zeigt States, User-Actions und HTTP-Requests in einem Diagramm.")
        sb.appendLine()

        sb.appendLine("```mermaid")
        sb.appendLine("flowchart TD")

        // Subgraphs für verschiedene Kategorien
        sb.appendLine("    subgraph user[\"👤 User-Actions\"]")
        timeline.getUserActions().take(10).forEachIndexed { index, action ->
            val id = "action_$index"
            val label = sanitizeLabel(action.details.actionType ?: "action")
            sb.appendLine("        $id[\"$label\"]")
        }
        sb.appendLine("    end")
        sb.appendLine()

        sb.appendLine("    subgraph states[\"🔄 States\"]")
        val uniqueStates = timeline.stateTransitions
            .flatMap { listOf(it.fromState, it.toState) }
            .distinct()

        if (uniqueStates.isEmpty()) {
            sb.appendLine("        state_none[\"Keine States\"]")
        } else {
            uniqueStates.take(8).forEachIndexed { index, state ->
                val id = "state_$index"
                sb.appendLine("        $id((\"$state\"))")
            }
        }
        sb.appendLine("    end")
        sb.appendLine()

        sb.appendLine("    subgraph requests[\"🌐 HTTP-Requests\"]")
        timeline.getHttpRequests().take(8).forEachIndexed { index, request ->
            val id = "req_$index"
            val method = request.details.method ?: "GET"
            val path = request.details.path?.take(15) ?: "/"
            sb.appendLine("        $id[\"$method $path\"]")
        }
        sb.appendLine("    end")
        sb.appendLine()

        // Verbindungen zwischen Actions und States
        timeline.stateTransitions.take(8).forEachIndexed { index, transition ->
            val actionIndex = timeline.getUserActions().indexOfFirst { it.id == transition.triggeredBy }
            if (actionIndex >= 0 && actionIndex < 10) {
                val fromStateIndex = uniqueStates.indexOf(transition.fromState)
                val toStateIndex = uniqueStates.indexOf(transition.toState)

                if (fromStateIndex >= 0 && toStateIndex >= 0) {
                    sb.appendLine("    action_$actionIndex --> state_$toStateIndex")
                }
            }
        }

        // Verbindungen zwischen Actions und Requests
        timeline.getUserActions().take(10).forEachIndexed { actionIndex, action ->
            val correlated = timeline.getCorrelatedRequests(action.id)
            val allRequests = timeline.getHttpRequests()

            correlated.take(2).forEach { request ->
                val reqIndex = allRequests.indexOf(request)
                if (reqIndex >= 0 && reqIndex < 8) {
                    sb.appendLine("    action_$actionIndex -.-> req_$reqIndex")
                }
            }
        }

        sb.appendLine()
        sb.appendLine("    classDef userAction fill:#4CAF50,color:white")
        sb.appendLine("    classDef stateNode fill:#2196F3,color:white")
        sb.appendLine("    classDef requestNode fill:#FF9800,color:white")
        sb.appendLine()

        timeline.getUserActions().take(10).forEachIndexed { index, _ ->
            sb.appendLine("    class action_$index userAction")
        }
        uniqueStates.take(8).forEachIndexed { index, _ ->
            sb.appendLine("    class state_$index stateNode")
        }
        timeline.getHttpRequests().take(8).forEachIndexed { index, _ ->
            sb.appendLine("    class req_$index requestNode")
        }

        sb.appendLine("```")
        sb.appendLine()

        return sb.toString()
    }

    /**
     * Generiert eine Summary der State-Verteilung.
     */
    fun generateStateSummary(timeline: NormalizedTimeline): String {
        val sb = StringBuilder()

        sb.appendLine("# State-Verteilung Summary")
        sb.appendLine()

        val stateOccurrences = timeline.stateTransitions
            .groupingBy { it.toState }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }

        if (stateOccurrences.isEmpty()) {
            sb.appendLine("*Keine State-Übergänge in dieser Session.*")
            return sb.toString()
        }

        // Pie Chart
        sb.appendLine("## Verteilung der States")
        sb.appendLine()
        sb.appendLine("```mermaid")
        sb.appendLine("pie showData")
        sb.appendLine("    title State-Verteilung")
        stateOccurrences.take(8).forEach { (state, count) ->
            sb.appendLine("    \"$state\" : $count")
        }
        sb.appendLine("```")
        sb.appendLine()

        // Tabelle
        sb.appendLine("## State-Details")
        sb.appendLine()
        sb.appendLine("| State | Anzahl | Erste Occurrence | Letzte Occurrence |")
        sb.appendLine("|-------|--------|------------------|-------------------|")

        stateOccurrences.forEach { (state, count) ->
            val transitions = timeline.stateTransitions.filter { it.toState == state }
            val firstTime = transitions.minByOrNull { it.timestamp }?.timestamp?.let { formatTime(it) } ?: "-"
            val lastTime = transitions.maxByOrNull { it.timestamp }?.timestamp?.let { formatTime(it) } ?: "-"

            sb.appendLine("| $state | $count | $firstTime | $lastTime |")
        }
        sb.appendLine()

        return sb.toString()
    }

    /**
     * Generiert den vollständigen State-Graph Export.
     */
    fun generateFullExport(timeline: NormalizedTimeline): String {
        val sb = StringBuilder()

        // Header
        sb.appendLine("---")
        sb.appendLine("title: State-Graph Export - ${timeline.sessionName}")
        sb.appendLine("generated: ${formatTimestamp(timeline.metadata.generatedAt)}")
        sb.appendLine("session_id: ${timeline.sessionId}")
        sb.appendLine("---")
        sb.appendLine()

        // Alle Sections
        sb.append(generateSessionStateGraph(timeline))
        sb.append(generateStateSummary(timeline))
        sb.append(generatePerActionStateGraphs(timeline))
        sb.append(generateCombinedFlowGraph(timeline))

        // Footer
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("*Generiert von FishIT-Mapper State-Graph Exporter*")

        return sb.toString()
    }

    // === Helper Methods ===

    private fun sanitizeStateName(name: String): String {
        return name
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "unknown" }
    }

    private fun sanitizeLabel(label: String): String {
        return label
            .replace("\"", "'")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun formatTimestamp(instant: Instant): String {
        return instant.toString().replace("T", " ").substringBefore(".")
    }

    private fun formatTime(instant: Instant): String {
        return instant.toString().substringAfter("T").substringBefore(".")
    }
}
