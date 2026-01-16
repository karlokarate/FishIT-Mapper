package dev.fishit.mapper.android.proxy

/**
 * DEPRECATED: MITM Proxy Server is no longer used.
 *
 * Traffic capture is handled externally by HttpCanary.
 * This file is kept for backwards compatibility during migration.
 *
 * @deprecated No internal traffic capture - use HttpCanary for traffic capture
 */
@Deprecated("Traffic capture removed - use HttpCanary instead")
class MitmProxyServer {
    @Deprecated("Not used")
    fun start() {}

    @Deprecated("Not used")
    fun stop() {}

    @Deprecated("Not used")
    fun isRunning(): Boolean = false
}
