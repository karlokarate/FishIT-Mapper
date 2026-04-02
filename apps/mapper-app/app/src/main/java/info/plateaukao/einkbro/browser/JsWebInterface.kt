package info.plateaukao.einkbro.browser

import android.util.Log
import android.webkit.JavascriptInterface
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import info.plateaukao.einkbro.database.BookmarkManager
import info.plateaukao.einkbro.database.TranslationCache
import info.plateaukao.einkbro.preference.ChatGPTActionInfo
import info.plateaukao.einkbro.preference.ConfigManager
import info.plateaukao.einkbro.preference.GptActionType
import info.plateaukao.einkbro.service.ChatMessage
import info.plateaukao.einkbro.service.ChatRole
import info.plateaukao.einkbro.service.OpenAiRepository
import info.plateaukao.einkbro.service.TranslateRepository
import info.plateaukao.einkbro.view.EBWebView
import info.plateaukao.einkbro.viewmodel.TRANSLATE_API
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

private const val CACHE_EXPIRATION_DAYS = 5
private const val CACHE_TEXT_LENGTH_LIMIT = 15

class JsWebInterface(private val webView: EBWebView) :
    KoinComponent {
    private val translateRepository: TranslateRepository = TranslateRepository()
    private val openAiRepository: OpenAiRepository = OpenAiRepository()
    private val configManager: ConfigManager by inject()
    private val bookmarkManager: BookmarkManager by inject()
    private val jsRequestMap = ConcurrentHashMap<String, String>()

    private fun escapeForJs(text: String): String =
        text.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    // to control the translation request threshold
    private val semaphoreForTranslate = Semaphore(4)

    // deepL has a limit of 5 requests per second
    private val semaphoreForDeepL = Semaphore(1)

    @OptIn(DelicateCoroutinesApi::class)
    @JavascriptInterface
    fun getTranslation(originalText: String, elementId: String, callback: String) {
        GlobalScope.launch(Dispatchers.IO) {
            val currentLanguage = configManager.translationLanguage.value
            val currentTime = System.currentTimeMillis()

            // Check cache for short strings
            if (originalText.length < CACHE_TEXT_LENGTH_LIMIT) {
                val cachedEntry = bookmarkManager.getTranslationCache(originalText, currentLanguage)
                if (cachedEntry != null) {
                    val daysDiff = TimeUnit.MILLISECONDS.toDays(currentTime - cachedEntry.timestamp)
                    if (daysDiff < CACHE_EXPIRATION_DAYS) {
                        Log.d("JsWebInterface", "Cache hit for: $originalText")
                        withContext(Dispatchers.Main) {
                            if (webView.isAttachedToWindow) {
                                webView.evaluateJavascript(
                                    "$callback('$elementId', '${escapeForJs(cachedEntry.translatedText)}')",
                                    null
                                )
                            }
                        }
                        return@launch
                    } else {
                        // Optionally delete old cache entry, though we have a bulk delete method
                    }
                }
            }

            val semaphore = getSemaphoreForApi(webView.translateApi)
            semaphore.acquire()

            Log.d("JsWebInterface", "getTranslation: $originalText")
            val translatedString = performTranslation(originalText, webView.translateApi)

            if (translatedString.isNotEmpty() && originalText.length < CACHE_TEXT_LENGTH_LIMIT) {
                bookmarkManager.insertTranslationCache(
                    TranslationCache(
                        originalText = originalText,
                        targetLanguage = currentLanguage,
                        translatedText = translatedString,
                        timestamp = currentTime
                    )
                )
            }

            withContext(Dispatchers.Main) {
                if (webView.isAttachedToWindow && translatedString.isNotEmpty()) {
                    webView.evaluateJavascript(
                        "$callback('$elementId', '${escapeForJs(translatedString)}')",
                        null
                    )
                }
            }

            delayIfNeeded(webView.translateApi)
            semaphore.release()
        }
    }

    private fun getSemaphoreForApi(api: TRANSLATE_API): Semaphore {
        return if (api == TRANSLATE_API.DEEPL || api == TRANSLATE_API.GEMINI) {
            semaphoreForDeepL
        } else {
            semaphoreForTranslate
        }
    }

    private suspend fun performTranslation(originalText: String, api: TRANSLATE_API): String {
        return when (api) {
            TRANSLATE_API.PAPAGO -> translateRepository.pTranslate(
                originalText,
                configManager.translationLanguage.value
            ).orEmpty()

            TRANSLATE_API.GOOGLE -> translateRepository.gTranslateWithApi(
                originalText,
                configManager.translationLanguage.value
            ).orEmpty()

            TRANSLATE_API.OPENAI -> translateWithOpenAi(originalText)

            TRANSLATE_API.GEMINI -> translateWithGemini(originalText)

            TRANSLATE_API.DEEPL -> translateRepository.deepLTranslate(
                originalText,
                configManager.translationLanguage
            ).orEmpty()

            else -> ""
        }
    }

    private suspend fun translateWithOpenAi(originalText: String): String {
        val chatGptActionInfo = ChatGPTActionInfo(
            userMessage = "translate following content to ${configManager.translationLanguage.value}; no other extra explanation:\n",
            actionType = GptActionType.OpenAi,
            model = configManager.gptModel,
        )
        val messages = listOf((chatGptActionInfo.userMessage + originalText).toUserMessage())
        val completion = openAiRepository.chatCompletion(messages, chatGptActionInfo)
        return completion?.choices?.firstOrNull { it.message.role == ChatRole.Assistant }?.message?.content
            ?: "Something went wrong."
    }

    private suspend fun translateWithGemini(originalText: String): String {
        val chatGptActionInfo = ChatGPTActionInfo(
            userMessage = "translate following content to ${configManager.translationLanguage.value}; no other extra explanation:\n",
            actionType = GptActionType.Gemini,
            model = configManager.geminiModel,
        )
        val messages = listOf((chatGptActionInfo.userMessage + originalText).toUserMessage())
        return openAiRepository.queryGemini(messages, chatGptActionInfo)
    }

    private suspend fun delayIfNeeded(api: TRANSLATE_API) {
        if (api == TRANSLATE_API.DEEPL || api == TRANSLATE_API.GEMINI) {
            delay(1500)
        }
    }
    @JavascriptInterface
    fun getAnchorPosition(left: Float, top: Float, right: Float, bottom: Float) {
        Log.d("touch", "rect: $left, $top, $right, $bottom")
        webView.browserController?.updateSelectionRect(left, top, right, bottom)
    }

    @JavascriptInterface
    fun onInnerScrollChanged(isAtTop: Boolean, scrollTop: Int, scrollHeight: Int, clientHeight: Int) {
        webView.isInnerScrollAtTop = isAtTop
        webView.innerScrollTop = scrollTop
        webView.innerScrollHeight = scrollHeight
        webView.innerClientHeight = clientHeight
        webView.post { webView.updatePageInfo() }
    }

    @JavascriptInterface
    fun runtimeToolkitNetworkEvent(rawJson: String?) {
        if (rawJson.isNullOrBlank()) return
        val context = webView.context.applicationContext
        runCatching {
            val event = JSONObject(rawJson)
            val stage = event.optString("stage").ifBlank { "response" }.lowercase()
            val method = event.optString("method").ifBlank { "GET" }.uppercase()
            val url = event.optString("url").ifBlank { event.optString("responseUrl") }
            if (url.isBlank()) return@runCatching
            RuntimeToolkitTelemetry.logCorrelationEvent(
                context = context,
                operation = "js_bridge_network_event",
                payload = mapOf(
                    "stage" to stage,
                    "url" to url,
                    "source" to event.optString("source").ifBlank { "unknown" },
                ),
            )

            val jsRequestId = event.optString("requestId").ifBlank { null }
            val requestHeaders = jsonToStringMap(event.optJSONObject("requestHeaders"))
            val responseHeaders = jsonToStringMap(event.optJSONObject("headers"))
            val bodyPreview = event.optString("bodyPreview").ifBlank { null }?.take(MAX_BRIDGE_BODY_PREVIEW_CHARS)
            val bodyPreviewTruncated = event.optBoolean("bodyPreviewTruncated", false)
            val bodyOriginalLength = optionalInt(event, "bodyOriginalLength")
            val mimeType = event.optString("mimeType").ifBlank { null }
            val reason = event.optString("reason").ifBlank { null }
            val statusCode = optionalInt(event, "status")

            if (stage == "request") {
                val existingRequestId = RuntimeToolkitTelemetry.resolveRecentRequestId(url = url, method = method)
                val requestId = existingRequestId ?: RuntimeToolkitTelemetry.logNetworkRequest(
                    context = context,
                    source = "webview_js_bridge",
                    url = url,
                    method = method,
                    headers = requestHeaders,
                    payload = mapOf(
                        "bridge_stage" to stage,
                        "bridge_request_id" to jsRequestId,
                        "bridge_observed" to true,
                    ),
                ).requestId

                if (!jsRequestId.isNullOrBlank()) {
                    jsRequestMap[jsRequestId] = requestId
                }
                requestHeaders.forEach { (headerKey, _) ->
                    if (shouldTrackProvenanceHeader(headerKey)) {
                        RuntimeToolkitTelemetry.logProvenanceEvent(
                            context = context,
                            entityType = "header",
                            entityKey = headerKey.lowercase(),
                            consumedBy = requestId,
                            payload = mapOf(
                                "url" to url,
                                "method" to method,
                                "source" to "webview_js_bridge",
                            ),
                        )
                    }
                }
                return@runCatching
            }

            val requestId = jsRequestId?.let { jsRequestMap.remove(it) }
                ?: RuntimeToolkitTelemetry.resolveRecentRequestId(url = url, method = method)
            val rawBody = bodyPreview?.toByteArray(Charsets.UTF_8)
            val captureLimitBytes = rawBody?.size ?: 0
            val hasContentLengthHeader = responseHeaders.keys.any { it.equals("content-length", ignoreCase = true) }
            val contentLengthHeader = if (!hasContentLengthHeader && bodyOriginalLength != null && bodyOriginalLength > 0) {
                bodyOriginalLength.toString()
            } else {
                null
            }
            val bodyCapturePolicy = resolveBridgeBodyCapturePolicy(url = url, mimeType = mimeType)
            val candidateRelevance = candidateRelevanceForPolicy(bodyCapturePolicy)
            val truncationReason = if (bodyPreviewTruncated) "body_size_limit" else ""
            val captureFailure = if (bodyPreviewTruncated && bodyCapturePolicy == "full_candidate_required") {
                "required_body_truncated"
            } else {
                ""
            }

            val responseId = RuntimeToolkitTelemetry.logNetworkResponse(
                context = context,
                source = "webview_js_bridge",
                url = url,
                method = method,
                statusCode = statusCode,
                reason = reason,
                mimeType = mimeType,
                headers = responseHeaders,
                rawBody = rawBody,
                requestId = requestId,
                payload = mapOf(
                    "bridge_stage" to stage,
                    "bridge_request_id" to jsRequestId,
                    "bridge_observed" to true,
                    "capture_truncated" to bodyPreviewTruncated,
                    "capture_limit_bytes" to if (bodyPreviewTruncated) captureLimitBytes else 0,
                    "stored_size_bytes" to captureLimitBytes,
                    "content_length_header" to contentLengthHeader,
                    "truncation_reason" to truncationReason,
                    "body_capture_policy" to bodyCapturePolicy,
                    "candidate_relevance" to candidateRelevance,
                    "capture_reason" to "js_bridge_capture",
                    "capture_failure" to captureFailure,
                ),
            )

            responseHeaders.forEach { (headerKey, _) ->
                if (shouldTrackProvenanceHeader(headerKey)) {
                    RuntimeToolkitTelemetry.logProvenanceEvent(
                        context = context,
                        entityType = "header",
                        entityKey = headerKey.lowercase(),
                        producedBy = responseId,
                        consumedBy = requestId,
                        payload = mapOf(
                            "url" to url,
                            "status_code" to statusCode,
                            "source" to "webview_js_bridge",
                        ),
                    )
                }
            }
            RuntimeToolkitTelemetry.recordCookieSnapshot(context, url)
        }.onFailure { throwable ->
            RuntimeToolkitTelemetry.logExtractionEvent(
                context = context,
                operation = "js_bridge_network_event_failed",
                payload = mapOf("message" to (throwable.message ?: "unknown")),
            )
            Log.w("JsWebInterface", "runtimeToolkitNetworkEvent failed: ${throwable.message}")
        }
    }

    @JavascriptInterface
    fun runtimeToolkitPlaybackEvent(rawJson: String?) {
        if (rawJson.isNullOrBlank()) return
        val context = webView.context.applicationContext
        runCatching {
            val event = JSONObject(rawJson)
            val signal = event.optString("signal").ifBlank { "unknown" }.lowercase()
            val mediaUrl = event.optString("mediaUrl").ifBlank { webView.url.orEmpty() }
            val currentTime = optionalDouble(event, "currentTime")
            val duration = optionalDouble(event, "duration")
            val playbackRate = optionalDouble(event, "playbackRate")
            val readyState = optionalInt(event, "readyState")
            val networkState = optionalInt(event, "networkState")
            val bufferedEnd = optionalDouble(event, "bufferedEnd")
            val paused = event.optBoolean("paused", false)
            val seeking = event.optBoolean("seeking", false)

            val actionName = playbackActionName(
                signal = signal,
                seeking = seeking,
                currentTime = currentTime,
            )
            val payload = mapOf(
                "signal" to signal,
                "playback_operation" to actionName,
                "media_url" to mediaUrl,
                "current_time" to currentTime,
                "duration" to duration,
                "playback_rate" to playbackRate,
                "ready_state" to readyState,
                "network_state" to networkState,
                "buffered_end" to bufferedEnd,
                "paused" to paused,
                "seeking" to seeking,
                "source" to "webview_js_playback",
            )

            if (isDiscretePlaybackSignal(signal)) {
                val correlation = RuntimeToolkitTelemetry.beginUiAction(
                    context = context,
                    actionName = actionName,
                    screenId = "browser",
                    payload = payload + mapOf("result" to "started"),
                )
                RuntimeToolkitTelemetry.finishUiAction(
                    context = context,
                    correlation = correlation,
                    actionName = actionName,
                    result = "observed",
                    payload = payload + mapOf("result" to "observed"),
                )
            } else {
                RuntimeToolkitTelemetry.logUiObserved(
                    context = context,
                    actionName = actionName,
                    payload = payload,
                    screenId = "browser",
                )
            }

            RuntimeToolkitTelemetry.logCorrelationEvent(
                context = context,
                operation = "playback_signal_correlated",
                payload = payload,
            )
        }.onFailure { throwable ->
            RuntimeToolkitTelemetry.logExtractionEvent(
                context = context,
                operation = "js_bridge_playback_event_failed",
                payload = mapOf("message" to (throwable.message ?: "unknown")),
            )
            Log.w("JsWebInterface", "runtimeToolkitPlaybackEvent failed: ${throwable.message}")
        }
    }

    private fun jsonToStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }

    private fun optionalInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return runCatching { obj.getInt(key) }.getOrNull()
    }

    private fun optionalDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return runCatching { obj.getDouble(key) }.getOrNull()
    }

    private fun shouldTrackProvenanceHeader(headerKey: String): Boolean {
        val normalized = headerKey.lowercase()
        return normalized == "authorization" ||
            normalized == "cookie" ||
            normalized == "set-cookie" ||
            normalized.contains("token") ||
            normalized.startsWith("x-")
    }

    private fun resolveBridgeBodyCapturePolicy(url: String, mimeType: String?): String {
        val lowerUrl = url.lowercase()
        val lowerMime = (mimeType ?: "").lowercase()
        val isMediaSegment = lowerUrl.endsWith(".m4s") ||
            lowerUrl.endsWith(".ts") ||
            lowerUrl.endsWith(".mp4") ||
            lowerMime.startsWith("video/") ||
            lowerMime.startsWith("audio/")
        if (isMediaSegment) return "skipped_media_segment"
        if (
            lowerUrl.contains("graphql") ||
            lowerUrl.contains(".m3u8") ||
            lowerUrl.contains(".mpd") ||
            lowerUrl.contains("manifest") ||
            lowerUrl.contains("resolver") ||
            lowerMime.contains("json")
        ) {
            return "full_candidate_required"
        }
        if (lowerMime.contains("html") || lowerMime.contains("xml")) return "full_candidate"
        return "metadata_only"
    }

    private fun candidateRelevanceForPolicy(policy: String): String {
        return when (policy) {
            "full_candidate_required" -> "required_candidate"
            "full_candidate", "truncated_candidate" -> "signal_candidate"
            else -> "non_candidate"
        }
    }

    private fun playbackActionName(
        signal: String,
        seeking: Boolean,
        currentTime: Double?,
    ): String {
        return when (signal.lowercase()) {
            "play" -> if ((currentTime ?: 0.0) > 0.75) "playback_resume" else "playback_start"
            "pause" -> "playback_pause"
            "seeking" -> "playback_seek_start"
            "seeked" -> "playback_seek_complete"
            "ended" -> "playback_stop"
            "waiting" -> "playback_buffering"
            "ratechange" -> "playback_rate_change"
            "loadedmetadata" -> "playback_metadata_ready"
            "timeupdate" -> if (seeking) "playback_seek_progress" else "playback_progress"
            else -> "playback_signal_${signal.lowercase()}"
        }
    }

    private fun isDiscretePlaybackSignal(signal: String): Boolean {
        return when (signal.lowercase()) {
            "play", "pause", "seeking", "seeked", "ended", "waiting", "ratechange", "loadedmetadata" -> true
            else -> false
        }
    }

    companion object {
        private const val MAX_BRIDGE_BODY_PREVIEW_CHARS = 16 * 1024 * 1024
    }
}

fun String.toUserMessage() = ChatMessage(
    role = ChatRole.User,
    content = this
)
fun String.toSystemMessage() = ChatMessage(
    role = ChatRole.System,
    content = this
)
fun String.toAssistantMessage() = ChatMessage(
    role = ChatRole.Assistant,
    content = this
)
