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

            val responseId = RuntimeToolkitTelemetry.logNetworkResponse(
                context = context,
                source = "webview_js_bridge",
                url = url,
                method = method,
                statusCode = statusCode,
                reason = reason,
                mimeType = mimeType,
                headers = responseHeaders,
                rawBody = bodyPreview?.toByteArray(Charsets.UTF_8),
                requestId = requestId,
                payload = mapOf(
                    "bridge_stage" to stage,
                    "bridge_request_id" to jsRequestId,
                    "bridge_observed" to true,
                ),
            )

            if (statusCode == 401 || statusCode == 403) {
                RuntimeToolkitTelemetry.logAuthEvent(
                    context = context,
                    operation = "auth_state_changed",
                    payload = mapOf(
                        "status_code" to statusCode,
                        "url" to url,
                        "source" to "webview_js_bridge",
                        "request_id" to requestId,
                        "response_id" to responseId,
                    ),
                )
            }

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

    private fun shouldTrackProvenanceHeader(headerKey: String): Boolean {
        val normalized = headerKey.lowercase()
        return normalized == "authorization" ||
            normalized == "cookie" ||
            normalized == "set-cookie" ||
            normalized.contains("token") ||
            normalized.startsWith("x-")
    }

    companion object {
        private const val MAX_BRIDGE_BODY_PREVIEW_CHARS = 16 * 1024
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
