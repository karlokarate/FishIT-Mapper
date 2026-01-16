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
 * 
 * Architektur:
 * 1. VPN Interface (TUN) - fängt System-Traffic ab
 * 2. tun2socks (Native) - leitet TUN→SOCKS5 Pakete weiter
 * 3. SOCKS5 Server - bridged zu HTTP Proxy
 * 4. HTTP Proxy (MitmProxyServer) - erfasst und analysiert Traffic
 * 
 * Dependencies:
 * - com.ooimi.library:tun2socks (Maven) - Native TUN→SOCKS5 Library
 */
class TrafficCaptureVpnService : VpnService() {

    companion object {
        private const val TAG = "TrafficCaptureVpn"
        const val ACTION_START_VPN = "dev.fishit.mapper.START_VPN"
        const val ACTION_STOP_VPN = "dev.fishit.mapper.STOP_VPN"
        
        // VPN-Konfiguration
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_GATEWAY = "10.0.0.1"
        private const val VPN_NETMASK = "255.255.255.0"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        private const val VPN_MTU = 1500
        
        // Proxy-Konfiguration
        const val PROXY_PORT = 8888  // HTTP Proxy Port
        private const val SOCKS_PORT = 1080  // SOCKS5 Proxy Port
        private const val PROXY_ADDRESS = "127.0.0.1"
        
        // Notification
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private var tun2socksJob: Job? = null
    private var socksServer: Socks5Server? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
        createNotificationChannel()
        
        // Initialisiere CoroutineScope für Service
        serviceScope = CoroutineScope(Dispatchers.IO)
        
        // Initialisiere tun2socks Native Library
        Tun2SocksWrapper.initialize(applicationContext)
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
            
            // Starte SOCKS5 Server (Bridge zu HTTP Proxy)
            startSocksServer()
            
            // Erstelle VPN-Interface
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

            // Starte tun2socks in separatem Thread
            startTun2Socks()

            Log.i(TAG, "VPN service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    /**
     * Startet den SOCKS5 Server als Bridge zum HTTP Proxy.
     */
    private fun startSocksServer() {
        if (socksServer != null && socksServer!!.isRunning()) {
            Log.w(TAG, "SOCKS5 server already running")
            return
        }

        val scope = serviceScope ?: run {
            Log.e(TAG, "Service scope is null, cannot start SOCKS5 server")
            return
        }

        scope.launch {
            try {
                socksServer = Socks5Server(
                    port = SOCKS_PORT,
                    httpProxyHost = PROXY_ADDRESS,
                    httpProxyPort = PROXY_PORT
                )
                socksServer?.start()
                Log.i(TAG, "SOCKS5 server started on port $SOCKS_PORT, bridging to HTTP proxy at $PROXY_ADDRESS:$PROXY_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SOCKS5 server", e)
            }
        }
    }

    /**
     * Startet tun2socks um TUN-Traffic zum SOCKS5 Proxy zu leiten.
     */
    private fun startTun2Socks() {
        val tunFd = vpnInterface ?: run {
            Log.e(TAG, "VPN interface is null, cannot start tun2socks")
            return
        }

        if (!Tun2SocksWrapper.isLibraryLoaded()) {
            Log.e(TAG, "============================================")
            Log.e(TAG, "VPN traffic routing not available")
            Log.e(TAG, "")
            Log.e(TAG, "System-wide VPN traffic capture requires")
            Log.e(TAG, "additional native library support that is")
            Log.e(TAG, "currently not configured.")
            Log.e(TAG, "")
            Log.e(TAG, "Recommendation: Use WebView Browser tab")
            Log.e(TAG, "for full traffic capture functionality.")
            Log.e(TAG, "============================================")
            return
        }

        val scope = serviceScope ?: run {
            Log.e(TAG, "Service scope is null, cannot start tun2socks")
            return
        }

        tun2socksJob = scope.launch {
            try {
                val success = Tun2SocksWrapper.start(
                    tunFd = tunFd,
                    mtu = VPN_MTU,
                    socksAddress = PROXY_ADDRESS,
                    socksPort = SOCKS_PORT,
                    tunAddress = VPN_ADDRESS,
                    tunNetmask = VPN_NETMASK,
                    forwardUdp = false // UDP not supported by HTTP proxy
                )

                if (success) {
                    Log.i(TAG, "tun2socks started successfully - VPN is now routing traffic")
                } else {
                    Log.e(TAG, "tun2socks failed to start - VPN traffic routing not working")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting tun2socks", e)
            }
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN service...")

        // Stoppe tun2socks
        try {
            Tun2SocksWrapper.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tun2socks", e)
        }

        tun2socksJob?.cancel()
        tun2socksJob = null

        // Stoppe SOCKS5 Server
        try {
            socksServer?.stop()
            socksServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SOCKS5 server", e)
        }

        serviceScope?.cancel()
        serviceScope = null

        // Schließe VPN Interface
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
