package dev.fishit.mapper.android.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.webkit.*
import dev.fishit.mapper.android.webview.AuthAwareCookieManager
import dev.fishit.mapper.android.webview.WebViewDiagnosticsManager
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

    /**
     * Cookie-Event für vollständiges Cookie-Tracking.
     *
     * Erfasst WANN, WO und WIE Cookies gesetzt/verwendet werden:
     * - SET: Cookie wurde durch Set-Cookie Header oder document.cookie gesetzt
     * - SENT: Cookie wurde bei einem Request mitgesendet
     * - DELETED: Cookie wurde gelöscht (expires in past)
     */
    data class CookieEvent(
        val id: String = UUID.randomUUID().toString(),
        val type: CookieEventType,
        val name: String,
        val value: String?,
        val domain: String?,
        val path: String?,
        val expires: String?,
        val secure: Boolean,
        val httpOnly: Boolean,
        val sameSite: String?,
        val timestamp: Instant,
        val sourceUrl: String,
        val sourceType: CookieSourceType,
        val relatedRequestId: String? = null // Korrelation mit Exchange
    )

    enum class CookieEventType {
        SET,      // Cookie wurde gesetzt
        SENT,     // Cookie wurde bei Request mitgesendet
        DELETED,  // Cookie wurde gelöscht
        MODIFIED  // Cookie wurde geändert
    }

    enum class CookieSourceType {
        RESPONSE_HEADER,  // Set-Cookie Header in Response
        JAVASCRIPT,       // document.cookie = "..."
        NAVIGATION        // Cookie bei Navigation mitgesendet
    }

    // State Flows für Beobachter
    private val _capturedExchanges = MutableStateFlow<List<CapturedExchange>>(emptyList())
    val capturedExchanges: StateFlow<List<CapturedExchange>> = _capturedExchanges.asStateFlow()

    private val _userActions = MutableStateFlow<List<UserAction>>(emptyList())
    val userActions: StateFlow<List<UserAction>> = _userActions.asStateFlow()

    private val _pageEvents = MutableStateFlow<List<PageEvent>>(emptyList())
    val pageEvents: StateFlow<List<PageEvent>> = _pageEvents.asStateFlow()

    private val _cookieEvents = MutableStateFlow<List<CookieEvent>>(emptyList())
    val cookieEvents: StateFlow<List<CookieEvent>> = _cookieEvents.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    // Pending Requests (warten auf Response)
    private val pendingRequests = mutableMapOf<String, CapturedExchange>()

    /**
     * Steuert ob Traffic-Interception aktiv ist.
     * Wenn false, wird KEIN JavaScript injiziert und keine Requests erfasst.
     * Das verhindert Crashes auf problematischen Seiten (z.B. GraphQL mit spezieller JS-Engine).
     */
    private val _interceptionEnabled = MutableStateFlow(false)
    val interceptionEnabled: StateFlow<Boolean> = _interceptionEnabled.asStateFlow()

    /**
     * Aktiviert/Deaktiviert Traffic-Interception.
     * Bei Deaktivierung wird die Seite neu geladen um die JS-Hooks zu entfernen.
     */
    fun setInterceptionEnabled(enabled: Boolean, reloadPage: Boolean = false) {
        _interceptionEnabled.value = enabled
        if (reloadPage && !enabled) {
            // Seite neu laden ohne Interception
            reload()
        } else if (enabled) {
            // Interception aktivieren - Seite neu laden um Hooks zu injizieren
            injectInterceptors()
        }
    }

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

        // WICHTIG für WebAuthn: Multiple Windows Support aktivieren
        // WebAuthn-Dialoge benötigen dies, sonst erscheint ein grauer Overlay
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        android.util.Log.d(TAG, "Multiple windows support enabled for WebAuthn/OAuth dialogs")

        // WICHTIG: Mixed Content erlauben (HTTPS-Seiten mit HTTP-Ressourcen)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Third-Party Cookies erlauben (für Login-Sessions und OAuth)
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@TrafficInterceptWebView, true)
            android.util.Log.d(TAG, "Third-party cookies enabled for OAuth/SSO support")
        }

        // OAuth-spezifische Cookie-Konfiguration
        AuthAwareCookieManager.configureWebViewForOAuth(this)

        // Wichtige Session-Domains registrieren
        AuthAwareCookieManager.registerSessionDomain("kolping-hochschule.de")
        AuthAwareCookieManager.registerSessionDomain("cms.kolping-hochschule.de")
        AuthAwareCookieManager.registerSessionDomain("login.microsoftonline.com")
        AuthAwareCookieManager.registerSessionDomain("login.windows.net")

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
        
        // Update diagnostics data with current WebView configuration
        WebViewDiagnosticsManager.updateDiagnosticsData(context, this)
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
            WebViewDiagnosticsManager.logConsoleMessage("ALERT", message ?: "", url)
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
            WebViewDiagnosticsManager.logConsoleMessage("CONFIRM", message ?: "", url)
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
            WebViewDiagnosticsManager.logConsoleMessage("PROMPT", message ?: "", url)
            result?.confirm(defaultValue ?: "")
            return true
        }

        // Permission Request (Kamera, Mikrofon, etc.)
        override fun onPermissionRequest(request: PermissionRequest?) {
            android.util.Log.d(TAG, "Permission request: ${request?.resources?.joinToString()}")
            WebViewDiagnosticsManager.logConsoleMessage(
                "PERMISSION",
                "Permission requested: ${request?.resources?.joinToString()}",
                request?.origin?.toString()
            )
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
            val message = consoleMessage?.message() ?: ""
            val sourceUrl = consoleMessage?.sourceId()
            
            android.util.Log.d(TAG, "[JS $level] $message")
            
            // Log to diagnostics manager
            WebViewDiagnosticsManager.logConsoleMessage(level, message, sourceUrl)
            
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
        _cookieEvents.value = emptyList()
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
     * Gibt alle Cookie-Events für einen bestimmten Exchange zurück.
     * Ermöglicht Korrelation: Welche Cookies wurden bei diesem Request gesetzt?
     */
    fun getCookieEventsForExchange(exchangeId: String): List<CookieEvent> {
        return _cookieEvents.value.filter { it.relatedRequestId == exchangeId }
    }

    /**
     * Extrahiert Set-Cookie Headers aus Response und erstellt CookieEvents.
     * Wird aufgerufen wenn eine Response erfasst wird.
     */
    private fun extractSetCookieHeaders(headers: Map<String, String>, sourceUrl: String, requestId: String) {
        try {
            // Set-Cookie Header können mehrere Cookies enthalten
            val setCookieHeaders = headers.filterKeys { key ->
                key.equals("Set-Cookie", ignoreCase = true) ||
                key.equals("set-cookie", ignoreCase = true)
            }

            setCookieHeaders.values.forEach { cookieValue ->
                parseCookieString(cookieValue, sourceUrl, requestId)
            }

            // Auch aus dem Android CookieManager die aktuellen Cookies für die URL holen
            // um HttpOnly Cookies zu erfassen, die im JS nicht sichtbar sind
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie(sourceUrl)
                if (!cookies.isNullOrBlank()) {
                    android.util.Log.d(TAG, "CookieManager cookies for $sourceUrl: ${cookies.take(200)}")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to get cookies from CookieManager: ${e.message}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "extractSetCookieHeaders failed: ${e.message}", e)
        }
    }

    /**
     * Parst einen Set-Cookie Header-Wert und erstellt ein CookieEvent.
     *
     * Beispiel: "sessionId=abc123; Path=/; HttpOnly; Secure; SameSite=Strict"
     */
    private fun parseCookieString(cookieString: String, sourceUrl: String, requestId: String) {
        try {
            val parts = cookieString.split(";").map { it.trim() }
            if (parts.isEmpty()) return

            // Erstes Teil ist Name=Value
            val nameValue = parts[0].split("=", limit = 2)
            if (nameValue.isEmpty()) return

            val name = nameValue[0].trim()
            val value = if (nameValue.size > 1) nameValue[1].trim() else ""

            // Attribute parsen
            var domain: String? = null
            var path: String? = null
            var expires: String? = null
            var secure = false
            var httpOnly = false
            var sameSite: String? = null

            parts.drop(1).forEach { attr ->
                val attrParts = attr.split("=", limit = 2)
                val attrName = attrParts[0].trim().lowercase()
                val attrValue = if (attrParts.size > 1) attrParts[1].trim() else ""

                when (attrName) {
                    "domain" -> domain = attrValue
                    "path" -> path = attrValue
                    "expires" -> expires = attrValue
                    "max-age" -> expires = "max-age: $attrValue"
                    "secure" -> secure = true
                    "httponly" -> httpOnly = true
                    "samesite" -> sameSite = attrValue
                }
            }

            // Cookie-Event erstellen
            val eventType = if (expires?.contains("-") == true && expires?.startsWith("Thu, 01 Jan 1970") == true) {
                CookieEventType.DELETED
            } else {
                CookieEventType.SET
            }

            val event = CookieEvent(
                type = eventType,
                name = name,
                value = value.take(1000),
                domain = domain,
                path = path,
                expires = expires,
                secure = secure,
                httpOnly = httpOnly,
                sameSite = sameSite,
                timestamp = Clock.System.now(),
                sourceUrl = sourceUrl,
                sourceType = CookieSourceType.RESPONSE_HEADER,
                relatedRequestId = requestId
            )

            _cookieEvents.value = _cookieEvents.value + event
            android.util.Log.d(TAG, "Cookie from Set-Cookie header: $name (domain=$domain, httpOnly=$httpOnly, secure=$secure)")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "parseCookieString failed: ${e.message}", e)
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

                // Set-Cookie Headers extrahieren und als CookieEvent erfassen
                extractSetCookieHeaders(headers, pending.url, requestId)
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

        /**
         * Erfasst Cookie-Änderungen aus JavaScript (document.cookie).
         *
         * @param name Cookie-Name
         * @param value Cookie-Wert
         * @param domain Cookie-Domain (optional)
         * @param path Cookie-Path (optional)
         * @param expires Cookie-Ablaufdatum (optional)
         * @param secure Ist das Cookie nur über HTTPS?
         * @param sameSite SameSite-Attribut (optional)
         * @param eventType "set", "deleted", "modified"
         */
        @JavascriptInterface
        fun captureCookie(
            name: String?,
            value: String?,
            domain: String?,
            path: String?,
            expires: String?,
            secure: Boolean,
            sameSite: String?,
            eventType: String?
        ) {
            try {
                if (name.isNullOrBlank()) return

                val cookieEventType = when (eventType?.lowercase()) {
                    "deleted" -> CookieEventType.DELETED
                    "modified" -> CookieEventType.MODIFIED
                    else -> CookieEventType.SET
                }

                val event = CookieEvent(
                    type = cookieEventType,
                    name = name,
                    value = value?.take(1000), // Werte limitieren
                    domain = domain,
                    path = path,
                    expires = expires,
                    secure = secure,
                    httpOnly = false, // JS kann HttpOnly cookies nicht sehen
                    sameSite = sameSite,
                    timestamp = Clock.System.now(),
                    sourceUrl = _currentUrl.value ?: "unknown",
                    sourceType = CookieSourceType.JAVASCRIPT,
                    relatedRequestId = null
                )

                _cookieEvents.value = _cookieEvents.value + event
                android.util.Log.d(TAG, "Cookie captured from JS: $name=$value (${cookieEventType.name})")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "captureCookie failed: ${e.message}", e)
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

            // WICHTIG: Cookies persistieren nach OAuth-Redirects
            // Das stellt sicher, dass Session-Tokens nicht verloren gehen
            url?.let { finishedUrl ->
                if (AuthAwareCookieManager.isOAuthRedirect(finishedUrl) ||
                    AuthAwareCookieManager.isOAuthUrl(finishedUrl)) {
                    android.util.Log.d(TAG, "OAuth page finished, persisting cookies")
                    AuthAwareCookieManager.persistCookies()
                }
            }

            // Interceptors NUR injizieren wenn:
            // 1. Interception aktiviert ist
            // 2. Die Seite KEINE OAuth-Seite ist (verhindert Auth-Probleme)
            val shouldInject = _interceptionEnabled.value &&
                url?.let { !AuthAwareCookieManager.isOAuthUrl(it) } != false

            if (shouldInject) {
                try {
                    injectInterceptors()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "JS Injection failed: ${e.message}", e)
                }
            } else if (_interceptionEnabled.value) {
                android.util.Log.d(TAG, "Skipping JS injection on OAuth page: $url")
            }

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

            val url = request?.url?.toString()
            val errorDescription = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                error?.description?.toString() ?: "Unknown error"
            } else {
                "Unknown error"
            }
            val errorCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                error?.errorCode
            } else {
                null
            }

            android.util.Log.e(TAG, "WebView error: $errorDescription for $url (code: $errorCode)")
            
            // Log to diagnostics manager
            WebViewDiagnosticsManager.logError(
                type = WebViewDiagnosticsManager.ErrorType.WEB_RESOURCE_ERROR,
                description = errorDescription,
                failingUrl = url,
                errorCode = errorCode
            )

            url?.let {
                val event = PageEvent(
                    url = it,
                    title = errorDescription,
                    timestamp = Clock.System.now(),
                    type = PageEventType.ERROR
                )
                _pageEvents.value = _pageEvents.value + event
            }
        }
        
        /**
         * HTTP-Fehler behandeln (404, 500, etc.)
         */
        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            
            val url = request?.url?.toString()
            val statusCode = errorResponse?.statusCode ?: 0
            val reasonPhrase = errorResponse?.reasonPhrase ?: "Unknown"
            
            android.util.Log.w(TAG, "HTTP Error: $statusCode $reasonPhrase for $url")
            
            // Log to diagnostics manager
            WebViewDiagnosticsManager.logError(
                type = WebViewDiagnosticsManager.ErrorType.HTTP_ERROR,
                description = "$statusCode $reasonPhrase",
                failingUrl = url,
                errorCode = statusCode
            )
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
            val errorType = when (error?.primaryError) {
                android.net.http.SslError.SSL_NOTYETVALID -> "Certificate not yet valid"
                android.net.http.SslError.SSL_EXPIRED -> "Certificate expired"
                android.net.http.SslError.SSL_IDMISMATCH -> "Certificate hostname mismatch"
                android.net.http.SslError.SSL_UNTRUSTED -> "Certificate authority not trusted"
                android.net.http.SslError.SSL_DATE_INVALID -> "Certificate date invalid"
                android.net.http.SslError.SSL_INVALID -> "Generic SSL error"
                else -> "Unknown SSL error"
            }
            
            // SSL-Fehler loggen
            android.util.Log.w(TAG, "SSL Error: $errorType (${error?.primaryError}) for ${error?.url}")
            
            // Log to diagnostics manager
            WebViewDiagnosticsManager.logError(
                type = WebViewDiagnosticsManager.ErrorType.SSL_ERROR,
                description = errorType,
                failingUrl = error?.url,
                errorCode = error?.primaryError
            )

            // Im Debug-Modus: SSL-Fehler ignorieren um Traffic zu capturen
            // HINWEIS: Für Produktion sollte hier ein User-Dialog kommen
            handler?.proceed()

            error?.url?.let { url ->
                val event = PageEvent(
                    url = url,
                    title = "SSL Error: $errorType",
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
         *
         * WICHTIG für OAuth:
         * - OAuth-Redirects MÜSSEN durchgelassen werden (return false)
         * - Cookies werden automatisch mitgesendet
         * - JS-Injection wird auf OAuth-Seiten übersprungen
         */
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let { req ->
                val urlString = req.url.toString()

                // OAuth-URLs erkennen und speziell behandeln
                if (AuthAwareCookieManager.isOAuthUrl(urlString) ||
                    AuthAwareCookieManager.isOAuthRedirect(urlString)) {
                    android.util.Log.d(TAG, "OAuth redirect detected: $urlString")

                    // Cookies vor dem Redirect persistieren
                    AuthAwareCookieManager.persistCookies()

                    // WICHTIG: return false damit WebView den Redirect durchführt
                    // NIEMALS true zurückgeben, das würde den OAuth-Flow unterbrechen!
                    return false
                }

                // Redirect/Navigation als Exchange erfassen
                val exchange = CapturedExchange(
                    method = req.method ?: "GET",
                    url = urlString,
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
                    target = urlString,
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
         * - User Actions (Click, Submit, Input)
         *
         * ROBUST VERSION: Verzögerte Injection, Graceful Degradation,
         * Body-Limits im JS, keine Konflikte mit modernen SPAs/GraphQL.
         */
        private const val INTERCEPTOR_SCRIPT = """
(function() {
    'use strict';

    // Verhindere doppelte Injection
    if (window.__fishit_injected) return;
    window.__fishit_injected = true;

    var bridge = window.FishIT;
    if (!bridge) {
        console.warn('[FishIT] Bridge not found, retrying...');
        // Retry nach 100ms
        setTimeout(function() {
            if (window.FishIT) {
                window.__fishit_injected = false;
                eval(arguments.callee.toString() + '()');
            }
        }, 100);
        return;
    }

    // ==================== KONFIGURATION ====================
    var MAX_BODY_SIZE = 5 * 1024 * 1024; // 5 MB - im JS limitieren
    var SAFE_MODE = true; // Bei Fehlern original Funktionen nutzen

    // ==================== HELPER FUNKTIONEN ====================
    function generateId() {
        return 'req_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    }

    function safeStringify(obj) {
        try {
            return JSON.stringify(obj);
        } catch(e) {
            return '{}';
        }
    }

    function limitBody(body) {
        if (!body) return null;
        try {
            var str = typeof body === 'string' ? body : String(body);
            return str.length > MAX_BODY_SIZE ? str.substring(0, MAX_BODY_SIZE) + '...[TRUNCATED]' : str;
        } catch(e) {
            return null;
        }
    }

    function headersToJson(headers) {
        try {
            if (!headers) return '{}';
            if (headers instanceof Headers) {
                var obj = {};
                headers.forEach(function(value, key) { obj[key] = value; });
                return safeStringify(obj);
            }
            if (typeof headers === 'object') {
                return safeStringify(headers);
            }
        } catch(e) {}
        return '{}';
    }

    function safeBridgeCall(fn) {
        try {
            fn();
        } catch(e) {
            try { bridge.log('Bridge error: ' + e.message); } catch(e2) {}
        }
    }

    // ==================== COOKIE TRACKING ====================
    // Überwacht document.cookie Änderungen für vollständiges Cookie-Tracking
    try {
        var cookieDescriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie') ||
                               Object.getOwnPropertyDescriptor(HTMLDocument.prototype, 'cookie');

        if (cookieDescriptor && cookieDescriptor.set) {
            var originalCookieSetter = cookieDescriptor.set;
            var lastCookieValue = document.cookie;

            Object.defineProperty(document, 'cookie', {
                get: function() {
                    return cookieDescriptor.get.call(document);
                },
                set: function(value) {
                    // Cookie setzen
                    originalCookieSetter.call(document, value);

                    // Cookie parsen und an Bridge senden
                    safeBridgeCall(function() {
                        try {
                            var parts = value.split(';');
                            var nameValue = parts[0].split('=');
                            if (nameValue.length < 2) return;

                            var name = nameValue[0].trim();
                            var cookieValue = nameValue.slice(1).join('=').trim();

                            var domain = null;
                            var path = null;
                            var expires = null;
                            var secure = false;
                            var sameSite = null;

                            for (var i = 1; i < parts.length; i++) {
                                var attr = parts[i].trim();
                                var attrParts = attr.split('=');
                                var attrName = attrParts[0].trim().toLowerCase();
                                var attrValue = attrParts.length > 1 ? attrParts[1].trim() : '';

                                if (attrName === 'domain') domain = attrValue;
                                else if (attrName === 'path') path = attrValue;
                                else if (attrName === 'expires') expires = attrValue;
                                else if (attrName === 'max-age') expires = 'max-age:' + attrValue;
                                else if (attrName === 'secure') secure = true;
                                else if (attrName === 'samesite') sameSite = attrValue;
                            }

                            // Prüfen ob Cookie gelöscht wird (expires in Vergangenheit)
                            var eventType = 'set';
                            if (expires && (expires.indexOf('1970') !== -1 || expires.indexOf('max-age:0') !== -1 || expires.indexOf('max-age:-') !== -1)) {
                                eventType = 'deleted';
                            }

                            bridge.captureCookie(name, cookieValue, domain, path, expires, secure, sameSite, eventType);
                        } catch(e) {
                            bridge.log('Cookie parse error: ' + e.message);
                        }
                    });

                    return value;
                },
                configurable: true
            });

            bridge.log('Cookie tracking installed');
        }
    } catch(e) {
        try { bridge.log('Cookie tracking failed: ' + e.message); } catch(e2) {}
    }

    // ==================== XHR Interception (SAFE) ====================
    try {
        var OriginalXHR = window.XMLHttpRequest;

        function InterceptedXHR() {
            var xhr = new OriginalXHR();
            var requestId = generateId();
            var method = 'GET';
            var url = '';
            var requestHeaders = {};
            var captured = false;

            // Intercept open() - SAFE
            var originalOpen = xhr.open;
            xhr.open = function(m, u) {
                try {
                    method = m || 'GET';
                    url = u || '';
                } catch(e) {}
                return originalOpen.apply(xhr, arguments);
            };

            // Intercept setRequestHeader() - SAFE
            var originalSetHeader = xhr.setRequestHeader;
            xhr.setRequestHeader = function(key, value) {
                try {
                    requestHeaders[key] = value;
                } catch(e) {}
                return originalSetHeader.apply(xhr, arguments);
            };

            // Intercept send() - SAFE
            var originalSend = xhr.send;
            xhr.send = function(body) {
                if (!captured) {
                    captured = true;
                    safeBridgeCall(function() {
                        bridge.captureRequest(
                            requestId,
                            method,
                            url,
                            safeStringify(requestHeaders),
                            limitBody(body)
                        );
                    });
                }

                // Response Handler
                xhr.addEventListener('load', function() {
                    safeBridgeCall(function() {
                        var responseHeaders = {};
                        try {
                            var headerStr = xhr.getAllResponseHeaders();
                            if (headerStr) {
                                headerStr.split('\r\n').forEach(function(line) {
                                    var parts = line.split(': ');
                                    if (parts.length === 2) {
                                        responseHeaders[parts[0]] = parts[1];
                                    }
                                });
                            }
                        } catch(e) {}

                        var responseBody = null;
                        try {
                            responseBody = limitBody(xhr.responseText);
                        } catch(e) {}

                        bridge.captureResponse(
                            requestId,
                            xhr.status || 0,
                            safeStringify(responseHeaders),
                            responseBody
                        );
                    });
                });

                xhr.addEventListener('error', function() {
                    safeBridgeCall(function() {
                        bridge.captureResponse(requestId, 0, '{"X-FishIT-Error":"Network Error"}', null);
                    });
                });

                return originalSend.apply(xhr, arguments);
            };

            return xhr;
        }

        // Properties kopieren
        Object.keys(OriginalXHR).forEach(function(key) {
            try { InterceptedXHR[key] = OriginalXHR[key]; } catch(e) {}
        });
        InterceptedXHR.prototype = OriginalXHR.prototype;
        window.XMLHttpRequest = InterceptedXHR;

    } catch(e) {
        try { bridge.log('XHR interception failed: ' + e.message); } catch(e2) {}
    }

    // ==================== Fetch Interception (SAFE) ====================
    try {
        var originalFetch = window.fetch;
        if (originalFetch) {
            window.fetch = function(input, init) {
                var requestId = generateId();
                var url = '';
                var method = 'GET';

                try {
                    url = typeof input === 'string' ? input : (input && input.url ? input.url : String(input));
                    method = (init && init.method) || (input && input.method) || 'GET';
                } catch(e) {}

                // Request capturen
                safeBridgeCall(function() {
                    var reqHeaders = {};
                    try {
                        reqHeaders = (init && init.headers) || {};
                        if (input instanceof Request && input.headers) {
                            input.headers.forEach(function(v, k) { reqHeaders[k] = v; });
                        }
                    } catch(e) {}

                    bridge.captureRequest(
                        requestId,
                        method,
                        url,
                        headersToJson(reqHeaders),
                        limitBody(init && init.body)
                    );
                });

                // Original Fetch aufrufen
                return originalFetch.apply(this, arguments).then(function(response) {
                    // Response capturen (async, non-blocking)
                    try {
                        var clone = response.clone();
                        var respHeaders = {};
                        response.headers.forEach(function(v, k) { respHeaders[k] = v; });

                        if (response.redirected) {
                            respHeaders['X-FishIT-Redirect'] = 'true';
                            respHeaders['X-FishIT-RedirectUrl'] = response.url;
                        }

                        clone.text().then(function(body) {
                            safeBridgeCall(function() {
                                bridge.captureResponse(
                                    requestId,
                                    response.status,
                                    safeStringify(respHeaders),
                                    limitBody(body)
                                );
                            });
                        }).catch(function() {
                            safeBridgeCall(function() {
                                bridge.captureResponse(requestId, response.status, safeStringify(respHeaders), null);
                            });
                        });
                    } catch(e) {}

                    return response;
                }).catch(function(e) {
                    safeBridgeCall(function() {
                        bridge.captureResponse(requestId, 0, '{"X-FishIT-Error":"' + (e.message || 'Fetch Error') + '"}', null);
                    });
                    throw e;
                });
            };
        }
    } catch(e) {
        try { bridge.log('Fetch interception failed: ' + e.message); } catch(e2) {}
    }

    // ==================== sendBeacon (SAFE) ====================
    try {
        if (navigator.sendBeacon) {
            var originalSendBeacon = navigator.sendBeacon.bind(navigator);
            navigator.sendBeacon = function(url, data) {
                var requestId = generateId();
                safeBridgeCall(function() {
                    bridge.captureRequest(requestId, 'POST', url, '{"X-FishIT-Beacon":"true"}', limitBody(data));
                    bridge.captureResponse(requestId, 200, '{}', null);
                });
                return originalSendBeacon(url, data);
            };
        }
    } catch(e) {}

    // ==================== User Actions (SAFE, MINIMAL) ====================
    try {
        // Click Events - einfach und sicher
        document.addEventListener('click', function(e) {
            safeBridgeCall(function() {
                var target = e.target;
                var selector = target.tagName ? target.tagName.toLowerCase() : 'unknown';
                try {
                    if (target.id) selector += '#' + target.id;
                    if (target.className && typeof target.className === 'string') {
                        selector += '.' + target.className.split(' ').slice(0, 3).join('.');
                    }
                    var link = target.closest ? target.closest('a') : null;
                    if (link && link.href) {
                        selector = 'a[href]';
                    }
                } catch(e) {}
                var value = target.textContent ? target.textContent.trim().substring(0, 50) : null;
                bridge.captureUserAction('click', selector.substring(0, 200), value);
            });
        }, true);

        // Form Submit Events
        document.addEventListener('submit', function(e) {
            safeBridgeCall(function() {
                var form = e.target;
                var action = form.action || window.location.href;
                bridge.captureUserAction('submit', action.substring(0, 200), form.method || 'GET');
            });
        }, true);

        // Input Events (debounced)
        var inputTimeout;
        document.addEventListener('input', function(e) {
            clearTimeout(inputTimeout);
            inputTimeout = setTimeout(function() {
                var target = e.target;
                if (target.type === 'password') return;
                safeBridgeCall(function() {
                    var selector = target.tagName ? target.tagName.toLowerCase() : 'input';
                    if (target.name) selector += '[name="' + target.name + '"]';
                    else if (target.id) selector += '#' + target.id;
                    var value = target.value ? target.value.substring(0, 50) : '';
                    bridge.captureUserAction('input', selector.substring(0, 200), value);
                });
            }, 500);
        }, true);
    } catch(e) {}

    // ==================== History/Navigation (SAFE) ====================
    try {
        var originalPushState = history.pushState;
        history.pushState = function() {
            safeBridgeCall(function() {
                bridge.captureUserAction('navigation', arguments[2] || window.location.href, 'pushState');
            });
            return originalPushState.apply(history, arguments);
        };

        var originalReplaceState = history.replaceState;
        history.replaceState = function() {
            safeBridgeCall(function() {
                bridge.captureUserAction('navigation', arguments[2] || window.location.href, 'replaceState');
            });
            return originalReplaceState.apply(history, arguments);
        };
    } catch(e) {}

    // ==================== WebSocket (OPTIONAL, kann deaktiviert werden) ====================
    try {
        var OriginalWebSocket = window.WebSocket;
        if (OriginalWebSocket) {
            window.WebSocket = function(url, protocols) {
                var wsId = generateId();
                safeBridgeCall(function() {
                    bridge.captureRequest(wsId, 'WEBSOCKET', url, '{}', null);
                });

                var ws = protocols ? new OriginalWebSocket(url, protocols) : new OriginalWebSocket(url);

                ws.addEventListener('open', function() {
                    safeBridgeCall(function() {
                        bridge.captureResponse(wsId, 101, '{"Upgrade":"websocket"}', null);
                    });
                });

                ws.addEventListener('message', function(event) {
                    safeBridgeCall(function() {
                        var data = typeof event.data === 'string' ? event.data.substring(0, 500) : '[Binary]';
                        bridge.captureUserAction('websocket_message', url.substring(0, 200), data);
                    });
                });

                ws.addEventListener('error', function() {
                    safeBridgeCall(function() {
                        bridge.captureResponse(wsId, 0, '{"X-FishIT-Error":"WebSocket Error"}', null);
                    });
                });

                // Send intercepten
                var originalSend = ws.send;
                ws.send = function(data) {
                    safeBridgeCall(function() {
                        var msg = typeof data === 'string' ? data.substring(0, 500) : '[Binary]';
                        bridge.captureUserAction('websocket_send', url.substring(0, 200), msg);
                    });
                    return originalSend.apply(ws, arguments);
                };

                return ws;
            };

            // Static properties kopieren
            window.WebSocket.CONNECTING = OriginalWebSocket.CONNECTING;
            window.WebSocket.OPEN = OriginalWebSocket.OPEN;
            window.WebSocket.CLOSING = OriginalWebSocket.CLOSING;
            window.WebSocket.CLOSED = OriginalWebSocket.CLOSED;
            window.WebSocket.prototype = OriginalWebSocket.prototype;
        }
    } catch(e) {
        try { bridge.log('WebSocket interception skipped'); } catch(e2) {}
    }

    try { bridge.log('FishIT interceptors injected (safe mode)'); } catch(e) {}
})();
"""
    }
}
