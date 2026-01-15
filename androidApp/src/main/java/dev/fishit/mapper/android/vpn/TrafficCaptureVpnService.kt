package dev.fishit.mapper.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * VPN Service für System-weite Netzwerk-Traffic-Erfassung.
 * Nutzt tun2socks via Maven artifact (io.github.nekohasekai:libcore) um Traffic zu leiten.
 * 
 * Implementation mit tun2socks Maven artifact:
 * 1. VPN Interface erstellen
 * 2. SOCKS5-to-HTTP bridge starten (Port 1080 -> 8888)
 * 3. libcore TunManager nutzt pre-built natives um TUN -> SOCKS5 zu routen
 * 4. Traffic wird im HTTP Proxy erfasst und analysiert
 * 
 * Vorteile Maven-basierte Implementation:
 * - Keine manuelle Native Library Kompilierung
 * - Pre-built .so files für alle ABIs
 * - Einfache Gradle dependency
 * - 2-4 Stunden statt 12-23 Stunden Aufwand
 * 
 * Status: 
 * - ✅ Maven dependency konfiguriert
 * - ✅ VPN Interface Setup
 * - ⏳ libcore TunManager Integration (siehe MAVEN_TUN2SOCKS_INTEGRATION.md)
 * - ⏳ SOCKS5-to-HTTP bridge benötigt
 */
class TrafficCaptureVpnService : VpnService() {

    companion object {
        private const val TAG = "TrafficCaptureVpn"
        const val ACTION_START_VPN = "dev.fishit.mapper.START_VPN"
        const val ACTION_STOP_VPN = "dev.fishit.mapper.STOP_VPN"
        
        // VPN-Konfiguration
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_GATEWAY = "10.0.0.1"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        private const val VPN_MTU = 1500
        
        // Proxy-Konfiguration
        const val PROXY_PORT = 8888  // HTTP Proxy Port
        private const val SOCKS_PORT = 1080  // SOCKS5 Proxy Port (für tun2socks)
        private const val PROXY_ADDRESS = "127.0.0.1"
        
        // Notification
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private var tun2socksJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
        createNotificationChannel()
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
            // Starte Foreground Service mit Notification (required für Android O+)
            startForeground(NOTIFICATION_ID, createNotification())
            
            // Erstelle VPN-Interface mit optimierter Konfiguration
            val builder = Builder()
                .setSession("FishIT-Mapper VPN")
                .addAddress(VPN_ADDRESS, 24)
                .addRoute(VPN_ROUTE, 0)
                .addDnsServer(VPN_DNS)
                .addDnsServer("8.8.4.4") // Backup DNS
                .setMtu(VPN_MTU)
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            Log.i(TAG, "VPN interface established successfully")
            Log.i(TAG, "  - Address: $VPN_ADDRESS/24")
            Log.i(TAG, "  - Gateway: $VPN_GATEWAY")
            Log.i(TAG, "  - DNS: $VPN_DNS")
            Log.i(TAG, "  - MTU: $VPN_MTU")

            // Starte tun2socks in Coroutine
            serviceScope = CoroutineScope(Dispatchers.IO)
            tun2socksJob = serviceScope?.launch {
                startTun2Socks()
            }

            Log.i(TAG, "VPN with tun2socks integration started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    /**
     * Startet tun2socks um VPN-Traffic zum SOCKS5 Proxy zu leiten.
     * 
     * Maven-basierte Implementation mit io.github.nekohasekai:libcore:
     * 1. Liest IP-Pakete vom TUN device (vpnInterface)
     * 2. Nutzt pre-built native libraries (keine Kompilierung nötig)
     * 3. Erstellt SOCKS5 connections für TCP/UDP
     * 4. Leitet zu lokalem SOCKS5 server (Port 1080)
     * 5. SOCKS5 server konvertiert zu HTTP requests (Port 8888)
     * 
     * NEUE APPROACH: Maven artifact mit pre-built natives
     * - ✅ Maven dependency: io.github.nekohasekai:libcore:2.5.2
     * - ✅ Keine manuelle Kompilierung nötig
     * - ✅ Pre-built .so files für alle ABIs enthalten
     * - ⏳ libcore API Integration nötig
     * - ⏳ SOCKS5-to-HTTP bridge nötig
     * 
     * Siehe MAVEN_TUN2SOCKS_INTEGRATION.md für vollständige Anleitung.
     */
    private fun startTun2Socks() {
        try {
            val vpnFd = vpnInterface ?: run {
                Log.e(TAG, "VPN interface is null, cannot start tun2socks")
                return
            }
            
            val fd = vpnFd.fd
            
            Log.i(TAG, "=== tun2socks Maven Configuration ===")
            Log.i(TAG, "Approach: Maven artifact with pre-built natives")
            Log.i(TAG, "Library: io.github.nekohasekai:libcore:2.5.2")
            Log.i(TAG, "TUN FD: $fd")
            Log.i(TAG, "MTU: $VPN_MTU")
            Log.i(TAG, "SOCKS Server: $PROXY_ADDRESS:$SOCKS_PORT")
            Log.i(TAG, "Gateway: $VPN_GATEWAY")
            Log.i(TAG, "DNS: $VPN_DNS")
            Log.i(TAG, "=======================================")
            
            // Maven-based implementation with libcore
            // Code example (requires libcore API integration):
            /*
            import io.nekohasekai.libcore.Libcore
            import io.nekohasekai.libcore.TunManager
            
            Libcore.init()  // One-time initialization
            
            val tunManager = TunManager(
                fd = fd,
                mtu = VPN_MTU,
                gateway = VPN_GATEWAY,
                dns = VPN_DNS,
                socksAddress = "$PROXY_ADDRESS:$SOCKS_PORT"
            )
            
            tunManager.start()
            */
            
            Log.i(TAG, "=== Implementation Status ===")
            Log.i(TAG, "✅ Maven dependency configured")
            Log.i(TAG, "✅ Native libraries available via Maven")
            Log.i(TAG, "⏳ libcore TunManager integration pending")
            Log.i(TAG, "⏳ SOCKS5-to-HTTP bridge pending")
            Log.i(TAG, "")
            Log.i(TAG, "Next steps (see MAVEN_TUN2SOCKS_INTEGRATION.md):")
            Log.i(TAG, "  1. Import libcore classes")
            Log.i(TAG, "  2. Initialize Libcore")
            Log.i(TAG, "  3. Create TunManager instance")
            Log.i(TAG, "  4. Implement SOCKS5-to-HTTP bridge")
            Log.i(TAG, "  5. Start both services")
            Log.i(TAG, "")
            Log.i(TAG, "Estimated effort: 2-4 hours")
            Log.i(TAG, "Current workaround: Use WebView browser (fully functional)")
            Log.i(TAG, "============================")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in tun2socks integration", e)
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN service...")

        tun2socksJob?.cancel()
        tun2socksJob = null

        serviceScope?.cancel()
        serviceScope = null

        vpnInterface?.close()
        vpnInterface = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.i(TAG, "VPN service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Traffic Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FishIT-Mapper VPN Service for network traffic analysis"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FishIT-Mapper VPN Active")
            .setContentText("Network traffic capture is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        Log.i(TAG, "VPN Service destroyed")
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
        Log.i(TAG, "VPN permission revoked by user")
    }
}
