package dev.fishit.mapper.engine.api

import dev.fishit.mapper.android.import.httpcanary.CapturedExchange
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Extrahiert API-Endpoints aus gecaptureten HTTP-Exchanges.
 *
 * Der Extractor analysiert URL-Patterns, gruppiert ähnliche Requests
 * und erkennt Path-Parameter automatisch.
 *
 * ## Verwendung
 * ```kotlin
 * val extractor = EndpointExtractor()
 * val endpoints = extractor.extract(exchanges)
 * ```
 *
 * ## Algorithmus
 * 1. Gruppiere Exchanges nach Method + Host
 * 2. Analysiere URL-Pfade für Pattern-Erkennung
 * 3. Identifiziere Path-Parameter (IDs, UUIDs, etc.)
 * 4. Extrahiere Query-Parameter
 * 5. Analysiere Request/Response Bodies
 */
class EndpointExtractor {

    companion object {
        // Regex-Patterns für automatische Parameter-Erkennung
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        private val NUMERIC_ID_PATTERN = Regex("^\\d+$")
        private val MONGO_ID_PATTERN = Regex("^[0-9a-f]{24}$", RegexOption.IGNORE_CASE)
        private val SLUG_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)+$", RegexOption.IGNORE_CASE)

        // Common API path segments that are NOT parameters
        private val KNOWN_STATIC_SEGMENTS = setOf(
            "api", "v1", "v2", "v3", "v4",
            "users", "posts", "comments", "items", "products",
            "auth", "login", "logout", "register", "signup",
            "search", "filter", "sort", "page",
            "admin", "dashboard", "settings", "profile",
            "public", "private", "internal"
        )
    }

    /**
     * Extrahiert Endpoints aus einer Liste von Exchanges.
     *
     * @param exchanges Gecapturete HTTP-Exchanges
     * @param filterApiOnly Nur API-Requests (keine Assets) berücksichtigen
     * @return Liste der extrahierten Endpoints
     */
    fun extract(
        exchanges: List<CapturedExchange>,
        filterApiOnly: Boolean = true
    ): List<ApiEndpoint> {
        // Filter für API-Requests
        val apiExchanges = if (filterApiOnly) {
            exchanges.filter { isApiRequest(it) }
        } else {
            exchanges
        }

        // Gruppiere nach Method + Path Pattern
        val grouped = groupByEndpoint(apiExchanges)

        // Konvertiere Gruppen zu Endpoints
        return grouped.map { (key, group) ->
            buildEndpoint(key, group)
        }
    }

    /**
     * Prüft ob ein Exchange ein API-Request ist.
     */
    private fun isApiRequest(exchange: CapturedExchange): Boolean {
        val url = exchange.request.url
        val contentType = exchange.response?.contentType ?: exchange.request.contentType

        // Exclude static assets
        val staticExtensions = listOf(".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg", ".woff", ".woff2", ".ttf")
        if (staticExtensions.any { url.lowercase().contains(it) }) {
            return false
        }

        // Include if JSON or API-path
        if (contentType?.contains("json") == true) return true
        if (url.contains("/api/") || url.contains("/v1/") || url.contains("/v2/")) return true

        // Include POST/PUT/PATCH/DELETE requests
        val method = exchange.request.method.uppercase()
        if (method in listOf("POST", "PUT", "PATCH", "DELETE")) return true

        return false
    }

    /**
     * Gruppiert Exchanges nach Endpoint-Pattern.
     */
    private fun groupByEndpoint(exchanges: List<CapturedExchange>): Map<EndpointKey, List<CapturedExchange>> {
        val groups = mutableMapOf<EndpointKey, MutableList<CapturedExchange>>()

        for (exchange in exchanges) {
            val key = createEndpointKey(exchange)
            groups.getOrPut(key) { mutableListOf() }.add(exchange)
        }

        return groups
    }

    /**
     * Erstellt einen Endpoint-Key für Gruppierung.
     */
    private fun createEndpointKey(exchange: CapturedExchange): EndpointKey {
        val url = parseUrl(exchange.request.url)
        val method = HttpMethod.valueOf(exchange.request.method.uppercase())

        // Erstelle Path-Template
        val pathTemplate = createPathTemplate(url.pathSegments)

        return EndpointKey(
            method = method,
            host = url.host,
            pathTemplate = pathTemplate
        )
    }

    /**
     * Erstellt ein Path-Template aus Path-Segmenten.
     * Ersetzt dynamische Werte durch Parameter-Platzhalter.
     */
    private fun createPathTemplate(segments: List<String>): String {
        var paramCounter = 0

        val templateSegments = segments.map { segment ->
            when {
                segment.isBlank() -> ""
                isStaticSegment(segment) -> segment
                isLikelyParameter(segment) -> {
                    val paramName = inferParameterName(segment, segments, paramCounter++)
                    "{$paramName}"
                }
                else -> segment
            }
        }

        return "/" + templateSegments.filter { it.isNotBlank() }.joinToString("/")
    }

    /**
     * Prüft ob ein Segment ein bekanntes statisches Segment ist.
     */
    private fun isStaticSegment(segment: String): Boolean {
        return segment.lowercase() in KNOWN_STATIC_SEGMENTS
    }

    /**
     * Prüft ob ein Segment wahrscheinlich ein Parameter ist.
     */
    private fun isLikelyParameter(segment: String): Boolean {
        // UUIDs
        if (UUID_PATTERN.matches(segment)) return true

        // Numeric IDs
        if (NUMERIC_ID_PATTERN.matches(segment)) return true

        // MongoDB ObjectIds
        if (MONGO_ID_PATTERN.matches(segment)) return true

        // Long alphanumeric strings (likely tokens/IDs)
        if (segment.length > 20 && segment.all { it.isLetterOrDigit() }) return true

        return false
    }

    /**
     * Inferiert einen sinnvollen Parameter-Namen.
     */
    private fun inferParameterName(segment: String, allSegments: List<String>, index: Int): String {
        // Finde das vorherige Segment für Kontext
        val segmentIndex = allSegments.indexOf(segment)
        if (segmentIndex > 0) {
            val previousSegment = allSegments[segmentIndex - 1].lowercase()

            // Entferne Plural-S und füge "Id" hinzu
            val singularForm = when {
                previousSegment.endsWith("ies") -> previousSegment.dropLast(3) + "y"
                previousSegment.endsWith("es") -> previousSegment.dropLast(2)
                previousSegment.endsWith("s") -> previousSegment.dropLast(1)
                else -> previousSegment
            }

            if (singularForm.isNotBlank() && singularForm !in KNOWN_STATIC_SEGMENTS) {
                return "${singularForm}Id"
            }
        }

        // Fallback
        return when {
            UUID_PATTERN.matches(segment) -> "uuid"
            NUMERIC_ID_PATTERN.matches(segment) -> "id"
            MONGO_ID_PATTERN.matches(segment) -> "objectId"
            else -> "param$index"
        }
    }

    /**
     * Baut einen ApiEndpoint aus einer Gruppe von Exchanges.
     */
    private fun buildEndpoint(key: EndpointKey, exchanges: List<CapturedExchange>): ApiEndpoint {
        val parameterAnalyzer = ParameterAnalyzer()

        // Analysiere Path-Parameter
        val pathParams = parameterAnalyzer.extractPathParameters(
            key.pathTemplate,
            exchanges.map { parseUrl(it.request.url).path }
        )

        // Analysiere Query-Parameter
        val queryParams = parameterAnalyzer.extractQueryParameters(
            exchanges.map { parseUrl(it.request.url).queryParams }
        )

        // Analysiere Header-Parameter
        val headerParams = parameterAnalyzer.extractHeaderParameters(
            exchanges.map { it.request.headers }
        )

        // Analysiere Request Body
        val requestBody = analyzeRequestBody(exchanges)

        // Analysiere Responses
        val responses = analyzeResponses(exchanges)

        // Erkenne Auth-Requirement
        val authRequired = detectAuthRequirement(exchanges)

        // Berechne Metadata
        val timestamps = exchanges.map { it.startedAt }
        val responseTimes = exchanges.mapNotNull { exchange ->
            exchange.completedAt?.let { completed ->
                (completed - exchange.startedAt).inWholeMilliseconds
            }
        }
        val successCount = exchanges.count {
            it.response?.status in 200..299
        }

        return ApiEndpoint(
            id = generateEndpointId(key),
            method = key.method,
            pathTemplate = key.pathTemplate,
            pathParameters = pathParams,
            queryParameters = queryParams,
            headerParameters = headerParams,
            requestBody = requestBody,
            responses = responses,
            authRequired = authRequired,
            examples = exchanges.map { it.exchangeId },
            metadata = EndpointMetadata(
                hitCount = exchanges.size,
                firstSeen = timestamps.minOrNull() ?: Clock.System.now(),
                lastSeen = timestamps.maxOrNull() ?: Clock.System.now(),
                avgResponseTimeMs = responseTimes.average().toLong().takeIf { responseTimes.isNotEmpty() },
                successRate = if (exchanges.isNotEmpty()) successCount.toFloat() / exchanges.size else null
            ),
            tags = inferTags(key.pathTemplate)
        )
    }

    /**
     * Analysiert Request-Bodies.
     */
    private fun analyzeRequestBody(exchanges: List<CapturedExchange>): RequestBodySpec? {
        val bodiesWithContentType = exchanges.mapNotNull { exchange ->
            exchange.requestBody?.let { body ->
                body to (exchange.request.contentType ?: "application/octet-stream")
            }
        }

        if (bodiesWithContentType.isEmpty()) return null

        val contentTypes = bodiesWithContentType.map { it.second }.distinct()
        val primaryContentType = contentTypes.firstOrNull { it.contains("json") }
            ?: contentTypes.first()

        val examples = bodiesWithContentType
            .filter { it.second == primaryContentType }
            .take(3)
            .mapIndexed { index, (body, _) ->
                BodyExample(
                    name = "example${index + 1}",
                    value = body.take(10000) // Limit size
                )
            }

        // Versuche JSON Schema zu inferieren
        val schema = if (primaryContentType.contains("json")) {
            inferJsonSchema(bodiesWithContentType.map { it.first })
        } else null

        return RequestBodySpec(
            contentType = primaryContentType,
            schema = schema,
            examples = examples
        )
    }

    /**
     * Analysiert Responses.
     */
    private fun analyzeResponses(exchanges: List<CapturedExchange>): List<ResponseSpec> {
        return exchanges
            .mapNotNull { it.response }
            .groupBy { it.status }
            .map { (status, responses) ->
                val contentTypes = responses.mapNotNull { it.contentType }.distinct()
                val primaryContentType = contentTypes.firstOrNull { it.contains("json") }
                    ?: contentTypes.firstOrNull()

                val bodies = exchanges
                    .filter { it.response?.status == status }
                    .mapNotNull { it.responseBody }

                ResponseSpec(
                    statusCode = status,
                    description = getStatusDescription(status),
                    contentType = primaryContentType,
                    schema = if (primaryContentType?.contains("json") == true) {
                        inferJsonSchema(bodies)
                    } else null,
                    examples = bodies.take(2).mapIndexed { index, body ->
                        BodyExample(
                            name = "example${index + 1}",
                            value = body.take(5000)
                        )
                    }
                )
            }
            .sortedBy { it.statusCode }
    }

    /**
     * Erkennt Auth-Requirements.
     */
    private fun detectAuthRequirement(exchanges: List<CapturedExchange>): AuthType {
        for (exchange in exchanges) {
            val headers = exchange.request.headers

            // Bearer Token
            val authHeader = headers["Authorization"] ?: headers["authorization"]
            if (authHeader != null) {
                return when {
                    authHeader.startsWith("Bearer ", ignoreCase = true) -> AuthType.BearerToken
                    authHeader.startsWith("Basic ", ignoreCase = true) -> AuthType.BasicAuth
                    else -> AuthType.ApiKey
                }
            }

            // API Key in Header
            if (headers.any { (key, _) ->
                key.contains("api", ignoreCase = true) && key.contains("key", ignoreCase = true)
            }) {
                return AuthType.ApiKey
            }

            // Session Cookie
            val cookies = headers["Cookie"] ?: headers["cookie"]
            if (cookies != null && (cookies.contains("session", ignoreCase = true) ||
                                    cookies.contains("token", ignoreCase = true))) {
                return AuthType.SessionCookie
            }
        }

        return AuthType.None
    }

    /**
     * Inferiert Tags aus dem Path.
     */
    private fun inferTags(pathTemplate: String): List<String> {
        val segments = pathTemplate.split("/").filter { it.isNotBlank() && !it.startsWith("{") }

        return segments
            .filter { it !in listOf("api", "v1", "v2", "v3") }
            .take(2)
    }

    /**
     * Generiert eine eindeutige Endpoint-ID.
     */
    private fun generateEndpointId(key: EndpointKey): String {
        val pathPart = key.pathTemplate
            .replace("/", "_")
            .replace("{", "")
            .replace("}", "")
            .trim('_')
        return "${key.method.name.lowercase()}_$pathPart"
    }

    /**
     * Inferiert ein JSON Schema aus Beispiel-Bodies.
     */
    private fun inferJsonSchema(bodies: List<String>): JsonSchema? {
        // Vereinfachte Implementation - in einer echten App würde man
        // JSON parsen und Typen inferieren
        return try {
            if (bodies.isEmpty()) return null

            val firstBody = bodies.first().trim()
            when {
                firstBody.startsWith("{") -> JsonSchema(type = ParameterType.OBJECT)
                firstBody.startsWith("[") -> JsonSchema(type = ParameterType.ARRAY)
                else -> JsonSchema(type = ParameterType.STRING)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gibt eine Beschreibung für einen HTTP Status-Code zurück.
     */
    private fun getStatusDescription(status: Int): String {
        return when (status) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "HTTP $status"
        }
    }

    /**
     * Parst eine URL.
     */
    private fun parseUrl(urlString: String): ParsedUrl {
        val url = try {
            java.net.URL(urlString)
        } catch (e: Exception) {
            return ParsedUrl("", "", emptyList(), emptyMap())
        }

        val queryParams = url.query?.split("&")
            ?.mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            ?.toMap()
            ?: emptyMap()

        return ParsedUrl(
            host = url.host,
            path = url.path,
            pathSegments = url.path.split("/").filter { it.isNotBlank() },
            queryParams = queryParams
        )
    }

    /**
     * Interne Datenklassen.
     */
    private data class EndpointKey(
        val method: HttpMethod,
        val host: String,
        val pathTemplate: String
    )

    private data class ParsedUrl(
        val host: String,
        val path: String,
        val pathSegments: List<String>,
        val queryParams: Map<String, String>
    )
}
