package dev.fishit.mapper.android.webview

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Controls WebView proxy configuration for traffic capture.
 *
 * Uses androidx.webkit.ProxyController to route all WebView traffic through the local MITM proxy
 * server.
 *
 * Requirements:
 * - Android 14+ (API 34+) - ProxyController is available without fallbacks
 * - androidx.webkit dependency (already included)
 */
object WebViewProxyController {

    private const val TAG = "WebViewProxyController"

    @Volatile private var isProxyEnabled = false

    /**
     * Enables proxy override for all WebView instances in the app.
     *
     * @param proxyHost The proxy host (e.g., "127.0.0.1")
     * @param proxyPort The proxy port (e.g., 8888)
     * @param onComplete Callback when proxy is enabled (success: Boolean)
     */
    fun enableProxy(proxyHost: String, proxyPort: Int, onComplete: ((Boolean) -> Unit)? = null) {
        if (!isProxySupported()) {
            Log.w(TAG, "ProxyController not supported on this device/WebView version")
            onComplete?.invoke(false)
            return
        }

        if (isProxyEnabled) {
            Log.d(TAG, "Proxy already enabled")
            onComplete?.invoke(true)
            return
        }

        try {
            val proxyUrl = "$proxyHost:$proxyPort"
            Log.i(TAG, "Enabling WebView proxy: $proxyUrl")

            val proxyConfig =
                    ProxyConfig.Builder()
                            .addProxyRule(proxyUrl)
                            .addDirect() // Fallback to direct if proxy fails
                            .build()

            val executor = Executor { command -> command.run() }

            ProxyController.getInstance().setProxyOverride(proxyConfig, executor) {
                isProxyEnabled = true
                Log.i(TAG, "WebView proxy enabled successfully")
                onComplete?.invoke(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable WebView proxy", e)
            onComplete?.invoke(false)
        }
    }

    /**
     * Disables proxy override, returning to default WebView behavior.
     *
     * @param onComplete Callback when proxy is disabled
     */
    fun disableProxy(onComplete: (() -> Unit)? = null) {
        if (!isProxySupported()) {
            Log.w(TAG, "ProxyController not supported")
            onComplete?.invoke()
            return
        }

        if (!isProxyEnabled) {
            Log.d(TAG, "Proxy not enabled, nothing to disable")
            onComplete?.invoke()
            return
        }

        try {
            Log.i(TAG, "Disabling WebView proxy")

            val executor = Executor { command -> command.run() }

            ProxyController.getInstance().clearProxyOverride(executor) {
                isProxyEnabled = false
                Log.i(TAG, "WebView proxy disabled successfully")
                onComplete?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable WebView proxy", e)
            isProxyEnabled = false
            onComplete?.invoke()
        }
    }

    /**
     * Checks if ProxyController is supported on this device.
     *
     * For Android 14+ (API 34+), this should always return true with modern WebView versions.
     */
    fun isProxySupported(): Boolean {
        return try {
            // Check if the feature is supported by the installed WebView
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking proxy support", e)
            false
        }
    }

    /** Returns the current proxy state. */
    fun isProxyEnabled(): Boolean = isProxyEnabled
}
