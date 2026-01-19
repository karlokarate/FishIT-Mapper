package dev.fishit.mapper.android.capture

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/**
 * Speichert Capture-Sessions **direkt als HAR-Dateien**.
 *
 * ## Warum HAR?
 * - **Standard-Format** (W3C Web Performance Working Group)
 * - **Direkt nutzbar** mit Chrome DevTools, Postman, Insomnia
 * - **GitHub Copilot** versteht HAR nativ
 * - **Kein Export nötig** - die Datei IST bereits der Export
 *
 * ## Speicherstruktur
 * ```
 * fishit/
 * └── har/
 *     ├── index.json                    # Session-Index mit Metadaten
 *     ├── 2024-01-15_amazon-api.har     # HAR-Datei (Standard!)
 *     ├── 2024-01-16_github-login.har
 *     └── ...
 * ```
 *
 * ## Verwendung
 * ```kotlin
 * val store = HarSessionStore(context)
 *
 * // Session speichern → erzeugt .har Datei
 * store.saveSession(session)
 *
 * // HAR-Datei direkt teilen
 * val harFile = store.getHarFile(sessionId)
 * shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(harFile))
 *
 * // Für Copilot/DevTools: Datei ist sofort nutzbar!
 * ```
 */
class HarSessionStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val rootDir: File
        get() = File(context.filesDir, "fishit/har")

    private val indexFile: File
        get() = File(rootDir, "index.json")

    // ==================== Public API ====================

    /**
     * Speichert eine Session als HAR-Datei.
     *
     * @return Pfad zur erzeugten HAR-Datei
     */
    suspend fun saveSession(session: CaptureSessionManager.CaptureSession): File = withContext(Dispatchers.IO) {
        rootDir.mkdirs()

        // Dateiname: datum_name.har
        val safeName = session.name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .take(50)
        val date = session.startedAt.toString().take(10) // YYYY-MM-DD
        val fileName = "${date}_${safeName}.har"

        val harFile = File(rootDir, fileName)

        // HAR erzeugen
        val harContent = buildHar(session)
        harFile.writeText(harContent)

        // Index aktualisieren
        updateIndex(session, fileName)

        harFile
    }

    /**
     * Listet alle Sessions.
     */
    suspend fun listSessions(): List<HarSessionSummary> = withContext(Dispatchers.IO) {
        loadIndex().sessions
    }

    /**
     * Lädt eine Session aus der HAR-Datei.
     */
    suspend fun loadSession(sessionId: String): CaptureSessionManager.CaptureSession? = withContext(Dispatchers.IO) {
        val index = loadIndex()
        val summary = index.sessions.find { it.id == sessionId } ?: return@withContext null
        val harFile = File(rootDir, summary.fileName)

        if (!harFile.exists()) return@withContext null

        parseHar(harFile, summary)
    }

    /**
     * Gibt die HAR-Datei direkt zurück (zum Teilen).
     */
    suspend fun getHarFile(sessionId: String): File? = withContext(Dispatchers.IO) {
        val index = loadIndex()
        val summary = index.sessions.find { it.id == sessionId } ?: return@withContext null
        val file = File(rootDir, summary.fileName)
        if (file.exists()) file else null
    }

    /**
     * Gibt den HAR-Inhalt als String zurück.
     */
    suspend fun getHarContent(sessionId: String): String? = withContext(Dispatchers.IO) {
        getHarFile(sessionId)?.readText()
    }

    /**
     * Löscht eine Session.
     */
    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        val index = loadIndex()
        val summary = index.sessions.find { it.id == sessionId }

        // HAR-Datei löschen
        summary?.let { File(rootDir, it.fileName).delete() }

        // Zusatz-Datei (Actions) löschen
        summary?.let { File(rootDir, it.fileName.replace(".har", ".actions.json")).delete() }

        // Index aktualisieren
        saveIndex(index.copy(sessions = index.sessions.filter { it.id != sessionId }))
    }

    /**
     * Löscht alle Sessions.
     */
    suspend fun deleteAllSessions() = withContext(Dispatchers.IO) {
        rootDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Gibt den Speicherplatz formatiert zurück.
     */
    suspend fun getStorageSizeFormatted(): String = withContext(Dispatchers.IO) {
        val bytes = rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / 1024 / 1024} MB"
        }
    }

    // ==================== HAR Builder ====================

    private fun buildHar(session: CaptureSessionManager.CaptureSession): String {
        val entries = session.exchanges.map { exchange ->
            buildEntry(exchange, session)
        }

        val har = buildJsonObject {
            put("log", buildJsonObject {
                put("version", "1.2")

                put("creator", buildJsonObject {
                    put("name", "FishIT-Mapper")
                    put("version", "1.0.0")
                    put("comment", "WebView Traffic Capture")
                })

                // Browser-Info (FishIT-Mapper WebView)
                put("browser", buildJsonObject {
                    put("name", "FishIT-Mapper")
                    put("version", "1.0.0")
                    put("comment", "Android ${android.os.Build.VERSION.RELEASE} WebView")
                })

                // Pages (für Navigation)
                put("pages", buildPages(session))

                // Entries (die HTTP-Exchanges)
                put("entries", JsonArray(entries))

                // Custom: FishIT Metadaten
                put("_fishit", buildJsonObject {
                    put("sessionId", session.id)
                    put("sessionName", session.name)
                    put("targetUrl", session.targetUrl ?: "")
                    put("startedAt", session.startedAt.toString())
                    put("stoppedAt", session.stoppedAt?.toString() ?: "")
                    put("notes", session.notes ?: "")

                    // User Actions (HAR-Erweiterung)
                    put("userActions", buildActions(session.userActions))

                    // Page Events
                    put("pageEvents", buildPageEvents(session.pageEvents))

                    // Cookie Events - vollständiges Cookie-Tracking
                    put("cookieEvents", buildCookieEvents(session.cookieEvents))
                })
            })
        }

        return json.encodeToString(har)
    }

    private fun buildEntry(
        exchange: TrafficInterceptWebView.CapturedExchange,
        session: CaptureSessionManager.CaptureSession
    ): JsonObject {
        val time = exchange.completedAt?.let {
            it.toEpochMilliseconds() - exchange.startedAt.toEpochMilliseconds()
        } ?: 0

        return buildJsonObject {
            put("startedDateTime", exchange.startedAt.toString())
            put("time", time)

            // Request
            put("request", buildRequest(exchange))

            // Response
            put("response", buildResponse(exchange))

            // Cache
            put("cache", buildJsonObject { })

            // Timings
            put("timings", buildJsonObject {
                put("blocked", -1)
                put("dns", -1)
                put("connect", -1)
                put("send", 0)
                put("wait", time)
                put("receive", 0)
                put("ssl", -1)
            })

            // Custom: Korrelierte Actions
            val correlatedActions = session.userActions.filter { action ->
                val actionTime = action.timestamp.toEpochMilliseconds()
                val exchangeTime = exchange.startedAt.toEpochMilliseconds()
                exchangeTime >= actionTime && exchangeTime <= actionTime + 2000
            }
            if (correlatedActions.isNotEmpty()) {
                put("_fishit_actions", JsonArray(correlatedActions.map { action ->
                    buildJsonObject {
                        put("type", action.type.name)
                        put("target", action.target)
                        put("value", action.value ?: "")
                    }
                }))
            }
        }
    }

    private fun buildRequest(exchange: TrafficInterceptWebView.CapturedExchange): JsonObject {
        val url = exchange.url
        val queryStart = url.indexOf('?')
        val queryString = if (queryStart >= 0) url.substring(queryStart + 1) else ""

        return buildJsonObject {
            put("method", exchange.method)
            put("url", exchange.url)
            put("httpVersion", "HTTP/1.1")

            // Headers
            put("headers", JsonArray(exchange.requestHeaders.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Query String
            put("queryString", JsonArray(parseQueryParams(queryString).map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Cookies
            val cookies = extractCookies(exchange.requestHeaders)
            put("cookies", JsonArray(cookies.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Body
            if (exchange.requestBody != null) {
                put("bodySize", exchange.requestBody.length)
                put("postData", buildJsonObject {
                    put("mimeType", exchange.requestHeaders["Content-Type"] ?: "application/octet-stream")
                    put("text", exchange.requestBody)
                })
            } else {
                put("bodySize", 0)
            }

            put("headersSize", -1)
        }
    }

    private fun buildResponse(exchange: TrafficInterceptWebView.CapturedExchange): JsonObject {
        return buildJsonObject {
            put("status", exchange.responseStatus ?: 0)
            put("statusText", getStatusText(exchange.responseStatus ?: 0))
            put("httpVersion", "HTTP/1.1")

            // Headers
            val headers = exchange.responseHeaders ?: emptyMap()
            put("headers", JsonArray(headers.map { (name, value) ->
                buildJsonObject {
                    put("name", name)
                    put("value", value)
                }
            }))

            // Cookies
            put("cookies", JsonArray(emptyList()))

            // Content
            val body = exchange.responseBody
            put("content", buildJsonObject {
                put("size", body?.length ?: 0)
                put("mimeType", headers["Content-Type"] ?: "application/octet-stream")
                if (body != null) {
                    put("text", body)
                }
            })

            put("redirectURL", "")
            put("headersSize", -1)
            put("bodySize", body?.length ?: 0)
        }
    }

    private fun buildPages(session: CaptureSessionManager.CaptureSession): JsonArray {
        val pages = session.pageEvents
            .filter { it.type == TrafficInterceptWebView.PageEventType.FINISHED }
            .mapIndexed { index, event ->
                buildJsonObject {
                    put("startedDateTime", event.timestamp.toString())
                    put("id", "page_$index")
                    put("title", event.title ?: event.url)
                    put("pageTimings", buildJsonObject {
                        put("onContentLoad", -1)
                        put("onLoad", -1)
                    })
                }
            }
        return JsonArray(pages)
    }

    private fun buildActions(actions: List<TrafficInterceptWebView.UserAction>): JsonArray {
        return JsonArray(actions.map { action ->
            buildJsonObject {
                put("id", action.id)
                put("type", action.type.name)
                put("target", action.target)
                put("value", action.value ?: "")
                put("timestamp", action.timestamp.toString())
                put("pageUrl", action.pageUrl ?: "")
            }
        })
    }

    private fun buildPageEvents(events: List<TrafficInterceptWebView.PageEvent>): JsonArray {
        return JsonArray(events.map { event ->
            buildJsonObject {
                put("url", event.url)
                put("title", event.title ?: "")
                put("type", event.type.name)
                put("timestamp", event.timestamp.toString())
            }
        })
    }

    /**
     * Baut JSON-Array für Cookie-Events.
     * Ermöglicht vollständige Nachverfolgung wann/wo/wie Cookies gesetzt werden.
     */
    private fun buildCookieEvents(events: List<TrafficInterceptWebView.CookieEvent>): JsonArray {
        return JsonArray(events.map { event ->
            buildJsonObject {
                put("name", event.name)
                put("value", event.value ?: "")
                put("domain", event.domain ?: "")
                put("path", event.path ?: "/")
                put("expires", event.expires ?: "")
                put("secure", event.secure)
                put("httpOnly", event.httpOnly)
                put("sameSite", event.sameSite ?: "")
                put("eventType", event.type.name)
                put("sourceType", event.sourceType.name)
                put("sourceUrl", event.sourceUrl)
                put("relatedRequestId", event.relatedRequestId ?: "")
                put("timestamp", event.timestamp.toString())
            }
        })
    }

    // ==================== HAR Parser ====================

    private fun parseHar(file: File, summary: HarSessionSummary): CaptureSessionManager.CaptureSession {
        val harJson = json.parseToJsonElement(file.readText()).jsonObject
        val log = harJson["log"]?.jsonObject ?: return createEmptySession(summary)

        // FishIT Metadaten
        val fishit = log["_fishit"]?.jsonObject

        // Entries parsen
        val entries = log["entries"]?.jsonArray ?: JsonArray(emptyList())
        val exchanges = entries.mapNotNull { parseEntry(it.jsonObject) }

        // Actions parsen
        val actions = fishit?.get("userActions")?.jsonArray?.mapNotNull { parseAction(it.jsonObject) }
            ?: emptyList()

        // Page Events parsen
        val pageEvents = fishit?.get("pageEvents")?.jsonArray?.mapNotNull { parsePageEvent(it.jsonObject) }
            ?: emptyList()

        // Cookie Events parsen
        val cookieEvents = fishit?.get("cookieEvents")?.jsonArray?.mapNotNull { parseCookieEvent(it.jsonObject) }
            ?: emptyList()

        return CaptureSessionManager.CaptureSession(
            id = summary.id,
            name = summary.name,
            targetUrl = summary.targetUrl,
            startedAt = summary.startedAt,
            stoppedAt = summary.stoppedAt,
            exchanges = exchanges,
            userActions = actions,
            pageEvents = pageEvents,
            cookieEvents = cookieEvents,
            notes = fishit?.get("notes")?.jsonPrimitive?.contentOrNull
        )
    }

    private fun parseEntry(entry: JsonObject): TrafficInterceptWebView.CapturedExchange? {
        return try {
            val request = entry["request"]?.jsonObject ?: return null
            val response = entry["response"]?.jsonObject

            TrafficInterceptWebView.CapturedExchange(
                id = UUID.randomUUID().toString(),
                method = request["method"]?.jsonPrimitive?.content ?: "GET",
                url = request["url"]?.jsonPrimitive?.content ?: "",
                requestHeaders = request["headers"]?.jsonArray?.associate {
                    val h = it.jsonObject
                    (h["name"]?.jsonPrimitive?.content ?: "") to (h["value"]?.jsonPrimitive?.content ?: "")
                } ?: emptyMap(),
                requestBody = request["postData"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
                responseStatus = response?.get("status")?.jsonPrimitive?.intOrNull,
                responseHeaders = response?.get("headers")?.jsonArray?.associate {
                    val h = it.jsonObject
                    (h["name"]?.jsonPrimitive?.content ?: "") to (h["value"]?.jsonPrimitive?.content ?: "")
                },
                responseBody = response?.get("content")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull,
                startedAt = Instant.parse(entry["startedDateTime"]?.jsonPrimitive?.content ?: Clock.System.now().toString()),
                completedAt = null
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAction(action: JsonObject): TrafficInterceptWebView.UserAction? {
        return try {
            TrafficInterceptWebView.UserAction(
                id = action["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                type = TrafficInterceptWebView.ActionType.valueOf(action["type"]?.jsonPrimitive?.content ?: "CLICK"),
                target = action["target"]?.jsonPrimitive?.content ?: "",
                value = action["value"]?.jsonPrimitive?.contentOrNull,
                timestamp = Instant.parse(action["timestamp"]?.jsonPrimitive?.content ?: Clock.System.now().toString()),
                pageUrl = action["pageUrl"]?.jsonPrimitive?.contentOrNull
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePageEvent(event: JsonObject): TrafficInterceptWebView.PageEvent? {
        return try {
            TrafficInterceptWebView.PageEvent(
                url = event["url"]?.jsonPrimitive?.content ?: "",
                title = event["title"]?.jsonPrimitive?.contentOrNull,
                timestamp = Instant.parse(event["timestamp"]?.jsonPrimitive?.content ?: Clock.System.now().toString()),
                type = TrafficInterceptWebView.PageEventType.valueOf(event["type"]?.jsonPrimitive?.content ?: "PAGE_LOAD")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parst ein Cookie-Event aus JSON.
     */
    private fun parseCookieEvent(event: JsonObject): TrafficInterceptWebView.CookieEvent? {
        return try {
            TrafficInterceptWebView.CookieEvent(
                name = event["name"]?.jsonPrimitive?.content ?: "",
                value = event["value"]?.jsonPrimitive?.contentOrNull,
                domain = event["domain"]?.jsonPrimitive?.contentOrNull,
                path = event["path"]?.jsonPrimitive?.contentOrNull,
                expires = event["expires"]?.jsonPrimitive?.contentOrNull,
                secure = event["secure"]?.jsonPrimitive?.booleanOrNull ?: false,
                httpOnly = event["httpOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                sameSite = event["sameSite"]?.jsonPrimitive?.contentOrNull,
                type = TrafficInterceptWebView.CookieEventType.valueOf(
                    event["eventType"]?.jsonPrimitive?.content ?: "SET"
                ),
                sourceType = TrafficInterceptWebView.CookieSourceType.valueOf(
                    event["sourceType"]?.jsonPrimitive?.content ?: "JAVASCRIPT"
                ),
                sourceUrl = event["sourceUrl"]?.jsonPrimitive?.content ?: "",
                relatedRequestId = event["relatedRequestId"]?.jsonPrimitive?.contentOrNull,
                timestamp = Instant.parse(event["timestamp"]?.jsonPrimitive?.content ?: Clock.System.now().toString())
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun createEmptySession(summary: HarSessionSummary) = CaptureSessionManager.CaptureSession(
        id = summary.id,
        name = summary.name,
        targetUrl = summary.targetUrl,
        startedAt = summary.startedAt,
        stoppedAt = summary.stoppedAt,
        exchanges = emptyList(),
        userActions = emptyList(),
        pageEvents = emptyList(),
        cookieEvents = emptyList()
    )

    // ==================== Index Management ====================

    private fun loadIndex(): HarIndex {
        if (!indexFile.exists()) return HarIndex()
        return try {
            json.decodeFromString(indexFile.readText())
        } catch (e: Exception) {
            HarIndex()
        }
    }

    private fun saveIndex(index: HarIndex) {
        rootDir.mkdirs()
        indexFile.writeText(json.encodeToString(index))
    }

    private fun updateIndex(session: CaptureSessionManager.CaptureSession, fileName: String) {
        val current = loadIndex()
        val summary = HarSessionSummary(
            id = session.id,
            name = session.name,
            fileName = fileName,
            targetUrl = session.targetUrl,
            startedAt = session.startedAt,
            stoppedAt = session.stoppedAt,
            exchangeCount = session.exchanges.size,
            actionCount = session.userActions.size
        )

        val existing = current.sessions.indexOfFirst { it.id == session.id }
        val updated = if (existing >= 0) {
            current.sessions.toMutableList().apply { this[existing] = summary }
        } else {
            current.sessions + summary
        }

        saveIndex(current.copy(sessions = updated))
    }

    // ==================== Helpers ====================

    private fun parseQueryParams(query: String): List<Pair<String, String>> {
        if (query.isEmpty()) return emptyList()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }

    private fun extractCookies(headers: Map<String, String>): List<Pair<String, String>> {
        val cookieHeader = headers["Cookie"] ?: headers["cookie"] ?: return emptyList()
        return cookieHeader.split(";").mapNotNull { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
    }

    private fun getStatusText(status: Int): String = when (status) {
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
        else -> ""
    }

    // ==================== Data Classes ====================

    @Serializable
    data class HarIndex(
        val sessions: List<HarSessionSummary> = emptyList()
    )

    @Serializable
    data class HarSessionSummary(
        val id: String,
        val name: String,
        val fileName: String,
        val targetUrl: String?,
        val startedAt: Instant,
        val stoppedAt: Instant?,
        val exchangeCount: Int,
        val actionCount: Int
    )
}
