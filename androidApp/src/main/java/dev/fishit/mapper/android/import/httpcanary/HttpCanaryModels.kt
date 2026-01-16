package dev.fishit.mapper.android.import.httpcanary

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data models for parsing HttpCanary ZIP exports.
 *
 * HttpCanary ZIP structure:
 * ```
 * export.zip/
 * ├── request.json          # Root-level request (optional)
 * ├── response.json         # Root-level response (optional)
 * ├── 1/
 * │   ├── request.json      # HTTP request metadata
 * │   ├── response.json     # HTTP response metadata
 * │   ├── request_body.txt  # Request body (optional)
 * │   ├── response_body.json/.txt # Response body (optional)
 * │   └── *.hcy             # HttpCanary internal (ignored)
 * ├── 2/
 * │   └── ...
 * ├── 3/                    # WebSocket folder
 * │   ├── websocket.json    # WebSocket metadata
 * │   └── *.txt             # WebSocket messages
 * └── 17/                   # UDP folder
 *     ├── udp.json          # UDP metadata
 *     └── *.bin             # UDP packets
 * ```
 */

// ============================================================================
// HttpCanary Request Model
// ============================================================================

/**
 * HttpCanary request.json schema.
 *
 * Example:
 * ```json
 * {
 *   "method": "GET",
 *   "url": "https://example.com/api/data",
 *   "httpVersion": "HTTP/1.1",
 *   "headers": {
 *     "Host": "example.com",
 *     "User-Agent": "Mozilla/5.0 ..."
 *   },
 *   "contentType": "application/json",
 *   "contentLength": 0,
 *   "startTime": 1705392000000,
 *   "endTime": 1705392000100
 * }
 * ```
 */
@Serializable
data class HttpCanaryRequest(
    val method: String,
    val url: String,
    @SerialName("httpVersion")
    val httpVersion: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val contentLength: Long? = null,
    @SerialName("startTime")
    val startTime: Long? = null,
    @SerialName("endTime")
    val endTime: Long? = null,
    // Alternative field names HttpCanary might use
    @SerialName("time")
    val time: Long? = null,
    @SerialName("timestamp")
    val timestamp: Long? = null
) {
    /**
     * Gets the best available timestamp in milliseconds.
     */
    fun getTimestampMs(): Long {
        return startTime ?: time ?: timestamp ?: System.currentTimeMillis()
    }
}

// ============================================================================
// HttpCanary Response Model
// ============================================================================

/**
 * HttpCanary response.json schema.
 *
 * Example:
 * ```json
 * {
 *   "statusCode": 200,
 *   "statusMessage": "OK",
 *   "httpVersion": "HTTP/1.1",
 *   "headers": {
 *     "Content-Type": "application/json",
 *     "Content-Length": "1234"
 *   },
 *   "contentType": "application/json",
 *   "contentLength": 1234,
 *   "startTime": 1705392000050,
 *   "endTime": 1705392000150
 * }
 * ```
 */
@Serializable
data class HttpCanaryResponse(
    @SerialName("statusCode")
    val statusCode: Int? = null,
    @SerialName("status")
    val status: Int? = null,
    @SerialName("code")
    val code: Int? = null,
    @SerialName("statusMessage")
    val statusMessage: String? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("httpVersion")
    val httpVersion: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val contentLength: Long? = null,
    @SerialName("startTime")
    val startTime: Long? = null,
    @SerialName("endTime")
    val endTime: Long? = null
) {
    /**
     * Gets the HTTP status code from any of the possible field names.
     */
    fun resolveStatusCode(): Int {
        return statusCode ?: status ?: code ?: 0
    }

    /**
     * Gets the status message from any of the possible field names.
     */
    fun resolveStatusMessage(): String? {
        return statusMessage ?: message
    }

    /**
     * Checks if this is a redirect response (3xx status).
     */
    fun isRedirect(): Boolean {
        val code = resolveStatusCode()
        return code in 300..399
    }

    /**
     * Gets the redirect location from headers (case-insensitive).
     */
    fun getRedirectLocation(): String? {
        return headers.entries.find {
            it.key.equals("Location", ignoreCase = true)
        }?.value
    }
}

// ============================================================================
// HttpCanary WebSocket Model
// ============================================================================

/**
 * HttpCanary websocket.json schema for WebSocket connections.
 */
@Serializable
data class HttpCanaryWebSocket(
    val url: String? = null,
    @SerialName("startTime")
    val startTime: Long? = null,
    @SerialName("endTime")
    val endTime: Long? = null,
    val headers: Map<String, String> = emptyMap()
)

// ============================================================================
// HttpCanary UDP Model
// ============================================================================

/**
 * HttpCanary udp.json schema for UDP packets.
 */
@Serializable
data class HttpCanaryUdp(
    val localAddress: String? = null,
    val remoteAddress: String? = null,
    @SerialName("startTime")
    val startTime: Long? = null,
    @SerialName("endTime")
    val endTime: Long? = null
)

// ============================================================================
// Normalized CapturedExchange Model
// ============================================================================

/**
 * Normalized representation of an HTTP exchange captured by HttpCanary.
 * This is the internal format used by FishIT-Mapper after import.
 */
@Serializable
data class CapturedExchange(
    /** Unique identifier for this exchange (typically the folder name like "1", "2", etc.) */
    val exchangeId: String,

    /** When the request was initiated (milliseconds since epoch) */
    val startedAt: Instant,

    /** The HTTP request */
    val request: CapturedRequest,

    /** The HTTP response (may be null if request failed) */
    val response: CapturedResponse? = null,

    /** Protocol type: http, websocket, udp */
    val protocol: String = "http"
)

/**
 * Normalized HTTP request.
 */
@Serializable
data class CapturedRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyBinary: Boolean = false
)

/**
 * Normalized HTTP response.
 */
@Serializable
data class CapturedResponse(
    val status: Int,
    val statusMessage: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyBinary: Boolean = false,
    val redirectLocation: String? = null
) {
    fun isRedirect(): Boolean = status in 300..399
}
