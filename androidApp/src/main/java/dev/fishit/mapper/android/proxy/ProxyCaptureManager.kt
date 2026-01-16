package dev.fishit.mapper.android.proxy

import android.content.Context
import android.util.Log
import dev.fishit.mapper.contract.RecorderEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Manages the MITM Proxy lifecycle for browser traffic capture.
 *
 * This class:
 * - Starts/stops the MitmProxyServer when recording starts/stops
 * - Routes proxy events to subscribers (ViewModel)
 * - Is lifecycle-safe and survives Compose recompositions
 * - Provides the proxy port for WebView configuration
 */
class ProxyCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "ProxyCaptureManager"
        const val PROXY_PORT = 8888
        const val PROXY_HOST = "127.0.0.1"
    }

    private var proxyServer: MitmProxyServer? = null
    private var scope: CoroutineScope? = null

    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<RecorderEvent> = _events.asSharedFlow()

    @Volatile private var isRecording = false

    /** Starts the proxy server for traffic capture. Should be called when recording starts. */
    fun startCapture() {
        if (proxyServer != null) {
            Log.w(TAG, "Proxy already running")
            return
        }

        Log.i(TAG, "Starting proxy capture on $PROXY_HOST:$PROXY_PORT")

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        isRecording = true

        proxyServer =
                MitmProxyServer(
                        context = context,
                        port = PROXY_PORT,
                        onEvent = { event ->
                            if (isRecording) {
                                scope?.launch { _events.emit(event) }
                            }
                        }
                )
        proxyServer?.start()

        Log.i(TAG, "Proxy capture started")
    }

    /** Stops the proxy server. Should be called when recording stops. */
    fun stopCapture() {
        Log.i(TAG, "Stopping proxy capture")

        isRecording = false

        proxyServer?.stop()
        proxyServer = null

        scope?.cancel()
        scope = null

        Log.i(TAG, "Proxy capture stopped")
    }

    /** Returns true if the proxy is currently running. */
    fun isCapturing(): Boolean = proxyServer != null

    /** Returns the proxy address for WebView configuration. */
    fun getProxyAddress(): String = "$PROXY_HOST:$PROXY_PORT"
}
