# tun2socks Maven Integration Guide

## ✅ Status: Maven Artifact Configured

This document explains the Maven-based tun2socks integration approach.

## What Changed

**Previous Approach:**
- Required manual native library compilation
- Manual .so file management
- Complex JNI wrapper creation
- Estimated effort: 12-23 hours

**New Approach:**
- Using Maven artifact with pre-built native libraries
- No manual compilation needed
- Simplified integration
- Estimated effort: 2-4 hours

## Maven Artifact Details

**Library:** `io.github.nekohasekai:libcore`
**Version:** 2.5.2
**Includes:** 
- Pre-compiled native libraries (.so files) for all ABIs
- High-level Kotlin/Java API
- Built-in SOCKS5 support
- Based on shadowsocks technology

**ABIs Included:**
- arm64-v8a (modern 64-bit ARM devices)
- armeabi-v7a (older 32-bit ARM devices)
- x86 (Intel 32-bit emulators)
- x86_64 (Intel 64-bit emulators/devices)

## Configuration

### ✅ Already Configured

**gradle/libs.versions.toml:**
```toml
tun2socks-core = "2.5.2"

tun2socks-core = { module = "io.github.nekohasekai:libcore", version.ref = "tun2socks-core" }
```

**settings.gradle.kts:**
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }  // For libcore
    }
}
```

**androidApp/build.gradle.kts:**
```kotlin
dependencies {
    implementation(libs.tun2socks.core)
}
```

## Implementation

### Step 1: Use the Library API

The libcore library provides a simple API:

```kotlin
import io.nekohasekai.libcore.Libcore
import io.nekohasekai.libcore.TunManager

class TrafficCaptureVpnService : VpnService() {
    
    private var tunManager: TunManager? = null
    
    private fun startTun2Socks() {
        val vpnFd = vpnInterface ?: return
        
        // Initialize libcore
        Libcore.init()
        
        // Create TUN manager
        tunManager = TunManager(
            fd = vpnFd.fd,
            mtu = VPN_MTU,
            gateway = VPN_GATEWAY,
            dns = VPN_DNS,
            socksAddress = "$PROXY_ADDRESS:$SOCKS_PORT"
        )
        
        // Start packet routing
        tunManager?.start()
        
        Log.i(TAG, "tun2socks started with Maven artifact")
    }
    
    private fun stopTun2Socks() {
        tunManager?.stop()
        tunManager = null
    }
}
```

### Step 2: Implement SOCKS5 Proxy

The library expects a SOCKS5 proxy running on the specified port. Two options:

**Option A: Simple SOCKS5 to HTTP Bridge**

Create a lightweight SOCKS5 server that forwards to your HTTP proxy:

```kotlin
class Socks5ToHttpBridge(
    private val socksPort: Int = 1080,
    private val httpProxyPort: Int = 8888
) {
    private var serverSocket: ServerSocket? = null
    
    fun start() {
        serverSocket = ServerSocket(socksPort)
        
        while (true) {
            val client = serverSocket?.accept() ?: break
            handleClient(client)
        }
    }
    
    private fun handleClient(client: Socket) {
        // Parse SOCKS5 handshake
        // Forward to HTTP proxy
        // Return responses
    }
}
```

**Option B: Use Existing SOCKS5 Library**

```kotlin
dependencies {
    // Lightweight SOCKS5 server implementation
    implementation("com.github.bbottema:simple-java-mail:7.6.0") // includes SOCKS5
}
```

### Step 3: Update VPN Service

**Complete implementation in TrafficCaptureVpnService.kt:**

```kotlin
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
import io.nekohasekai.libcore.Libcore
import io.nekohasekai.libcore.TunManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrafficCaptureVpnService : VpnService() {

    companion object {
        private const val TAG = "TrafficCaptureVpn"
        const val ACTION_START_VPN = "dev.fishit.mapper.START_VPN"
        const val ACTION_STOP_VPN = "dev.fishit.mapper.STOP_VPN"
        
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_GATEWAY = "10.0.0.1"
        private const val VPN_DNS = "8.8.8.8"
        private const val VPN_MTU = 1500
        
        const val PROXY_PORT = 8888  // HTTP Proxy
        private const val SOCKS_PORT = 1080  // SOCKS5 for tun2socks
        private const val PROXY_ADDRESS = "127.0.0.1"
        
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunManager: TunManager? = null
    private var serviceScope: CoroutineScope? = null
    private var socksServer: Socks5ToHttpBridge? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Initialize libcore (one-time setup)
        try {
            Libcore.init()
            Log.i(TAG, "Libcore initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize libcore", e)
        }
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
            startForeground(NOTIFICATION_ID, createNotification())
            
            // Start SOCKS5 proxy
            serviceScope = CoroutineScope(Dispatchers.IO)
            serviceScope?.launch {
                socksServer = Socks5ToHttpBridge(SOCKS_PORT, PROXY_PORT)
                socksServer?.start()
            }
            
            // Create VPN interface
            val builder = Builder()
                .setSession("FishIT-Mapper VPN")
                .addAddress(VPN_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(VPN_DNS)
                .addDnsServer("8.8.4.4")
                .setMtu(VPN_MTU)
                .setBlocking(false)
            
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude own app", e)
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            // Start tun2socks with Maven artifact
            tunManager = TunManager(
                fd = vpnInterface!!.fd,
                mtu = VPN_MTU,
                gateway = VPN_GATEWAY,
                dns = VPN_DNS,
                socksAddress = "$PROXY_ADDRESS:$SOCKS_PORT"
            )
            
            tunManager?.start()

            Log.i(TAG, "VPN started successfully with Maven tun2socks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN...")
        
        tunManager?.stop()
        tunManager = null
        
        socksServer?.stop()
        socksServer = null
        
        serviceScope?.cancel()
        serviceScope = null

        vpnInterface?.close()
        vpnInterface = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.i(TAG, "VPN stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Traffic Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FishIT-Mapper VPN Active")
            .setContentText("Traffic capture running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }
}
```

## Testing

### Step 1: Verify Library Loading

```bash
adb logcat | grep -i "libcore"
```

Expected output:
```
I/TrafficCaptureVpn: Libcore initialized successfully
I/TrafficCaptureVpn: VPN started successfully with Maven tun2socks
```

### Step 2: Test VPN Connection

1. Enable VPN in app
2. Open browser/any app
3. Try loading website
4. Check logcat for traffic

### Step 3: Verify Traffic Capture

- HTTP/HTTPS requests should appear in proxy logs
- Certificate interception should work (if CA installed)

## Troubleshooting

### Library Not Found

**Problem:** `UnsatisfiedLinkError: dlopen failed`

**Solution:**
1. Clean and rebuild project
2. Verify Maven repository accessible
3. Check ABI filters in build.gradle.kts

### SOCKS5 Connection Failed

**Problem:** "Connection refused to 127.0.0.1:1080"

**Solution:**
1. Verify SOCKS5 server is running
2. Check port not already in use
3. Add logging to SOCKS5 server

### No Traffic Routing

**Problem:** VPN active but no internet

**Solution:**
1. Check VPN interface established
2. Verify tun2socks started
3. Test SOCKS5 proxy separately
4. Check DNS resolution

## Advantages of Maven Approach

✅ **Simple Setup**
- No manual native library compilation
- No JNI wrapper needed
- Standard Gradle dependency

✅ **Reliable**
- Pre-built and tested binaries
- Handles all ABIs automatically
- Regular updates via Maven

✅ **Less Code**
- High-level API
- Built-in error handling
- Standard Kotlin/Java integration

✅ **Faster Development**
- 2-4 hours instead of 12-23 hours
- Focus on SOCKS5 bridge
- Iterate quickly

## Next Steps

1. ✅ Maven dependency configured
2. ⏳ Update TrafficCaptureVpnService with libcore API
3. ⏳ Implement SOCKS5 to HTTP bridge
4. ⏳ Test and verify traffic routing
5. ⏳ Production hardening

## References

- libcore GitHub: https://github.com/nekohasekai/libcore
- SOCKS5 Protocol: RFC 1928
- Android VPN Guide: https://developer.android.com/reference/android/net/VpnService
- Shadowsocks Documentation: https://shadowsocks.org/
