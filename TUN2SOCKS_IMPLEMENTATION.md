# tun2socks Implementation Guide

## Status: Framework Added, Native Integration Pending

This document provides step-by-step instructions for completing the tun2socks integration.

## What's Implemented

✅ **Build Configuration**
- Added JitPack repository to settings.gradle.kts
- Added tun2socks dependency to gradle/libs.versions.toml
- Added dependency to androidApp/build.gradle.kts

✅ **VPN Service Structure**
- Rewritten TrafficCaptureVpnService with tun2socks architecture
- Added foreground service with notification (Android O+ requirement)
- Added proper VPN configuration (address, DNS, MTU, gateway)
- Added tun2socks method stub with configuration logging

✅ **Code Quality Fixes**
- Fixed companion object in Composable function
- Fixed typo in VPN_IMPLEMENTATION_PLAN.md
- Improved certificate comparison to use encoded bytes

## What's Pending

❌ **Native Library Integration**

The shadowsocks/tun2socks library is a native (C/Go) library that requires additional setup:

### Step 1: Get Native Libraries

Option A: Build from source
```bash
# Clone tun2socks
git clone https://github.com/shadowsocks/tun2socks
cd tun2socks

# Build for Android
make android

# This generates .so files for different ABIs:
# - armeabi-v7a
# - arm64-v8a  
# - x86
# - x86_64
```

Option B: Download pre-built binaries
- Check shadowsocks releases: https://github.com/shadowsocks/tun2socks/releases
- Download Android AAR or .so files

### Step 2: Add Native Libraries to Project

```
androidApp/src/main/jniLibs/
├── arm64-v8a/
│   └── libtun2socks.so
├── armeabi-v7a/
│   └── libtun2socks.so
├── x86/
│   └── libtun2socks.so
└── x86_64/
    └── libtun2socks.so
```

### Step 3: Create JNI Wrapper

Create `Tun2socksWrapper.kt`:

```kotlin
package dev.fishit.mapper.android.vpn

import android.util.Log

object Tun2socksWrapper {
    private const val TAG = "Tun2socksWrapper"
    
    init {
        try {
            System.loadLibrary("tun2socks")
            Log.i(TAG, "tun2socks native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load tun2socks library", e)
        }
    }
    
    /**
     * Start tun2socks with configuration
     * 
     * @param tunFd File descriptor of TUN device
     * @param mtu Maximum Transmission Unit
     * @param socksServerAddr SOCKS5 server address (e.g. "127.0.0.1:1080")
     * @param gateway VPN gateway address
     * @param dnsServer DNS server address
     * @return true if started successfully
     */
    external fun start(
        tunFd: Int,
        mtu: Int,
        socksServerAddr: String,
        gateway: String,
        dnsServer: String
    ): Boolean
    
    /**
     * Stop tun2socks
     */
    external fun stop()
    
    /**
     * Check if tun2socks is running
     */
    external fun isRunning(): Boolean
}
```

### Step 4: Implement JNI C/C++ Code

Create `androidApp/src/main/cpp/tun2socks_jni.cpp`:

```cpp
#include <jni.h>
#include <android/log.h>
#include "tun2socks.h"  // From tun2socks library

#define TAG "Tun2socksJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_fishit_mapper_android_vpn_Tun2socksWrapper_start(
    JNIEnv *env,
    jobject /* this */,
    jint tunFd,
    jint mtu,
    jstring socksServerAddr,
    jstring gateway,
    jstring dnsServer
) {
    const char *socks_addr = env->GetStringUTFChars(socksServerAddr, nullptr);
    const char *gw = env->GetStringUTFChars(gateway, nullptr);
    const char *dns = env->GetStringUTFChars(dnsServer, nullptr);
    
    LOGI("Starting tun2socks: fd=%d, mtu=%d, socks=%s", tunFd, mtu, socks_addr);
    
    // Call actual tun2socks library function
    int result = tun2socks_start(tunFd, mtu, socks_addr, gw, dns);
    
    env->ReleaseStringUTFChars(socksServerAddr, socks_addr);
    env->ReleaseStringUTFChars(gateway, gw);
    env->ReleaseStringUTFChars(dnsServer, dns);
    
    return result == 0;
}

JNIEXPORT void JNICALL
Java_dev_fishit_mapper_android_vpn_Tun2socksWrapper_stop(
    JNIEnv *env,
    jobject /* this */
) {
    LOGI("Stopping tun2socks");
    tun2socks_stop();
}

JNIEXPORT jboolean JNICALL
Java_dev_fishit_mapper_android_vpn_Tun2socksWrapper_isRunning(
    JNIEnv *env,
    jobject /* this */
) {
    return tun2socks_is_running();
}

}
```

### Step 5: Configure CMake

Create `androidApp/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.18.1)
project("tun2socks-jni")

# Add tun2socks library
add_library(tun2socks SHARED IMPORTED)
set_target_properties(tun2socks PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtun2socks.so)

# Add JNI wrapper
add_library(tun2socks-jni SHARED
    tun2socks_jni.cpp)

target_link_libraries(tun2socks-jni
    tun2socks
    android
    log)
```

Update `androidApp/build.gradle.kts`:

```kotlin
android {
    // ... existing config
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    defaultConfig {
        // ... existing config
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }
}
```

### Step 6: Update TrafficCaptureVpnService

Replace the TODO section in `startTun2Socks()`:

```kotlin
private fun startTun2Socks() {
    try {
        val vpnFd = vpnInterface ?: run {
            Log.e(TAG, "VPN interface is null, cannot start tun2socks")
            return
        }
        
        val fd = vpnFd.fd
        
        Log.i(TAG, "Starting tun2socks...")
        
        val success = Tun2socksWrapper.start(
            tunFd = fd,
            mtu = VPN_MTU,
            socksServerAddr = "$PROXY_ADDRESS:$SOCKS_PORT",
            gateway = VPN_GATEWAY,
            dnsServer = VPN_DNS
        )
        
        if (success) {
            Log.i(TAG, "tun2socks started successfully")
        } else {
            Log.e(TAG, "Failed to start tun2socks")
        }
        
    } catch (e: Exception) {
        Log.e(TAG, "Error starting tun2socks", e)
    }
}
```

### Step 7: Implement SOCKS5 Server

tun2socks routes traffic to a SOCKS5 server. You need to implement one:

Option A: Use existing library
```kotlin
dependencies {
    implementation("com.github.Kodein-Framework:knet-socks:1.0.0")
}
```

Option B: Implement minimal SOCKS5 server
```kotlin
class Socks5Server(private val port: Int) {
    fun start() {
        // Accept SOCKS5 connections
        // Parse SOCKS5 protocol
        // Forward to HTTP proxy
    }
}
```

### Step 8: Testing

1. Build the app with native libraries
2. Install on device
3. Enable VPN
4. Check logcat for tun2socks messages
5. Test with various apps
6. Verify traffic in proxy logs

## Alternative: Simpler Approach

If native integration is too complex, consider:

### Option 1: WebView Only (Already Works!)
- Use existing WebView browser tab
- Traffic is already captured
- No VPN needed
- Works immediately

### Option 2: HTTP Proxy Settings
```kotlin
// Set system proxy (requires root or ADB)
Settings.Global.putString(
    contentResolver,
    Settings.Global.HTTP_PROXY,
    "127.0.0.1:8888"
)
```

### Option 3: Use Pre-built Solution
- Integrate Clash for Android library
- Or use shadowsocks-android as reference
- These have complete tun2socks integration

## Current Status Summary

**What Works:**
- ✅ Certificate management
- ✅ WebView browser with traffic capture
- ✅ VPN interface creation
- ✅ Foreground service notification

**What's Pending:**
- ❌ tun2socks native integration
- ❌ SOCKS5 server implementation
- ❌ System-wide traffic routing

**Recommended Immediate Action:**
- Use WebView browser tab (fully functional)
- VPN implementation can be completed later with native library

**Estimated Effort for Full VPN:**
- Native library setup: 2-4 hours
- JNI wrapper: 2-3 hours
- SOCKS5 server: 4-8 hours
- Testing & debugging: 4-8 hours
- **Total: 12-23 hours** (1.5-3 days)

## References

- tun2socks source: https://github.com/shadowsocks/tun2socks
- Shadowsocks Android: https://github.com/shadowsocks/shadowsocks-android
- Clash Android: https://github.com/Kr328/ClashForAndroid
- Android VPN Guide: https://developer.android.com/reference/android/net/VpnService
