package dev.fishit.mapper.android.proxy

/**
 * DEPRECATED: Proxy Capture Manager is no longer used.
 *
 * Traffic capture is handled externally by HttpCanary.
 * This file is kept for backwards compatibility during migration.
 *
 * @deprecated No internal traffic capture - use HttpCanary for traffic capture
 */
@Deprecated("Traffic capture removed - use HttpCanary instead")
class ProxyCaptureManager {
    companion object {
        @Deprecated("Not used")
        const val PROXY_PORT = 8888
        @Deprecated("Not used")
        const val PROXY_HOST = "127.0.0.1"
    }

    @Deprecated("Not used")
    fun startCapture() {}

    @Deprecated("Not used")
    fun stopCapture() {}

    @Deprecated("Not used")
    fun isCapturing(): Boolean = false
}
