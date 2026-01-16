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
            System.loadLibrary("tun2socks")
            isInitialized = true
            Log.i(TAG, "tun2socks native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load tun2socks native library", e)
            Log.e(TAG, "VPN mode will not work without native library")
            Log.e(TAG, "Please ensure com.ooimi.library:tun2socks dependency is correctly configured")
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

            // Bereite Argumente für native Funktion vor
            val extraArgs = emptyList<String>()
            
            // Rufe native Funktion auf
            // Die Funktion blockiert bis tun2socks stoppt oder ein Fehler auftritt
            // Daher sollte dies in einem separaten Thread laufen
            val success = nativeStart(
                logLevel = LogLevel.INFO.ordinal,
                vpnInterfaceFileDescriptor = tunFd.fd,
                vpnInterfaceMtu = mtu,
                socksServerAddress = socksAddress,
                socksServerPort = socksPort,
                netIPv4Address = tunAddress,
                netIPv6Address = null,
                netmask = tunNetmask,
                forwardUdp = forwardUdp,
                extraArgs = extraArgs.toTypedArray()
            )

            if (success) {
                isRunning = true
                Log.i(TAG, "tun2socks started successfully")
            } else {
                Log.e(TAG, "tun2socks failed to start")
            }

            return success
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
            nativeStop()
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

    /**
     * Native Funktion zum Starten von tun2socks.
     * Entspricht der Signatur von com.LondonX.tun2socks.Tun2Socks.startTun2Socks()
     */
    private external fun nativeStart(
        logLevel: Int,
        vpnInterfaceFileDescriptor: Int,
        vpnInterfaceMtu: Int,
        socksServerAddress: String,
        socksServerPort: Int,
        netIPv4Address: String,
        netIPv6Address: String?,
        netmask: String,
        forwardUdp: Boolean,
        extraArgs: Array<String>
    ): Boolean

    /**
     * Native Funktion zum Stoppen von tun2socks.
     */
    private external fun nativeStop()

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
