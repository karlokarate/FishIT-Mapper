package dev.fishit.mapper.engine.export

import dev.fishit.mapper.engine.api.*
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Exportiert Traffic-Daten ins HAR-Format (HTTP Archive).
 *
 * HAR ist ein Standard-Format das von vielen Tools verstanden wird:
 * - Chrome DevTools
 * - Postman
 * - Charles Proxy
 * - GitHub Copilot (versteht HAR nativ!)
 *
 * ## Verwendung
 * ```kotlin
 * val exporter = HarExporter()
 * val harJson = exporter.export(exchanges)
 * // Speichern als traffic.har
 * ```
 *
 * ## Warum HAR?
 * - Standard-Format (W3C Web Performance Working Group)
 * - Copilot kann es direkt analysieren
 * - Import in Postman, Insomnia, etc. möglich
 */
class HarExporter {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Exportiert Exchanges ins HAR-Format.
     */
    fun export(
        exchanges: List<CapturedExchange>,
        creatorName: String = "FishIT-Mapper",
        creatorVersion: String = "1.0.0"
    ): String {
        val entries = exchanges.map { exchange ->
            buildJsonObject {
                // Timing
                put("startedDateTime", exchange.startedAt.toString())
                put("time", calculateTime(exchange))

                // Request
                put("request", buildRequest(exchange.request))

                // Response
                put("response", buildResponse(exchange.response))

                // Cache (leer)
                put("cache", buildJsonObject { })

                // Timings (vereinfacht)
                put("timings", buildJsonObject {
                    put("send", 0)
                    put("wait", calculateTime(exchange).toInt())
                    put("receive", 0)
                })
            }
        }

        val har = buildJsonObject {
            put("log", buildJsonObject {
                put("version", "1.2")

                put("creator", buildJsonObject {
                    put("name", creatorName)
                    put("version", creatorVersion)
                })

                put("entries", JsonArray(entries))

                // Browser info (optional)
                put("browser", buildJsonObject {
                    put("name", "HttpCanary Import")
                    put("version", "1.0")
                })
            })
        }

        return json.encodeToString(har)
    }

    private fun buildRequest(request: EngineRequest): JsonObject {
        // Parse URL für Query-Params
        val url = request.url
        val queryStart = url.indexOf('?')
        val queryString = if (queryStart >= 0) url.substring(queryStart + 1) else ""
        val queryParams = parseQueryParams(queryString)

        return buildJsonObject {
            put("method", request.method)
            put("url", request.url)
            put("httpVersion", "HTTP/1.1")

            // Headers
            put("headers", JsonArray(request.headers.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Query Params
            put("queryString", JsonArray(queryParams.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Cookies (aus Header extrahieren)
            val cookies = extractCookies(request.headers)
            put("cookies", JsonArray(cookies.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Body
            if (request.body != null) {
                put("bodySize", request.body!!.length)
                put("postData", buildJsonObject {
                    put("mimeType", request.contentType ?: request.headers["Content-Type"] ?: "application/octet-stream")
                    put("text", request.body)
                })
            } else {
                put("bodySize", 0)
            }

            put("headersSize", -1) // Unbekannt
        }
    }

    private fun buildResponse(response: EngineResponse?): JsonObject {
        if (response == null) {
            return buildJsonObject {
                put("status", 0)
                put("statusText", "No Response")
                put("httpVersion", "HTTP/1.1")
                put("headers", JsonArray(emptyList()))
                put("cookies", JsonArray(emptyList()))
                put("content", buildJsonObject {
                    put("size", 0)
                    put("mimeType", "")
                })
                put("redirectURL", "")
                put("headersSize", -1)
                put("bodySize", 0)
            }
        }

        return buildJsonObject {
            put("status", response.status)
            put("statusText", response.statusMessage ?: getStatusText(response.status))
            put("httpVersion", "HTTP/1.1")

            // Headers
            put("headers", JsonArray(response.headers.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Cookies (aus Set-Cookie Header)
            val cookies = extractSetCookies(response.headers)
            put("cookies", JsonArray(cookies.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Content
            put("content", buildJsonObject {
                put("size", response.body?.length ?: 0)
                put("mimeType", response.contentType ?: response.headers["Content-Type"] ?: "")
                if (response.body != null) {
                    put("text", response.body)
                }
            })

            put("redirectURL", response.redirectLocation ?: "")
            put("headersSize", -1)
            put("bodySize", response.body?.length ?: 0)
        }
    }

    private fun parseQueryParams(queryString: String): List<Pair<String, String>> {
        if (queryString.isEmpty()) return emptyList()

        return queryString.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                parts[0] to parts[1]
            } else if (parts.isNotEmpty()) {
                parts[0] to ""
            } else null
        }
    }

    private fun extractCookies(headers: Map<String, String>): List<Pair<String, String>> {
        val cookieHeader = headers["Cookie"] ?: headers["cookie"] ?: return emptyList()

        return cookieHeader.split(";").mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) {
                parts[0].trim() to parts[1].trim()
            } else null
        }
    }

    private fun extractSetCookies(headers: Map<String, String>): List<Pair<String, String>> {
        val setCookies = mutableListOf<Pair<String, String>>()

        headers.forEach { (name, value) ->
            if (name.equals("Set-Cookie", ignoreCase = true)) {
                val cookiePart = value.split(";").firstOrNull() ?: return@forEach
                val parts = cookiePart.split("=", limit = 2)
                if (parts.size == 2) {
                    setCookies.add(parts[0].trim() to parts[1].trim())
                }
            }
        }

        return setCookies
    }

    private fun calculateTime(exchange: CapturedExchange): Long {
        // Vereinfacht: Nehme einen Standardwert wenn keine Endzeit bekannt
        return 100 // ms
    }

    private fun getStatusText(status: Int): String {
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
            500 -> "Internal Server Error"
            else -> "HTTP $status"
        }
    }
}
