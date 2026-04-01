package dev.fishit.mapper.network

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class FallbackOkHttpMapperHttpClient(
    private val baseClient: OkHttpClient = OkHttpClient(),
) : MapperNativeHttpClient {
    override fun execute(request: MapperNativeHttpRequest): MapperNativeHttpResponse {
        val startedAt = System.nanoTime()
        val resolvedUrl = request.resolvedUrl()

        val client = baseClient.newBuilder()
            .followRedirects(request.redirectPolicy == MapperRedirectPolicy.FOLLOW)
            .followSslRedirects(request.redirectPolicy == MapperRedirectPolicy.FOLLOW)
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()

        val requestBuilder = Request.Builder().url(resolvedUrl)
        request.headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        when (request.method) {
            MapperHttpMethod.GET -> requestBuilder.get()
            MapperHttpMethod.POST -> {
                val mediaType = request.contentType?.toMediaTypeOrNull()
                val body = (request.body ?: ByteArray(0)).toRequestBody(mediaType)
                requestBuilder.post(body)
            }
        }

        val call = client.newCall(requestBuilder.build())
        request.cancellationSignal?.setOnCancelListener {
            call.cancel()
        }

        return try {
            call.execute().use { response ->
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                MapperNativeHttpResponse(
                    requestUrl = resolvedUrl,
                    finalUrl = response.request.url.toString(),
                    statusCode = response.code,
                    statusText = response.message,
                    headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                    body = response.body?.bytes() ?: ByteArray(0),
                    durationMillis = durationMs,
                    transport = "okhttp",
                    redirectCount = 0,
                    succeeded = response.isSuccessful,
                )
            }
        } catch (ioe: IOException) {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            MapperNativeHttpResponse(
                requestUrl = resolvedUrl,
                finalUrl = resolvedUrl,
                statusCode = 0,
                statusText = null,
                headers = emptyMap(),
                body = ByteArray(0),
                durationMillis = durationMs,
                transport = "okhttp",
                redirectCount = 0,
                succeeded = false,
                failureType = if (call.isCanceled()) "cancelled" else "io_error",
                failureMessage = ioe.message,
            )
        }
    }
}
