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
 * Nutzt tun2socks um Traffic über lokalen SOCKS5 Proxy zu leiten.
 * 
 * Implementation mit tun2socks:
 * 1. VPN Interface erstellen
 * 2. tun2socks library verwenden um TUN -> SOCKS5 zu routen
 * 3. SOCKS5 Proxy konvertiert zu HTTP Proxy requests
 * 4. Traffic wird im HTTP Proxy erfasst und analysiert
 * 
 * Alternative ohne tun2socks:
 * - Apps müssen HTTP Proxy Settings respektieren
 * - Nur WebView traffic wird erfasst
 * - System-weite Erfassung nicht möglich
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
     * tun2socks Workflow:
     * 1. Liest IP-Pakete vom TUN device (vpnInterface)
     * 2. Parsed TCP/UDP/ICMP packets
     * 3. Erstellt SOCKS5 connections für TCP
     * 4. Leitet zu lokalem SOCKS5 server (Port 1080)
     * 5. SOCKS5 server konvertiert zu HTTP requests (Port 8888)
     * 
     * HINWEIS: Die aktuelle tun2socks dependency von shadowsocks
     * ist eine native library und benötigt spezifische JNI bindings.
     * 
     * Für Production-Implementation:
     * - tun2socks native library kompilieren
     * - JNI wrapper erstellen
     * - SOCKS5 server implementieren
     * 
     * Alternative für MVP:
     * - Verwende nur WebView mit HTTP Proxy settings
     * - Kein system-weiter VPN
     * - Funktioniert aber nur für WebView traffic
     */
    private fun startTun2Socks() {
        try {
            val vpnFd = vpnInterface ?: run {
                Log.e(TAG, "VPN interface is null, cannot start tun2socks")
                return
            }
            
            // Hole den File Descriptor
            val fd = vpnFd.fd
            
            Log.i(TAG, "=== tun2socks Configuration ===")
            Log.i(TAG, "TUN FD: $fd")
            Log.i(TAG, "MTU: $VPN_MTU")
            Log.i(TAG, "SOCKS Server: $PROXY_ADDRESS:$SOCKS_PORT")
            Log.i(TAG, "Gateway: $VPN_GATEWAY")
            Log.i(TAG, "DNS: $VPN_DNS")
            Log.i(TAG, "==============================")
            
            // TODO: Actual tun2socks native library integration
            // Die shadowsocks tun2socks library benötigt:
            // 1. Native library (.so files) im APK
            // 2. JNI wrapper für Kotlin/Java
            // 3. SOCKS5 server implementation
            
            // Beispiel Code (wenn library verfügbar):
            /*
            Tun2socks.run(
                tunFd = fd,
                mtu = VPN_MTU,
                socksServerAddr = "$PROXY_ADDRESS:$SOCKS_PORT",
                gateway = VPN_GATEWAY,
                dnsServer = VPN_DNS,
                enableIPv6 = false
            )
            */
            
            Log.w(TAG, "=== tun2socks Implementation Status ===")
            Log.w(TAG, "Native library integration: PENDING")
            Log.w(TAG, "Required steps:")
            Log.w(TAG, "  1. Add tun2socks native .so files to jniLibs/")
            Log.w(TAG, "  2. Implement JNI wrapper")
            Log.w(TAG, "  3. Implement SOCKS5 proxy server")
            Log.w(TAG, "  4. Connect SOCKS5 to HTTP proxy")
            Log.w(TAG, "")
            Log.w(TAG, "Current status: VPN interface active but traffic not routed")
            Log.w(TAG, "Workaround: Use WebView browser tab (already functional)")
            Log.w(TAG, "==========================================")
            
            // Für jetzt: VPN ist aktiv aber leitet keinen Traffic
            // User muss WebView browser verwenden
            
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
