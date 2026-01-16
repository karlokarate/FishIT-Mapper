package dev.fishit.mapper.android.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * SOCKS5 Proxy Server der als Bridge zwischen TUN-Interface und HTTP-Proxy fungiert.
 * 
 * Implementiert SOCKS5 Protocol (RFC 1928) mit folgenden Features:
 * - No Authentication (METHOD 0x00)
 * - CONNECT command (CMD 0x01)
 * - IPv4 addresses (ATYP 0x01)
 * - Domain names (ATYP 0x03)
 * 
 * Die Implementation leitet TCP-Verbindungen vom SOCKS5 Client zum HTTP-Proxy weiter.
 */
class Socks5Server(
    private val port: Int = 1080,
    private val httpProxyHost: String = "127.0.0.1",
    private val httpProxyPort: Int = 8888
) {
    companion object {
        private const val TAG = "Socks5Server"
        
        // SOCKS5 Protocol Constants
        private const val SOCKS_VERSION = 0x05.toByte()
        private const val METHOD_NO_AUTH = 0x00.toByte()
        private const val METHOD_NO_ACCEPTABLE = 0xFF.toByte()
        private const val CMD_CONNECT = 0x01.toByte()
        private const val ATYP_IPV4 = 0x01.toByte()
        private const val ATYP_DOMAIN = 0x03.toByte()
        private const val ATYP_IPV6 = 0x04.toByte()
        private const val REP_SUCCESS = 0x00.toByte()
        private const val REP_GENERAL_FAILURE = 0x01.toByte()
        private const val REP_CONNECTION_NOT_ALLOWED = 0x02.toByte()
        private const val REP_NETWORK_UNREACHABLE = 0x03.toByte()
        private const val REP_HOST_UNREACHABLE = 0x04.toByte()
        private const val REP_CONNECTION_REFUSED = 0x05.toByte()
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07.toByte()
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08.toByte()
        
        private const val BUFFER_SIZE = 8192
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile private var isRunning = false

    /**
     * Startet den SOCKS5 Server.
     */
    fun start() {
        if (isRunning) {
            Log.w(TAG, "SOCKS5 server already running")
            return
        }

        // Erstelle neue Scope bei jedem Start (ermöglicht Restart nach stop())
        scope = CoroutineScope(Dispatchers.IO)

        serverJob = scope?.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.i(TAG, "SOCKS5 server started on port $port, forwarding to HTTP proxy at $httpProxyHost:$httpProxyPort")

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: IOException) {
                        if (isRunning) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 server error", e)
            } finally {
                isRunning = false
            }
        }
    }

    /**
     * Stoppt den SOCKS5 Server.
     */
    fun stop() {
        Log.i(TAG, "Stopping SOCKS5 server...")
        isRunning = false
        
        serverJob?.cancel()
        serverJob = null
        
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        
        scope?.cancel()
        scope = null
        
        Log.i(TAG, "SOCKS5 server stopped")
    }

    /**
     * Prüft ob der Server läuft.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Behandelt eine Client-Verbindung gemäß SOCKS5 Protocol.
     */
    private suspend fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 30000 // 30 second timeout
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            // Step 1: Authentication negotiation
            if (!handleAuthentication(input, output)) {
                Log.w(TAG, "Authentication negotiation failed")
                clientSocket.close()
                return
            }

            // Step 2: Request processing
            val targetAddress = handleRequest(input, output)
            if (targetAddress == null) {
                Log.w(TAG, "Request processing failed")
                clientSocket.close()
                return
            }

            Log.d(TAG, "SOCKS5 CONNECT request to ${targetAddress.first}:${targetAddress.second}")

            // Step 3: Connect to HTTP proxy and establish tunnel
            connectToHttpProxy(clientSocket, targetAddress.first, targetAddress.second, input, output)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling SOCKS5 client", e)
        } finally {
            try {
                clientSocket.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
    }

    /**
     * SOCKS5 Authentication Negotiation (RFC 1928 Section 3)
     * 
     * Client sendet:
     *   +----+----------+----------+
     *   |VER | NMETHODS | METHODS  |
     *   +----+----------+----------+
     * 
     * Server antwortet:
     *   +----+--------+
     *   |VER | METHOD |
     *   +----+--------+
     */
    private fun handleAuthentication(input: InputStream, output: OutputStream): Boolean {
        try {
            val version = input.read().toByte()
            if (version != SOCKS_VERSION) {
                Log.w(TAG, "Invalid SOCKS version: $version")
                return false
            }

            val nMethods = input.read()
            if (nMethods <= 0) {
                Log.w(TAG, "Invalid number of methods: $nMethods")
                return false
            }

            val methods = ByteArray(nMethods)
            val bytesRead = input.read(methods)
            if (bytesRead != nMethods) {
                Log.w(TAG, "Failed to read all methods")
                return false
            }

            // Check if NO_AUTH method is supported
            val supportsNoAuth = methods.contains(METHOD_NO_AUTH)
            
            // Send response
            output.write(byteArrayOf(
                SOCKS_VERSION,
                if (supportsNoAuth) METHOD_NO_AUTH else METHOD_NO_ACCEPTABLE
            ))
            output.flush()

            return supportsNoAuth
        } catch (e: Exception) {
            Log.e(TAG, "Error in authentication negotiation", e)
            return false
        }
    }

    /**
     * SOCKS5 Request Processing (RFC 1928 Section 4)
     * 
     * Client sendet:
     *   +----+-----+-------+------+----------+----------+
     *   |VER | CMD |  RSV  | ATYP | DST.ADDR | DST.PORT |
     *   +----+-----+-------+------+----------+----------+
     * 
     * @return Pair of (hostname, port) oder null bei Fehler
     */
    private fun handleRequest(input: InputStream, output: OutputStream): Pair<String, Int>? {
        try {
            val version = input.read().toByte()
            if (version != SOCKS_VERSION) {
                sendErrorResponse(output, REP_GENERAL_FAILURE)
                return null
            }

            val cmd = input.read().toByte()
            if (cmd != CMD_CONNECT) {
                sendErrorResponse(output, REP_COMMAND_NOT_SUPPORTED)
                Log.w(TAG, "Unsupported command: $cmd")
                return null
            }

            val rsv = input.read() // Reserved, ignore

            val atyp = input.read().toByte()
            val hostname = when (atyp) {
                ATYP_IPV4 -> {
                    val addr = ByteArray(4)
                    if (input.read(addr) != 4) {
                        sendErrorResponse(output, REP_GENERAL_FAILURE)
                        return null
                    }
                    InetAddress.getByAddress(addr).hostAddress
                }
                ATYP_DOMAIN -> {
                    val len = input.read()
                    if (len <= 0) {
                        sendErrorResponse(output, REP_GENERAL_FAILURE)
                        return null
                    }
                    val domain = ByteArray(len)
                    if (input.read(domain) != len) {
                        sendErrorResponse(output, REP_GENERAL_FAILURE)
                        return null
                    }
                    String(domain, Charsets.UTF_8)
                }
                ATYP_IPV6 -> {
                    // IPv6 not fully supported yet, but read the address
                    val addr = ByteArray(16)
                    if (input.read(addr) != 16) {
                        sendErrorResponse(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                        return null
                    }
                    sendErrorResponse(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                    return null
                }
                else -> {
                    sendErrorResponse(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                    Log.w(TAG, "Unsupported address type: $atyp")
                    return null
                }
            }

            // Read port (2 bytes, big endian)
            val portHigh = input.read()
            val portLow = input.read()
            if (portHigh < 0 || portLow < 0) {
                sendErrorResponse(output, REP_GENERAL_FAILURE)
                return null
            }
            val port = (portHigh shl 8) or portLow

            return Pair(hostname, port)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing request", e)
            sendErrorResponse(output, REP_GENERAL_FAILURE)
            return null
        }
    }

    /**
     * Sendet SOCKS5 Error Response
     */
    private fun sendErrorResponse(output: OutputStream, errorCode: Byte) {
        try {
            output.write(byteArrayOf(
                SOCKS_VERSION,
                errorCode,
                0x00, // RSV
                ATYP_IPV4,
                0, 0, 0, 0, // Bind address (0.0.0.0)
                0, 0 // Bind port (0)
            ))
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending error response", e)
        }
    }

    /**
     * Verbindet mit HTTP Proxy via CONNECT method und etabliert Tunnel.
     */
    private suspend fun connectToHttpProxy(
        clientSocket: Socket,
        targetHost: String,
        targetPort: Int,
        clientInput: InputStream,
        clientOutput: OutputStream
    ) {
        var proxySocket: Socket? = null
        try {
            // Connect to HTTP proxy
            proxySocket = Socket()
            proxySocket.connect(InetSocketAddress(httpProxyHost, httpProxyPort), 10000)
            proxySocket.soTimeout = 30000
            
            val proxyInput = proxySocket.getInputStream()
            val proxyOutput = proxySocket.getOutputStream()

            // Send HTTP CONNECT request to proxy
            val connectRequest = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
                    "Host: $targetHost:$targetPort\r\n" +
                    "Proxy-Connection: Keep-Alive\r\n" +
                    "\r\n"
            
            proxyOutput.write(connectRequest.toByteArray(Charsets.UTF_8))
            proxyOutput.flush()

            // Read HTTP CONNECT response
            val responseLine = readLine(proxyInput)
            if (responseLine == null || !responseLine.contains("200")) {
                Log.w(TAG, "HTTP CONNECT failed: $responseLine")
                sendErrorResponse(clientOutput, REP_CONNECTION_REFUSED)
                return
            }

            // Skip remaining headers
            while (true) {
                val line = readLine(proxyInput) ?: break
                if (line.isEmpty()) break
            }

            Log.d(TAG, "HTTP CONNECT tunnel established for $targetHost:$targetPort")

            // Send SOCKS5 success response
            sendSuccessResponse(clientOutput)

            // Erstelle connection-spezifische Scope für Forwarding
            // Dies ermöglicht Cancellation einzelner Connections ohne Server-Stop
            val connectionScope = CoroutineScope(Dispatchers.IO)
            
            try {
                // Start bidirectional data forwarding and wait for completion
                // Both directions run in parallel and terminate when either closes
                val clientToProxyJob = connectionScope.launch {
                    forwardData(clientInput, proxyOutput, "client->proxy")
                }
                val proxyToClientJob = connectionScope.launch {
                    forwardData(proxyInput, clientOutput, "proxy->client")
                }
                
                // Wait for both forwarding jobs to complete
                // (they complete when connection closes or error occurs)
                try {
                    clientToProxyJob.join()
                    proxyToClientJob.join()
                } catch (e: Exception) {
                    // Fehler beim Daten-Forwarding protokollieren
                    Log.e(TAG, "Error during data forwarding for $targetHost:$targetPort", e)
                } finally {
                    // Ensure both jobs are cancelled for cleanup
                    clientToProxyJob.cancel()
                    proxyToClientJob.cancel()
                    connectionScope.cancel()
                }
            } finally {
                // Ensure proxy socket is closed
                proxySocket?.close()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to HTTP proxy", e)
            sendErrorResponse(clientOutput, REP_CONNECTION_REFUSED)
            proxySocket?.close()
        }
    }

    /**
     * Sendet SOCKS5 Success Response (RFC 1928 Section 6)
     */
    private fun sendSuccessResponse(output: OutputStream) {
        try {
            output.write(byteArrayOf(
                SOCKS_VERSION,
                REP_SUCCESS,
                0x00, // RSV
                ATYP_IPV4,
                0, 0, 0, 0, // Bind address (0.0.0.0)
                0, 0 // Bind port (0)
            ))
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending success response", e)
        }
    }

    /**
     * Liest eine Zeile (bis \r\n) aus dem InputStream.
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code && prev == '\r'.code) {
                return sb.substring(0, sb.length - 1) // Remove trailing \r
            }
            sb.append(c.toChar())
            prev = c
        }
    }

    /**
     * Leitet Daten bidirectional zwischen zwei Streams weiter.
     */
    private fun forwardData(input: InputStream, output: OutputStream, direction: String) {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0L
        try {
            while (isRunning) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
                totalBytes += count
            }
            Log.d(TAG, "Forwarding $direction completed: $totalBytes bytes")
        } catch (e: Exception) {
            if (isRunning) {
                Log.d(TAG, "Forwarding $direction ended: ${e.message}")
            }
        }
    }
}
