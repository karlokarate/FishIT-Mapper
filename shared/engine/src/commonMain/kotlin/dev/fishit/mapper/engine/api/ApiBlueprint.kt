package dev.fishit.mapper.engine.api

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API Blueprint - Das zentrale Datenmodell für Reverse-Engineering-Ergebnisse.
 *
 * Ein Blueprint repräsentiert die vollständig analysierte API einer Website,
 * extrahiert aus importiertem Traffic und korreliert mit User Actions.
 *
 * ## Verwendung
 * ```kotlin
 * val builder = ApiBlueprintBuilder()
 * val blueprint = builder.build(
 *     projectId = "my-project",
 *     exchanges = capturedExchanges,
 *     correlatedActions = websiteMap.actions
 * )
 *
 * // Export
 * val openApi = OpenApiExporter().export(blueprint)
 * val curlCommands = CurlExporter().export(blueprint)
 * ```
 */
@Serializable
data class ApiBlueprint(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String? = null,
    val baseUrl: String,
    val endpoints: List<ApiEndpoint>,
    val authPatterns: List<AuthPattern> = emptyList(),
    val flows: List<ApiFlow> = emptyList(),
    val metadata: BlueprintMetadata,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Metadata über den Blueprint und seine Erstellung.
 */
@Serializable
data class BlueprintMetadata(
    val totalExchangesAnalyzed: Int,
    val uniqueEndpointsDetected: Int,
    val authPatternsDetected: Int,
    val flowsDetected: Int,
    val coveragePercent: Float,
    val generatedBy: String = "FishIT-Mapper"
)

// =============================================================================
// API Endpoint Model
// =============================================================================

/**
 * Ein API-Endpoint mit allen analysierten Details.
 *
 * @property pathTemplate URL-Template mit Parameter-Platzhaltern, z.B. "/api/users/{userId}/posts"
 * @property examples Referenzen zu echten Captures, die diesen Endpoint nutzen
 */
@Serializable
data class ApiEndpoint(
    val id: String,
    val method: HttpMethod,
    val pathTemplate: String,
    val pathParameters: List<ApiParameter> = emptyList(),
    val queryParameters: List<ApiParameter> = emptyList(),
    val headerParameters: List<ApiParameter> = emptyList(),
    val requestBody: RequestBodySpec? = null,
    val responses: List<ResponseSpec> = emptyList(),
    val authRequired: AuthType = AuthType.None,
    val examples: List<String> = emptyList(),
    val metadata: EndpointMetadata,
    val tags: List<String> = emptyList()
)

/**
 * Metadata über den Endpoint.
 */
@Serializable
data class EndpointMetadata(
    val hitCount: Int,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val avgResponseTimeMs: Long? = null,
    val successRate: Float? = null,
    val description: String? = null
)

/**
 * HTTP-Methoden.
 */
@Serializable
enum class HttpMethod {
    @SerialName("GET") GET,
    @SerialName("POST") POST,
    @SerialName("PUT") PUT,
    @SerialName("PATCH") PATCH,
    @SerialName("DELETE") DELETE,
    @SerialName("HEAD") HEAD,
    @SerialName("OPTIONS") OPTIONS
}

// =============================================================================
// Parameter Models
// =============================================================================

/**
 * Ein API-Parameter (Path, Query, Header, Body).
 *
 * @property observedValues Tatsächlich beobachtete Werte aus dem Traffic
 */
@Serializable
data class ApiParameter(
    val name: String,
    val location: ParameterLocation,
    val type: ParameterType,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val observedValues: List<String> = emptyList(),
    val description: String? = null,
    val example: String? = null
)

/**
 * Ort des Parameters im Request.
 */
@Serializable
enum class ParameterLocation {
    @SerialName("path") PATH,
    @SerialName("query") QUERY,
    @SerialName("header") HEADER,
    @SerialName("body") BODY,
    @SerialName("cookie") COOKIE
}

/**
 * Typ des Parameters (inferiert aus beobachteten Werten).
 */
@Serializable
enum class ParameterType {
    @SerialName("string") STRING,
    @SerialName("integer") INTEGER,
    @SerialName("number") NUMBER,
    @SerialName("boolean") BOOLEAN,
    @SerialName("array") ARRAY,
    @SerialName("object") OBJECT
}

// =============================================================================
// Request/Response Body Models
// =============================================================================

/**
 * Spezifikation des Request-Body.
 */
@Serializable
data class RequestBodySpec(
    val contentType: String,
    val schema: JsonSchema? = null,
    val examples: List<BodyExample> = emptyList(),
    val required: Boolean = true
)

/**
 * Spezifikation einer Response.
 */
@Serializable
data class ResponseSpec(
    val statusCode: Int,
    val description: String? = null,
    val contentType: String? = null,
    val schema: JsonSchema? = null,
    val headers: List<ApiParameter> = emptyList(),
    val examples: List<BodyExample> = emptyList()
)

/**
 * Ein Beispiel-Body aus echtem Traffic.
 */
@Serializable
data class BodyExample(
    val name: String,
    val value: String,
    val exchangeId: String? = null
)

/**
 * Vereinfachtes JSON Schema für Body-Analyse.
 */
@Serializable
data class JsonSchema(
    val type: ParameterType,
    val properties: Map<String, JsonSchema>? = null,
    val items: JsonSchema? = null,
    val required: List<String>? = null,
    val example: String? = null
)

// =============================================================================
// Authentication Models
// =============================================================================

/**
 * Authentifizierungstyp.
 */
@Serializable
enum class AuthType {
    @SerialName("none") None,
    @SerialName("bearer") BearerToken,
    @SerialName("session") SessionCookie,
    @SerialName("apiKey") ApiKey,
    @SerialName("basic") BasicAuth,
    @SerialName("oauth2") OAuth2
}

/**
 * Erkanntes Auth-Pattern.
 */
@Serializable
sealed class AuthPattern {
    abstract val id: String
    abstract val type: AuthType

    /**
     * Bearer Token Authentifizierung.
     */
    @Serializable
    @SerialName("bearer")
    data class BearerTokenPattern(
        override val id: String,
        override val type: AuthType = AuthType.BearerToken,
        val headerName: String = "Authorization",
        val tokenPrefix: String = "Bearer ",
        val tokenSource: TokenSource? = null
    ) : AuthPattern()

    /**
     * Session Cookie Authentifizierung.
     */
    @Serializable
    @SerialName("session")
    data class SessionCookiePattern(
        override val id: String,
        override val type: AuthType = AuthType.SessionCookie,
        val cookieName: String,
        val domain: String? = null
    ) : AuthPattern()

    /**
     * API Key Authentifizierung.
     */
    @Serializable
    @SerialName("apiKey")
    data class ApiKeyPattern(
        override val id: String,
        override val type: AuthType = AuthType.ApiKey,
        val location: ParameterLocation,
        val parameterName: String
    ) : AuthPattern()

    /**
     * Basic Auth.
     */
    @Serializable
    @SerialName("basic")
    data class BasicAuthPattern(
        override val id: String,
        override val type: AuthType = AuthType.BasicAuth
    ) : AuthPattern()

    /**
     * OAuth2 Authentifizierung.
     */
    @Serializable
    @SerialName("oauth2")
    data class OAuth2Pattern(
        override val id: String,
        override val type: AuthType = AuthType.OAuth2,
        val tokenEndpoint: String,
        val grantType: String,
        val scopes: List<String> = emptyList()
    ) : AuthPattern()
}

/**
 * Quelle eines Auth-Tokens.
 */
@Serializable
data class TokenSource(
    val endpointId: String? = null,
    val jsonPath: String? = null,
    val headerName: String? = null
)

// =============================================================================
// API Flow Models
// =============================================================================

/**
 * Ein API-Flow repräsentiert eine Sequenz von API-Calls für einen User-Flow.
 *
 * Beispiel: Login-Flow, Checkout-Flow, Create-Post-Flow
 */
@Serializable
data class ApiFlow(
    val id: String,
    val name: String,
    val description: String? = null,
    val steps: List<FlowStep>,
    val sourceActionIds: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)

/**
 * Ein Schritt in einem API-Flow.
 *
 * @property parameterBindings Mapping von Parameter-Name zu Binding
 * @property extractors Extraktoren für Response-Werte
 */
@Serializable
data class FlowStep(
    val order: Int,
    val endpointId: String,
    val description: String? = null,
    val parameterBindings: Map<String, ParameterBinding> = emptyMap(),
    val expectedStatus: Int? = null,
    val extractors: List<ResponseExtractor> = emptyList(),
    val optional: Boolean = false
)

/**
 * Binding für einen Parameter-Wert.
 */
@Serializable
sealed class ParameterBinding {
    /**
     * Statischer Wert.
     */
    @Serializable
    @SerialName("static")
    data class StaticValue(val value: String) : ParameterBinding()

    /**
     * Wert aus vorherigem Step (Variable).
     */
    @Serializable
    @SerialName("variable")
    data class FromVariable(val variableName: String) : ParameterBinding()

    /**
     * Wert vom User Input.
     */
    @Serializable
    @SerialName("input")
    data class UserInput(
        val inputName: String,
        val description: String? = null,
        val defaultValue: String? = null
    ) : ParameterBinding()
}

/**
 * Extrahiert Werte aus einer Response für nachfolgende Steps.
 */
@Serializable
data class ResponseExtractor(
    val variableName: String,
    val source: ExtractionSource
)

/**
 * Quelle für die Extraktion.
 */
@Serializable
sealed class ExtractionSource {
    /**
     * Aus JSON Body via JSONPath.
     */
    @Serializable
    @SerialName("jsonPath")
    data class JsonPath(val path: String) : ExtractionSource()

    /**
     * Aus Response Header.
     */
    @Serializable
    @SerialName("header")
    data class Header(val headerName: String) : ExtractionSource()

    /**
     * Aus Cookie.
     */
    @Serializable
    @SerialName("cookie")
    data class Cookie(val cookieName: String) : ExtractionSource()

    /**
     * Regex aus Body.
     */
    @Serializable
    @SerialName("regex")
    data class Regex(val pattern: String, val group: Int = 1) : ExtractionSource()
}
