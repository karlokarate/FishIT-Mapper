package dev.fishit.mapper.engine.api

import kotlinx.datetime.Instant

/**
 * Erkennt API-Flows aus korrelierten User Actions und HTTP-Exchanges.
 *
 * Ein Flow ist eine Sequenz von API-Calls, die zusammen einen User-Flow bilden,
 * z.B. Login → Dashboard laden → Profil anzeigen.
 *
 * ## Algorithmus
 * 1. Gruppiere korrelierte Exchanges nach User Action
 * 2. Erkenne Abhängigkeiten zwischen Requests (Token-Weitergabe, etc.)
 * 3. Identifiziere wiederkehrende Patterns
 * 4. Baue Flows mit extrahierten Variablen
 *
 * ## Verwendung
 * ```kotlin
 * val detector = FlowDetector()
 * val flows = detector.detect(
 *     correlatedActions = websiteMap.actions,
 *     exchanges = capturedExchanges,
 *     endpoints = analyzedEndpoints
 * )
 * ```
 */
class FlowDetector {

    companion object {
        // Minimale Anzahl an Steps für einen Flow
        private const val MIN_FLOW_STEPS = 2

        // Maximale Zeit zwischen Steps (in Millisekunden)
        private const val MAX_STEP_GAP_MS = 60_000L
    }

    private var flowIdCounter = 0
    private var stepCounter = 0

    /**
     * Erkennt API-Flows aus korrelierten Actions.
     *
     * @param correlatedActions Korrelierte User Actions aus WebsiteMap
     * @param exchanges Alle HTTP-Exchanges
     * @param endpoints Analysierte API-Endpoints
     * @return Liste erkannter API-Flows
     */
    fun detect(
        correlatedActions: List<CorrelatedAction>,
        exchanges: List<CapturedExchange>,
        endpoints: List<ApiEndpoint>
    ): List<ApiFlow> {
        val flows = mutableListOf<ApiFlow>()

        // Erstelle Exchange-Lookup
        val exchangeMap = exchanges.associateBy { it.exchangeId }

        // Erstelle Endpoint-Lookup basierend auf URL-Matching
        val endpointLookup = EndpointLookup(endpoints)

        // Gruppiere Actions nach zeitlicher Nähe (Sessions/Flows)
        val actionGroups = groupActionsByProximity(correlatedActions)

        // Analysiere jede Gruppe für Flows
        for (group in actionGroups) {
            val detectedFlows = analyzeActionGroup(group, exchangeMap, endpointLookup)
            flows.addAll(detectedFlows)
        }

        // Erkenne auch endpoint-basierte Flows (wiederkehrende Sequenzen)
        val patternBasedFlows = detectPatternBasedFlows(correlatedActions, endpointLookup)
        flows.addAll(patternBasedFlows)

        return flows.distinctBy { it.id }
    }

    /**
     * Gruppiert Actions nach zeitlicher Nähe.
     */
    private fun groupActionsByProximity(actions: List<CorrelatedAction>): List<List<CorrelatedAction>> {
        if (actions.isEmpty()) return emptyList()

        val sorted = actions.sortedBy { it.timestamp }
        val groups = mutableListOf<MutableList<CorrelatedAction>>()
        var currentGroup = mutableListOf<CorrelatedAction>()

        for (action in sorted) {
            if (currentGroup.isEmpty()) {
                currentGroup.add(action)
            } else {
                val lastAction = currentGroup.last()
                val gap = (action.timestamp - lastAction.timestamp).inWholeMilliseconds

                if (gap > MAX_STEP_GAP_MS) {
                    // Starte neue Gruppe
                    if (currentGroup.size >= MIN_FLOW_STEPS) {
                        groups.add(currentGroup)
                    }
                    currentGroup = mutableListOf(action)
                } else {
                    currentGroup.add(action)
                }
            }
        }

        // Letzte Gruppe hinzufügen
        if (currentGroup.size >= MIN_FLOW_STEPS) {
            groups.add(currentGroup)
        }

        return groups
    }

    /**
     * Analysiert eine Gruppe von Actions für Flows.
     */
    private fun analyzeActionGroup(
        actions: List<CorrelatedAction>,
        exchangeMap: Map<String, CapturedExchange>,
        endpointLookup: EndpointLookup
    ): List<ApiFlow> {
        if (actions.size < MIN_FLOW_STEPS) return emptyList()

        val flows = mutableListOf<ApiFlow>()

        // Erstelle einen Flow aus der Sequenz
        val steps = mutableListOf<FlowStep>()
        val extractors = mutableMapOf<String, ResponseExtractor>()

        for ((index, action) in actions.withIndex()) {
            // Verarbeite jeden Exchange in dieser Action
            for (exchangeRef in action.exchanges) {
                val exchange = exchangeMap[exchangeRef.exchangeId] ?: continue
                val endpoint = endpointLookup.findEndpoint(exchange) ?: continue

                // Erstelle FlowStep
                val step = createFlowStep(
                    order = steps.size,
                    endpoint = endpoint,
                    exchange = exchange,
                    previousExtractors = extractors
                )

                steps.add(step)

                // Füge neue Extractors hinzu
                val newExtractors = detectExtractors(exchange, endpoint)
                extractors.putAll(newExtractors.associateBy { it.variableName })
            }
        }

        if (steps.size >= MIN_FLOW_STEPS) {
            val flowName = inferFlowName(actions, steps)

            flows.add(
                ApiFlow(
                    id = "flow_${flowIdCounter++}",
                    name = flowName,
                    description = "Automatisch erkannter Flow aus ${actions.size} User Actions",
                    steps = steps,
                    sourceActionIds = actions.map { it.actionId },
                    tags = inferFlowTags(steps)
                )
            )
        }

        return flows
    }

    /**
     * Erstellt einen FlowStep aus einem Exchange.
     */
    private fun createFlowStep(
        order: Int,
        endpoint: ApiEndpoint,
        exchange: CapturedExchange,
        previousExtractors: Map<String, ResponseExtractor>
    ): FlowStep {
        // Erkenne Parameter-Bindings
        val bindings = mutableMapOf<String, ParameterBinding>()

        // Prüfe Path-Parameter
        for (param in endpoint.pathParameters) {
            val binding = findBinding(param.name, exchange, previousExtractors)
            bindings[param.name] = binding
        }

        // Prüfe Query-Parameter
        for (param in endpoint.queryParameters) {
            val binding = findBinding(param.name, exchange, previousExtractors)
            bindings[param.name] = binding
        }

        // Erkenne Extractors für diesen Step
        val stepExtractors = detectExtractors(exchange, endpoint)

        return FlowStep(
            order = order,
            endpointId = endpoint.id,
            description = "${endpoint.method.name} ${endpoint.pathTemplate}",
            parameterBindings = bindings,
            expectedStatus = exchange.response?.status,
            extractors = stepExtractors
        )
    }

    /**
     * Findet das passende Binding für einen Parameter.
     */
    private fun findBinding(
        paramName: String,
        exchange: CapturedExchange,
        previousExtractors: Map<String, ResponseExtractor>
    ): ParameterBinding {
        // Extrahiere den tatsächlichen Wert aus dem Exchange
        val actualValue = extractParameterValue(paramName, exchange)

        // Prüfe ob der Wert von einem vorherigen Extractor kommen könnte
        for ((extractorName, _) in previousExtractors) {
            // Heuristik: Ähnliche Namen deuten auf Zusammenhang hin
            if (extractorName.contains(paramName, ignoreCase = true) ||
                paramName.contains(extractorName, ignoreCase = true)) {
                return ParameterBinding.FromVariable(extractorName)
            }
        }

        // Prüfe ob es ein typischer User-Input ist
        if (isUserInputParameter(paramName)) {
            return ParameterBinding.UserInput(
                inputName = paramName,
                description = "User-provided $paramName"
            )
        }

        // Statischer Wert
        return ParameterBinding.StaticValue(actualValue ?: "")
    }

    /**
     * Extrahiert den tatsächlichen Wert eines Parameters aus einem Exchange.
     */
    private fun extractParameterValue(paramName: String, exchange: CapturedExchange): String? {
        val url = exchange.request.url

        // Query-Parameter
        val queryStart = url.indexOf('?')
        if (queryStart != -1) {
            val queryString = url.substring(queryStart + 1)
            val params = queryString.split("&")
            for (param in params) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == paramName) {
                    return parts[1]
                }
            }
        }

        // Header
        return exchange.request.headers[paramName]
    }

    /**
     * Prüft ob ein Parameter typischerweise User-Input ist.
     */
    private fun isUserInputParameter(paramName: String): Boolean {
        val inputParams = setOf(
            "username", "email", "password", "name", "query", "search",
            "message", "comment", "title", "content", "description"
        )
        return paramName.lowercase() in inputParams
    }

    /**
     * Erkennt Extractors für einen Exchange.
     */
    private fun detectExtractors(exchange: CapturedExchange, endpoint: ApiEndpoint): List<ResponseExtractor> {
        val extractors = mutableListOf<ResponseExtractor>()
        val responseBody = exchange.responseBody ?: return extractors

        // Suche nach typischen Token/ID-Feldern im Response
        val tokenPatterns = listOf(
            "access_token" to "accessToken",
            "token" to "token",
            "id" to "id",
            "user_id" to "userId",
            "session_id" to "sessionId",
            "refresh_token" to "refreshToken"
        )

        for ((jsonKey, varName) in tokenPatterns) {
            if (responseBody.contains("\"$jsonKey\"")) {
                extractors.add(
                    ResponseExtractor(
                        variableName = varName,
                        source = ExtractionSource.JsonPath("$.$jsonKey")
                    )
                )
            }
        }

        // Suche in Response Headers
        val interestingHeaders = listOf("Set-Cookie", "X-Auth-Token", "Location")
        for (headerName in interestingHeaders) {
            val headerValue = exchange.response?.headers?.get(headerName)
            if (headerValue != null) {
                val varName = headerName.replace("-", "").lowercase()
                extractors.add(
                    ResponseExtractor(
                        variableName = varName,
                        source = ExtractionSource.Header(headerName)
                    )
                )
            }
        }

        return extractors
    }

    /**
     * Erkennt wiederkehrende Endpoint-Sequenzen als Flows.
     */
    private fun detectPatternBasedFlows(
        actions: List<CorrelatedAction>,
        endpointLookup: EndpointLookup
    ): List<ApiFlow> {
        // Sammle Endpoint-Sequenzen
        val sequences = mutableListOf<List<String>>()

        for (action in actions) {
            val endpointIds = action.exchanges.mapNotNull { ref ->
                endpointLookup.findEndpointByUrl(ref.url)?.id
            }
            if (endpointIds.size >= 2) {
                sequences.add(endpointIds)
            }
        }

        // Finde wiederkehrende Sub-Sequenzen
        val patternFlows = mutableListOf<ApiFlow>()
        val patternCounts = mutableMapOf<List<String>, Int>()

        for (seq in sequences) {
            // Sliding Window für Sub-Sequenzen
            for (windowSize in 2..minOf(5, seq.size)) {
                for (start in 0..seq.size - windowSize) {
                    val subSeq = seq.subList(start, start + windowSize)
                    patternCounts[subSeq] = (patternCounts[subSeq] ?: 0) + 1
                }
            }
        }

        // Erstelle Flows für häufige Patterns
        for ((pattern, count) in patternCounts) {
            if (count >= 2) { // Mindestens 2x gesehen
                val steps = pattern.mapIndexed { index, endpointId ->
                    FlowStep(
                        order = index,
                        endpointId = endpointId,
                        description = "Step ${index + 1}"
                    )
                }

                patternFlows.add(
                    ApiFlow(
                        id = "pattern_flow_${flowIdCounter++}",
                        name = "Wiederkehrendes Pattern (${count}x)",
                        description = "Automatisch erkanntes wiederkehrendes API-Pattern",
                        steps = steps,
                        tags = listOf("pattern", "recurring")
                    )
                )
            }
        }

        return patternFlows
    }

    /**
     * Inferiert einen Namen für den Flow.
     */
    private fun inferFlowName(actions: List<CorrelatedAction>, steps: List<FlowStep>): String {
        // Basierend auf Action-Typen
        val actionTypes = actions.map { it.actionType }.distinct()

        return when {
            actionTypes.contains("login") || actionTypes.contains("submit") &&
                steps.any { it.endpointId.contains("auth", ignoreCase = true) } ->
                "Login Flow"

            actionTypes.contains("click") &&
                steps.any { it.endpointId.contains("search", ignoreCase = true) } ->
                "Search Flow"

            actionTypes.any { it.contains("form", ignoreCase = true) } ->
                "Form Submit Flow"

            else -> "User Flow (${steps.size} Steps)"
        }
    }

    /**
     * Inferiert Tags für einen Flow.
     */
    private fun inferFlowTags(steps: List<FlowStep>): List<String> {
        val tags = mutableSetOf<String>()

        for (step in steps) {
            when {
                step.endpointId.contains("auth", ignoreCase = true) -> tags.add("auth")
                step.endpointId.contains("user", ignoreCase = true) -> tags.add("user")
                step.endpointId.contains("search", ignoreCase = true) -> tags.add("search")
                step.endpointId.contains("create", ignoreCase = true) ||
                    step.endpointId.startsWith("post_") -> tags.add("write")
                step.endpointId.startsWith("get_") -> tags.add("read")
            }
        }

        return tags.toList()
    }

    /**
     * Hilfsklasse für Endpoint-Lookup.
     */
    private inner class EndpointLookup(private val endpoints: List<ApiEndpoint>) {

        fun findEndpoint(exchange: CapturedExchange): ApiEndpoint? {
            val method = HttpMethod.valueOf(exchange.request.method.uppercase())
            val url = exchange.request.url

            return endpoints.find { endpoint ->
                endpoint.method == method && matchesPath(endpoint.pathTemplate, url)
            }
        }

        fun findEndpointByUrl(url: String): ApiEndpoint? {
            return endpoints.find { endpoint ->
                matchesPath(endpoint.pathTemplate, url)
            }
        }

        private fun matchesPath(template: String, url: String): Boolean {
            // Extrahiere Path aus URL
            val path = try {
                java.net.URL(url).path
            } catch (e: Exception) {
                return false
            }

            // Konvertiere Template zu Regex
            val regexPattern = template
                .replace(Regex("\\{[^}]+\\}"), "[^/]+")
                .replace("/", "\\/")

            return Regex("^$regexPattern$").matches(path)
        }
    }
}
