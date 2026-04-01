package dev.fishit.mapper.wave01.debug

import android.content.Context
import android.os.SystemClock
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer

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

        RuntimeToolkitTelemetry.logNetworkRequest(
            context = appContext,
            source = source,
            url = request.url.toString(),
            method = request.method,
            headers = requestHeaders,
            payload = mapOf(
                "body_preview" to requestPreview,
                "content_type" to request.body?.contentType()?.toString(),
            ),
        )

        val startedNs = SystemClock.elapsedRealtimeNanos()

        return try {
            val response = chain.proceed(request)
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000
            val responseHeaders = headersToMap(response.headers)
            val peekBytes = runCatching { response.peekBody(MAX_PEEK_BYTES).bytes() }.getOrNull()
            val rawBody = if (peekBytes != null && shouldCaptureBody(response.body?.contentType())) peekBytes else null

            RuntimeToolkitTelemetry.logNetworkResponse(
                context = appContext,
                source = source,
                url = request.url.toString(),
                method = request.method,
                statusCode = response.code,
                reason = response.message,
                mimeType = response.body?.contentType()?.toString(),
                headers = responseHeaders,
                rawBody = rawBody,
                payload = mapOf(
                    "elapsed_ms" to elapsedMs,
                    "protocol" to response.protocol.toString(),
                ),
            )

            val setCookieHeaders = response.headers("Set-Cookie")
            setCookieHeaders.forEach { cookieLine ->
                RuntimeToolkitTelemetry.logCookieEvent(
                    context = appContext,
                    operation = "set_cookie",
                    domain = extractCookieDomain(request.url.host, cookieLine),
                    cookieName = extractCookieName(cookieLine),
                    cookieValuePreview = cookieLine.take(128),
                    payload = mapOf("source" to source),
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
                    ),
                )
            }

            response
        } catch (t: Throwable) {
            RuntimeToolkitTelemetry.logNetworkResponse(
                context = appContext,
                source = source,
                url = request.url.toString(),
                method = request.method,
                statusCode = null,
                reason = t.message,
                mimeType = null,
                headers = emptyMap(),
                rawBody = null,
                payload = mapOf(
                    "error_type" to (t::class.java.simpleName ?: "Throwable"),
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

    private fun shouldCaptureBody(contentType: MediaType?): Boolean {
        if (contentType == null) return false
        val type = contentType.type.lowercase()
        val subtype = contentType.subtype.lowercase()
        if (type == "text") return true
        return subtype.contains("json") ||
            subtype.contains("xml") ||
            subtype.contains("javascript") ||
            subtype.contains("x-www-form-urlencoded") ||
            subtype.contains("html")
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
        private const val MAX_PEEK_BYTES = 64L * 1024L
        private const val MAX_BODY_PREVIEW_BYTES = 16 * 1024
    }
}
