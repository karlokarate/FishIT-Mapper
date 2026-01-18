package dev.fishit.mapper.engine.export

import dev.fishit.mapper.engine.api.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Exportiert API Blueprints in verschiedene Formate.
 *
 * Unterstützte Formate:
 * - OpenAPI 3.0 (Swagger)
 * - cURL Commands
 * - Postman Collection
 * - TypeScript Client
 * - Kotlin Client
 *
 * ## Verwendung
 * ```kotlin
 * val exporter = ApiExporter()
 *
 * // OpenAPI
 * val openApiSpec = exporter.toOpenApi(blueprint)
 *
 * // cURL
 * val curlCommands = exporter.toCurl(blueprint)
 *
 * // Postman
 * val postmanCollection = exporter.toPostman(blueprint)
 * ```
 */
class ApiExporter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    // =========================================================================
    // OpenAPI Export
    // =========================================================================

    /**
     * Exportiert als OpenAPI 3.0 Spezifikation (JSON).
     */
    fun toOpenApi(blueprint: ApiBlueprint): String {
        val paths = buildOpenApiPaths(blueprint.endpoints)
        val securitySchemes = buildSecuritySchemes(blueprint.authPatterns)

        val openApi = buildString {
            appendLine("{")
            appendLine("""  "openapi": "3.0.3",""")
            appendLine("""  "info": {""")
            appendLine("""    "title": "${escapeJson(blueprint.name)}",""")
            appendLine("""    "description": "${escapeJson(blueprint.description ?: "")}",""")
            appendLine("""    "version": "1.0.0"  """)
            appendLine("  },")
            appendLine("""  "servers": [""")
            appendLine("""    { "url": "${escapeJson(blueprint.baseUrl)}" }""")
            appendLine("  ],")

            // Security Schemes
            if (securitySchemes.isNotEmpty()) {
                appendLine("""  "components": {""")
                appendLine("""    "securitySchemes": {""")
                append(securitySchemes.joinToString(",\n") { "      $it" })
                appendLine()
                appendLine("    }")
                appendLine("  },")
            }

            // Paths
            appendLine("""  "paths": {""")
            append(paths.joinToString(",\n"))
            appendLine()
            appendLine("  }")
            appendLine("}")
        }

        return openApi
    }

    private fun buildOpenApiPaths(endpoints: List<ApiEndpoint>): List<String> {
        return endpoints
            .groupBy { it.pathTemplate }
            .map { (path, pathEndpoints) ->
                val methods = pathEndpoints.map { endpoint ->
                    buildOpenApiMethod(endpoint)
                }
                """    "${escapeJson(path)}": {
${methods.joinToString(",\n")}
    }"""
            }
    }

    private fun buildOpenApiMethod(endpoint: ApiEndpoint): String {
        val methodName = endpoint.method.name.lowercase()

        val parameters = mutableListOf<String>()

        // Path parameters
        for (param in endpoint.pathParameters) {
            parameters.add(buildOpenApiParameter(param, "path"))
        }

        // Query parameters
        for (param in endpoint.queryParameters) {
            parameters.add(buildOpenApiParameter(param, "query"))
        }

        // Header parameters
        for (param in endpoint.headerParameters) {
            parameters.add(buildOpenApiParameter(param, "header"))
        }

        val responsesJson = endpoint.responses.joinToString(",\n") { response ->
            """        "${response.statusCode}": {
          "description": "${escapeJson(response.description ?: "")}"
        }"""
        }.ifEmpty {
            """        "200": { "description": "Successful response" }"""
        }

        return buildString {
            appendLine("""      "$methodName": {""")
            appendLine("""        "summary": "${endpoint.metadata.description ?: endpoint.pathTemplate}",""")
            appendLine("""        "operationId": "${endpoint.id}",""")

            // Tags
            if (endpoint.tags.isNotEmpty()) {
                appendLine("""        "tags": [${endpoint.tags.joinToString(", ") { "\"$it\"" }}],""")
            }

            // Parameters
            if (parameters.isNotEmpty()) {
                appendLine("""        "parameters": [""")
                append(parameters.joinToString(",\n"))
                appendLine()
                appendLine("        ],")
            }

            // Request Body
            if (endpoint.requestBody != null) {
                appendLine("""        "requestBody": {""")
                appendLine("""          "required": ${endpoint.requestBody.required},""")
                appendLine("""          "content": {""")
                appendLine("""            "${endpoint.requestBody.contentType}": {""")
                appendLine("""              "schema": { "type": "object" }""")
                appendLine("            }")
                appendLine("          }")
                appendLine("        },")
            }

            // Responses
            appendLine("""        "responses": {""")
            appendLine(responsesJson)
            appendLine("        }")
            append("      }")
        }
    }

    private fun buildOpenApiParameter(param: ApiParameter, location: String): String {
        val schemaType = when (param.type) {
            ParameterType.INTEGER -> "integer"
            ParameterType.NUMBER -> "number"
            ParameterType.BOOLEAN -> "boolean"
            ParameterType.ARRAY -> "array"
            else -> "string"
        }

        return """          {
            "name": "${param.name}",
            "in": "$location",
            "required": ${param.required},
            "schema": { "type": "$schemaType" }${param.example?.let { """, "example": "${escapeJson(it)}"""" } ?: ""}
          }"""
    }

    private fun buildSecuritySchemes(authPatterns: List<AuthPattern>): List<String> {
        return authPatterns.mapNotNull { pattern ->
            when (pattern) {
                is AuthPattern.BearerTokenPattern -> """
                    "bearerAuth": {
                      "type": "http",
                      "scheme": "bearer"
                    }"""

                is AuthPattern.ApiKeyPattern -> """
                    "apiKey": {
                      "type": "apiKey",
                      "name": "${pattern.parameterName}",
                      "in": "${pattern.location.name.lowercase()}"
                    }"""

                is AuthPattern.BasicAuthPattern -> """
                    "basicAuth": {
                      "type": "http",
                      "scheme": "basic"
                    }"""

                is AuthPattern.OAuth2Pattern -> """
                    "oauth2": {
                      "type": "oauth2",
                      "flows": {
                        "clientCredentials": {
                          "tokenUrl": "${pattern.tokenEndpoint}",
                          "scopes": {}
                        }
                      }
                    }"""

                else -> null
            }
        }
    }

    // =========================================================================
    // cURL Export
    // =========================================================================

    /**
     * Exportiert als cURL Commands.
     */
    fun toCurl(blueprint: ApiBlueprint): String {
        return buildString {
            appendLine("# API: ${blueprint.name}")
            appendLine("# Base URL: ${blueprint.baseUrl}")
            appendLine()

            for (endpoint in blueprint.endpoints) {
                appendLine("# ${endpoint.method.name} ${endpoint.pathTemplate}")
                appendLine(buildCurlCommand(blueprint.baseUrl, endpoint))
                appendLine()
            }
        }
    }

    /**
     * Exportiert einen einzelnen Endpoint als cURL Command.
     */
    fun toCurl(baseUrl: String, endpoint: ApiEndpoint): String {
        return buildCurlCommand(baseUrl, endpoint)
    }

    private fun buildCurlCommand(baseUrl: String, endpoint: ApiEndpoint): String {
        val parts = mutableListOf<String>()

        parts.add("curl")

        // Method
        if (endpoint.method != HttpMethod.GET) {
            parts.add("-X ${endpoint.method.name}")
        }

        // URL
        val url = buildCurlUrl(baseUrl, endpoint)
        parts.add("'$url'")

        // Headers
        for (header in endpoint.headerParameters) {
            val value = header.example ?: "<${header.name}>"
            parts.add("-H '${header.name}: $value'")
        }

        // Content-Type for body
        if (endpoint.requestBody != null) {
            parts.add("-H 'Content-Type: ${endpoint.requestBody.contentType}'")

            // Body
            val bodyExample = endpoint.requestBody.examples.firstOrNull()?.value ?: "{}"
            parts.add("-d '$bodyExample'")
        }

        // Format nicely
        return if (parts.size <= 3) {
            parts.joinToString(" ")
        } else {
            parts.joinToString(" \\\n  ")
        }
    }

    private fun buildCurlUrl(baseUrl: String, endpoint: ApiEndpoint): String {
        var path = endpoint.pathTemplate

        // Replace path parameters with examples
        for (param in endpoint.pathParameters) {
            val value = param.example ?: "<${param.name}>"
            path = path.replace("{${param.name}}", value)
        }

        // Add query parameters
        val queryParams = endpoint.queryParameters.mapNotNull { param ->
            val value = param.example ?: return@mapNotNull null
            "${param.name}=$value"
        }

        return if (queryParams.isEmpty()) {
            "$baseUrl$path"
        } else {
            "$baseUrl$path?${queryParams.joinToString("&")}"
        }
    }

    // =========================================================================
    // Postman Export
    // =========================================================================

    /**
     * Exportiert als Postman Collection v2.1.
     */
    fun toPostman(blueprint: ApiBlueprint): String {
        val items = blueprint.endpoints.map { endpoint ->
            buildPostmanItem(blueprint.baseUrl, endpoint)
        }

        return buildString {
            appendLine("{")
            appendLine("""  "info": {""")
            appendLine("""    "name": "${escapeJson(blueprint.name)}",""")
            appendLine("""    "description": "${escapeJson(blueprint.description ?: "")}",""")
            appendLine("""    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"""")
            appendLine("  },")
            appendLine("""  "item": [""")
            append(items.joinToString(",\n"))
            appendLine()
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun buildPostmanItem(baseUrl: String, endpoint: ApiEndpoint): String {
        val pathSegments = endpoint.pathTemplate.split("/").filter { it.isNotEmpty() }

        return buildString {
            appendLine("    {")
            appendLine("""      "name": "${endpoint.method.name} ${endpoint.pathTemplate}",""")
            appendLine("""      "request": {""")
            appendLine("""        "method": "${endpoint.method.name}",""")

            // Headers
            val headers = endpoint.headerParameters.map { param ->
                """{ "key": "${param.name}", "value": "${param.example ?: ""}" }"""
            }
            appendLine("""        "header": [${headers.joinToString(", ")}],""")

            // URL
            appendLine("""        "url": {""")
            appendLine("""          "raw": "$baseUrl${endpoint.pathTemplate}",""")
            appendLine("""          "host": ["${baseUrl.removePrefix("https://").removePrefix("http://")}"],""")
            appendLine("""          "path": [${pathSegments.joinToString(", ") { "\"$it\"" }}]""")
            appendLine("        }")

            appendLine("      }")
            append("    }")
        }
    }

    // =========================================================================
    // TypeScript Client Export
    // =========================================================================

    /**
     * Exportiert als TypeScript Client Code.
     */
    fun toTypeScript(blueprint: ApiBlueprint): String {
        return buildString {
            appendLine("/**")
            appendLine(" * ${blueprint.name} API Client")
            appendLine(" * Auto-generated by FishIT-Mapper")
            appendLine(" */")
            appendLine()
            appendLine("const BASE_URL = '${blueprint.baseUrl}';")
            appendLine()

            // Auth helper
            if (blueprint.authPatterns.isNotEmpty()) {
                appendLine("let authToken: string | null = null;")
                appendLine()
                appendLine("export function setAuthToken(token: string) {")
                appendLine("  authToken = token;")
                appendLine("}")
                appendLine()
            }

            // Helper function
            appendLine("async function apiCall<T>(")
            appendLine("  method: string,")
            appendLine("  path: string,")
            appendLine("  params?: Record<string, any>,")
            appendLine("  body?: any")
            appendLine("): Promise<T> {")
            appendLine("  const url = new URL(BASE_URL + path);")
            appendLine("  if (params) {")
            appendLine("    Object.entries(params).forEach(([key, value]) => {")
            appendLine("      if (value !== undefined) url.searchParams.append(key, String(value));")
            appendLine("    });")
            appendLine("  }")
            appendLine()
            appendLine("  const headers: Record<string, string> = {")
            appendLine("    'Content-Type': 'application/json',")
            if (blueprint.authPatterns.any { it is AuthPattern.BearerTokenPattern }) {
                appendLine("    ...(authToken ? { 'Authorization': `Bearer \${authToken}` } : {}),")
            }
            appendLine("  };")
            appendLine()
            appendLine("  const response = await fetch(url.toString(), {")
            appendLine("    method,")
            appendLine("    headers,")
            appendLine("    body: body ? JSON.stringify(body) : undefined,")
            appendLine("  });")
            appendLine()
            appendLine("  if (!response.ok) {")
            appendLine("    throw new Error(`HTTP \${response.status}: \${response.statusText}`);")
            appendLine("  }")
            appendLine()
            appendLine("  return response.json();")
            appendLine("}")
            appendLine()

            // Generate functions for each endpoint
            for (endpoint in blueprint.endpoints) {
                appendLine(buildTypeScriptFunction(endpoint))
                appendLine()
            }
        }
    }

    private fun buildTypeScriptFunction(endpoint: ApiEndpoint): String {
        val funcName = endpoint.id.replace(Regex("[^a-zA-Z0-9]"), "_")
        val allParams = endpoint.pathParameters + endpoint.queryParameters

        val paramsList = allParams.joinToString(", ") { param ->
            "${param.name}${if (param.required) "" else "?"}: ${tsType(param.type)}"
        }

        val hasBody = endpoint.requestBody != null
        val fullParams = if (hasBody) {
            if (paramsList.isEmpty()) "body: any" else "$paramsList, body: any"
        } else {
            paramsList
        }

        var path = endpoint.pathTemplate
        for (param in endpoint.pathParameters) {
            path = path.replace("{${param.name}}", "\${${param.name}}")
        }

        val queryParams = endpoint.queryParameters.map { it.name }
        val queryObj = if (queryParams.isEmpty()) "undefined" else "{ ${queryParams.joinToString(", ")} }"

        return buildString {
            appendLine("/**")
            appendLine(" * ${endpoint.method.name} ${endpoint.pathTemplate}")
            endpoint.metadata.description?.let { appendLine(" * $it") }
            appendLine(" */")
            appendLine("export async function $funcName($fullParams) {")
            append("  return apiCall('${endpoint.method.name}', `$path`, $queryObj")
            if (hasBody) append(", body")
            appendLine(");")
            append("}")
        }
    }

    private fun tsType(type: ParameterType): String {
        return when (type) {
            ParameterType.INTEGER, ParameterType.NUMBER -> "number"
            ParameterType.BOOLEAN -> "boolean"
            ParameterType.ARRAY -> "any[]"
            ParameterType.OBJECT -> "Record<string, any>"
            else -> "string"
        }
    }

    // =========================================================================
    // Kotlin Client Export
    // =========================================================================

    /**
     * Exportiert als Kotlin Client Code.
     */
    fun toKotlin(blueprint: ApiBlueprint, packageName: String = "api"): String {
        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import kotlinx.serialization.Serializable")
            appendLine("import io.ktor.client.*")
            appendLine("import io.ktor.client.call.*")
            appendLine("import io.ktor.client.request.*")
            appendLine("import io.ktor.http.*")
            appendLine()
            appendLine("/**")
            appendLine(" * ${blueprint.name} API Client")
            appendLine(" * Auto-generated by FishIT-Mapper")
            appendLine(" */")
            appendLine("class ${blueprint.name.replace(Regex("[^a-zA-Z0-9]"), "")}Client(")
            appendLine("    private val client: HttpClient,")
            appendLine("    private val baseUrl: String = \"${blueprint.baseUrl}\"")
            appendLine(") {")
            appendLine()

            if (blueprint.authPatterns.any { it is AuthPattern.BearerTokenPattern }) {
                appendLine("    var authToken: String? = null")
                appendLine()
            }

            for (endpoint in blueprint.endpoints) {
                appendLine(buildKotlinFunction(endpoint))
                appendLine()
            }

            appendLine("}")
        }
    }

    private fun buildKotlinFunction(endpoint: ApiEndpoint): String {
        val funcName = endpoint.id
            .split("_")
            .mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.uppercase() } }
            .joinToString("")

        val params = (endpoint.pathParameters + endpoint.queryParameters).map { param ->
            "${param.name}: ${kotlinType(param.type)}${if (param.required) "" else "? = null"}"
        }

        val hasBody = endpoint.requestBody != null
        val allParams = if (hasBody) params + listOf("body: Any") else params

        var path = endpoint.pathTemplate
        for (param in endpoint.pathParameters) {
            path = path.replace("{${param.name}}", "\$${param.name}")
        }

        return buildString {
            appendLine("    /**")
            appendLine("     * ${endpoint.method.name} ${endpoint.pathTemplate}")
            appendLine("     */")
            appendLine("    suspend fun $funcName(${allParams.joinToString(", ")}): HttpResponse {")
            appendLine("        return client.request(\"\$baseUrl$path\") {")
            appendLine("            method = HttpMethod.${endpoint.method.name.uppercase()}")

            if (endpoint.queryParameters.isNotEmpty()) {
                for (param in endpoint.queryParameters) {
                    if (param.required) {
                        appendLine("            parameter(\"${param.name}\", ${param.name})")
                    } else {
                        appendLine("            ${param.name}?.let { parameter(\"${param.name}\", it) }")
                    }
                }
            }

            if (hasBody) {
                appendLine("            contentType(ContentType.Application.Json)")
                appendLine("            setBody(body)")
            }

            appendLine("        }")
            append("    }")
        }
    }

    private fun kotlinType(type: ParameterType): String {
        return when (type) {
            ParameterType.INTEGER -> "Int"
            ParameterType.NUMBER -> "Double"
            ParameterType.BOOLEAN -> "Boolean"
            ParameterType.ARRAY -> "List<Any>"
            ParameterType.OBJECT -> "Map<String, Any>"
            else -> "String"
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
