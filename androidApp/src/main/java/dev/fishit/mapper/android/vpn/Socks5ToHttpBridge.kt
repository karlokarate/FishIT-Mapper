package dev.fishit.mapper.android.vpn

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * SOCKS5 zu HTTP Bridge Server.
 * 
 * Nimmt SOCKS5 Verbindungen entgegen (von tun2socks) und leitet sie
 * an den HTTP Proxy weiter.
 * 
 * SOCKS5 Protocol RFC 1928:
 * 1. Client Greeting: VERSION(1) + NMETHODS(1) + METHODS(1-255)
 * 2. Server Choice: VERSION(1) + METHOD(1)
 * 3. Client Connection Request: VER(1) + CMD(1) + RSV(1) + ATYP(1) + DST.ADDR(var) + DST.PORT(2)
 * 4. Server Reply: VER(1) + REP(1) + RSV(1) + ATYP(1) + BND.ADDR(var) + BND.PORT(2)
 * 5. Data Transfer
 */
class Socks5ToHttpBridge(
    private val socksPort: Int = 1080,
    private val httpProxyPort: Int = 8888
) {
    companion object {
        private const val TAG = "Socks5Bridge"
        
        // SOCKS5 Protocol Constants
        private const val SOCKS_VERSION = 0x05.toByte()
        private const val METHOD_NO_AUTH = 0x00.toByte()
        private const val METHOD_NO_ACCEPTABLE = 0xFF.toByte()
        
        private const val CMD_CONNECT = 0x01.toByte()
        private const val CMD_BIND = 0x02.toByte()
        private const val CMD_UDP_ASSOCIATE = 0x03.toByte()
        
        private const val ATYP_IPV4 = 0x01.toByte()
        private const val ATYP_DOMAIN = 0x03.toByte()
        private const val ATYP_IPV6 = 0x04.toByte()
        
        private const val REP_SUCCESS = 0x00.toByte()
        private const val REP_GENERAL_FAILURE = 0x01.toByte()
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07.toByte()
    }
    
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build()
    
    /**
     * Startet den SOCKS5 Server.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "SOCKS5 server already running")
            return@withContext
        }
        
        try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress("127.0.0.1", socksPort))
            isRunning = true
            
            Log.i(TAG, "SOCKS5 server started on port $socksPort")
            
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    Log.d(TAG, "New SOCKS5 client connected")
                    
                    // Handle each client in separate thread
                    Thread {
                        handleClient(clientSocket)
                    }.start()
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "Socket error", e)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS5 server error", e)
        } finally {
            isRunning = false
        }
    }
    
    /**
     * Stoppt den SOCKS5 Server.
     */
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "SOCKS5 server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SOCKS5 server", e)
        }
    }
    
    /**
     * Behandelt einen SOCKS5 Client.
     */
    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000 // 30 second timeout
            val input = client.getInputStream()
            val output = client.getOutputStream()
            
            // Step 1: Handshake
            if (!performHandshake(input, output)) {
                Log.w(TAG, "SOCKS5 handshake failed")
                client.close()
                return
            }
            
            // Step 2: Read connection request
            val connectionRequest = readConnectionRequest(input)
            if (connectionRequest == null) {
                sendReply(output, REP_GENERAL_FAILURE)
                client.close()
                return
            }
            
            Log.d(TAG, "SOCKS5 CONNECT to ${connectionRequest.host}:${connectionRequest.port}")
            
            // Step 3: For CONNECT command, establish connection
            if (connectionRequest.command == CMD_CONNECT) {
                // Send success reply
                sendReply(output, REP_SUCCESS)
                
                // Forward data between SOCKS5 client and HTTP proxy
                forwardToHttpProxy(client, connectionRequest)
            } else {
                // Command not supported
                sendReply(output, REP_COMMAND_NOT_SUPPORTED)
                client.close()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling SOCKS5 client", e)
        } finally {
            try {
                client.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * Führt SOCKS5 Handshake durch.
     * 
     * Client: VER | NMETHODS | METHODS
     * Server: VER | METHOD
     */
    private fun performHandshake(input: InputStream, output: OutputStream): Boolean {
        try {
            // Read client greeting
            val version = input.read()
            if (version != SOCKS_VERSION.toInt()) {
                Log.w(TAG, "Unsupported SOCKS version: $version")
                return false
            }
            
            val nMethods = input.read()
            if (nMethods < 1) {
                return false
            }
            
            val methods = ByteArray(nMethods)
            val bytesRead = input.read(methods)
            if (bytesRead != nMethods) {
                return false
            }
            
            // We only support NO_AUTH method
            val hasNoAuth = methods.contains(METHOD_NO_AUTH)
            
            // Send method selection
            if (hasNoAuth) {
                output.write(byteArrayOf(SOCKS_VERSION, METHOD_NO_AUTH))
                output.flush()
                return true
            } else {
                output.write(byteArrayOf(SOCKS_VERSION, METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake error", e)
            return false
        }
    }
    
    /**
     * Liest SOCKS5 Connection Request.
     * 
     * Request: VER | CMD | RSV | ATYP | DST.ADDR | DST.PORT
     */
    private fun readConnectionRequest(input: InputStream): ConnectionRequest? {
        try {
            val version = input.read()
            if (version != SOCKS_VERSION.toInt()) {
                return null
            }
            
            val command = input.read().toByte()
            val reserved = input.read() // Must be 0x00
            val addressType = input.read().toByte()
            
            // Read destination address
            val host = when (addressType) {
                ATYP_IPV4 -> {
                    val addr = ByteArray(4)
                    input.read(addr)
                    "${addr[0].toUByte()}.${addr[1].toUByte()}.${addr[2].toUByte()}.${addr[3].toUByte()}"
                }
                ATYP_DOMAIN -> {
                    val length = input.read()
                    val domain = ByteArray(length)
                    input.read(domain)
                    String(domain)
                }
                ATYP_IPV6 -> {
                    // Read 16 bytes for IPv6
                    val addr = ByteArray(16)
                    input.read(addr)
                    // Format as IPv6 string
                    addr.joinToString(":") { String.format("%02x", it) }
                }
                else -> {
                    Log.w(TAG, "Unsupported address type: $addressType")
                    return null
                }
            }
            
            // Read port (2 bytes, big-endian)
            val portHigh = input.read()
            val portLow = input.read()
            val port = (portHigh shl 8) or portLow
            
            return ConnectionRequest(command, host, port)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading connection request", e)
            return null
        }
    }
    
    /**
     * Sendet SOCKS5 Reply.
     * 
     * Reply: VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
     */
    private fun sendReply(output: OutputStream, replyCode: Byte) {
        try {
            // Send simple reply with IPv4 0.0.0.0:0
            val reply = byteArrayOf(
                SOCKS_VERSION,    // VER
                replyCode,        // REP
                0x00,             // RSV
                ATYP_IPV4,        // ATYP
                0, 0, 0, 0,       // BND.ADDR (0.0.0.0)
                0, 0              // BND.PORT (0)
            )
            output.write(reply)
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending reply", e)
        }
    }
    
    /**
     * Leitet Daten zwischen SOCKS5 Client und HTTP Proxy weiter.
     * 
     * Für HTTP/HTTPS Requests:
     * 1. Liest Request vom SOCKS5 client
     * 2. Sendet an HTTP Proxy (127.0.0.1:8888)
     * 3. Empfängt Response vom Proxy
     * 4. Sendet Response zurück an SOCKS5 client
     */
    private fun forwardToHttpProxy(socksClient: Socket, request: ConnectionRequest) {
        var proxySocket: Socket? = null
        
        try {
            // Connect to HTTP proxy
            proxySocket = Socket()
            proxySocket.connect(InetSocketAddress("127.0.0.1", httpProxyPort), 5000)
            proxySocket.soTimeout = 30000
            
            Log.d(TAG, "Connected to HTTP proxy for ${request.host}:${request.port}")
            
            // Create bidirectional forwarding threads
            val clientToProxy = Thread {
                try {
                    socksClient.getInputStream().copyTo(proxySocket.getOutputStream())
                } catch (e: Exception) {
                    // Connection closed
                }
            }
            
            val proxyToClient = Thread {
                try {
                    proxySocket.getInputStream().copyTo(socksClient.getOutputStream())
                } catch (e: Exception) {
                    // Connection closed
                }
            }
            
            clientToProxy.start()
            proxyToClient.start()
            
            // Wait for both threads to finish
            clientToProxy.join()
            proxyToClient.join()
            
            Log.d(TAG, "Connection closed: ${request.host}:${request.port}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding to HTTP proxy", e)
        } finally {
            try {
                proxySocket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * SOCKS5 Connection Request data.
     */
    private data class ConnectionRequest(
        val command: Byte,
        val host: String,
        val port: Int
    )
}
