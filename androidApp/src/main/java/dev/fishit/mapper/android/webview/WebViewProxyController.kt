package dev.fishit.mapper.android.webview

/**
 * DEPRECATED: WebView proxy control is no longer used.
 *
 * Traffic capture is handled externally by HttpCanary.
 * This file is kept for backwards compatibility during migration.
 *
 * @deprecated No internal traffic capture - use HttpCanary for traffic capture
 */
@Deprecated("Traffic capture removed - use HttpCanary instead")
object WebViewProxyController {
    @Deprecated("Not used")
    fun enableProxy(proxyHost: String, proxyPort: Int, onComplete: ((Boolean) -> Unit)? = null) {
        onComplete?.invoke(false)
    }

    @Deprecated("Not used")
    fun disableProxy(onComplete: (() -> Unit)? = null) {
        onComplete?.invoke()
    }

    @Deprecated("Not used")
    fun isProxySupported(): Boolean = false

    @Deprecated("Not used")
    fun isProxyEnabled(): Boolean = false
}
