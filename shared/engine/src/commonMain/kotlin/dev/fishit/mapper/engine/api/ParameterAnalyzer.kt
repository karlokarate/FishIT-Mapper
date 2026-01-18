package dev.fishit.mapper.engine.api

/**
 * Analysiert Parameter aus HTTP-Requests.
 *
 * Extrahiert und typisiert:
 * - Path-Parameter (aus URL-Pfaden)
 * - Query-Parameter (aus URL-Query-Strings)
 * - Header-Parameter (aus HTTP-Headers)
 *
 * ## Verwendung
 * ```kotlin
 * val analyzer = ParameterAnalyzer()
 *
 * // Path-Parameter
 * val pathParams = analyzer.extractPathParameters(
 *     template = "/api/users/{userId}/posts/{postId}",
 *     actualPaths = listOf("/api/users/123/posts/456", "/api/users/789/posts/101")
 * )
 *
 * // Query-Parameter
 * val queryParams = analyzer.extractQueryParameters(
 *     queryMaps = listOf(mapOf("page" to "1", "limit" to "10"))
 * )
 * ```
 */
class ParameterAnalyzer {

    companion object {
        // Headers die typischerweise nicht als Parameter relevant sind
        private val IGNORED_HEADERS = setOf(
            "host", "connection", "content-length", "content-type",
            "accept", "accept-encoding", "accept-language",
            "user-agent", "origin", "referer",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "cache-control", "pragma", "if-modified-since", "if-none-match"
        )

        // Headers die auf Auth hindeuten
        private val AUTH_HEADERS = setOf(
            "authorization", "x-api-key", "api-key", "x-auth-token",
            "x-access-token", "x-csrf-token", "x-xsrf-token"
        )
    }

    /**
     * Extrahiert Path-Parameter aus einem Template und echten Pfaden.
     *
     * @param template Path-Template mit Platzhaltern, z.B. "/api/users/{userId}"
     * @param actualPaths Liste von echten Pfaden, z.B. ["/api/users/123", "/api/users/456"]
     * @return Liste der erkannten Path-Parameter mit beobachteten Werten
     */
    fun extractPathParameters(
        template: String,
        actualPaths: List<String>
    ): List<ApiParameter> {
        // Finde alle Parameter im Template
        val paramRegex = Regex("\\{([^}]+)\\}")
        val paramNames = paramRegex.findAll(template).map { it.groupValues[1] }.toList()

        if (paramNames.isEmpty()) return emptyList()

        // Erstelle Regex aus Template um Werte zu extrahieren
        val regexPattern = template
            .replace(Regex("\\{[^}]+\\}"), "([^/]+)")
            .replace("/", "\\/")

        val valueRegex = Regex(regexPattern)

        // Sammle beobachtete Werte pro Parameter
        val observedValues = paramNames.associateWith { mutableSetOf<String>() }

        for (path in actualPaths) {
            val match = valueRegex.find(path) ?: continue
            paramNames.forEachIndexed { index, name ->
                match.groupValues.getOrNull(index + 1)?.let { value ->
                    observedValues[name]?.add(value)
                }
            }
        }

        // Erstelle Parameter-Objekte
        return paramNames.map { name ->
            val values = observedValues[name]?.toList() ?: emptyList()
            ApiParameter(
                name = name,
                location = ParameterLocation.PATH,
                type = inferType(values),
                required = true, // Path-Parameter sind immer required
                observedValues = values.take(10), // Limitiere auf 10 Beispiele
                example = values.firstOrNull()
            )
        }
    }

    /**
     * Extrahiert Query-Parameter aus mehreren Query-Maps.
     *
     * @param queryMaps Liste von Query-Parameter-Maps aus verschiedenen Requests
     * @return Liste der erkannten Query-Parameter
     */
    fun extractQueryParameters(queryMaps: List<Map<String, String>>): List<ApiParameter> {
        if (queryMaps.isEmpty()) return emptyList()

        // Sammle alle Parameter-Namen und deren Werte
        val parameterValues = mutableMapOf<String, MutableSet<String>>()
        val parameterCounts = mutableMapOf<String, Int>()

        for (queryMap in queryMaps) {
            for ((name, value) in queryMap) {
                parameterValues.getOrPut(name) { mutableSetOf() }.add(value)
                parameterCounts[name] = (parameterCounts[name] ?: 0) + 1
            }
        }

        val totalRequests = queryMaps.size

        // Erstelle Parameter-Objekte
        return parameterValues.map { (name, values) ->
            val valueList = values.toList()
            val count = parameterCounts[name] ?: 0

            ApiParameter(
                name = name,
                location = ParameterLocation.QUERY,
                type = inferType(valueList),
                required = count == totalRequests && totalRequests > 1,
                observedValues = valueList.take(10),
                example = valueList.firstOrNull(),
                description = inferParameterDescription(name, valueList)
            )
        }.sortedByDescending { parameterCounts[it.name] }
    }

    /**
     * Extrahiert relevante Header-Parameter aus mehreren Header-Maps.
     *
     * Ignoriert Standard-HTTP-Headers und fokussiert auf custom/auth Headers.
     *
     * @param headerMaps Liste von Header-Maps aus verschiedenen Requests
     * @return Liste der erkannten Header-Parameter
     */
    fun extractHeaderParameters(headerMaps: List<Map<String, String>>): List<ApiParameter> {
        if (headerMaps.isEmpty()) return emptyList()

        // Sammle relevante Headers
        val parameterValues = mutableMapOf<String, MutableSet<String>>()
        val parameterCounts = mutableMapOf<String, Int>()

        for (headerMap in headerMaps) {
            for ((name, value) in headerMap) {
                val lowerName = name.lowercase()

                // Ignoriere Standard-Headers
                if (lowerName in IGNORED_HEADERS) continue

                parameterValues.getOrPut(name) { mutableSetOf() }.add(value)
                parameterCounts[name] = (parameterCounts[name] ?: 0) + 1
            }
        }

        val totalRequests = headerMaps.size

        // Erstelle Parameter-Objekte
        return parameterValues.map { (name, values) ->
            val valueList = values.toList()
            val count = parameterCounts[name] ?: 0
            val isAuthHeader = name.lowercase() in AUTH_HEADERS

            ApiParameter(
                name = name,
                location = ParameterLocation.HEADER,
                type = ParameterType.STRING,
                required = isAuthHeader || (count == totalRequests && totalRequests > 1),
                observedValues = if (isAuthHeader) emptyList() else valueList.take(5), // Verstecke Auth-Werte
                example = if (isAuthHeader) "<${name}>" else valueList.firstOrNull(),
                description = if (isAuthHeader) "Authentication header" else null
            )
        }.sortedByDescending {
            // Priorisiere Auth-Headers
            if (it.name.lowercase() in AUTH_HEADERS) Int.MAX_VALUE
            else parameterCounts[it.name] ?: 0
        }
    }

    /**
     * Inferiert den Typ eines Parameters aus beobachteten Werten.
     */
    fun inferType(values: List<String>): ParameterType {
        if (values.isEmpty()) return ParameterType.STRING

        // Prüfe ob alle Werte einem Typ entsprechen
        val allIntegers = values.all { it.toLongOrNull() != null }
        if (allIntegers) return ParameterType.INTEGER

        val allNumbers = values.all { it.toDoubleOrNull() != null }
        if (allNumbers) return ParameterType.NUMBER

        val allBooleans = values.all { it.lowercase() in listOf("true", "false", "0", "1") }
        if (allBooleans) return ParameterType.BOOLEAN

        // Prüfe auf Arrays (Komma-getrennte Werte)
        val hasArrays = values.any { it.contains(",") && !it.startsWith("{") }
        if (hasArrays) return ParameterType.ARRAY

        return ParameterType.STRING
    }

    /**
     * Inferiert eine Beschreibung für einen Parameter basierend auf Namen und Werten.
     */
    private fun inferParameterDescription(name: String, values: List<String>): String? {
        val lowerName = name.lowercase()

        return when {
            lowerName == "page" || lowerName == "p" -> "Page number for pagination"
            lowerName == "limit" || lowerName == "per_page" || lowerName == "perpage" -> "Number of items per page"
            lowerName == "offset" || lowerName == "skip" -> "Offset for pagination"
            lowerName == "sort" || lowerName == "order" || lowerName == "orderby" -> "Sort order"
            lowerName == "q" || lowerName == "query" || lowerName == "search" -> "Search query"
            lowerName == "filter" || lowerName == "filters" -> "Filter criteria"
            lowerName == "include" || lowerName == "expand" -> "Related resources to include"
            lowerName == "fields" || lowerName == "select" -> "Fields to return"
            lowerName == "id" || lowerName.endsWith("id") || lowerName.endsWith("_id") -> "Unique identifier"
            lowerName == "token" -> "Authentication or session token"
            lowerName == "callback" || lowerName == "jsonp" -> "JSONP callback function name"
            else -> null
        }
    }

    /**
     * Analysiert Korrelationen zwischen Parametern.
     *
     * Erkennt z.B. dass 'page' und 'limit' oft zusammen auftreten.
     */
    fun analyzeParameterCorrelations(
        queryMaps: List<Map<String, String>>
    ): List<ParameterCorrelation> {
        val correlations = mutableListOf<ParameterCorrelation>()
        val parameterNames = queryMaps.flatMap { it.keys }.toSet()

        // Prüfe Paare
        for (param1 in parameterNames) {
            for (param2 in parameterNames) {
                if (param1 >= param2) continue

                val coOccurrences = queryMaps.count { it.containsKey(param1) && it.containsKey(param2) }
                val param1Only = queryMaps.count { it.containsKey(param1) && !it.containsKey(param2) }
                val param2Only = queryMaps.count { !it.containsKey(param1) && it.containsKey(param2) }

                if (coOccurrences > 0 && (param1Only == 0 || param2Only == 0)) {
                    correlations.add(
                        ParameterCorrelation(
                            parameters = setOf(param1, param2),
                            coOccurrenceRate = coOccurrences.toFloat() / queryMaps.size,
                            type = when {
                                param1Only == 0 && param2Only == 0 -> CorrelationType.ALWAYS_TOGETHER
                                param1Only == 0 -> CorrelationType.FIRST_REQUIRES_SECOND
                                param2Only == 0 -> CorrelationType.SECOND_REQUIRES_FIRST
                                else -> CorrelationType.OFTEN_TOGETHER
                            }
                        )
                    )
                }
            }
        }

        return correlations
    }
}

/**
 * Korrelation zwischen Parametern.
 */
data class ParameterCorrelation(
    val parameters: Set<String>,
    val coOccurrenceRate: Float,
    val type: CorrelationType
)

/**
 * Art der Korrelation.
 */
enum class CorrelationType {
    ALWAYS_TOGETHER,
    OFTEN_TOGETHER,
    FIRST_REQUIRES_SECOND,
    SECOND_REQUIRES_FIRST
}
