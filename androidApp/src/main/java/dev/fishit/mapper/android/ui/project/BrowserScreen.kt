package dev.fishit.mapper.android.ui.project

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.fishit.mapper.android.webview.AuthAwareCookieManager
import dev.fishit.mapper.android.webview.JavaScriptBridge
import dev.fishit.mapper.android.webview.TrackingScript
import dev.fishit.mapper.android.webview.WebViewDiagnosticsManager
import dev.fishit.mapper.contract.ConsoleLevel
import dev.fishit.mapper.contract.ConsoleMessageEvent
import dev.fishit.mapper.contract.NavigationEvent
import dev.fishit.mapper.contract.ProjectMeta
import dev.fishit.mapper.contract.RecorderEvent
import dev.fishit.mapper.contract.ResourceKind
import dev.fishit.mapper.contract.ResourceRequestEvent
import dev.fishit.mapper.engine.IdGenerator
import kotlinx.datetime.Clock

private const val TAG = "BrowserScreen"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    projectMeta: ProjectMeta?,
    isRecording: Boolean,
    liveEvents: List<RecorderEvent>,
    onStartRecording: (initialUrl: String) -> Unit,
    onStopRecording: (finalUrl: String?) -> Unit,
    onRecorderEvent: (RecorderEvent) -> Unit
) {
    val context = LocalContext.current
    val recordingState by rememberUpdatedState(isRecording)
    val onRecorderEventState by rememberUpdatedState(onRecorderEvent)

    // Use project start URL or a sensible default
    val defaultUrl = projectMeta?.startUrl?.takeIf { it.isNotBlank() } ?: "https://www.google.com"
    var urlText by remember(projectMeta?.id?.value) { mutableStateOf(defaultUrl) }

    // Keep a stable WebView instance across recompositions
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("URL") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = {
                val wv = webViewHolder[0]
                if (wv != null) wv.loadUrl(urlText.trim())
            }) {
                Text("Go")
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (!isRecording) onStartRecording(urlText.trim())
                    else onStopRecording(webViewHolder[0]?.url)
                }
            ) {
                Text(if (isRecording) "Stop" else "Record")
            }

            Text("Events: ${liveEvents.size}")
        }

        Spacer(Modifier.height(4.dp))

        AndroidView(
            modifier = Modifier
                .fillMaxSize(),
            factory = {
                WebView(context).apply {
                    // Initialize diagnostics
                    WebViewDiagnosticsManager.initialize(context)
                    
                    // WICHTIG: Focus-Handling für Tastatur
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
                        // WICHTIG: false zurückgeben, damit WebView das Event verarbeitet
                        false
                    }

                    requestFocus()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.userAgentString = settings.userAgentString + " FishIT-Mapper/0.1"

                    // Enable debugging for WebView
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }

                    // Additional settings for better compatibility
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                    // Enable zoom controls (can help with debugging)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false

                    // WICHTIG für WebAuthn und Dialoge:
                    // Multiple Windows Support aktivieren, damit WebAuthn-Dialoge funktionieren
                    settings.setSupportMultipleWindows(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true

                    // Database für WebAuthn Credentials
                    settings.databaseEnabled = true

                    // OAuth Cookie Support konfigurieren
                    AuthAwareCookieManager.configureWebViewForOAuth(this)
                    AuthAwareCookieManager.registerSessionDomain("kolping-hochschule.de")
                    AuthAwareCookieManager.registerSessionDomain("cms.kolping-hochschule.de")
                    AuthAwareCookieManager.registerSessionDomain("app-kolping-prod-gateway.azurewebsites.net")

                    val mainHandler = Handler(Looper.getMainLooper())

                    webViewClient = object : WebViewClient() {
                        private var lastUrl: String? = null

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val urlString = request?.url?.toString()

                            // OAuth URLs nicht überschreiben - WebView soll sie normal verarbeiten
                            if (urlString != null && AuthAwareCookieManager.isOAuthUrl(urlString)) {
                                Log.d(TAG, "OAuth URL detected, allowing WebView to handle: $urlString")
                                AuthAwareCookieManager.persistCookies()
                                return false
                            }

                            // OAuth Redirects erkennen und Cookies speichern
                            if (urlString != null && AuthAwareCookieManager.isOAuthRedirect(urlString)) {
                                Log.d(TAG, "OAuth redirect detected: $urlString")
                                AuthAwareCookieManager.persistCookies()
                                return false
                            }

                            return false
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
                            
                            Log.e(TAG, "WebView error: $errorDescription for $url (code: $errorCode)")
                            
                            WebViewDiagnosticsManager.logError(
                                type = WebViewDiagnosticsManager.ErrorType.WEB_RESOURCE_ERROR,
                                description = errorDescription,
                                failingUrl = url,
                                errorCode = errorCode
                            )
                        }
                        
                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            super.onReceivedHttpError(view, request, errorResponse)
                            
                            val url = request?.url?.toString()
                            val statusCode = errorResponse?.statusCode ?: 0
                            val reasonPhrase = errorResponse?.reasonPhrase ?: "Unknown"
                            
                            Log.w(TAG, "HTTP Error: $statusCode $reasonPhrase for $url")
                            
                            WebViewDiagnosticsManager.logError(
                                type = WebViewDiagnosticsManager.ErrorType.HTTP_ERROR,
                                description = "$statusCode $reasonPhrase",
                                failingUrl = url,
                                errorCode = statusCode
                            )
                        }
                        
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
                            
                            Log.w(TAG, "SSL Error: $errorType for ${error?.url}")
                            
                            WebViewDiagnosticsManager.logError(
                                type = WebViewDiagnosticsManager.ErrorType.SSL_ERROR,
                                description = errorType,
                                failingUrl = error?.url,
                                errorCode = error?.primaryError
                            )
                            
                            // Proceed anyway (for development/testing)
                            handler?.proceed()
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            Log.d(TAG, "Page started loading: $url")
                            val u = url ?: return
                            if (!recordingState) {
                                lastUrl = u
                                return
                            }

                            val event = NavigationEvent(
                                id = IdGenerator.newEventId(),
                                at = Clock.System.now(),
                                url = u,
                                fromUrl = lastUrl,
                                title = null,
                                isRedirect = false
                            )
                            lastUrl = u
                            mainHandler.post { onRecorderEventState(event) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d(TAG, "Page finished loading: $url")

                            // OAuth URLs: Cookies persistieren, aber kein JS injizieren
                            if (url != null && AuthAwareCookieManager.isOAuthUrl(url)) {
                                Log.d(TAG, "OAuth page finished, persisting cookies: $url")
                                AuthAwareCookieManager.persistCookies()
                                return
                            }

                            // Nach OAuth-Redirect: Cookies synchronisieren
                            if (url != null && AuthAwareCookieManager.isOAuthRedirect(url)) {
                                Log.d(TAG, "OAuth redirect finished, syncing cookies: $url")
                                AuthAwareCookieManager.persistCookies()
                            }

                            // Always inject tracking script so it's available when recording starts mid-session
                            if (view != null) {
                                view.evaluateJavascript(TrackingScript.getScript(), null)
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                            if (!recordingState) return super.shouldInterceptRequest(view, request)

                            val urlStr = request.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                            // WICHTIG: view.url darf nicht auf einem Background-Thread aufgerufen werden!
                            // shouldInterceptRequest läuft auf einem Worker-Thread, nicht dem UI-Thread.
                            // Stattdessen verwenden wir die request-URL oder null als Initiator.
                            val initiator: String? = try {
                                // request.requestHeaders kann sicher gelesen werden
                                request.requestHeaders?.get("Referer")
                            } catch (e: Exception) {
                                null
                            }

                            val kind = guessResourceKind(urlStr, request.isForMainFrame)

                            val event = ResourceRequestEvent(
                                id = IdGenerator.newEventId(),
                                at = Clock.System.now(),
                                url = urlStr,
                                initiatorUrl = initiator,
                                method = request.method,
                                resourceKind = kind
                            )

                            mainHandler.post { onRecorderEventState(event) }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        // Variablen für Fullscreen/Custom View Support
                        private var customView: View? = null
                        private var customViewCallback: CustomViewCallback? = null

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            if (!recordingState) return super.onConsoleMessage(consoleMessage)
                            val msg = consoleMessage ?: return super.onConsoleMessage(consoleMessage)

                            val level = when (msg.messageLevel()) {
                                ConsoleMessage.MessageLevel.LOG -> ConsoleLevel.Log
                                ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.Warn
                                ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.Error
                                // Note: ConsoleLevel enum only has Log, Info, Warn, Error
                                // DEBUG maps to Info as there's no Debug level in the contract
                                ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.Info
                                else -> ConsoleLevel.Info
                            }
                            
                            // Also log to diagnostics manager
                            val levelStr = when (msg.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                ConsoleMessage.MessageLevel.WARNING -> "WARN"
                                else -> "LOG"
                            }
                            WebViewDiagnosticsManager.logConsoleMessage(levelStr, msg.message(), msg.sourceId())

                            val event = ConsoleMessageEvent(
                                id = IdGenerator.newEventId(),
                                at = Clock.System.now(),
                                level = level,
                                message = msg.message()
                            )

                            mainHandler.post { onRecorderEventState(event) }
                            return true
                        }

                        // WICHTIG: Permission Requests für WebAuthn, Kamera, Mikrofon etc.
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            Log.d(TAG, "Permission request: ${request?.resources?.joinToString()}")
                            // WebAuthn und andere Permissions erlauben
                            mainHandler.post {
                                request?.grant(request.resources)
                            }
                        }

                        // Custom View Support (für WebAuthn-Dialoge und Fullscreen)
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            Log.d(TAG, "onShowCustomView called")
                            if (customView != null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                            // View wird angezeigt, nicht blockieren
                        }

                        override fun onHideCustomView() {
                            Log.d(TAG, "onHideCustomView called")
                            customViewCallback?.onCustomViewHidden()
                            customView = null
                            customViewCallback = null
                        }

                        // Window Management - verhindert dass neue Fenster den UI blockieren
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            Log.d(TAG, "onCreateWindow: isDialog=$isDialog, isUserGesture=$isUserGesture")
                            // Neue Fenster im gleichen WebView öffnen
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = view
                            resultMsg?.sendToTarget()
                            return true
                        }

                        override fun onCloseWindow(window: WebView?) {
                            Log.d(TAG, "onCloseWindow called")
                            super.onCloseWindow(window)
                        }
                    }

                    // Add JavaScript bridge for user action tracking
                    addJavascriptInterface(
                        JavaScriptBridge(
                            isRecording = { recordingState },
                            onUserAction = { event ->
                                mainHandler.post { onRecorderEventState(event) }
                            }
                        ),
                        "FishITMapper"
                    )

                    loadUrl(urlText.trim())
                    webViewHolder[0] = this
                    
                    // Update diagnostics with WebView configuration
                    WebViewDiagnosticsManager.updateDiagnosticsData(context, this)
                }
            },
            update = { webView ->
                // WICHTIG: Focus bei jedem Update sicherstellen für Tastatur-Input
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
            }
        )
    }
}

private fun guessResourceKind(url: String, isMainFrame: Boolean): ResourceKind? {
    if (isMainFrame) return ResourceKind.Document
    val lower = url.lowercase()
    return when {
        lower.endsWith(".css") -> ResourceKind.Stylesheet
        lower.endsWith(".js") -> ResourceKind.Script
        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg") -> ResourceKind.Image
        lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".otf") -> ResourceKind.Font
        lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mp3") || lower.endsWith(".wav") -> ResourceKind.Media
        else -> ResourceKind.Other
    }
}
