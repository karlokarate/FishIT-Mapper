package dev.fishit.mapper.engine.api

import dev.fishit.mapper.android.import.httpcanary.CapturedExchange
import kotlinx.datetime.Clock

/**
 * Erkennt Authentifizierungs-Patterns aus HTTP-Traffic.
 *
 * Analysiert Headers, Cookies und Request/Response Bodies um zu erkennen:
 * - Bearer Token Authentifizierung
 * - Session Cookies
 * - API Keys
 * - Basic Auth
 * - OAuth2 Flows
 *
 * ## Verwendung
 * ```kotlin
 * val detector = AuthPatternDetector()
 * val patterns = detector.detect(exchanges)
 *
 * // Erkannte Patterns verwenden
 * patterns.forEach { pattern ->
 *     when (pattern) {
 *         is AuthPattern.BearerTokenPattern -> {
 *             println("Token in Header: ${pattern.headerName}")
 *         }
 *         is AuthPattern.SessionCookiePattern -> {
 *             println("Session Cookie: ${pattern.cookieName}")
 *         }
 *         // ...
 *     }
 * }
 * ```
 */
class AuthPatternDetector {

    companion object {
        // Bekannte Auth-Header
        private val BEARER_HEADERS = setOf("authorization", "x-auth-token", "x-access-token")
        private val API_KEY_HEADERS = setOf("x-api-key", "api-key", "apikey", "x-api-secret")

        // Bekannte Session-Cookie-Namen
        private val SESSION_COOKIE_PATTERNS = listOf(
            Regex("session", RegexOption.IGNORE_CASE),
            Regex("sid", RegexOption.IGNORE_CASE),
            Regex("token", RegexOption.IGNORE_CASE),
            Regex("auth", RegexOption.IGNORE_CASE),
            Regex("jwt", RegexOption.IGNORE_CASE),
            Regex("access", RegexOption.IGNORE_CASE)
        )

        // OAuth2 Endpoints
        private val OAUTH_TOKEN_PATTERNS = listOf(
            Regex("/oauth/token", RegexOption.IGNORE_CASE),
            Regex("/oauth2/token", RegexOption.IGNORE_CASE),
            Regex("/token", RegexOption.IGNORE_CASE),
            Regex("/auth/token", RegexOption.IGNORE_CASE)
        )
    }

    private var patternIdCounter = 0

    /**
     * Erkennt alle Auth-Patterns aus den Exchanges.
     *
     * @param exchanges Liste der HTTP-Exchanges
     * @return Liste erkannter Auth-Patterns
     */
    fun detect(exchanges: List<CapturedExchange>): List<AuthPattern> {
        val patterns = mutableListOf<AuthPattern>()

        // Suche nach Bearer Tokens
        detectBearerTokens(exchanges)?.let { patterns.add(it) }

        // Suche nach Basic Auth
        detectBasicAuth(exchanges)?.let { patterns.add(it) }

        // Suche nach API Keys
        detectApiKeys(exchanges).forEach { patterns.add(it) }

        // Suche nach Session Cookies
        detectSessionCookies(exchanges).forEach { patterns.add(it) }

        // Suche nach OAuth2 Flows
        detectOAuth2(exchanges)?.let { patterns.add(it) }

        return patterns
    }

    /**
     * Erkennt Bearer Token Authentifizierung.
     */
    private fun detectBearerTokens(exchanges: List<CapturedExchange>): AuthPattern.BearerTokenPattern? {
        for (exchange in exchanges) {
            for ((headerName, headerValue) in exchange.request.headers) {
                val lowerName = headerName.lowercase()

                if (lowerName in BEARER_HEADERS) {
                    val trimmedValue = headerValue.trim()

                    // Bearer Token Format
                    if (trimmedValue.startsWith("Bearer ", ignoreCase = true)) {
                        val tokenSource = findTokenSource(exchanges, trimmedValue.substring(7).trim())

                        return AuthPattern.BearerTokenPattern(
                            id = "auth_bearer_${patternIdCounter++}",
                            headerName = headerName,
                            tokenPrefix = "Bearer ",
                            tokenSource = tokenSource
                        )
                    }

                    // Generic Token Format
                    if (trimmedValue.startsWith("Token ", ignoreCase = true)) {
                        return AuthPattern.BearerTokenPattern(
                            id = "auth_token_${patternIdCounter++}",
                            headerName = headerName,
                            tokenPrefix = "Token "
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Erkennt Basic Auth.
     */
    private fun detectBasicAuth(exchanges: List<CapturedExchange>): AuthPattern.BasicAuthPattern? {
        for (exchange in exchanges) {
            val authHeader = exchange.request.headers["Authorization"]
                ?: exchange.request.headers["authorization"]

            if (authHeader?.startsWith("Basic ", ignoreCase = true) == true) {
                return AuthPattern.BasicAuthPattern(
                    id = "auth_basic_${patternIdCounter++}"
                )
            }
        }
        return null
    }

    /**
     * Erkennt API Keys in Headers oder Query-Parametern.
     */
    private fun detectApiKeys(exchanges: List<CapturedExchange>): List<AuthPattern.ApiKeyPattern> {
        val patterns = mutableListOf<AuthPattern.ApiKeyPattern>()
        val foundKeys = mutableSetOf<Pair<ParameterLocation, String>>()

        for (exchange in exchanges) {
            // Header API Keys
            for ((headerName, _) in exchange.request.headers) {
                val lowerName = headerName.lowercase()
                if (lowerName in API_KEY_HEADERS) {
                    val key = ParameterLocation.HEADER to headerName
                    if (key !in foundKeys) {
                        foundKeys.add(key)
                        patterns.add(
                            AuthPattern.ApiKeyPattern(
                                id = "auth_apikey_header_${patternIdCounter++}",
                                location = ParameterLocation.HEADER,
                                parameterName = headerName
                            )
                        )
                    }
                }
            }

            // Query API Keys
            val url = exchange.request.url
            val queryString = url.substringAfter("?", "")
            if (queryString.isNotEmpty()) {
                val queryParams = queryString.split("&").associate { param ->
                    val parts = param.split("=", limit = 2)
                    parts[0] to (parts.getOrNull(1) ?: "")
                }

                for ((paramName, _) in queryParams) {
                    val lowerName = paramName.lowercase()
                    if (lowerName.contains("key") || lowerName.contains("token") || lowerName.contains("api")) {
                        val key = ParameterLocation.QUERY to paramName
                        if (key !in foundKeys) {
                            foundKeys.add(key)
                            patterns.add(
                                AuthPattern.ApiKeyPattern(
                                    id = "auth_apikey_query_${patternIdCounter++}",
                                    location = ParameterLocation.QUERY,
                                    parameterName = paramName
                                )
                            )
                        }
                    }
                }
            }
        }

        return patterns
    }

    /**
     * Erkennt Session Cookies.
     */
    private fun detectSessionCookies(exchanges: List<CapturedExchange>): List<AuthPattern.SessionCookiePattern> {
        val patterns = mutableListOf<AuthPattern.SessionCookiePattern>()
        val foundCookies = mutableSetOf<String>()

        for (exchange in exchanges) {
            // Parse Cookie Header
            val cookieHeader = exchange.request.headers["Cookie"]
                ?: exchange.request.headers["cookie"]
                ?: continue

            val cookies = parseCookies(cookieHeader)

            for ((cookieName, _) in cookies) {
                // Prüfe ob Cookie-Name auf Session hinweist
                val isSessionCookie = SESSION_COOKIE_PATTERNS.any { pattern ->
                    pattern.containsMatchIn(cookieName)
                }

                if (isSessionCookie && cookieName !in foundCookies) {
                    foundCookies.add(cookieName)

                    // Extrahiere Domain aus URL
                    val domain = try {
                        java.net.URL(exchange.request.url).host
                    } catch (e: Exception) {
                        null
                    }

                    patterns.add(
                        AuthPattern.SessionCookiePattern(
                            id = "auth_session_${patternIdCounter++}",
                            cookieName = cookieName,
                            domain = domain
                        )
                    )
                }
            }
        }

        return patterns
    }

    /**
     * Erkennt OAuth2 Flows.
     */
    private fun detectOAuth2(exchanges: List<CapturedExchange>): AuthPattern.OAuth2Pattern? {
        for (exchange in exchanges) {
            val url = exchange.request.url.lowercase()

            // Suche nach OAuth Token Endpoints
            val isTokenEndpoint = OAUTH_TOKEN_PATTERNS.any { it.containsMatchIn(url) }

            if (isTokenEndpoint && exchange.request.method.uppercase() == "POST") {
                // Parse Request Body für Grant Type
                val body = exchange.requestBody ?: ""
                val grantType = extractFormValue(body, "grant_type")
                    ?: extractJsonValue(body, "grant_type")
                    ?: "unknown"

                val scopes = extractFormValue(body, "scope")
                    ?: extractJsonValue(body, "scope")
                    ?: ""

                return AuthPattern.OAuth2Pattern(
                    id = "auth_oauth2_${patternIdCounter++}",
                    tokenEndpoint = exchange.request.url,
                    grantType = grantType,
                    scopes = scopes.split(" ").filter { it.isNotBlank() }
                )
            }
        }
        return null
    }

    /**
     * Findet die Quelle eines Tokens (wo er ursprünglich herkam).
     */
    private fun findTokenSource(exchanges: List<CapturedExchange>, token: String): TokenSource? {
        // Suche Token in Response Bodies
        for (exchange in exchanges) {
            val responseBody = exchange.responseBody ?: continue

            if (responseBody.contains(token)) {
                // Token wurde in dieser Response zurückgegeben
                val jsonPath = findJsonPath(responseBody, token)

                return TokenSource(
                    endpointId = null, // Wird später gesetzt
                    jsonPath = jsonPath
                )
            }
        }

        // Suche Token in Response Headers
        for (exchange in exchanges) {
            for ((headerName, headerValue) in exchange.response?.headers ?: emptyMap()) {
                if (headerValue.contains(token)) {
                    return TokenSource(
                        headerName = headerName
                    )
                }
            }
        }

        return null
    }

    /**
     * Versucht den JSON-Path zu einem Wert zu finden.
     * Vereinfachte Implementation - in einer echten App würde man den JSON parsen.
     */
    private fun findJsonPath(json: String, value: String): String? {
        // Suche nach "key": "value" Pattern
        val keyPatterns = listOf(
            Regex("\"([^\"]+)\"\\s*:\\s*\"${Regex.escape(value)}\""),
            Regex("\"([^\"]+)\"\\s*:\\s*${Regex.escape(value)}[,}\\s]")
        )

        for (pattern in keyPatterns) {
            val match = pattern.find(json)
            if (match != null) {
                val key = match.groupValues[1]
                return "$.$key"
            }
        }

        // Bekannte Token-Keys
        val commonTokenKeys = listOf(
            "access_token", "accessToken", "token", "id_token", "idToken",
            "refresh_token", "refreshToken", "jwt", "bearer"
        )

        for (key in commonTokenKeys) {
            if (json.contains("\"$key\"")) {
                return "$.$key"
            }
        }

        return null
    }

    /**
     * Parst Cookies aus einem Cookie-Header.
     */
    private fun parseCookies(cookieHeader: String): Map<String, String> {
        return cookieHeader.split(";")
            .mapNotNull { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0].trim() to parts[1].trim()
                } else null
            }
            .toMap()
    }

    /**
     * Extrahiert einen Wert aus Form-Encoded Data.
     */
    private fun extractFormValue(body: String, key: String): String? {
        val pattern = Regex("(?:^|&)${Regex.escape(key)}=([^&]*)")
        return pattern.find(body)?.groupValues?.get(1)
    }

    /**
     * Extrahiert einen Wert aus JSON.
     */
    private fun extractJsonValue(body: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(body)?.groupValues?.get(1)
    }
}
