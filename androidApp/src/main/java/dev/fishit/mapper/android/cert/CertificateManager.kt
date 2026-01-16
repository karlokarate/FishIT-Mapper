package dev.fishit.mapper.android.cert

/**
 * DEPRECATED: Certificate Manager is no longer used.
 *
 * Traffic capture is handled externally by HttpCanary which manages its own certificates.
 * This file is kept for backwards compatibility during migration.
 *
 * @deprecated No internal MITM proxy - use HttpCanary for traffic capture
 */
@Deprecated("Traffic capture removed - use HttpCanary instead")
class CertificateManager
