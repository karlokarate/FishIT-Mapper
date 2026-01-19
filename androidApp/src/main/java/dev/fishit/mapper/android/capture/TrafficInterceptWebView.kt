package dev.fishit.mapper.android.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.webkit.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * WebView mit eingebauter Traffic-Interception.
 *
 * Fängt alle XHR- und Fetch-Requests ab und korreliert sie mit User-Actions.
 * Umgeht Certificate Pinning vollständig, da die Interception auf JS-Ebene stattfindet.
 *
 * ## Verwendung
 * ```kotlin
 * val webView = TrafficInterceptWebView(context)
 *
 * // Traffic beobachten
 * lifecycleScope.launch {
 *     webView.capturedExchanges.collect { exchanges ->
 *         // Neue Exchanges verarbeiten
 *     }
 * }
 *
 * // User Actions beobachten
 * lifecycleScope.launch {
 *     webView.userActions.collect { actions ->
 *         // Click/Submit/Input Events
 *     }
 * }
 *
 * // Website laden
 * webView.loadUrl("https://example.com")
 * ```
 *
 * ## Vorteile
 * - Kein Root erforderlich
 * - Umgeht Certificate Pinning komplett
 * - Perfekte Event-Korrelation
 * - Request/Response Bodies vollständig verfügbar
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class TrafficInterceptWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /**
     * Gecapturter HTTP-Exchange aus dem WebView.
     */
    data class CapturedExchange(
        val id: String = UUID.randomUUID().toString(),
        val method: String,
        val url: String,
        val requestHeaders: Map<String, String>,
        val requestBody: String?,
        val responseStatus: Int?,
        val responseHeaders: Map<String, String>?,
        val responseBody: String?,
        val startedAt: Instant,
        val completedAt: Instant?
    )

    /**
     * User-Aktion im WebView (Click, Submit, Input).
     */
    data class UserAction(
        val id: String = UUID.randomUUID().toString(),
        val type: ActionType,
        val target: String,
        val value: String?,
        val timestamp: Instant,
        val pageUrl: String?
    )

    enum class ActionType {
        CLICK,
        SUBMIT,
        INPUT,
        NAVIGATION
    }

    /**
     * Page-Load Event.
     */
    data class PageEvent(
        val url: String,
        val title: String?,
        val timestamp: Instant,
        val type: PageEventType
    )

    enum class PageEventType {
        STARTED,
        FINISHED,
        ERROR
    }

    // State Flows für Beobachter
    private val _capturedExchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
    val capturedExchanges: StateFlow<List<CapturedExchange>> = _capturedExchanges.asStateFlow()

    private val _userActions = MutableStateFlow<List<UserAction>>(emptyList())
    val userActions: StateFlow<List<UserAction>> = _userActions.asStateFlow()

    private val _pageEvents = MutableStateFlow<List<PageEvent>>(emptyList())
    val pageEvents: StateFlow<List<PageEvent>> = _pageEvents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    // Pending Requests (warten auf Response)
    private val pendingRequests = mutableMapOf<String, CapturedExchange>()

    init {
        setupWebView()
        // WICHTIG: Focus-Handling für Tastatur-Input bei Eingabefeldern
        isFocusable = true
        isFocusableInTouchMode = true

        // KRITISCH: OnTouchListener für Tastatur-Aktivierung
        setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_UP -> {
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
            }
            // WICHTIG: false zurückgeben, damit WebView das Event selbst verarbeitet
            false
        }
    }

    private fun setupWebView() {
        // JavaScript aktivieren (erforderlich für Interception)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // WICHTIG für WebAuthn: Multiple Windows Support aktivieren
        // WebAuthn-Dialoge benötigen dies, sonst erscheint ein grauer Overlay
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // WICHTIG: Mixed Content erlauben (HTTPS-Seiten mit HTTP-Ressourcen)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Third-Party Cookies erlauben (für Login-Sessions)
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@TrafficInterceptWebView, true)
        }

        // Zoom und Viewport korrekt konfigurieren
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // Media Autoplay erlauben (manche Seiten brauchen das)
        settings.mediaPlaybackRequiresUserGesture = false

        // Geolocation erlauben (falls benötigt)
        settings.setGeolocationEnabled(true)

        // File Access (für Upload-Formulare)
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        // WICHTIG: Soft Keyboard / Input Method Configuration
        // Ermöglicht Tastatur-Popup bei Eingabefeldern
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false

        // User Agent setzen (wie normaler Chrome, NICHT als WebView erkennbar)
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        settings.userAgentString = settings.userAgentString.replace("; wv", "")

        // JavaScript Interface für Capture
        addJavascriptInterface(CaptureJsBridge(), BRIDGE_NAME)

        // WebViewClient für Page Events
        webViewClient = InterceptingWebViewClient()

        // WebChromeClient für erweiterte Features
        webChromeClient = FullFeaturedChromeClient()
    }

    /**
     * Vollständiger WebChromeClient für alle Website-Features.
     */
    private inner class FullFeaturedChromeClient : WebChromeClient() {

        // File Upload Support (Input type="file")
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<android.net.Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // Speichere Callback für späteren Aufruf
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback

            // Öffne System File Picker
            try {
                val intent = fileChooserParams?.createIntent()
                if (intent != null && context is android.app.Activity) {
                    (context as android.app.Activity).startActivityForResult(intent, FILE_CHOOSER_REQUEST)
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "File chooser error: ${e.message}")
            }

            filePathCallback?.onReceiveValue(null)
            pendingFileCallback = null
            return false
        }

        // Geolocation Permission
        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            // Automatisch erlauben (für Standort-basierte Seiten)
            callback?.invoke(origin, true, false)
        }

        // JavaScript Alert/Confirm/Prompt Dialogs
        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            android.util.Log.d(TAG, "[JS Alert] $message")
            result?.confirm()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?
        ): Boolean {
            android.util.Log.d(TAG, "[JS Confirm] $message")
            result?.confirm()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            android.util.Log.d(TAG, "[JS Prompt] $message")
            result?.confirm(defaultValue ?: "")
            return true
        }

        // Permission Request (Kamera, Mikrofon, etc.)
        override fun onPermissionRequest(request: PermissionRequest?) {
            // Alle Permissions erlauben (für Video-Calls, WebRTC, etc.)
            request?.grant(request.resources)
        }

        // Console Messages für Debugging
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            val level = when (consoleMessage?.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                ConsoleMessage.MessageLevel.WARNING -> "WARN"
                else -> "LOG"
            }
            android.util.Log.d(TAG, "[JS $level] ${consoleMessage?.message()}")
            return true
        }

        // Progress Update
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            // Könnte für Progress-Bar verwendet werden
        }

        // Window Creation (für target="_blank" Links und WebAuthn-Dialoge)
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?
        ): Boolean {
            android.util.Log.d(TAG, "onCreateWindow: isDialog=$isDialog, isUserGesture=$isUserGesture")

            // Für WebAuthn/FIDO2-Dialoge: Erstelle ein temporäres WebView
            if (isDialog) {
                val newWebView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(false)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            // WebAuthn-Callbacks werden oft als URL-Redirects gehandhabt
                            val url = request?.url?.toString() ?: return false
                            android.util.Log.d(TAG, "Dialog WebView URL: $url")

                            // Lade die URL im Haupt-WebView
                            this@TrafficInterceptWebView.loadUrl(url)
                            return true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            android.util.Log.d(TAG, "Dialog WebView closed")
                        }
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }

            // Normale Links: Öffne in gleichem WebView
            view?.let { webView ->
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = webView
                resultMsg?.sendToTarget()
                return true
            }
            return false
        }

        // Fullscreen Support (für Videos)
        private var customView: android.view.View? = null
        private var customViewCallback: CustomViewCallback? = null

        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
            customView = view
            customViewCallback = callback
            // Hier könnte Fullscreen-Logik implementiert werden
        }

        override fun onHideCustomView() {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        }
    }

    // File Chooser Callback
    private var pendingFileCallback: ValueCallback<Array<android.net.Uri>>? = null

    /**
     * Muss von Activity aufgerufen werden nach File-Auswahl.
     */
    fun onFileChooserResult(resultCode: Int, data: android.content.Intent?) {
        if (resultCode == android.app.Activity.RESULT_OK) {
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            pendingFileCallback?.onReceiveValue(result)
        } else {
            pendingFileCallback?.onReceiveValue(null)
        }
        pendingFileCallback = null
    }

    /**
     * Injiziert die Interceptor-Scripts nach Page Load.
     */
    private fun injectInterceptors() {
        evaluateJavascript(INTERCEPTOR_SCRIPT, null)
    }

    /**
     * Löscht alle gesammelten Daten.
     */
    fun clearCapturedData() {
        _capturedExchanges.value = emptyList()
        _userActions.value = emptyList()
        _pageEvents.value = emptyList()
        pendingRequests.clear()
    }

    /**
     * Gibt alle Exchanges zurück, die mit einer bestimmten Action korreliert sind.
     *
     * Korrelation basiert auf Zeitnähe (innerhalb von 2 Sekunden nach der Action).
     */
    fun getExchangesForAction(action: UserAction, windowMs: Long = 2000): List<CapturedExchange> {
        val actionTime = action.timestamp.toEpochMilliseconds()
        return _capturedExchanges.value.filter { exchange ->
            val exchangeTime = exchange.startedAt.toEpochMilliseconds()
            exchangeTime >= actionTime && exchangeTime <= actionTime + windowMs
        }
    }

    /**
     * JavaScript Interface für Capture-Callbacks.
     *
     * WICHTIG: Alle Methoden haben try-catch um Crashes durch JavaScript-Fehler zu verhindern.
     * Bei großen GraphQL Responses oder speziellen Encodings können sonst OutOfMemory oder
     * Parsing-Fehler auftreten.
     */
    private inner class CaptureJsBridge {

        @JavascriptInterface
        fun captureRequest(
            requestId: String?,
            method: String?,
            url: String?,
            headersJson: String?,
            body: String?
        ) {
            try {
                // Null-Safety: Alle Parameter validieren
                if (requestId.isNullOrBlank() || url.isNullOrBlank()) {
                    android.util.Log.w(TAG, "captureRequest: Missing required params")
                    return
                }

                val headers = parseJsonToMap(headersJson ?: "{}")
                val exchange = CapturedExchange(
                    id = requestId,
                    method = method ?: "GET",
                    url = url,
                    requestHeaders = headers,
                    requestBody = body?.take(MAX_BODY_SIZE), // Request body auch limitieren
                    responseStatus = null,
                    responseHeaders = null,
                    responseBody = null,
                    startedAt = Clock.System.now(),
                    completedAt = null
                )
                pendingRequests[requestId] = exchange
            } catch (e: Exception) {
                android.util.Log.e(TAG, "captureRequest failed: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun captureResponse(
            requestId: String?,
            status: Int,
            headersJson: String?,
            body: String?
        ) {
            try {
                // Null-Safety
                if (requestId.isNullOrBlank()) {
                    android.util.Log.w(TAG, "captureResponse: Missing requestId")
                    return
                }

                val pending = pendingRequests.remove(requestId)
                if (pending == null) {
                    // Kann passieren bei race conditions oder wenn Request nicht erfasst wurde
                    android.util.Log.d(TAG, "captureResponse: No pending request for $requestId")
                    return
                }

                val headers = parseJsonToMap(headersJson ?: "{}")

                // Body sicher limitieren (große GraphQL Responses können OOM verursachen)
                val limitedBody = try {
                    body?.take(MAX_BODY_SIZE)
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e(TAG, "OOM while processing response body, truncating")
                    body?.substring(0, minOf(body.length, 1024 * 1024)) // Fallback auf 1 MB
                }

                val completed = pending.copy(
                    responseStatus = status,
                    responseHeaders = headers,
                    responseBody = limitedBody,
                    completedAt = Clock.System.now()
                )

                _capturedExchanges.value = _capturedExchanges.value + completed
            } catch (e: OutOfMemoryError) {
                android.util.Log.e(TAG, "OOM in captureResponse - clearing some data")
                // Bei OOM: Alte Exchanges löschen um Speicher freizugeben
                val currentList = _capturedExchanges.value
                if (currentList.size > 100) {
                    _capturedExchanges.value = currentList.takeLast(50)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "captureResponse failed: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun captureUserAction(type: String?, target: String?, value: String?) {
            try {
                val actionType = when (type?.lowercase()) {
                    "click" -> ActionType.CLICK
                    "submit" -> ActionType.SUBMIT
                    "input" -> ActionType.INPUT
                    "cookie_set" -> ActionType.INPUT // Cookie-Änderungen als Input behandeln
                    "websocket_message", "websocket_send", "websocket_close" -> ActionType.NAVIGATION
                    "sse_message" -> ActionType.NAVIGATION
                    "form_submit" -> ActionType.SUBMIT
                    "navigation" -> ActionType.NAVIGATION
                    else -> ActionType.CLICK
                }

                val action = UserAction(
                    type = actionType,
                    target = target?.take(500) ?: "unknown", // Target limitieren
                    value = value?.take(1000), // Value limitieren
                    timestamp = Clock.System.now(),
                    pageUrl = _currentUrl.value
                )

                _userActions.value = _userActions.value + action
            } catch (e: Exception) {
                android.util.Log.e(TAG, "captureUserAction failed: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun log(message: String?) {
            // Debug logging from JS - null-safe
            if (!message.isNullOrBlank()) {
                android.util.Log.d(TAG, "[JS] ${message.take(500)}")
            }
        }

        /**
         * Parst JSON-String zu Map.
         * Robustes Parsing das auch bei fehlerhaftem JSON nicht crasht.
         */
        private fun parseJsonToMap(json: String?): Map<String, String> {
            if (json.isNullOrBlank() || json == "{}" || json == "null") {
                return emptyMap()
            }

            return try {
                // Einfacher JSON Parser (ohne externe Dependencies)
                val cleaned = json.trim()
                    .removeSurrounding("{", "}")
                    .takeIf { it.isNotBlank() } ?: return emptyMap()

                cleaned.split(",")
                    .filter { it.contains(":") }
                    .mapNotNull { pair ->
                        try {
                            val parts = pair.split(":", limit = 2)
                            if (parts.size == 2) {
                                val key = parts[0].trim().removeSurrounding("\"")
                                val value = parts[1].trim().removeSurrounding("\"")
                                key to value
                            } else null
                        } catch (e: Exception) {
                            null // Einzelnes Paar ignorieren bei Fehler
                        }
                    }
                    .toMap()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "JSON parse error: ${e.message}")
                emptyMap()
            }
        }
    }

    /**
     * WebViewClient für Page Events und Script Injection.
     */
    private inner class InterceptingWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            _isLoading.value = true
            _currentUrl.value = url

            url?.let {
                val event = PageEvent(
                    url = it,
                    title = null,
                    timestamp = Clock.System.now(),
                    type = PageEventType.STARTED
                )
                _pageEvents.value = _pageEvents.value + event

                // Navigation als User Action tracken
                val action = UserAction(
                    type = ActionType.NAVIGATION,
                    target = it,
                    value = null,
                    timestamp = Clock.System.now(),
                    pageUrl = it
                )
                _userActions.value = _userActions.value + action
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            _isLoading.value = false

            // Interceptors injizieren nach Page Load
            injectInterceptors()

            url?.let {
                val event = PageEvent(
                    url = it,
                    title = view?.title,
                    timestamp = Clock.System.now(),
                    type = PageEventType.FINISHED
                )
                _pageEvents.value = _pageEvents.value + event
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)

            request?.url?.toString()?.let { url ->
                val event = PageEvent(
                    url = url,
                    title = error?.description?.toString(),
                    timestamp = Clock.System.now(),
                    type = PageEventType.ERROR
                )
                _pageEvents.value = _pageEvents.value + event
            }
        }

        /**
         * SSL-Fehler behandeln - WICHTIG für manche Seiten!
         * ACHTUNG: Im Produktionsbetrieb sollte dies dem User angezeigt werden.
         */
        @android.annotation.SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: android.webkit.SslErrorHandler?,
            error: android.net.http.SslError?
        ) {
            // SSL-Fehler loggen
            android.util.Log.w(TAG, "SSL Error: ${error?.primaryError} for ${error?.url}")

            // Im Debug-Modus: SSL-Fehler ignorieren um Traffic zu capturen
            // HINWEIS: Für Produktion sollte hier ein User-Dialog kommen
            handler?.proceed()

            error?.url?.let { url ->
                val event = PageEvent(
                    url = url,
                    title = "SSL Error: ${error.primaryError}",
                    timestamp = Clock.System.now(),
                    type = PageEventType.ERROR
                )
                _pageEvents.value = _pageEvents.value + event
            }
        }

        /**
         * HTTP Auth (Basic/Digest) - manche Seiten brauchen das
         */
        override fun onReceivedHttpAuthRequest(
            view: WebView?,
            handler: android.webkit.HttpAuthHandler?,
            host: String?,
            realm: String?
        ) {
            // Standard-Verhalten: Abbrechen (User muss sich im Browser einloggen)
            handler?.cancel()
        }

        /**
         * Fängt ALLE Navigation-Requests ab (auch Redirects!).
         * Gibt false zurück damit WebView die Navigation durchführt,
         * aber wir loggen sie trotzdem.
         */
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let { req ->
                // Redirect/Navigation als Exchange erfassen
                val exchange = CapturedExchange(
                    method = req.method ?: "GET",
                    url = req.url.toString(),
                    requestHeaders = req.requestHeaders ?: emptyMap(),
                    requestBody = null,
                    responseStatus = null, // Wird durch Page-Load-Event ergänzt
                    responseHeaders = null,
                    responseBody = null,
                    startedAt = Clock.System.now(),
                    completedAt = null
                )

                // Als Navigation-Request markieren (nicht XHR/Fetch)
                val action = UserAction(
                    type = if (req.isRedirect) ActionType.NAVIGATION else ActionType.CLICK,
                    target = req.url.toString(),
                    value = if (req.isRedirect) "redirect" else "navigation",
                    timestamp = Clock.System.now(),
                    pageUrl = _currentUrl.value
                )
                _userActions.value = _userActions.value + action
            }

            // false = WebView führt Navigation durch
            return false
        }
    }

    companion object {
        private const val TAG = "TrafficInterceptWebView"
        private const val BRIDGE_NAME = "FishIT"
        private const val MAX_BODY_SIZE = 20 * 1024 * 1024 // 20 MB - erhöht für große GraphQL Responses
        const val FILE_CHOOSER_REQUEST = 1001

        /**
         * JavaScript für VOLLSTÄNDIGE Traffic-Interception:
         * - XHR (XMLHttpRequest)
         * - Fetch API
         * - WebSocket (Real-time Kommunikation)
         * - Redirects (durch Response-Analyse)
         * - Cookies (document.cookie Hook)
         * - sendBeacon (Analytics)
         * - Form Submissions
         * - User Actions (Click, Submit, Input)
         * - Service Worker fetch events
         *
         * KEIN HttpCanary nötig - die App fängt ALLES selbst ab!
         */
        private const val INTERCEPTOR_SCRIPT = """
(function() {
    if (window.__fishit_injected) return;
    window.__fishit_injected = true;

    const bridge = window.FishIT;
    if (!bridge) {
        console.error('[FishIT] Bridge not found');
        return;
    }

    // Helper: Generate unique ID
    function generateId() {
        return 'req_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    }

    // Helper: Headers to JSON
    function headersToJson(headers) {
        if (!headers) return '{}';
        if (headers instanceof Headers) {
            const obj = {};
            headers.forEach((value, key) => { obj[key] = value; });
            return JSON.stringify(obj);
        }
        if (typeof headers === 'object') {
            return JSON.stringify(headers);
        }
        return '{}';
    }

    // Helper: Check if redirect
    function isRedirectStatus(status) {
        return [301, 302, 303, 307, 308].includes(status);
    }

    // ==================== XHR Interception ====================
    const OriginalXHR = window.XMLHttpRequest;

    function InterceptedXHR() {
        const xhr = new OriginalXHR();
        const requestId = generateId();
        let method = 'GET';
        let url = '';
        let requestHeaders = {};

        // Intercept open()
        const originalOpen = xhr.open;
        xhr.open = function(m, u) {
            method = m;
            url = u;
            return originalOpen.apply(xhr, arguments);
        };

        // Intercept setRequestHeader()
        const originalSetHeader = xhr.setRequestHeader;
        xhr.setRequestHeader = function(key, value) {
            requestHeaders[key] = value;
            return originalSetHeader.apply(xhr, arguments);
        };

        // Intercept send()
        const originalSend = xhr.send;
        xhr.send = function(body) {
            try {
                bridge.captureRequest(
                    requestId,
                    method,
                    url,
                    JSON.stringify(requestHeaders),
                    body ? String(body) : null
                );
            } catch(e) {
                bridge.log('XHR capture error: ' + e.message);
            }

            xhr.addEventListener('load', function() {
                try {
                    const responseHeaders = {};
                    const headerStr = xhr.getAllResponseHeaders();
                    if (headerStr) {
                        headerStr.split('\r\n').forEach(line => {
                            const parts = line.split(': ');
                            if (parts.length === 2) {
                                responseHeaders[parts[0]] = parts[1];
                            }
                        });
                    }

                    // Redirect Detection
                    if (isRedirectStatus(xhr.status)) {
                        responseHeaders['X-FishIT-Redirect'] = 'true';
                        responseHeaders['X-FishIT-RedirectUrl'] = xhr.responseURL || responseHeaders['Location'] || '';
                    }

                    bridge.captureResponse(
                        requestId,
                        xhr.status,
                        JSON.stringify(responseHeaders),
                        xhr.responseText
                    );
                } catch(e) {
                    bridge.log('XHR response capture error: ' + e.message);
                }
            });

            // Error handling
            xhr.addEventListener('error', function() {
                bridge.captureResponse(requestId, 0, '{"X-FishIT-Error": "Network Error"}', null);
            });

            xhr.addEventListener('timeout', function() {
                bridge.captureResponse(requestId, 0, '{"X-FishIT-Error": "Timeout"}', null);
            });

            return originalSend.apply(xhr, arguments);
        };

        return xhr;
    }

    // Alle Properties/Methods von OriginalXHR kopieren
    Object.keys(OriginalXHR).forEach(key => {
        InterceptedXHR[key] = OriginalXHR[key];
    });
    InterceptedXHR.prototype = OriginalXHR.prototype;

    window.XMLHttpRequest = InterceptedXHR;

    // ==================== Fetch Interception ====================
    const originalFetch = window.fetch;

    window.fetch = async function(input, init = {}) {
        const requestId = generateId();
        const url = typeof input === 'string' ? input : (input.url || input.toString());
        const method = init.method || (input.method) || 'GET';

        // Request Headers sammeln
        let reqHeaders = init.headers || {};
        if (input instanceof Request) {
            const h = {};
            input.headers.forEach((v, k) => { h[k] = v; });
            reqHeaders = {...h, ...reqHeaders};
        }

        try {
            bridge.captureRequest(
                requestId,
                method,
                url,
                headersToJson(reqHeaders),
                init.body ? String(init.body) : null
            );
        } catch(e) {
            bridge.log('Fetch capture error: ' + e.message);
        }

        try {
            const response = await originalFetch.apply(this, arguments);
            const clone = response.clone();

            // Response Headers mit Redirect-Info
            const respHeaders = {};
            response.headers.forEach((v, k) => { respHeaders[k] = v; });

            // Redirect Detection (fetch folgt automatisch)
            if (response.redirected) {
                respHeaders['X-FishIT-Redirect'] = 'true';
                respHeaders['X-FishIT-RedirectUrl'] = response.url;
                respHeaders['X-FishIT-OriginalUrl'] = url;
            }

            // Response body lesen (async)
            clone.text().then(body => {
                try {
                    bridge.captureResponse(
                        requestId,
                        response.status,
                        JSON.stringify(respHeaders),
                        body
                    );
                } catch(e) {
                    bridge.log('Fetch response capture error: ' + e.message);
                }
            }).catch(() => {
                // Body nicht lesbar (z.B. Blob)
                bridge.captureResponse(requestId, response.status, JSON.stringify(respHeaders), null);
            });

            return response;
        } catch(e) {
            // Fetch Error
            bridge.captureResponse(requestId, 0, '{"X-FishIT-Error": "' + e.message + '"}', null);
            throw e;
        }
    };

    // ==================== sendBeacon Interception (Analytics) ====================
    if (navigator.sendBeacon) {
        const originalSendBeacon = navigator.sendBeacon.bind(navigator);
        navigator.sendBeacon = function(url, data) {
            const requestId = generateId();
            try {
                bridge.captureRequest(
                    requestId,
                    'POST',
                    url,
                    '{"Content-Type": "application/x-www-form-urlencoded"}',
                    data ? String(data) : null
                );
                // sendBeacon hat keinen Response
                bridge.captureResponse(requestId, 200, '{"X-FishIT-Beacon": "true"}', null);
            } catch(e) {}
            return originalSendBeacon(url, data);
        };
    }

    // ==================== Cookie Tracking ====================
    let lastCookies = document.cookie;

    // Cookie-Änderungen überwachen
    const cookieDescriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie') ||
                             Object.getOwnPropertyDescriptor(HTMLDocument.prototype, 'cookie');

    if (cookieDescriptor && cookieDescriptor.set) {
        Object.defineProperty(document, 'cookie', {
            get: function() {
                return cookieDescriptor.get.call(document);
            },
            set: function(value) {
                try {
                    bridge.captureUserAction('cookie_set', value.split('=')[0], value.substring(0, 200));
                } catch(e) {}
                return cookieDescriptor.set.call(document, value);
            }
        });
    }

    // ==================== Form Submission Interception ====================
    const originalSubmit = HTMLFormElement.prototype.submit;
    HTMLFormElement.prototype.submit = function() {
        const requestId = generateId();
        const form = this;
        const formData = new FormData(form);
        const data = {};
        formData.forEach((value, key) => {
            if (key.toLowerCase().includes('password')) {
                data[key] = '***REDACTED***';
            } else {
                data[key] = value;
            }
        });

        try {
            bridge.captureRequest(
                requestId,
                form.method.toUpperCase() || 'GET',
                form.action || window.location.href,
                '{"Content-Type": "application/x-www-form-urlencoded"}',
                JSON.stringify(data)
            );
            bridge.captureUserAction('form_submit', form.action || form.id || 'form', form.method);
        } catch(e) {}

        return originalSubmit.apply(this, arguments);
    };

    // ==================== User Action Tracking ====================

    // Click Events
    document.addEventListener('click', function(e) {
        const target = e.target;
        let selector = target.tagName.toLowerCase();
        if (target.id) selector += '#' + target.id;
        if (target.className && typeof target.className === 'string') {
            selector += '.' + target.className.split(' ').filter(c => c).join('.');
        }

        // Link-Clicks besonders behandeln
        const link = target.closest('a');
        if (link && link.href) {
            selector = 'a[href="' + link.href.substring(0, 100) + '"]';
        }

        const value = target.textContent ? target.textContent.trim().substring(0, 100) : null;

        try {
            bridge.captureUserAction('click', selector, value);
        } catch(e) {}
    }, true);

    // Form Submit Events
    document.addEventListener('submit', function(e) {
        const form = e.target;
        const action = form.action || window.location.href;

        try {
            bridge.captureUserAction('submit', action, form.method || 'GET');
        } catch(e) {}
    }, true);

    // Input Events (debounced)
    let inputTimeout;
    document.addEventListener('input', function(e) {
        clearTimeout(inputTimeout);
        inputTimeout = setTimeout(function() {
            const target = e.target;
            if (target.type === 'password') return; // Keine Passwörter loggen

            let selector = target.tagName.toLowerCase();
            if (target.name) selector += '[name="' + target.name + '"]';
            else if (target.id) selector += '#' + target.id;

            try {
                bridge.captureUserAction('input', selector, target.value.substring(0, 100));
            } catch(e) {}
        }, 500);
    }, true);

    // ==================== History/Navigation Tracking ====================
    const originalPushState = history.pushState;
    history.pushState = function() {
        try {
            bridge.captureUserAction('navigation', arguments[2] || '', 'pushState');
        } catch(e) {}
        return originalPushState.apply(history, arguments);
    };

    const originalReplaceState = history.replaceState;
    history.replaceState = function() {
        try {
            bridge.captureUserAction('navigation', arguments[2] || '', 'replaceState');
        } catch(e) {}
        return originalReplaceState.apply(history, arguments);
    };

    window.addEventListener('popstate', function(e) {
        try {
            bridge.captureUserAction('navigation', window.location.href, 'popstate');
        } catch(e) {}
    });

    // ==================== WebSocket Interception ====================
    const OriginalWebSocket = window.WebSocket;

    window.WebSocket = function(url, protocols) {
        const wsId = generateId();
        bridge.log('WebSocket connecting: ' + url);

        try {
            bridge.captureRequest(wsId, 'WEBSOCKET', url, '{}', null);
        } catch(e) {}

        const ws = protocols ? new OriginalWebSocket(url, protocols) : new OriginalWebSocket(url);

        ws.addEventListener('open', function() {
            try {
                bridge.captureResponse(wsId, 101, '{"Upgrade": "websocket"}', null);
            } catch(e) {}
        });

        ws.addEventListener('message', function(event) {
            try {
                const data = typeof event.data === 'string' ? event.data.substring(0, 1000) : '[Binary]';
                bridge.captureUserAction('websocket_message', url, data);
            } catch(e) {}
        });

        ws.addEventListener('error', function() {
            try {
                bridge.captureResponse(wsId, 0, '{"X-FishIT-Error": "WebSocket Error"}', null);
            } catch(e) {}
        });

        ws.addEventListener('close', function(event) {
            try {
                bridge.captureUserAction('websocket_close', url, 'Code: ' + event.code);
            } catch(e) {}
        });

        // Intercept send
        const originalSend = ws.send;
        ws.send = function(data) {
            try {
                const msg = typeof data === 'string' ? data.substring(0, 1000) : '[Binary]';
                bridge.captureUserAction('websocket_send', url, msg);
            } catch(e) {}
            return originalSend.apply(ws, arguments);
        };

        return ws;
    };

    // Copy static properties
    window.WebSocket.CONNECTING = OriginalWebSocket.CONNECTING;
    window.WebSocket.OPEN = OriginalWebSocket.OPEN;
    window.WebSocket.CLOSING = OriginalWebSocket.CLOSING;
    window.WebSocket.CLOSED = OriginalWebSocket.CLOSED;
    window.WebSocket.prototype = OriginalWebSocket.prototype;

    // ==================== EventSource (Server-Sent Events) ====================
    if (window.EventSource) {
        const OriginalEventSource = window.EventSource;

        window.EventSource = function(url, config) {
            const sseId = generateId();
            bridge.log('EventSource connecting: ' + url);

            try {
                bridge.captureRequest(sseId, 'SSE', url, '{}', null);
            } catch(e) {}

            const es = new OriginalEventSource(url, config);

            es.addEventListener('open', function() {
                try {
                    bridge.captureResponse(sseId, 200, '{"Content-Type": "text/event-stream"}', null);
                } catch(e) {}
            });

            es.addEventListener('message', function(event) {
                try {
                    bridge.captureUserAction('sse_message', url, event.data.substring(0, 500));
                } catch(e) {}
            });

            es.addEventListener('error', function() {
                try {
                    bridge.captureResponse(sseId, 0, '{"X-FishIT-Error": "SSE Error"}', null);
                } catch(e) {}
            });

            return es;
        };

        window.EventSource.prototype = OriginalEventSource.prototype;
    }

    bridge.log('FishIT interceptors injected successfully - capturing ALL traffic including WebSockets');
})();
"""
    }
}
