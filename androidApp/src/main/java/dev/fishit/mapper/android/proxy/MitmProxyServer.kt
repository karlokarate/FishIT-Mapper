package dev.fishit.mapper.android.proxy

import android.content.Context
import android.util.Log
import dev.fishit.mapper.android.cert.CertificateManager
import dev.fishit.mapper.contract.RecorderEvent
import dev.fishit.mapper.contract.ResourceRequestEvent
import dev.fishit.mapper.engine.IdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.KeyManagerFactory
import java.security.KeyStore

/**
 * MITM-Proxy-Server für HTTPS-Decryption.
 * Fängt HTTP/HTTPS-Requests ab, entschlüsselt sie und leitet sie weiter.
 */
class MitmProxyServer(
    private val context: Context,
    private val port: Int = 8888,
    private val onEvent: (RecorderEvent) -> Unit
) {
    companion object {
        private const val TAG = "MitmProxyServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private val certificateManager = CertificateManager(context)
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build()

    /**
     * Startet den Proxy-Server.
     */
    fun start() {
        if (serverSocket != null) {
            Log.w(TAG, "Proxy server already running")
            return
        }

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Proxy server started on port $port")

                while (true) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy server error", e)
            }
        }
    }

    /**
     * Stoppt den Proxy-Server.
     */
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        
        serverSocket?.close()
        serverSocket = null
        
        Log.i(TAG, "Proxy server stopped")
    }

    /**
     * Behandelt eine Client-Verbindung.
     */
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
                    reader = reader
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            clientSocket.close()
        }
    }

    /**
     * Behandelt HTTPS CONNECT-Request.
     */
    private suspend fun handleConnect(
        clientSocket: Socket,
        writer: BufferedWriter,
        host: String
    ) {
        try {
            // Sende 200 Connection Established
            writer.write("HTTP/1.1 200 Connection Established\r\n")
            writer.write("\r\n")
            writer.flush()

            // Erstelle SSL-Socket mit unserem Zertifikat
            val domain = host.split(":")[0]
            val (caCert, caKey) = certificateManager.getOrCreateCACertificate()
            val (serverCert, serverKey) = certificateManager.generateServerCertificate(
                domain, caCert, caKey
            )

            // Erstelle KeyStore mit Server-Zertifikat
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setKeyEntry(
                "server",
                serverKey,
                "password".toCharArray(),
                arrayOf(serverCert)
            )

            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, "password".toCharArray())

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())

            val sslSocket = sslContext.socketFactory.createSocket(
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

            // Logge Request
            logRequest(method, fullUrl, headers)

            // Leite Request zum echten Server weiter
            val response = forwardRequest(method, fullUrl, headers)

            // Sende Response zum Client
            sslWriter.write("HTTP/1.1 ${response.code} ${response.message}\r\n")
            response.headers.forEach { (name, value) ->
                sslWriter.write("$name: $value\r\n")
            }
            sslWriter.write("\r\n")
            sslWriter.flush()

            response.body?.byteStream()?.copyTo(sslSocket.outputStream)
            sslSocket.outputStream.flush()

        } catch (e: Exception) {
            Log.e(TAG, "Error handling CONNECT", e)
        }
    }

    /**
     * Behandelt HTTP-Request (nicht verschlüsselt).
     */
    private suspend fun handleHttpRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        writer: BufferedWriter,
        reader: BufferedReader
    ) {
        try {
            // Logge Request
            logRequest(method, url, headers)

            // Leite Request weiter
            val response = forwardRequest(method, url, headers)

            // Sende Response
            writer.write("HTTP/1.1 ${response.code} ${response.message}\r\n")
            response.headers.forEach { (name, value) ->
                writer.write("$name: $value\r\n")
            }
            writer.write("\r\n")
            writer.flush()

            response.body?.string()?.let { body ->
                writer.write(body)
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling HTTP request", e)
        }
    }

    /**
     * Leitet Request zum echten Server weiter.
     */
    private suspend fun forwardRequest(
        method: String,
        url: String,
        headers: Map<String, String>
    ): Response {
        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder()
                .url(url)
                .method(method, null)

            headers.forEach { (key, value) ->
                if (key.lowercase() != "proxy-connection") {
                    requestBuilder.addHeader(key, value)
                }
            }

            httpClient.newCall(requestBuilder.build()).execute()
        }
    }

    /**
     * Loggt einen Request als RecorderEvent.
     */
    private fun logRequest(method: String, url: String, headers: Map<String, String>) {
        val event = ResourceRequestEvent(
            id = IdGenerator.newEventId(),
            at = Clock.System.now(),
            url = url,
            initiatorUrl = null,
            method = method,
            resourceKind = dev.fishit.mapper.contract.ResourceKind.Other
        )
        
        onEvent(event)
        Log.d(TAG, "Request logged: $method $url")
    }

    /**
     * Prüft, ob der Server läuft.
     */
    fun isRunning(): Boolean {
        return serverSocket != null && !serverSocket!!.isClosed
    }
}
