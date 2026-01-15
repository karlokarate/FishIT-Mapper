package dev.fishit.mapper.android.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel

/**
 * VPN Service für System-weite Netzwerk-Traffic-Erfassung.
 * Leitet allen Traffic über einen lokalen Proxy, um HTTPS zu entschlüsseln.
 */
class TrafficCaptureVpnService : VpnService() {

    companion object {
        private const val TAG = "TrafficCaptureVpn"
        const val ACTION_START_VPN = "dev.fishit.mapper.START_VPN"
        const val ACTION_STOP_VPN = "dev.fishit.mapper.STOP_VPN"
        
        // VPN-Konfiguration
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        
        // Proxy-Konfiguration
        const val PROXY_PORT = 8888
        private const val PROXY_ADDRESS = "127.0.0.1"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private var packetForwardingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START_VPN -> {
                startVpn()
                START_STICKY
            }
            ACTION_STOP_VPN -> {
                stopVpn()
                stopSelf()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            Log.w(TAG, "VPN already running")
            return
        }

        try {
            // Erstelle VPN-Interface mit verbesserter Konfiguration
            val builder = Builder()
                .setSession("FishIT-Mapper VPN")
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(VPN_DNS)
                .addDnsServer("8.8.4.4") // Backup DNS
                .setMtu(1500)
                .setBlocking(false) // Non-blocking für bessere Performance
            
            // Erlaube Apps, die nicht durch VPN geroutet werden sollen
            try {
                // Lasse die eigene App durch (um Loops zu vermeiden)
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude own app from VPN", e)
            }

            // Setze diesen VPN als System-Standard
            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            Log.i(TAG, "VPN interface established")

            // Starte Packet-Forwarding in Coroutine
            serviceScope = CoroutineScope(Dispatchers.IO)
            packetForwardingJob = serviceScope?.launch {
                forwardPackets()
            }

            Log.i(TAG, "VPN started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN...")

        packetForwardingJob?.cancel()
        packetForwardingJob = null

        serviceScope?.cancel()
        serviceScope = null

        vpnInterface?.close()
        vpnInterface = null

        Log.i(TAG, "VPN stopped")
    }

    /**
     * Leitet Netzwerk-Pakete vom VPN-Interface zum Proxy weiter.
     * 
     * WICHTIGER HINWEIS: Diese Implementierung ist vereinfacht und nicht vollständig funktional.
     * Eine vollständige VPN-Implementierung würde benötigen:
     * 1. Einen kompletten TCP/IP-Stack (z.B. lwIP)
     * 2. NAT (Network Address Translation)
     * 3. TCP-State-Machine für Verbindungen
     * 4. Proper Packet-Reassembly
     * 5. Socket-Connection-Pool für Proxy-Forwarding
     * 
     * Für die MVP-Version wird empfohlen:
     * - WebView mit JavaScript-Bridge für Browser-Traffic (bereits implementiert)
     * - System-weite Proxy-Einstellungen für andere Apps
     * - Oder Verwendung einer VPN-Library wie tun2socks oder Clash
     * 
     * Siehe: https://github.com/shadowsocks/shadowsocks-android/tree/master/core
     */
    private suspend fun forwardPackets() {
        val vpnFd = vpnInterface ?: return
        val inputStream = FileInputStream(vpnFd.fileDescriptor)
        val outputStream = FileOutputStream(vpnFd.fileDescriptor)

        val buffer = ByteBuffer.allocate(32767)

        try {
            while (true) {
                // Lese Paket vom VPN-Interface
                val length = inputStream.read(buffer.array())
                if (length <= 0) break

                buffer.limit(length)
                buffer.position(0)

                // Analysiere IP-Header (vereinfacht)
                val version = (buffer.get(0).toInt() and 0xF0) shr 4
                if (version != 4) {
                    // Nur IPv4 unterstützen
                    buffer.clear()
                    continue
                }

                val protocol = buffer.get(9).toInt() and 0xFF
                
                // WARNUNG: Einfaches Durchreichen ohne Proxy-Routing
                // Dies führt dazu, dass VPN zwar aktiv ist, aber Traffic nicht funktioniert
                // TCP (6) und UDP (17) Pakete bearbeiten
                when (protocol) {
                    6 -> handleTcpPacket(buffer, outputStream)
                    17 -> handleUdpPacket(buffer, outputStream)
                    else -> {
                        // Andere Protokolle ignorieren
                    }
                }

                buffer.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding packets", e)
        }
    }

    /**
     * Behandelt TCP-Pakete (HTTP/HTTPS Traffic).
     * Leitet sie zum lokalen Proxy weiter.
     */
    private fun handleTcpPacket(packet: ByteBuffer, outputStream: FileOutputStream) {
        try {
            // Parse TCP-Header (vereinfacht)
            val ipHeaderLength = (packet.get(0).toInt() and 0x0F) * 4
            val destPort = packet.getShort(ipHeaderLength + 2).toInt() and 0xFFFF
            
            // Prüfe, ob es HTTP/HTTPS Traffic ist (Port 80/443)
            if (destPort == 80 || destPort == 443) {
                // In Produktion: Paket zum Proxy umleiten
                // Für MVP: Pakete durchreichen
                Log.d(TAG, "TCP packet to port $destPort")
            }

            // Paket durchreichen (vereinfacht)
            outputStream.write(packet.array(), 0, packet.limit())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling TCP packet", e)
        }
    }

    /**
     * Behandelt UDP-Pakete (DNS, etc.).
     */
    private fun handleUdpPacket(packet: ByteBuffer, outputStream: FileOutputStream) {
        try {
            // Paket durchreichen (vereinfacht)
            outputStream.write(packet.array(), 0, packet.limit())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling UDP packet", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        Log.i(TAG, "VPN Service destroyed")
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
        Log.i(TAG, "VPN permission revoked")
    }
}
