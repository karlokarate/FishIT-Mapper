package dev.fishit.mapper.android.proxy

import android.content.Context
import android.util.Log
import dev.fishit.mapper.android.cert.CertificateManager
import dev.fishit.mapper.contract.RecorderEvent
import dev.fishit.mapper.contract.ResourceRequestEvent
import dev.fishit.mapper.contract.ResourceResponseEvent
import dev.fishit.mapper.engine.IdGenerator
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * MITM-Proxy-Server für HTTPS-Decryption. Fängt HTTP/HTTPS-Requests ab, entschlüsselt sie und
 * leitet sie weiter.
 */
class MitmProxyServer(
        private val context: Context,
        private val port: Int = 8888,
        private val onEvent: (RecorderEvent) -> Unit
) {
    companion object {
        private const val TAG = "MitmProxyServer"

        // Realistische Limits für vollständiges Website-Mapping
        const val MAX_TEXT_BODY_SIZE = 10 * 1024 * 1024 // 10 MB für Text (HTML, JSON, XML)
        const val MAX_BINARY_BODY_SIZE = 5 * 1024 * 1024 // 5 MB für Binär (Base64)
        const val MAX_TOTAL_SESSION_CACHE = 500 * 1024 * 1024 // 500 MB pro Session

        // Text Content-Types (vollständig erfassen)
        private val TEXT_CONTENT_TYPES =
                setOf(
                        "text/html",
                        "text/plain",
                        "text/css",
                        "text/javascript",
                        "text/xml",
                        "application/json",
                        "application/javascript",
                        "application/xml",
                        "application/xhtml+xml",
                        "application/ld+json",
                        "application/manifest+json",
                        "application/x-www-form-urlencoded"
                )

        // Redirect Status Codes
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val certificateManager = CertificateManager(context)
    private val httpClient = OkHttpClient.Builder().followRedirects(false).build()

    /** Startet den Proxy-Server. */
    fun start() {
        if (serverSocket != null) {
            Log.w(TAG, "Proxy server already running")
            return
        }

        serverJob =
                scope.launch {
                    try {
                        serverSocket = ServerSocket(port)
                        Log.i(TAG, "Proxy server started on port $port")

                        while (true) {
                            val clientSocket = serverSocket?.accept() ?: break
                            launch { handleClient(clientSocket) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Proxy server error", e)
                    }
                }
    }

    /** Stoppt den Proxy-Server. */
    fun stop() {
        serverJob?.cancel()
        serverJob = null

        serverSocket?.close()
        serverSocket = null

        Log.i(TAG, "Proxy server stopped")
    }

    /** Behandelt eine Client-Verbindung. */
    private suspend fun handleClient(clientSocket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream()))

            // Lese HTTP-Request-Line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val url = parts[1]
            val httpVersion = parts[2]

            // Lese Headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val colonIndex = line!!.indexOf(':')
                if (colonIndex > 0) {
                    val key = line!!.substring(0, colonIndex).trim()
                    val value = line!!.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }

            val host = headers["Host"] ?: ""

            // Lese Request Body falls vorhanden (für POST/PUT/PATCH)
            val requestBody = readRequestBody(clientSocket.getInputStream(), headers)

            // Behandle CONNECT-Request (HTTPS)
            if (method == "CONNECT") {
                handleConnect(clientSocket, writer, host)
            } else {
                // Behandle HTTP-Request
                handleHttpRequest(
                        method = method,
                        url = url,
                        headers = headers,
                        writer = writer,
                        clientSocket = clientSocket,
                        requestBody = requestBody
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            clientSocket.close()
        }
    }

    /** Behandelt HTTPS CONNECT-Request. */
    private suspend fun handleConnect(clientSocket: Socket, writer: BufferedWriter, host: String) {
        try {
            // Sende 200 Connection Established
            writer.write("HTTP/1.1 200 Connection Established\r\n")
            writer.write("\r\n")
            writer.flush()

            // Erstelle SSL-Socket mit unserem Zertifikat
            val domain = host.split(":")[0]
            val (caCert, caKey) = certificateManager.getOrCreateCACertificate()
            val (serverCert, serverKey) =
                    certificateManager.generateServerCertificate(domain, caCert, caKey)

            // Erstelle KeyStore mit Server-Zertifikat
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry("server", serverKey, "password".toCharArray(), arrayOf(serverCert))

            val keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, "password".toCharArray())

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())

            val sslSocket =
                    sslContext.socketFactory.createSocket(
                            clientSocket,
                            clientSocket.inetAddress.hostAddress,
                            clientSocket.port,
                            true
                    )

            // Lese verschlüsselten Request vom Client
            val sslReader = BufferedReader(InputStreamReader(sslSocket.inputStream))
            val sslWriter = BufferedWriter(OutputStreamWriter(sslSocket.outputStream))

            // Lese HTTPS-Request
            val requestLine = sslReader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val path = parts[1]

            // Lese Headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (sslReader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val colonIndex = line!!.indexOf(':')
                if (colonIndex > 0) {
                    val key = line!!.substring(0, colonIndex).trim()
                    val value = line!!.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }

            val fullUrl = "https://$host$path"

            // Logge Request und speichere RequestId
            val requestId = logRequest(method, fullUrl, headers)
            val requestTime = Clock.System.now()

            // Leite Request zum echten Server weiter
            val response = forwardRequest(method, fullUrl, headers)

            // Capture Response Body
            val capturedBody = captureResponseBody(response)

            // Logge Response mit allen HTTP-Details
            logResponse(requestId, requestTime, fullUrl, response, capturedBody)

            // Sende Response zum Client
            sslWriter.write("HTTP/1.1 ${response.code} ${response.message}\r\n")
            response.headers.forEach { (name, value) -> sslWriter.write("$name: $value\r\n") }
            sslWriter.write("\r\n")
            sslWriter.flush()

            // Sende Body zum Client (nutze gecachte Version falls vorhanden)
            if (capturedBody != null) {
                sslWriter.write(capturedBody)
                sslWriter.flush()
            } else {
                response.body?.byteStream()?.copyTo(sslSocket.outputStream)
                sslSocket.outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling CONNECT", e)
        }
    }

    /** Behandelt HTTP-Request (nicht verschlüsselt). */
    private suspend fun handleHttpRequest(
            method: String,
            url: String,
            headers: Map<String, String>,
            writer: BufferedWriter,
            clientSocket: Socket,
            requestBody: ByteArray? = null
    ) {
        try {
            // Logge Request und speichere RequestId
            val requestId = logRequest(method, url, headers)
            val requestTime = Clock.System.now()

            // Leite Request weiter (mit Body falls vorhanden)
            val response = forwardRequest(method, url, headers, requestBody)

            // Capture Response Body
            val capturedBody = captureResponseBody(response)

            // Logge Response mit allen HTTP-Details
            logResponse(requestId, requestTime, url, response, capturedBody)

            // Sende Response Headers
            writer.write("HTTP/1.1 ${response.code} ${response.message}\r\n")
            response.headers.forEach { (name, value) -> writer.write("$name: $value\r\n") }
            writer.write("\r\n")
            writer.flush()

            // Sende Body zum Client - byte-safe für Binary Content
            val responseBody = response.body
            if (responseBody != null) {
                val outputStream = clientSocket.getOutputStream()
                if (capturedBody != null) {
                    // Text content, already captured
                    outputStream.write(capturedBody.toByteArray(Charsets.UTF_8))
                } else {
                    // Binary content - copy raw bytes
                    responseBody.byteStream().copyTo(outputStream)
                }
                outputStream.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling HTTP request", e)
        }
    }

    /** Liest Request Body basierend auf Content-Length Header. */
    private fun readRequestBody(
            inputStream: java.io.InputStream,
            headers: Map<String, String>
    ): ByteArray? {
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: return null
        if (contentLength <= 0 || contentLength > MAX_TEXT_BODY_SIZE) return null

        return try {
            val buffer = ByteArray(contentLength)
            var bytesRead = 0
            while (bytesRead < contentLength) {
                val read = inputStream.read(buffer, bytesRead, contentLength - bytesRead)
                if (read < 0) break
                bytesRead += read
            }
            buffer
        } catch (e: Exception) {
            Log.e(TAG, "Error reading request body", e)
            null
        }
    }

    /** Leitet Request zum echten Server weiter. */
    private suspend fun forwardRequest(
            method: String,
            url: String,
            headers: Map<String, String>,
            requestBody: ByteArray? = null
    ): Response {
        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)

            // Build request body for methods that support it
            val body =
                    if (requestBody != null && method in listOf("POST", "PUT", "PATCH")) {
                        val contentType =
                                headers["Content-Type"]?.let { okhttp3.MediaType.parse(it) }
                                        ?: okhttp3.MediaType.parse("application/octet-stream")
                        okhttp3.RequestBody.create(contentType, requestBody)
                    } else {
                        null
                    }

            requestBuilder.method(method, body)

            headers.forEach { (key, value) ->
                if (key.lowercase() !in listOf("proxy-connection", "content-length")) {
                    requestBuilder.addHeader(key, value)
                }
            }

            httpClient.newCall(requestBuilder.build()).execute()
        }
    }

    /** Loggt einen Request als RecorderEvent und gibt die EventId zurück. */
    private fun logRequest(
            method: String,
            url: String,
            headers: Map<String, String>
    ): dev.fishit.mapper.contract.EventId {
        val eventId = IdGenerator.newEventId()
        val event =
                ResourceRequestEvent(
                        id = eventId,
                        at = Clock.System.now(),
                        url = url,
                        initiatorUrl = null,
                        method = method,
                        resourceKind = dev.fishit.mapper.contract.ResourceKind.Other
                )

        onEvent(event)
        Log.d(TAG, "Request logged: $method $url")
        return eventId
    }

    /** Erfasst Response Body falls Content-Type relevant ist. */
    private fun captureResponseBody(response: Response): String? {
        val contentType = response.header("Content-Type")?.lowercase() ?: return null
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0

        val body = response.body ?: return null

        // Prüfe, ob Content-Type text-basiert ist
        val isTextContent = TEXT_CONTENT_TYPES.any { contentType.contains(it) }

        // Bestimme Max-Size basierend auf Content-Type
        val maxSize = if (isTextContent) MAX_TEXT_BODY_SIZE else MAX_BINARY_BODY_SIZE

        // Überspringe zu große Bodies
        if (contentLength > maxSize) {
            Log.d(TAG, "Body too large ($contentLength bytes), skipping capture")
            return null
        }

        return try {
            val bodyString = body.string()
            if (bodyString.length > maxSize) {
                bodyString.substring(0, maxSize)
            } else {
                bodyString
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing body", e)
            null
        }
    }

    /** Loggt Response mit allen relevanten HTTP-Details. */
    private fun logResponse(
            requestId: dev.fishit.mapper.contract.EventId,
            requestTime: Instant,
            url: String,
            response: Response,
            capturedBody: String?
    ) {
        val statusCode = response.code
        val isRedirect = statusCode in REDIRECT_STATUS_CODES
        val redirectLocation = if (isRedirect) response.header("Location") else null

        // Konvertiere OkHttp Headers zu Map
        val headersMap = mutableMapOf<String, String>()
        response.headers.forEach { (name, value) -> headersMap[name] = value }

        val event =
                ResourceResponseEvent(
                        id = IdGenerator.newEventId(),
                        requestId = requestId,
                        at = Clock.System.now(),
                        url = url,
                        statusCode = statusCode,
                        statusMessage = response.message,
                        headers = headersMap,
                        contentType = response.header("Content-Type"),
                        contentLength = response.header("Content-Length")?.toLongOrNull(),
                        body = capturedBody,
                        bodyTruncated =
                                capturedBody != null &&
                                        capturedBody.length.toLong() <
                                                (response.header("Content-Length")?.toLongOrNull()
                                                        ?: 0),
                        responseTimeMs =
                                Clock.System.now().toEpochMilliseconds() -
                                        requestTime.toEpochMilliseconds(),
                        isRedirect = isRedirect,
                        redirectLocation = redirectLocation
                )

        onEvent(event)

        if (isRedirect) {
            Log.d(TAG, "REDIRECT: $statusCode $url → $redirectLocation")
        } else {
            Log.d(TAG, "Response logged: $statusCode $url")
        }
    }

    /** Prüft, ob der Server läuft. */
    fun isRunning(): Boolean {
        return serverSocket != null && !serverSocket!!.isClosed
    }
}
