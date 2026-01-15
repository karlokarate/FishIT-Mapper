package dev.fishit.mapper.android.ui.project

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.fishit.mapper.android.webview.JavaScriptBridge
import dev.fishit.mapper.android.webview.TrackingScript
import dev.fishit.mapper.contract.ConsoleLevel
import dev.fishit.mapper.contract.ConsoleMessageEvent
import dev.fishit.mapper.contract.NavigationEvent
import dev.fishit.mapper.contract.ProjectMeta
import dev.fishit.mapper.contract.RecorderEvent
import dev.fishit.mapper.contract.ResourceKind
import dev.fishit.mapper.contract.ResourceRequestEvent
import dev.fishit.mapper.engine.IdGenerator
import kotlinx.datetime.Clock

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

    var urlText by remember(projectMeta?.id?.value) { mutableStateOf(projectMeta?.startUrl ?: "https://") }

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
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.userAgentString = settings.userAgentString + " FishIT-Mapper/0.1"

                    val mainHandler = Handler(Looper.getMainLooper())

                    webViewClient = object : WebViewClient() {
                        private var lastUrl: String? = null

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
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
                            // Always inject tracking script so it's available when recording starts mid-session
                            if (view != null) {
                                view.evaluateJavascript(TrackingScript.getScript(), null)
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                            if (!recordingState) return super.shouldInterceptRequest(view, request)
                            val r = request ?: return super.shouldInterceptRequest(view, request)

                            val urlStr = r.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                            val initiator = view?.url

                            val kind = guessResourceKind(urlStr, r.isForMainFrame)

                            val event = ResourceRequestEvent(
                                id = IdGenerator.newEventId(),
                                at = Clock.System.now(),
                                url = urlStr,
                                initiatorUrl = initiator,
                                method = r.method,
                                resourceKind = kind
                            )

                            mainHandler.post { onRecorderEventState(event) }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
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

                            val event = ConsoleMessageEvent(
                                id = IdGenerator.newEventId(),
                                at = Clock.System.now(),
                                level = level,
                                message = msg.message()
                            )

                            mainHandler.post { onRecorderEventState(event) }
                            return true
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
                }
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
