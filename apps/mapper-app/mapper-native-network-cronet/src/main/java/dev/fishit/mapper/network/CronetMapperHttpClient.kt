package dev.fishit.mapper.network

import android.content.Context
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

class CronetMapperHttpClient(
    context: Context,
) : MapperNativeHttpClient {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val engine: CronetEngine by lazy {
        CronetEngine.Builder(appContext)
            .enableHttp2(true)
            .enableQuic(true)
            .build()
    }

    override fun execute(request: MapperNativeHttpRequest): MapperNativeHttpResponse {
        val startedAt = System.nanoTime()
        val resolvedUrl = request.resolvedUrl()
        val latch = CountDownLatch(1)
        val bodyBuffer = ByteArrayOutputStream()

        var finalUrl = resolvedUrl
        var statusCode = 0
        var statusText: String? = null
        var headers: Map<String, String> = emptyMap()
        var redirectCount = 0
        var succeeded = false
        var failureType: String? = null
        var failureMessage: String? = null

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                requestHandle: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String,
            ) {
                finalUrl = newLocationUrl
                headers = flattenHeaders(info)
                statusCode = info.httpStatusCode
                statusText = info.httpStatusText
                redirectCount += 1
                if (request.redirectPolicy == MapperRedirectPolicy.FOLLOW) {
                    requestHandle.followRedirect()
                } else {
                    failureType = "redirect_blocked"
                    requestHandle.cancel()
                    latch.countDown()
                }
            }

            override fun onResponseStarted(requestHandle: UrlRequest, info: UrlResponseInfo) {
                finalUrl = info.url
                statusCode = info.httpStatusCode
                statusText = info.httpStatusText
                headers = flattenHeaders(info)
                requestHandle.read(ByteBuffer.allocateDirect(64 * 1024))
            }

            override fun onReadCompleted(
                requestHandle: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer,
            ) {
                byteBuffer.flip()
                val data = ByteArray(max(byteBuffer.remaining(), 0))
                byteBuffer.get(data)
                bodyBuffer.write(data)
                byteBuffer.clear()
                requestHandle.read(byteBuffer)
            }

            override fun onSucceeded(requestHandle: UrlRequest, info: UrlResponseInfo) {
                finalUrl = info.url
                statusCode = info.httpStatusCode
                statusText = info.httpStatusText
                headers = flattenHeaders(info)
                succeeded = statusCode in 200..299
                latch.countDown()
            }

            override fun onFailed(
                requestHandle: UrlRequest,
                info: UrlResponseInfo?,
                error: CronetException,
            ) {
                if (info != null) {
                    finalUrl = info.url
                    statusCode = info.httpStatusCode
                    statusText = info.httpStatusText
                    headers = flattenHeaders(info)
                }
                failureType = "cronet_error"
                failureMessage = error.message
                latch.countDown()
            }
        }

        val requestBuilder = engine.newUrlRequestBuilder(resolvedUrl, callback, executor)
        request.headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }

        when (request.method) {
            MapperHttpMethod.GET -> requestBuilder.setHttpMethod("GET")
            MapperHttpMethod.POST -> {
                requestBuilder.setHttpMethod("POST")
                requestBuilder.setUploadDataProvider(
                    UploadDataProviders.create(request.body ?: ByteArray(0)),
                    executor,
                )
                request.contentType?.let { requestBuilder.addHeader("Content-Type", it) }
            }
        }

        val urlRequest = requestBuilder.build()
        request.cancellationSignal?.setOnCancelListener {
            failureType = "cancelled"
            urlRequest.cancel()
            latch.countDown()
        }
        urlRequest.start()

        val completed = latch.await(request.timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) {
            failureType = "timeout"
            failureMessage = "Request timed out after ${request.timeoutMillis}ms"
            urlRequest.cancel()
        }

        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        return MapperNativeHttpResponse(
            requestUrl = resolvedUrl,
            finalUrl = finalUrl,
            statusCode = statusCode,
            statusText = statusText,
            headers = headers,
            body = bodyBuffer.toByteArray(),
            durationMillis = durationMs,
            transport = "cronet",
            redirectCount = redirectCount,
            succeeded = succeeded && failureType == null,
            failureType = failureType,
            failureMessage = failureMessage,
        )
    }

    private fun flattenHeaders(info: UrlResponseInfo): Map<String, String> {
        return info.allHeaders.mapValues { (_, value) -> value.joinToString(",") }
    }
}
