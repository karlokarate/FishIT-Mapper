# VPN Implementation Plan - HTTPCanary-like Solution

## Problem Statement

The current VPN implementation does not route traffic properly. User expects VPN functionality similar to HTTPCanary - a professional Android packet capture app.

## HTTPCanary Architecture Analysis

HTTPCanary uses a complete VPN-based packet capture solution with:

1. **VPN Interface** - Captures all system traffic
2. **TCP/IP Stack** - Processes packets at network layer
3. **SOCKS Proxy** - Routes traffic through local proxy
4. **MITM Engine** - Decrypts HTTPS with user-installed CA certificate
5. **Traffic Analysis** - Captures, analyzes, and displays traffic

## Current Implementation Gaps

Our current implementation (`TrafficCaptureVpnService.kt`):
- ✅ Creates VPN interface
- ❌ No TCP/IP stack
- ❌ No packet reassembly
- ❌ No NAT (Network Address Translation)
- ❌ No socket connection pool
- ❌ Packets are read but not properly routed

**Result:** VPN is active but no traffic flows.

## Solution Options

### Option 1: Integrate tun2socks (Recommended)

**What is tun2socks?**
- Converts TUN device traffic to SOCKS proxy
- Used by: Shadowsocks, V2Ray, Clash
- Mature, battle-tested library

**Implementation:**
```kotlin
// 1. Add dependency
dependencies {
    implementation("io.github.tun2socks:tun2socks-android:2.0.0")
}

// 2. Use in VPN service
class TrafficCaptureVpnService : VpnService() {
    private var tun2socks: Tun2socks? = null
    
    private fun startVpn() {
        val vpnInterface = Builder()
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .establish()
        
        // Start tun2socks to route traffic through SOCKS proxy
        tun2socks = Tun2socks.create(
            tunFd = vpnInterface.fd,
            mtu = 1500,
            socksServerAddress = "127.0.0.1:1080",
            dnsServerAddress = "8.8.8.8:53"
        )
        
        tun2socks?.start()
    }
}
```

**Pros:**
- Proven solution
- Minimal code changes
- Professional-grade routing

**Cons:**
- Additional dependency (~2-3 MB)
- Native library (NDK)

### Option 2: Use Android's Local HTTP Proxy

**Simpler alternative:**
```kotlin
// Set system-wide HTTP proxy
val proxyHost = "127.0.0.1"
val proxyPort = 8888

// Configure WebView to use proxy
val webView = WebView(context)
webView.settings.apply {
    javaScriptEnabled = true
    // Proxy will be used automatically for HTTP/HTTPS
}
```

**Pros:**
- No VPN needed
- Simpler implementation
- No additional dependencies

**Cons:**
- Only works for apps that respect proxy settings
- Not system-wide capture
- Limited to HTTP/HTTPS

### Option 3: Implement Custom TCP/IP Stack

**Full implementation like HTTPCanary:**

```kotlin
class TcpIpStack {
    // Parse IP packets
    fun parseIpPacket(data: ByteArray): IpPacket
    
    // TCP state machine
    fun handleTcpPacket(packet: TcpPacket): List<SocketAction>
    
    // NAT translation
    fun translateAddress(srcIp: String, srcPort: Int): NatEntry
    
    // Socket connection pool
    fun connectToDestination(packet: TcpPacket): Socket
}
```

**Required components:**
1. IP packet parser
2. TCP/UDP packet parser
3. TCP state machine (SYN, SYN-ACK, ACK, FIN, RST)
4. NAT table
5. Socket connection pool
6. Packet reassembly
7. Checksum calculation

**Estimated effort:** 2-4 weeks full-time development

**Pros:**
- Full control
- No dependencies
- Custom features

**Cons:**
- Complex implementation
- High maintenance
- Bug-prone

## Recommended Approach

### Phase 1: tun2socks Integration (Short-term)

1. **Add tun2socks dependency**
2. **Implement SOCKS proxy** in `MitmProxyServer.kt`
3. **Connect VPN to proxy** via tun2socks
4. **Test with real traffic**

**Timeline:** 1-2 days

### Phase 2: MITM Proxy Enhancement (Medium-term)

1. **Convert MitmProxyServer to SOCKS proxy**
2. **Add TLS interception** using CA certificate
3. **Enhance packet capture**

**Timeline:** 3-5 days

### Phase 3: Production Hardening (Long-term)

1. **Error handling**
2. **Performance optimization**
3. **Battery optimization**
4. **User settings** (whitelist/blacklist apps)

**Timeline:** 1-2 weeks

## Implementation Details - Phase 1

### Step 1: Add tun2socks

Create `androidApp/build.gradle.kts`:
```kotlin
dependencies {
    // ... existing dependencies
    
    // VPN routing
    implementation("com.github.shadowsocks:tun2socks:2.5.2")
}
```

### Step 2: Modify TrafficCaptureVpnService

```kotlin
class TrafficCaptureVpnService : VpnService() {
    
    private var tun2socks: Tun2socks? = null
    private val proxyServer = MitmProxyServer(context = this, port = PROXY_PORT)
    
    private fun startVpn() {
        // Start MITM proxy server
        proxyServer.start()
        
        // Create VPN interface
        val vpnInterface = Builder()
            .setSession("FishIT-Mapper VPN")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(1500)
            .setBlocking(false)
            .addDisallowedApplication(packageName)
            .establish()
        
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN")
            return
        }
        
        // Start tun2socks to route traffic through proxy
        tun2socks = Tun2socks.create(
            tunFd = vpnInterface.detachFd(),
            mtu = 1500,
            gateway = "10.0.0.1",
            socksAddress = "127.0.0.1:$PROXY_PORT",
            dnsAddress = "8.8.8.8:53",
            enableIPv6 = false
        )
        
        tun2socks?.start()
        Log.i(TAG, "VPN with tun2socks started successfully")
    }
    
    private fun stopVpn() {
        tun2socks?.stop()
        tun2socks = null
        proxyServer.stop()
        Log.i(TAG, "VPN stopped")
    }
}
```

### Step 3: Convert MitmProxyServer to SOCKS

```kotlin
class MitmProxyServer(
    private val context: Context,
    private val port: Int = 8888
) {
    private var socksServer: SocksServer? = null
    
    fun start() {
        socksServer = SocksServer(port) { request ->
            // Intercept and analyze traffic
            analyzeTra ffic(request)
            
            // Forward to destination
            forwardRequest(request)
        }
        socksServer?.start()
    }
}
```

## Alternative: Quick Fix for Browser Only

If system-wide VPN is not immediately required, enhance WebView proxy:

```kotlin
// In BrowserScreen.kt
WebView(context).apply {
    // Enable proxy for WebView
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        WebkitProxy.setProxy(
            context.applicationContext,
            "127.0.0.1",
            PROXY_PORT
        )
    }
}
```

This works immediately without VPN but only for WebView traffic.

## Testing Plan

### Test 1: Basic Connectivity
- Enable VPN
- Open Chrome/Firefox
- Load https://example.com
- **Expected:** Page loads, traffic captured

### Test 2: HTTPS Interception
- Install CA certificate
- Enable VPN
- Load https://google.com
- **Expected:** HTTPS decrypted, requests visible

### Test 3: App Traffic
- Enable VPN
- Open any app (Twitter, Instagram)
- **Expected:** All HTTP/HTTPS traffic captured

## Security Considerations

1. **CA Certificate Trust**
   - Only user-installed certificates are trusted
   - User must explicitly install and trust

2. **Private Key Protection**
   - Private key stored in app-private directory
   - Only accessible to app

3. **VPN Permission**
   - User must explicitly grant VPN permission
   - Android shows VPN active indicator

4. **Traffic Isolation**
   - Own app excluded from VPN
   - Prevents routing loops

## Conclusion

**Immediate Action (Browser Fix):**
- Fix WebView white screen (already done in this commit)
- Add hardware acceleration
- Add error logging

**Short-term Action (VPN Fix):**
- Integrate tun2socks library
- Convert proxy to SOCKS
- Test system-wide capture

**Long-term Action:**
- Performance optimization
- User settings (app whitelist)
- Export enhancements

The HTTPCanary-like functionality is achievable with tun2socks integration.
