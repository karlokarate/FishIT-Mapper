package dev.fishit.mapper.android.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Wrapper für die tun2socks Native Library.
 * 
 * Diese Klasse kapselt die JNI-Aufrufe zur tun2socks Library und
 * bietet eine einfache Kotlin-API für VPN Services.
 * 
 * Die tun2socks Library implementiert einen vollständigen TCP/IP Stack
 * und leitet TUN-Interface-Pakete zu einem SOCKS5 Proxy weiter.
 */
object Tun2SocksWrapper {
    private const val TAG = "Tun2SocksWrapper"
    
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var isRunning = false

    /**
     * Initialisiert die native Library.
     * Muss einmalig beim App-Start aufgerufen werden.
     * 
     * @param context Application Context
     */
    fun initialize(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "tun2socks already initialized")
            return
        }

        try {
            // Lade native Library
            // Die com.ooimi.library:tun2socks Library verwendet den Namen "tun2socks"
            // Falls die Library nicht verfügbar ist, fangen wir den Fehler ab
            System.loadLibrary("tun2socks")
            isInitialized = true
            Log.i(TAG, "tun2socks native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "============================================")
            Log.e(TAG, "Failed to load tun2socks native library")
            Log.e(TAG, "")
            Log.e(TAG, "VPN mode requires native library support.")
            Log.e(TAG, "The library should be automatically included")
            Log.e(TAG, "via Maven dependency, but may be missing.")
            Log.e(TAG, "")
            Log.e(TAG, "VPN mode will not work, but WebView browser")
            Log.e(TAG, "remains fully functional.")
            Log.e(TAG, "============================================")
            Log.e(TAG, "Technical details:", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading tun2socks", e)
        }
    }

    /**
     * Startet tun2socks mit den angegebenen Parametern.
     * 
     * @param tunFd File Descriptor des TUN Interface (von VpnService.Builder.establish())
     * @param mtu Maximum Transmission Unit (sollte mit VpnService.Builder.setMtu() übereinstimmen)
     * @param socksAddress SOCKS5 Server Adresse (z.B. "127.0.0.1")
     * @param socksPort SOCKS5 Server Port (z.B. 1080)
     * @param tunAddress TUN Interface IPv4 Adresse (z.B. "10.0.0.2")
     * @param tunNetmask TUN Interface Netmask (z.B. "255.255.255.0")
     * @param forwardUdp Ob UDP auch weitergeleitet werden soll
     * @return true wenn erfolgreich gestartet
     */
    fun start(
        tunFd: ParcelFileDescriptor,
        mtu: Int,
        socksAddress: String,
        socksPort: Int,
        tunAddress: String = "10.0.0.2",
        tunNetmask: String = "255.255.255.0",
        forwardUdp: Boolean = false
    ): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "tun2socks not initialized. Call initialize() first.")
            return false
        }

        if (isRunning) {
            Log.w(TAG, "tun2socks already running")
            return true
        }

        try {
            Log.i(TAG, "Starting tun2socks...")
            Log.i(TAG, "  - TUN FD: ${tunFd.fd}")
            Log.i(TAG, "  - MTU: $mtu")
            Log.i(TAG, "  - SOCKS: $socksAddress:$socksPort")
            Log.i(TAG, "  - TUN Address: $tunAddress")
            Log.i(TAG, "  - TUN Netmask: $tunNetmask")
            Log.i(TAG, "  - Forward UDP: $forwardUdp")

            // NOTE: The actual JNI method signatures depend on the specific library version
            // The com.ooimi.library:tun2socks library should provide methods like:
            // - start_tun2socks(String[] args)
            // - stopTun2Socks()
            // 
            // However, without documentation, we cannot call them directly.
            // This wrapper would need to be updated with the correct JNI signatures
            // once the library is tested on a real device.
            //
            // For now, we log that the library is missing the JNI bindings.
            Log.e(TAG, "============================================")
            Log.e(TAG, "JNI method signatures not implemented")
            Log.e(TAG, "")
            Log.e(TAG, "The com.ooimi.library:tun2socks library")
            Log.e(TAG, "provides native methods, but the exact")
            Log.e(TAG, "method signatures are not documented.")
            Log.e(TAG, "")
            Log.e(TAG, "Next steps:")
            Log.e(TAG, "1. Test on real device to discover methods")
            Log.e(TAG, "2. Or use alternative library like:")
            Log.e(TAG, "   github.com/LondonX/tun2socks-android")
            Log.e(TAG, "")
            Log.e(TAG, "Fallback: Use WebView browser tab")
            Log.e(TAG, "============================================")

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error starting tun2socks", e)
            return false
        }
    }

    /**
     * Stoppt tun2socks.
     */
    fun stop() {
        if (!isRunning) {
            Log.d(TAG, "tun2socks not running")
            return
        }

        try {
            Log.i(TAG, "Stopping tun2socks...")
            // NOTE: Would call native stop method here
            // nativeStop()
            isRunning = false
            Log.i(TAG, "tun2socks stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping tun2socks", e)
        }
    }

    /**
     * Prüft ob tun2socks läuft.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Prüft ob die native Library erfolgreich geladen wurde.
     */
    fun isLibraryLoaded(): Boolean = isInitialized

    // ============================================================================
    // NOTE: JNI Method Signatures
    // ============================================================================
    // The following external methods would be implemented for the actual library.
    // However, the com.ooimi.library:tun2socks library's exact JNI signatures
    // are not publicly documented. These methods are commented out until the
    // library can be tested on a real device.
    //
    // Based on similar libraries like github.com/LondonX/tun2socks-android,
    // the signatures should be similar to:
    //
    // private external fun start_tun2socks(args: Array<String>): Int
    // external fun stopTun2Socks()
    //
    // Alternative: Build and include tun2socks-android library directly from source
    // ============================================================================

    /**
     * Log Levels für tun2socks (badvpn-tun2socks).
     */
    enum class LogLevel {
        NONE,    // 0
        ERROR,   // 1
        WARNING, // 2
        NOTICE,  // 3
        INFO,    // 4
        DEBUG    // 5
    }
}
