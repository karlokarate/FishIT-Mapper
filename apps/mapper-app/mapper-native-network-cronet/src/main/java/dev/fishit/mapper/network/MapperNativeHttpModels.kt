package dev.fishit.mapper.network

import android.os.CancellationSignal
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class MapperHttpMethod {
    GET,
    POST,
}

enum class MapperRedirectPolicy {
    FOLLOW,
    NO_FOLLOW,
}

data class MapperNativeHttpRequest(
    val url: String,
    val method: MapperHttpMethod = MapperHttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    val queryParams: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val timeoutMillis: Long = 20_000,
    val redirectPolicy: MapperRedirectPolicy = MapperRedirectPolicy.FOLLOW,
    val cancellationSignal: CancellationSignal? = null,
    val operationTag: String? = null,
) {
    fun resolvedUrl(): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (queryParams.isEmpty()) return parsed.toString()
        val builder = parsed.newBuilder()
        queryParams.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }
}

data class MapperNativeHttpResponse(
    val requestUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val statusText: String?,
    val headers: Map<String, String>,
    val body: ByteArray,
    val durationMillis: Long,
    val transport: String,
    val redirectCount: Int,
    val succeeded: Boolean,
    val failureType: String? = null,
    val failureMessage: String? = null,
)
