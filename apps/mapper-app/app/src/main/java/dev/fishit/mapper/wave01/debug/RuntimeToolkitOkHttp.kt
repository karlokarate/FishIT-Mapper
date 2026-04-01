package dev.fishit.mapper.wave01.debug

import android.content.Context
import android.os.SystemClock
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import java.time.Instant
import java.util.Locale

object RuntimeToolkitOkHttp {
    fun instrument(
        context: Context,
        builder: OkHttpClient.Builder,
        source: String,
    ): OkHttpClient.Builder {
        val hasInterceptor = builder.interceptors().any { interceptor ->
            interceptor is RuntimeToolkitOkHttpInterceptor && interceptor.source == source
        }
        if (!hasInterceptor) {
            builder.addInterceptor(RuntimeToolkitOkHttpInterceptor(context.applicationContext, source))
        }
        return builder
    }

    fun newClient(
        context: Context,
        source: String,
        base: OkHttpClient? = null,
    ): OkHttpClient {
        val builder = (base?.newBuilder() ?: OkHttpClient.Builder())
        return instrument(context, builder, source).build()
    }
}

class RuntimeToolkitOkHttpInterceptor(
    private val appContext: Context,
    val source: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestHeaders = headersToMap(request.headers)
        val requestPreview = requestBodyPreview(request)
        val requestStartedNs = SystemClock.elapsedRealtimeNanos()
        val requestStartedUtc = Instant.now().toString()
        val requestUrl = request.url.toString()

        val requestLog = RuntimeToolkitTelemetry.logNetworkRequest(
            context = appContext,
            source = source,
            url = requestUrl,
            method = request.method,
            headers = requestHeaders,
            payload = mapOf(
                "body_preview" to requestPreview,
                "content_type" to request.body?.contentType()?.toString(),
                "request_started_at_utc" to requestStartedUtc,
                "request_started_mono_ns" to requestStartedNs,
            ),
        )
        val requestId = requestLog.requestId

        requestHeaders.forEach { (headerKey, _) ->
            if (shouldTrackProvenanceHeader(headerKey)) {
                RuntimeToolkitTelemetry.logProvenanceEvent(
                    context = appContext,
                    entityType = "header",
                    entityKey = headerKey.lowercase(Locale.ROOT),
                    consumedBy = requestId,
                    payload = mapOf(
                        "url" to requestUrl,
                        "method" to request.method,
                        "source" to source,
                    ),
                )
            }
        }

        return try {
            val response = chain.proceed(request)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - requestStartedNs) / 1_000_000
            val responseHeaders = headersToMap(response.headers)
            val capturePlan = resolveCapturePlan(
                url = request.url.toString(),
                contentType = response.body?.contentType(),
            )
            val peekBytes = runCatching { response.peekBody(capturePlan.peekBytes).bytes() }.getOrNull()
            val rawBody = if (capturePlan.captureRaw && peekBytes != null && peekBytes.isNotEmpty()) {
                peekBytes
            } else {
                null
            }
            val requestBodyContentLength = runCatching { request.body?.contentLength() ?: -1L }.getOrDefault(-1L)
            val responseBodyContentLength = runCatching { response.body?.contentLength() ?: -1L }.getOrDefault(-1L)
            val contentLengthHeader = responseHeaders.entries
                .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
                ?.value
                ?.trim()
                .orEmpty()
            val priorCode = response.priorResponse?.code
            val responseId = "resp_${java.util.UUID.randomUUID()}"
            val storedSizeBytes = rawBody?.size ?: 0
            val captureTruncated = when {
                responseBodyContentLength > 0 && storedSizeBytes > 0 && storedSizeBytes.toLong() < responseBodyContentLength -> true
                capturePlan.captureRaw && capturePlan.peekBytes > 0 && storedSizeBytes.toLong() >= capturePlan.peekBytes -> true
                else -> false
            }
            val captureLimitBytes = if (captureTruncated) storedSizeBytes else 0

            RuntimeToolkitTelemetry.logNetworkResponse(
                context = appContext,
                source = source,
                url = requestUrl,
                method = request.method,
                statusCode = response.code,
                reason = response.message,
                mimeType = response.body?.contentType()?.toString(),
                headers = responseHeaders,
                rawBody = rawBody,
                requestId = requestId,
                responseId = responseId,
                payload = mapOf(
                    "elapsed_ms" to elapsedMs,
                    "protocol" to response.protocol.toString(),
                    "request_id" to requestId,
                    "response_id" to responseId,
                    "request_fingerprint" to requestLog.requestFingerprint,
                    "normalized_url" to requestLog.normalizedUrl,
                    "phase_id" to requestLog.phaseId,
                    "host_class" to requestLog.hostClass,
                    "dedup_of" to requestLog.dedupOf,
                    "dedup_count" to requestLog.dedupCount,
                    "request_canonical" to requestLog.canonical,
                    "request_started_at_utc" to requestStartedUtc,
                    "response_received_at_utc" to Instant.now().toString(),
                    "request_content_length" to requestBodyContentLength,
                    "response_content_length" to responseBodyContentLength,
                    "content_length_header" to contentLengthHeader,
                    "capture_mode" to capturePlan.mode,
                    "stored_size_bytes" to storedSizeBytes,
                    "captured_body_bytes" to (rawBody?.size ?: 0),
                    "capture_truncated" to captureTruncated,
                    "capture_limit_bytes" to captureLimitBytes,
                    "redirected" to (priorCode != null),
                    "prior_status_code" to priorCode,
                ),
            )

            val setCookieHeaders = response.headers("Set-Cookie")
            setCookieHeaders.forEach { cookieLine ->
                val cookieOp = cookieOperation(cookieLine)
                RuntimeToolkitTelemetry.logCookieEvent(
                    context = appContext,
                    operation = cookieOp,
                    domain = extractCookieDomain(request.url.host, cookieLine),
                    cookieName = extractCookieName(cookieLine),
                    cookieValuePreview = cookieLine.take(128),
                    reason = "set_cookie_header",
                    payload = mapOf(
                        "source" to source,
                        "request_id" to requestId,
                        "response_id" to responseId,
                    ),
                )
                RuntimeToolkitTelemetry.logProvenanceEvent(
                    context = appContext,
                    entityType = "cookie",
                    entityKey = extractCookieName(cookieLine) ?: "unknown_cookie",
                    producedBy = responseId,
                    consumedBy = requestId,
                    payload = mapOf(
                        "domain" to extractCookieDomain(request.url.host, cookieLine),
                        "reason" to "set_cookie_header",
                    ),
                )
            }

            if (response.code == 401 || response.code == 403) {
                RuntimeToolkitTelemetry.logAuthEvent(
                    context = appContext,
                    operation = "auth_state_changed",
                    payload = mapOf(
                        "status_code" to response.code,
                        "url" to request.url.toString(),
                        "source" to source,
                        "request_id" to requestId,
                        "response_id" to responseId,
                    ),
                )
            }
            if (isTokenRefreshCall(request.url.toString(), requestHeaders, responseHeaders, response.code)) {
                RuntimeToolkitTelemetry.logAuthEvent(
                    context = appContext,
                    operation = "token_refreshed",
                    payload = mapOf(
                        "status_code" to response.code,
                        "url" to requestUrl,
                        "source" to source,
                        "request_id" to requestId,
                        "response_id" to responseId,
                    ),
                )
                RuntimeToolkitTelemetry.logProvenanceEvent(
                    context = appContext,
                    entityType = "token",
                    entityKey = "refresh",
                    producedBy = responseId,
                    consumedBy = requestId,
                    payload = mapOf(
                        "url" to requestUrl,
                        "status_code" to response.code,
                        "source" to source,
                    ),
                )
            }

            responseHeaders.forEach { (headerKey, _) ->
                if (shouldTrackProvenanceHeader(headerKey)) {
                    RuntimeToolkitTelemetry.logProvenanceEvent(
                        context = appContext,
                        entityType = "header",
                        entityKey = headerKey.lowercase(Locale.ROOT),
                        producedBy = responseId,
                        consumedBy = requestId,
                        payload = mapOf(
                            "url" to requestUrl,
                            "status_code" to response.code,
                            "source" to source,
                        ),
                    )
                }
            }

            response
        } catch (t: Throwable) {
            RuntimeToolkitTelemetry.logNetworkResponse(
                context = appContext,
                source = source,
                url = requestUrl,
                method = request.method,
                statusCode = null,
                reason = t.message,
                mimeType = null,
                headers = emptyMap(),
                rawBody = null,
                requestId = requestId,
                payload = mapOf(
                    "error_type" to (t::class.java.simpleName ?: "Throwable"),
                    "capture_mode" to "error",
                    "request_fingerprint" to requestLog.requestFingerprint,
                    "normalized_url" to requestLog.normalizedUrl,
                    "phase_id" to requestLog.phaseId,
                    "host_class" to requestLog.hostClass,
                    "dedup_of" to requestLog.dedupOf,
                    "dedup_count" to requestLog.dedupCount,
                    "request_canonical" to requestLog.canonical,
                ),
            )
            throw t
        }
    }

    private fun requestBodyPreview(request: okhttp3.Request): String? {
        val body = request.body ?: return null
        if (body.isDuplex() || body.isOneShot()) return null
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val bytes = buffer.readByteArray(minOf(buffer.size, MAX_BODY_PREVIEW_BYTES.toLong()))
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    private data class CapturePlan(
        val mode: String,
        val captureRaw: Boolean,
        val peekBytes: Long,
    )

    private fun resolveCapturePlan(url: String, contentType: MediaType?): CapturePlan {
        val normalizedUrl = url.lowercase(Locale.ROOT)
        val mime = contentType?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val isMediaSegment = normalizedUrl.endsWith(".ts") ||
            normalizedUrl.endsWith(".m4s") ||
            normalizedUrl.endsWith(".m4a") ||
            normalizedUrl.endsWith(".m4v") ||
            normalizedUrl.endsWith(".mp4") ||
            normalizedUrl.endsWith(".webm") ||
            mime.startsWith("video/") ||
            mime.startsWith("audio/")
        if (isMediaSegment) {
            return CapturePlan(mode = "metadata_only", captureRaw = false, peekBytes = 0L)
        }

        val isManifest = normalizedUrl.endsWith(".m3u8") ||
            normalizedUrl.endsWith(".mpd") ||
            mime.contains("application/vnd.apple.mpegurl") ||
            mime.contains("application/x-mpegurl") ||
            mime.contains("application/dash+xml")
        if (isManifest || shouldCaptureTextLike(contentType)) {
            return CapturePlan(mode = "full_raw", captureRaw = true, peekBytes = MAX_PEEK_BYTES_FULL)
        }

        return CapturePlan(mode = "snippet", captureRaw = true, peekBytes = MAX_PEEK_BYTES_SNIPPET)
    }

    private fun shouldCaptureTextLike(contentType: MediaType?): Boolean {
        if (contentType == null) return false
        val type = contentType.type.lowercase(Locale.ROOT)
        val subtype = contentType.subtype.lowercase(Locale.ROOT)
        if (type == "text") return true
        return subtype.contains("json") ||
            subtype.contains("xml") ||
            subtype.contains("javascript") ||
            subtype.contains("x-www-form-urlencoded") ||
            subtype.contains("html")
    }

    private fun cookieOperation(cookieLine: String): String {
        val lower = cookieLine.lowercase(Locale.ROOT)
        if (lower.contains("max-age=0") || lower.contains("expires=thu, 01 jan 1970")) {
            return "delete"
        }
        return "set"
    }

    private fun shouldTrackProvenanceHeader(headerKey: String): Boolean {
        val normalized = headerKey.lowercase(Locale.ROOT)
        return normalized == "authorization" ||
            normalized == "cookie" ||
            normalized == "set-cookie" ||
            normalized.contains("token") ||
            normalized.startsWith("x-")
    }

    private fun isTokenRefreshCall(
        url: String,
        requestHeaders: Map<String, String>,
        responseHeaders: Map<String, String>,
        statusCode: Int,
    ): Boolean {
        if (statusCode !in 200..299) return false
        val lowerUrl = url.lowercase(Locale.ROOT)
        if (
            lowerUrl.contains("token") ||
            lowerUrl.contains("refresh") ||
            lowerUrl.contains("oauth") ||
            lowerUrl.contains("identity")
        ) {
            return true
        }
        val reqKeys = requestHeaders.keys.joinToString(" ").lowercase(Locale.ROOT)
        val resKeys = responseHeaders.keys.joinToString(" ").lowercase(Locale.ROOT)
        return reqKeys.contains("authorization") || resKeys.contains("set-cookie")
    }

    private fun extractCookieName(cookieLine: String): String? {
        val firstPart = cookieLine.substringBefore(';', cookieLine)
        val name = firstPart.substringBefore('=', firstPart).trim()
        return name.ifBlank { null }
    }

    private fun extractCookieDomain(defaultHost: String, cookieLine: String): String {
        val parts = cookieLine.split(';')
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("domain=", ignoreCase = true)) {
                return trimmed.substringAfter('=').trim().ifBlank { defaultHost }
            }
        }
        return defaultHost
    }

    private fun headersToMap(headers: Headers): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (name in headers.names()) {
            out[name] = headers.values(name).joinToString(",")
        }
        return out
    }

    companion object {
        private const val MAX_PEEK_BYTES_FULL = 4L * 1024L * 1024L
        private const val MAX_PEEK_BYTES_SNIPPET = 32L * 1024L
        private const val MAX_BODY_PREVIEW_BYTES = 16 * 1024
    }
}
