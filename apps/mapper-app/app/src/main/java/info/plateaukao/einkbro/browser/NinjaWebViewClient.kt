package info.plateaukao.einkbro.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.security.KeyChain
import android.util.Log
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.fishit.mapper.wave01.debug.RuntimeToolkitTelemetry
import info.plateaukao.einkbro.R
import info.plateaukao.einkbro.view.compose.MyTheme
import info.plateaukao.einkbro.caption.DualCaptionProcessor
import info.plateaukao.einkbro.preference.ConfigManager
import info.plateaukao.einkbro.unit.BrowserUnit
import info.plateaukao.einkbro.view.EBToast
import info.plateaukao.einkbro.view.EBWebView
import info.plateaukao.einkbro.view.dialog.DialogManager
import info.plateaukao.einkbro.view.dialog.compose.AuthenticationDialogFragment
import io.github.edsuns.adfilter.AdFilter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException


class EBWebViewClient(
    private val ebWebView: EBWebView,
    private val addHistoryAction: (String, String) -> Unit,
) : WebViewClient(), KoinComponent {
    private val context: Context = ebWebView.context
    private val config: ConfigManager by inject()
    private val cookie: Cookie by inject()
    private val dialogManager: DialogManager by lazy { DialogManager(context as Activity) }

    private val webContentPostProcessor = WebContentPostProcessor()
    private var hasAdBlock: Boolean = true
    private var activePageLoadCorrelation: RuntimeToolkitTelemetry.CorrelationContext? = null
    private var activePageLoadActionName: String = "webview_navigation"

    private val adFilter: AdFilter = AdFilter.get()

    private val dualCaptionProcessor = DualCaptionProcessor()

    fun enableAdBlock(enable: Boolean) {
        this.hasAdBlock = enable
    }


    private var onPageFinishedAction: () -> Unit = {}
    fun setOnPageFinishedAction(action: () -> Unit) {
        onPageFinishedAction = action
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        ebWebView.isInnerScrollAtTop = true
        ebWebView.innerScrollTop = 0
        ebWebView.innerScrollHeight = 0
        ebWebView.innerClientHeight = 0

        if (config.adBlock) {
            adFilter.performScript(view, url)
        }

        activePageLoadActionName = RuntimeToolkitTelemetry.navigationActionName(url)
        val semanticPayload = RuntimeToolkitTelemetry.navigationSemanticPayload(url)
        activePageLoadCorrelation = RuntimeToolkitTelemetry.beginUiAction(
            context = context,
            actionName = activePageLoadActionName,
            screenId = "browser",
            payload = semanticPayload + mapOf(
                "url" to url,
                "result" to "started",
                "source" to "webview",
            ),
        )
        RuntimeToolkitTelemetry.recordCookieSnapshot(context, url)
        view?.let { injectRuntimeToolkitNetworkHook(it) }
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        super.onPageCommitVisible(view, url)
        injectRuntimeToolkitNetworkHook(view)
    }

    override fun onPageFinished(view: WebView, url: String) {
        ebWebView.updateCssStyle()

        Log.d("ebWebViewClient", "onPageFinished: ${ebWebView.url}\n$url")
        webContentPostProcessor.postProcess(ebWebView, url)

        if (ebWebView.shouldHideTranslateContext) {
            ebWebView.postDelayed({
                ebWebView.hideTranslateContext()
            }, 2000)
        }

        ebWebView.postDelayed({
            ebWebView.updatePageInfo()
        }, 1000)

        // skip translation pages
        if (config.isSaveHistoryWhenLoad() &&
            !ebWebView.incognito &&
            !isTranslationDomain(url) &&
            url != BrowserUnit.URL_ABOUT_BLANK
        ) {
            addHistoryAction(ebWebView.albumTitle, url)
        }

        // test
        ebWebView.evaluateJavascript(
            """
                    function findTargetWithA(e){
                        var tt = e;
                        while(tt){
                            if(tt.tagName.toLowerCase() == "a"){
                                break;
                            }
                            tt = tt.parentElement;
                        }
                        return tt;
                    }
                    const w=window;
                    w.addEventListener('touchstart',wrappedOnDownFunc);
                    function wrappedOnDownFunc(e){
                        if(e.touches.length==1){
                            w._touchTarget = findTargetWithA(e.touches[0].target);
                        }
                        console.log('hey touched something ' +w._touchTarget);
                    }
        """.trimIndent(), null
        )

        if (url != "about:blank") {
            onPageFinishedAction()
        }
        injectRuntimeToolkitNetworkHook(view)
        captureMainFrameHtmlResponse(view, url)

        activePageLoadCorrelation?.let { correlation ->
            RuntimeToolkitTelemetry.finishUiAction(
                context = context,
                correlation = correlation,
                actionName = activePageLoadActionName,
                result = "ok",
                payload = RuntimeToolkitTelemetry.navigationSemanticPayload(url) + mapOf(
                    "url" to url,
                    "source" to "webview",
                ),
            )
        }
        activePageLoadCorrelation = null
        activePageLoadActionName = "webview_navigation"
        RuntimeToolkitTelemetry.recordCookieSnapshot(context, url)
    }

    private fun isTranslationDomain(url: String): Boolean {
        return url.contains("translate.goog") || url.contains("papago.naver.net")
                || url.contains("papago.naver.com") || url.contains("translate.google.com")
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleUri(view, request.url)

    private fun handleUri(webView: WebView, uri: Uri): Boolean {
        val url = uri.toString()
        Log.d("ebWebViewClient", "handleUri: $url")
        val list = webView.copyBackForwardList()

        for (i in 0 until list.size) {
            val item = list.getItemAtIndex(i)
            val title = item.title
            val url = item.url
            Log.d("ebWebViewClient", "Title: $title - URL: $url")
        }

        if (url.startsWith("http")) {
//            webView.loadUrl(url, ebWebView.requestHeaders)
//            return true
            return false
        }

        val packageManager = context.packageManager
        val browseIntent = Intent(Intent.ACTION_VIEW).setData(uri)
        if (browseIntent.resolveActivity(packageManager) != null) {
            try {
                context.startActivity(browseIntent)
                return true
            } catch (e: Exception) {
            }
        }
        if (url.startsWith("intent:")) {
            // prevent google map intent from opening google map app directly. Use browser instead!
            if (url.startsWith("intent://www.google.com/maps")) {
                return true
            }
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent.resolveActivity(context.packageManager) != null || intent.data?.scheme == "market") {
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        EBToast.show(context, R.string.toast_load_error)
                    }
                    return true
                }

                if (maybeHandleFallbackUrl(webView, intent)) return true

                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                //not an intent uri
                return false
            }
        }

        // handle rest scenarios: something like abc://, xyz://
        try {
            context.startActivity(browseIntent)
        } catch (e: Exception) {
            // ignore
        }

        return true //do nothing in other cases
    }

    private fun maybeHandleFallbackUrl(webView: WebView, intent: Intent): Boolean {
        val fallbackUrl = intent.getStringExtra("browser_fallback_url") ?: return false
        if (fallbackUrl.startsWith("market://")) {
            val intent = Intent.parseUri(fallbackUrl, Intent.URI_INTENT_SCHEME)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                EBToast.show(context, R.string.toast_load_error)
            }
            return true
        }

        webView.loadUrl(fallbackUrl)
        return true
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        AuthenticationDialogFragment { username, password ->
            handler?.proceed(username, password)
        }.show((context as FragmentActivity).supportFragmentManager, "AuthenticationDialog")
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        val requestId = RuntimeToolkitTelemetry.resolveRecentRequestId(
            url = request?.url?.toString().orEmpty(),
            method = request?.method ?: "GET",
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = request?.url?.toString().orEmpty(),
            method = request?.method ?: "GET",
            statusCode = null,
            reason = error?.description?.toString(),
            mimeType = null,
            headers = emptyMap(),
            requestId = requestId,
            payload = mapOf(
                "error_code" to (error?.errorCode ?: 0),
                "is_main_frame" to (request?.isForMainFrame ?: false),
            ),
        )

        // if https is not available, try http
        if (error?.description == "net::ERR_SSL_PROTOCOL_ERROR" && request != null) {
            ebWebView.loadUrl(request.url.buildUpon().scheme("http").build().toString())
        } else {
            Log.e("ebWebViewClient", "onReceivedError:${request?.url} / ${error?.description}")
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        val requestId = RuntimeToolkitTelemetry.resolveRecentRequestId(
            url = request?.url?.toString().orEmpty(),
            method = request?.method ?: "GET",
        )
        RuntimeToolkitTelemetry.logNetworkResponse(
            context = context,
            source = "webview",
            url = request?.url?.toString().orEmpty(),
            method = request?.method ?: "GET",
            statusCode = errorResponse?.statusCode,
            reason = errorResponse?.reasonPhrase,
            mimeType = errorResponse?.mimeType,
            headers = responseHeaders(errorResponse),
            requestId = requestId,
            payload = mapOf(
                "is_main_frame" to (request?.isForMainFrame ?: false),
                "has_gesture" to (request?.hasGesture() ?: false),
            ),
        )
        RuntimeToolkitTelemetry.recordCookieSnapshot(context, request?.url?.toString())
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        val requestId = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            RuntimeToolkitTelemetry.logNetworkRequest(
                context = context,
                source = "webview_deprecated_fallback",
                url = url,
                method = "GET",
                headers = emptyMap(),
                payload = mapOf("api_variant" to "deprecated_fallback"),
            ).requestId
        } else {
            RuntimeToolkitTelemetry.logCorrelationEvent(
                context = context,
                operation = "deprecated_should_intercept_ignored",
                payload = mapOf("url" to url),
            )
            RuntimeToolkitTelemetry.resolveRecentRequestId(url = url, method = "GET")
        }
        val handled = handleWebRequest(view, Uri.parse(url))
        if (handled != null) {
            RuntimeToolkitTelemetry.logNetworkResponse(
                context = context,
                source = "webview",
                url = url,
                method = "GET",
                statusCode = 200,
                reason = "intercepted_local_response",
                mimeType = handled.mimeType,
                headers = responseHeaders(handled),
                requestId = requestId,
                payload = mapOf("intercepted" to true, "api_variant" to "deprecated"),
            )
        }
        return handled ?: super.shouldInterceptRequest(view, url)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val requestLog = RuntimeToolkitTelemetry.logNetworkRequest(
            context = context,
            source = "webview",
            url = request.url.toString(),
            method = request.method,
            headers = request.requestHeaders,
            payload = mapOf(
                "is_main_frame" to request.isForMainFrame,
                "has_gesture" to request.hasGesture(),
                "is_redirect" to request.isRedirect,
            ),
        )
        val requestId = requestLog.requestId

        if (config.adBlock) {
            val result = adFilter.shouldIntercept(view, request)
            if (result.shouldBlock) {
                RuntimeToolkitTelemetry.logNetworkResponse(
                    context = context,
                    source = "webview",
                    url = request.url.toString(),
                    method = request.method,
                    statusCode = 499,
                    reason = "blocked_by_adblock",
                    mimeType = result.resourceResponse?.mimeType,
                    headers = responseHeaders(result.resourceResponse),
                    requestId = requestId,
                    payload = mapOf(
                        "is_main_frame" to request.isForMainFrame,
                        "is_redirect" to request.isRedirect,
                        "intercepted" to true,
                        "blocked" to true,
                        "request_fingerprint" to requestLog.requestFingerprint,
                        "host_class" to requestLog.hostClass,
                        "phase_id" to requestLog.phaseId,
                    ),
                )
                //Log.d("EBWebViewClient", "blocked\n rule: ${result.rule}\n url:${result.resourceUrl}")
                return result.resourceResponse
            }
        }

        val handled = handleWebRequest(view, request.url)
        if (handled != null) {
            RuntimeToolkitTelemetry.logNetworkResponse(
                context = context,
                source = "webview",
                url = request.url.toString(),
                method = request.method,
                statusCode = 200,
                reason = "intercepted_local_response",
                mimeType = handled.mimeType,
                headers = responseHeaders(handled),
                requestId = requestId,
                payload = mapOf(
                    "is_main_frame" to request.isForMainFrame,
                    "is_redirect" to request.isRedirect,
                    "intercepted" to true,
                    "request_fingerprint" to requestLog.requestFingerprint,
                    "host_class" to requestLog.hostClass,
                    "phase_id" to requestLog.phaseId,
                ),
            )
        }
        return handled ?: super.shouldInterceptRequest(view, request)
    }

    private fun handleWebRequest(webView: WebView, uri: Uri): WebResourceResponse? {
        val url = uri.toString()

        if (!config.cookies) {
            if (cookie.isWhite(url)) {
                val manager = CookieManager.getInstance()
                manager.getCookie(url)
                manager.setAcceptCookie(true)
            } else {
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(false)
            }
        }

        processCustomFontRequest(uri)?.let { return it }
        dualCaptionProcessor.processUrl(url)?.let {
            ebWebView.dualCaption = it
            return WebResourceResponse(
                "application/json",
                "UTF-8",
                ByteArrayInputStream(it.toByteArray())
            )
        }

        return null
    }

    private fun responseHeaders(response: WebResourceResponse?): Map<String, String> {
        val headers = response?.responseHeaders ?: return emptyMap()
        return headers.mapKeys { it.key.orEmpty() }.mapValues { it.value.orEmpty() }
    }

    private fun injectRuntimeToolkitNetworkHook(webView: WebView) {
        RuntimeToolkitTelemetry.logCorrelationEvent(
            context = context,
            operation = "js_network_hook_inject_attempt",
            payload = mapOf(
                "url" to (webView.url ?: ""),
                "screen_id" to "browser",
            ),
        )
        webView.evaluateJavascript(RUNTIME_TOOLKIT_NETWORK_HOOK_JS, null)
    }

    private fun captureMainFrameHtmlResponse(webView: WebView, url: String) {
        if (url.isBlank() || url == "about:blank") return
        webView.evaluateJavascript(
            "(function(){try{return document.documentElement && document.documentElement.outerHTML ? document.documentElement.outerHTML : '';}catch(e){return '';}})();",
        ) { raw ->
            val html = decodeEvaluateJavascriptString(raw).trim()
            if (html.isBlank()) return@evaluateJavascript
            val bytes = html.toByteArray(Charsets.UTF_8)
            val capped = if (bytes.size > MAX_MAIN_FRAME_HTML_CAPTURE_BYTES) {
                bytes.copyOf(MAX_MAIN_FRAME_HTML_CAPTURE_BYTES)
            } else {
                bytes
            }
            val requestId = RuntimeToolkitTelemetry.resolveRecentRequestId(url = url, method = "GET")
            RuntimeToolkitTelemetry.logNetworkResponse(
                context = context,
                source = "webview_main_frame_html",
                url = url,
                method = "GET",
                statusCode = 200,
                reason = "page_finished_dom_snapshot",
                mimeType = "text/html",
                headers = emptyMap(),
                rawBody = capped,
                requestId = requestId,
                payload = mapOf(
                    "capture_mode" to "main_frame_dom_snapshot",
                    "capture_truncated" to (bytes.size > MAX_MAIN_FRAME_HTML_CAPTURE_BYTES),
                    "capture_limit_bytes" to if (bytes.size > MAX_MAIN_FRAME_HTML_CAPTURE_BYTES) capped.size else 0,
                    "captured_body_bytes" to capped.size,
                    "stored_size_bytes" to capped.size,
                    "content_length_header" to bytes.size.toString(),
                ),
            )
        }
    }

    private fun decodeEvaluateJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            JSONObject("""{"v":$raw}""").optString("v")
        }.getOrElse { raw.trim('"') }
    }

    private fun processCustomFontRequest(uri: Uri): WebResourceResponse? {
        if (uri.path?.contains("mycustomfont") == true) {
            val fontUri = if (!ebWebView.shouldUseReaderFont()) {
                config.customFontInfo?.url?.toUri() ?: return null
            } else {
                config.readerCustomFontInfo?.url?.toUri() ?: return null
            }

            try {
                val inputStream = context.contentResolver.openInputStream(fontUri)
                return WebResourceResponse("application/x-font-ttf", "UTF-8", inputStream)
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
        return null
    }

    private val RUNTIME_TOOLKIT_NETWORK_HOOK_JS = """
            (function() {
              try {
                if (window.__mapperToolkitNetworkHookInstalled) { return; }
                if (!window.androidApp || typeof window.androidApp.runtimeToolkitNetworkEvent !== 'function') { return; }
                window.__mapperToolkitNetworkHookInstalled = true;
                var __mapperToolkitReqSeq = 0;
                function makeRequestId() {
                  __mapperToolkitReqSeq += 1;
                  return "jsreq_" + Date.now().toString(36) + "_" + __mapperToolkitReqSeq.toString(36);
                }
                function truncate(value, maxLen) {
                  if (value == null) { return null; }
                  var text = String(value);
                  if (text.length <= maxLen) { return text; }
                  return text.slice(0, maxLen);
                }
                function safeHeaders(headers) {
                  var out = {};
                  if (!headers) { return out; }
                  try {
                    if (typeof headers.forEach === 'function') {
                      headers.forEach(function(value, key) {
                        out[String(key)] = truncate(value, 512);
                      });
                      return out;
                    }
                  } catch (_ignored) {}
                  try {
                    if (Array.isArray(headers)) {
                      headers.forEach(function(item) {
                        if (Array.isArray(item) && item.length >= 2) {
                          out[String(item[0])] = truncate(item[1], 512);
                        }
                      });
                      return out;
                    }
                  } catch (_ignored2) {}
                  try {
                    Object.keys(headers).forEach(function(key) {
                      out[String(key)] = truncate(headers[key], 512);
                    });
                  } catch (_ignored3) {}
                  return out;
                }
                function parseRawHeaders(raw) {
                  var out = {};
                  if (!raw) { return out; }
                  raw.trim().split(/[\r\n]+/).forEach(function(line) {
                    var idx = line.indexOf(':');
                    if (idx <= 0) { return; }
                    var key = line.slice(0, idx).trim();
                    var value = line.slice(idx + 1).trim();
                    if (key) { out[key] = truncate(value, 512); }
                  });
                  return out;
                }
                function shouldCaptureBody(url, mimeType) {
                  var mime = (mimeType || '').toLowerCase();
                  var lowerUrl = (url || '').toLowerCase();
                  return mime.indexOf('json') >= 0 ||
                    mime.indexOf('text/') === 0 ||
                    mime.indexOf('xml') >= 0 ||
                    mime.indexOf('javascript') >= 0 ||
                    mime.indexOf('html') >= 0 ||
                    lowerUrl.endsWith('.m3u8') ||
                    lowerUrl.endsWith('.mpd');
                }
                function emitNetworkEvent(event) {
                  try {
                    window.androidApp.runtimeToolkitNetworkEvent(JSON.stringify(event));
                  } catch (_ignored) {}
                }
                function emitPlaybackEvent(event) {
                  try {
                    if (window.androidApp && typeof window.androidApp.runtimeToolkitPlaybackEvent === 'function') {
                      window.androidApp.runtimeToolkitPlaybackEvent(JSON.stringify(event));
                    }
                  } catch (_ignored) {}
                }
                function mediaState(element, signal) {
                  var mediaUrl = '';
                  var currentTime = null;
                  var duration = null;
                  var playbackRate = null;
                  var paused = null;
                  var seeking = null;
                  var readyState = null;
                  var networkState = null;
                  var bufferedEnd = null;
                  try {
                    mediaUrl = element.currentSrc || element.src || window.location.href;
                    currentTime = (typeof element.currentTime === 'number') ? element.currentTime : null;
                    duration = (typeof element.duration === 'number' && isFinite(element.duration)) ? element.duration : null;
                    playbackRate = (typeof element.playbackRate === 'number') ? element.playbackRate : null;
                    paused = !!element.paused;
                    seeking = !!element.seeking;
                    readyState = (typeof element.readyState === 'number') ? element.readyState : null;
                    networkState = (typeof element.networkState === 'number') ? element.networkState : null;
                    if (element.buffered && element.buffered.length > 0) {
                      bufferedEnd = element.buffered.end(element.buffered.length - 1);
                    }
                  } catch (_ignored) {}
                  return {
                    signal: signal,
                    mediaUrl: mediaUrl,
                    currentTime: currentTime,
                    duration: duration,
                    playbackRate: playbackRate,
                    paused: paused,
                    seeking: seeking,
                    readyState: readyState,
                    networkState: networkState,
                    bufferedEnd: bufferedEnd
                  };
                }
                function installPlaybackHooks() {
                  if (window.__mapperToolkitPlaybackHookInstalled) { return; }
                  window.__mapperToolkitPlaybackHookInstalled = true;
                  var watchedSignals = ['play', 'pause', 'seeking', 'seeked', 'waiting', 'ended', 'ratechange', 'loadedmetadata'];
                  watchedSignals.forEach(function(signal) {
                    document.addEventListener(signal, function(e) {
                      var target = e && e.target;
                      if (!target || typeof target.tagName !== 'string') { return; }
                      if (String(target.tagName).toLowerCase() !== 'video' && String(target.tagName).toLowerCase() !== 'audio') { return; }
                      emitPlaybackEvent(mediaState(target, signal));
                    }, true);
                  });
                  document.addEventListener('timeupdate', function(e) {
                    var target = e && e.target;
                    if (!target || typeof target.tagName !== 'string') { return; }
                    var tagName = String(target.tagName).toLowerCase();
                    if (tagName !== 'video' && tagName !== 'audio') { return; }
                    var now = Date.now();
                    var last = Number(target.__mapperToolkitLastTimeUpdateTs || 0);
                    if ((now - last) < 2000) { return; }
                    target.__mapperToolkitLastTimeUpdateTs = now;
                    emitPlaybackEvent(mediaState(target, 'timeupdate'));
                  }, true);
                }
                installPlaybackHooks();

                if (typeof window.fetch === 'function' && !window.__mapperToolkitFetchHookInstalled) {
                  window.__mapperToolkitFetchHookInstalled = true;
                  var originalFetch = window.fetch;
                  window.fetch = function(input, init) {
                    var requestId = makeRequestId();
                    var requestUrl = '';
                    var requestMethod = 'GET';
                    var requestHeaders = {};
                    try {
                      var requestObj = (typeof Request !== 'undefined' && input instanceof Request) ? input : null;
                      requestUrl = requestObj ? requestObj.url : String(input || '');
                      requestMethod = ((init && init.method) || (requestObj && requestObj.method) || 'GET').toUpperCase();
                      requestHeaders = safeHeaders((init && init.headers) || (requestObj && requestObj.headers));
                    } catch (_ignored) {}

                    emitNetworkEvent({
                      stage: 'request',
                      source: 'fetch',
                      requestId: requestId,
                      url: requestUrl,
                      method: requestMethod,
                      requestHeaders: requestHeaders
                    });

                    return originalFetch.apply(this, arguments)
                      .then(function(response) {
                        var responseUrl = response && response.url ? response.url : requestUrl;
                        var headers = safeHeaders(response && response.headers ? response.headers : null);
                        var mimeType = '';
                        try {
                          mimeType = (response && response.headers && response.headers.get('content-type')) || '';
                        } catch (_ignored2) {}
                        var bodyPromise = Promise.resolve(null);
                        if (response && shouldCaptureBody(responseUrl, mimeType)) {
                          bodyPromise = response.clone().text()
                            .then(function(text) {
                              var safeText = (text == null) ? '' : String(text);
                              var preview = truncate(safeText, 16777216);
                              return {
                                bodyPreview: preview,
                                bodyPreviewTruncated: preview.length < safeText.length,
                                bodyOriginalLength: safeText.length
                              };
                            })
                            .catch(function() {
                              return {
                                bodyPreview: null,
                                bodyPreviewTruncated: false,
                                bodyOriginalLength: 0
                              };
                            });
                        }
                        return bodyPromise.then(function(bodyPayload) {
                          var payload = bodyPayload || {
                            bodyPreview: null,
                            bodyPreviewTruncated: false,
                            bodyOriginalLength: 0
                          };
                          emitNetworkEvent({
                            stage: 'response',
                            source: 'fetch',
                            requestId: requestId,
                            url: responseUrl,
                            responseUrl: responseUrl,
                            method: requestMethod,
                            status: response ? response.status : null,
                            reason: response ? response.statusText : null,
                            mimeType: mimeType,
                            headers: headers,
                            bodyPreview: payload.bodyPreview,
                            bodyPreviewTruncated: !!payload.bodyPreviewTruncated,
                            bodyOriginalLength: payload.bodyOriginalLength || 0
                          });
                          return response;
                        });
                      })
                      .catch(function(error) {
                        emitNetworkEvent({
                          stage: 'response',
                          source: 'fetch',
                          requestId: requestId,
                          url: requestUrl,
                          method: requestMethod,
                          status: null,
                          reason: (error && error.message) ? String(error.message) : 'fetch_error',
                          headers: {}
                        });
                        throw error;
                      });
                  };
                }

                if (window.XMLHttpRequest && !window.__mapperToolkitXhrHookInstalled) {
                  window.__mapperToolkitXhrHookInstalled = true;
                  var originalOpen = XMLHttpRequest.prototype.open;
                  var originalSend = XMLHttpRequest.prototype.send;
                  var originalSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;

                  XMLHttpRequest.prototype.open = function(method, url) {
                    this.__mapperToolkitMeta = {
                      requestId: makeRequestId(),
                      method: String(method || 'GET').toUpperCase(),
                      url: String(url || ''),
                      requestHeaders: {}
                    };
                    return originalOpen.apply(this, arguments);
                  };

                  XMLHttpRequest.prototype.setRequestHeader = function(key, value) {
                    try {
                      if (this.__mapperToolkitMeta) {
                        this.__mapperToolkitMeta.requestHeaders[String(key)] = truncate(value, 512);
                      }
                    } catch (_ignored) {}
                    return originalSetRequestHeader.apply(this, arguments);
                  };

                  XMLHttpRequest.prototype.send = function() {
                    var meta = this.__mapperToolkitMeta || {
                      requestId: makeRequestId(),
                      method: 'GET',
                      url: this.responseURL || '',
                      requestHeaders: {}
                    };
                    emitNetworkEvent({
                      stage: 'request',
                      source: 'xhr',
                      requestId: meta.requestId,
                      url: meta.url,
                      method: meta.method,
                      requestHeaders: meta.requestHeaders
                    });

                    var xhr = this;
                    var onReadyState = function() {
                      if (xhr.readyState !== 4) { return; }
                      xhr.removeEventListener('readystatechange', onReadyState);
                      var responseHeaders = {};
                      var mimeType = '';
                      try {
                        responseHeaders = parseRawHeaders(xhr.getAllResponseHeaders ? xhr.getAllResponseHeaders() : '');
                        mimeType = (xhr.getResponseHeader && xhr.getResponseHeader('content-type')) || '';
                      } catch (_ignored2) {}
                      var responseUrl = xhr.responseURL || meta.url;
                      var bodyPreview = null;
                      var bodyPreviewTruncated = false;
                      var bodyOriginalLength = 0;
                      try {
                        if (shouldCaptureBody(responseUrl, mimeType) &&
                          (xhr.responseType === '' || xhr.responseType === 'text')) {
                          var responseText = (xhr.responseText == null) ? '' : String(xhr.responseText);
                          bodyOriginalLength = responseText.length;
                          bodyPreview = truncate(responseText, 16777216);
                          bodyPreviewTruncated = bodyPreview.length < responseText.length;
                        }
                      } catch (_ignored3) {}
                      emitNetworkEvent({
                        stage: 'response',
                        source: 'xhr',
                        requestId: meta.requestId,
                        url: responseUrl,
                        responseUrl: responseUrl,
                        method: meta.method,
                        status: xhr.status || null,
                        reason: xhr.statusText || null,
                        mimeType: mimeType,
                        headers: responseHeaders,
                        bodyPreview: bodyPreview,
                        bodyPreviewTruncated: bodyPreviewTruncated,
                        bodyOriginalLength: bodyOriginalLength
                      });
                    };
                    xhr.addEventListener('readystatechange', onReadyState);
                    return originalSend.apply(this, arguments);
                  };
                }
              } catch (_ignoredTop) {}
            })();
        """.trimIndent()

    companion object {
        private const val TAG = "ebWebViewClient"
        private const val MAX_MAIN_FRAME_HTML_CAPTURE_BYTES = 16 * 1024 * 1024
    }

    override fun onFormResubmission(view: WebView, doNotResend: Message, resend: Message) {
        val holder = view.context as? Activity ?: return
        val showDialog = mutableStateOf(true)
        val rootView = holder.findViewById<ViewGroup>(android.R.id.content)
        val composeView = ComposeView(holder).apply {
            setViewTreeLifecycleOwner(holder as androidx.lifecycle.LifecycleOwner)
            setViewTreeSavedStateRegistryOwner(holder as androidx.savedstate.SavedStateRegistryOwner)
        }
        rootView.addView(composeView)
        composeView.setContent {
            MyTheme {
                if (showDialog.value) {
                    androidx.compose.material.AlertDialog(
                        onDismissRequest = {
                            showDialog.value = false
                            doNotResend.sendToTarget()
                            rootView.removeView(composeView)
                        },
                        text = {
                            Text(
                                text = stringResource(R.string.dialog_content_resubmission),
                                color = MaterialTheme.colors.onBackground,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDialog.value = false
                                resend.sendToTarget()
                                rootView.removeView(composeView)
                            }) {
                                Text(
                                    text = stringResource(android.R.string.ok),
                                    color = MaterialTheme.colors.onBackground,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showDialog.value = false
                                doNotResend.sendToTarget()
                                rootView.removeView(composeView)
                            }) {
                                Text(
                                    text = stringResource(android.R.string.cancel),
                                    color = MaterialTheme.colors.onBackground,
                                )
                            }
                        },
                        backgroundColor = MaterialTheme.colors.background,
                    )
                }
            }
        }
    }

    // return true means it's processed
    private fun handlePrivateKeyAlias(request: ClientCertRequest, alias: String?): Boolean {
        val keyAlias = alias ?: return false
        val holder = context as? Activity ?: return false
        try {
            val certChain = KeyChain.getCertificateChain(holder, keyAlias) ?: return false
            val privateKey = KeyChain.getPrivateKey(holder, keyAlias) ?: return false
            request.proceed(privateKey, certChain)
            return true
        } catch (e: Exception) {
            Log.e(
                "ebWebViewClient",
                "Error when getting CertificateChain or PrivateKey for alias '${alias}'",
                e
            )
        }
        return false
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        val holder = view.context as? Activity ?: return
        KeyChain.choosePrivateKeyAlias(
            holder,
            { alias ->
                if (!handlePrivateKeyAlias(request, alias)) {
                    super.onReceivedClientCertRequest(view, request)
                }
            },
            request.keyTypes, request.principals, request.host, request.port, null
        )
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val title = "An Error Occurred!!!"
        var message =
            "The page you are trying to view cannot be shown because the connection isn't private or the authenticity of the received data could not be verified. \n\nIf you want to take the risk and continue viewing the page, please press OK.\n\n\nReason: "
        when (error.primaryError) {
            SslError.SSL_UNTRUSTED -> message += """"Certificate authority is not trusted.""""
            SslError.SSL_EXPIRED -> message += """"Certificate has expired.""""
            SslError.SSL_IDMISMATCH -> message += """"Certificate Hostname mismatch.""""
            SslError.SSL_NOTYETVALID -> message += """"Certificate is not yet valid.""""
            SslError.SSL_DATE_INVALID -> message += """"Certificate date is invalid.""""
            SslError.SSL_INVALID -> message += """"Certificate is invalid.""""
        }

        Log.e(TAG, "onReceivedSslError: $message")
        if (config.enableCertificateErrorDialog) {
            dialogManager.showOkCancelDialog(
                title = title,
                message = message,
                showInCenter = true,
                okAction = { handler.proceed() },
                cancelAction = { handler.cancel() }
            )
        } else {
            handler.proceed()
        }
    }

}
