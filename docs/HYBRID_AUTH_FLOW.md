# Hybrid Authentication Flow Documentation

## Overview

The Hybrid Authentication Flow enables seamless WebAuthn/FIDO2 authentication by automatically switching between WebView and Chrome Custom Tabs when needed. This solves the "grey overlay" problem that occurs when WebAuthn is used in WebView.

## Problem Statement

WebView has limitations with WebAuthn/FIDO2:
- **Grey Overlay**: WebAuthn dialogs may appear with an unresponsive grey overlay
- **Missing APIs**: Some WebAuthn features don't work properly in WebView
- **Browser Compatibility**: WebAuthn works best in full browsers like Chrome

Traditional solutions require full Chrome Custom Tabs, losing WebView's traffic capture capabilities.

## Solution: Hybrid Approach

**Start in WebView** → **Detect WebAuthn** → **Switch to Custom Tabs** → **Resume in WebView**

### Benefits
✅ **Zero Grey Overlay**: WebAuthn works perfectly in Chrome Custom Tabs  
✅ **Stable Login**: Full browser environment for authentication  
✅ **Traffic Capture**: Initial navigation captured in WebView  
✅ **Session Resume**: Cookies/tokens synced back to WebView  
✅ **Full UI Mapping**: Decision points logged for analysis  

## Architecture

### Components

1. **WebAuthnDetector** (`WebAuthnDetector.kt`)
   - Detects WebAuthn API availability (`window.PublicKeyCredential`)
   - Monitors actual WebAuthn API usage
   - Analyzes URLs for WebAuthn patterns

2. **CustomTabsManager** (`CustomTabsManager.kt`)
   - Launches Chrome Custom Tabs
   - Syncs cookies between WebView and Chrome
   - Manages Custom Tab lifecycle

3. **HybridAuthFlowManager** (`HybridAuthFlowManager.kt`)
   - Orchestrates WebView → Custom Tabs → WebView flow
   - Logs decision points with timestamps and context
   - Handles session restoration

4. **TrafficInterceptWebView** (`TrafficInterceptWebView.kt`)
   - Integrated WebAuthn error detection
   - Pattern-matching console messages for WebAuthn errors
   - External browser fallback with URL validation
   - Security: Only allows http/https schemes

5. **AuthFlowAnalyzer** (`AuthFlowAnalyzer.kt`)
   - Analyzes HTTP Canary data for auth flows
   - Identifies redirect chains and OAuth patterns
   - Correlates decision points with network requests

## Flow Diagram

```
┌─────────────────┐
│  User opens URL │
│   in WebView    │
└────────┬────────┘
         │
         ▼
┌─────────────────────┐
│ WebView loads page  │
│ JS detection active │
└────────┬────────────┘
         │
         ▼
    WebAuthn
    detected?
         │
    ┌────┴────┐
    │ NO      │ YES
    │         │
    ▼         ▼
┌────────┐ ┌──────────────────┐
│Continue│ │Switch to Custom  │
│WebView │ │Tabs with cookies │
└────────┘ └────────┬─────────┘
                    │
                    ▼
           ┌────────────────────┐
           │ User completes     │
           │ WebAuthn in Chrome │
           └────────┬───────────┘
                    │
                    ▼
           ┌────────────────────┐
           │ Sync cookies back  │
           │ to WebView         │
           └────────┬───────────┘
                    │
                    ▼
           ┌────────────────────┐
           │ Resume in WebView  │
           │ with session       │
           └────────────────────┘
```

## Usage

### In Code

```kotlin
// WebView automatically integrates hybrid auth flow
val webView = TrafficInterceptWebView(context)

// Monitor auth flow status
webView.hybridAuthFlowContext.collect { context ->
    when (context.state) {
        FlowState.WEBAUTHN_DETECTED -> {
            // WebAuthn detected, switching to Custom Tabs
        }
        FlowState.CUSTOM_TAB_ACTIVE -> {
            // User is in Custom Tab
        }
        FlowState.SESSION_RESTORED -> {
            // Back in WebView with session
        }
    }
}

// Get decision points for analysis
val decisionPoints = webView.getAuthFlowDecisionPoints()
```

### UI Indicators

The `CaptureWebViewScreen` shows visual indicators for each state:

**🔵 Blue Badge**: WebAuthn detected - switching to Chrome  
**🟢 Green Badge**: WebAuthn flow active in Chrome Custom Tab  
**✅ Green Badge**: Session successfully restored in WebView  

## Decision Points

Every state transition is logged as a "Decision Point" with:

- **Timestamp**: When the decision was made
- **Trigger**: What caused the transition (API call, URL pattern, etc.)
- **URL**: Current page URL
- **Cookies**: Current cookies (for debugging)
- **Outcome**: Success/failure/cancelled

### Example Decision Point

```kotlin
DecisionPoint(
    id = "dp_1705392000123",
    timestamp = Instant.now(),
    state = FlowState.WEBAUTHN_DETECTED,
    trigger = "webauthn_api_used:navigator.credentials.get",
    url = "https://example.com/login",
    cookies = mapOf("sessionId" to "abc123"),
    outcome = null
)
```

## HTTP Canary Analysis

The `AuthFlowAnalyzer` can analyze imported HTTP Canary data to visualize auth flows:

```kotlin
val analyzer = AuthFlowAnalyzer()
val analysis = analyzer.analyzeAuthFlow(
    exchanges = httpCanaryExchanges,
    decisionPoints = webView.getAuthFlowDecisionPoints()
)

// Redirect chain
analysis.redirectChain.forEach { redirect ->
    println("${redirect.fromUrl} → ${redirect.toUrl} (${redirect.statusCode})")
    if (redirect.isOAuth) println("  OAuth redirect")
    if (redirect.isWebAuthn) println("  WebAuthn redirect")
}

// WebAuthn indicators
analysis.webAuthnIndicators.forEach { indicator ->
    println("${indicator.type}: ${indicator.evidence}")
}

// Correlated requests
analysis.decisionPointCorrelations.forEach { correlation ->
    println("Decision: ${correlation.decisionPoint.trigger}")
    println("Requests: ${correlation.correlatedRequests.size}")
}
```

## Cookie Synchronization

### WebView → Custom Tabs

When switching to Custom Tabs:
1. Extract cookies from WebView using `CookieManager`
2. Transfer cookies to Chrome via `setCookie()`
3. Flush cookies to persist them

### Custom Tabs → WebView

After returning from Custom Tabs:
1. Read cookies from Chrome using `getCookie()`
2. Parse cookie string to extract name-value pairs
3. Set cookies in WebView
4. Reload page with new session

### Cookie Scope

Cookies are domain-scoped. Only cookies for the current domain are transferred.

```kotlin
// Extract domain from URL
val domain = Uri.parse(url).host

// Cookie string format
val cookieString = "name=value; Path=/; Domain=$domain"
```

## Testing

### Test Sites

Recommended sites for testing WebAuthn:

1. **webauthn.io** - WebAuthn demo site
2. **webauthn.me** - FIDO Alliance demo
3. **passkeys.io** - Passkey implementation examples

### Test Scenarios

1. **Basic WebAuthn Flow**
   - Load site in WebView
   - Trigger WebAuthn (register or authenticate)
   - Verify Custom Tab launches
   - Complete WebAuthn in Chrome
   - Verify session restored in WebView

2. **Cookie Transfer**
   - Login with username/password in WebView
   - Trigger WebAuthn
   - Verify cookies transferred to Custom Tab
   - Complete WebAuthn
   - Verify session cookies back in WebView

3. **Decision Point Logging**
   - Monitor `hybridAuthFlowContext`
   - Verify all transitions logged
   - Check timestamps and triggers
   - Export decision points for analysis

## Troubleshooting

### WebAuthn Not Supported Error (New!)

**Symptom**: Console shows "WebAuthn is not supported in this browser" and nothing happens

**Solution**: Automatic external browser fallback dialog

The app now automatically detects WebAuthn-related errors and offers to open the page in an external browser where WebAuthn is fully supported.

**How it works**:
1. JavaScript error is detected in WebView console
2. Pattern matching identifies it as a WebAuthn error
3. Dialog appears with explanation
4. User can click "Im Browser öffnen" to open in external browser
5. User completes authentication with full WebAuthn support

**Detected Error Patterns**:
- "webauthn.*not supported"
- "publickeycredential.*not.*defined"
- "navigator.credentials.*undefined"
- "webauthn.*unavailable"
- "fido.*not supported"

**Security**: Only http and https URLs are allowed (rejects javascript:, data:, file: schemes)

**Example Real-World Case**: eltern.kitaplus.de login throws "Uncaught (in promise) Error: WebAuthn is not supported in this browser" - automatically detected and handled.

### Custom Tabs Not Available

**Symptom**: `CustomTabsManager.isCustomTabsAvailable()` returns `false`

**Solutions**:
- Install Chrome (Stable, Beta, Dev, or Canary)
- Check if another browser supports Custom Tabs
- Fallback to external browser intent

### Cookies Not Transferred

**Symptom**: Session lost after Custom Tab return

**Debug**:
```kotlin
// Check cookies before transition
val beforeCookies = cookieManager.getCookie(url)
Log.d("Cookies", "Before: $beforeCookies")

// Check cookies after transition
val afterCookies = cookieManager.getCookie(url)
Log.d("Cookies", "After: $afterCookies")
```

**Common Issues**:
- **Domain mismatch**: Cookie domain doesn't match URL
- **Secure flag**: Cookie requires HTTPS
- **HttpOnly**: Cookie not accessible from WebView JS
- **SameSite**: Cookie SameSite policy blocks transfer

### WebAuthn Not Detected

**Symptom**: WebAuthn works but flow doesn't trigger

**Debug**:
```kotlin
// Check if detection script injected
webView.evaluateJavascript(
    "typeof window.__fishit_webauthn_detector",
    { result -> Log.d("Detection", "Script injected: $result") }
)

// Check API availability
webView.evaluateJavascript(
    "typeof window.PublicKeyCredential !== 'undefined'",
    { result -> Log.d("WebAuthn", "API available: $result") }
)
```

**Solutions**:
- Ensure page fully loaded before injection
- Check if site uses different WebAuthn API names
- Add URL pattern to `WebAuthnDetector.knownWebAuthnProviders`

## Performance Considerations

### Script Injection Timing

Inject detection scripts in `onPageStarted` using `webView.post()` to minimize delay while ensuring the page is loading:

```kotlin
override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
    super.onPageStarted(view, url, favicon)
    view?.post {
        webAuthnDetector.injectDetectionScript(view)
    }
}
```

Note: Using `onPageStarted` with `post()` provides earlier injection compared to `onPageFinished`, which is important for intercepting early WebAuthn calls.

### Cookie Sync Overhead

Cookie synchronization is designed to be fast but actual performance varies by device:

- Only sync when actually needed (WebAuthn detected)
- Flush cookies asynchronously when possible
- Limit cookie count and size

### Memory

Decision points are stored in memory. For long sessions:

- Consider pruning old decision points
- Export and clear after analysis
- Limit to last 100 decision points

## Security Considerations

### Cookie Security

- **Secure Cookies**: Only transferred over HTTPS
- **HttpOnly Cookies**: Native code can access, JS cannot
- **SameSite**: Strict/Lax policies may block transfer

### WebAuthn Security

- **Origin Validation**: WebAuthn validates origin automatically
- **User Presence**: Requires user interaction (biometric, PIN, key)
- **No Credential Theft**: Private keys never leave authenticator

### Data Logging

Decision points may contain sensitive data:

- **Don't log cookie values** in production
- **Sanitize URLs** (remove query params with tokens)
- **Encrypt logs** if persisted to storage

## Future Enhancements

### Potential Improvements

1. **Automatic Return Detection**
   - Detect when user returns from Custom Tab
   - Auto-trigger cookie sync
   - Resume WebView automatically

2. **Smart Decision Making**
   - Machine learning to predict WebAuthn usage
   - Preemptive Custom Tab warm-up
   - Site-specific policies

3. **Enhanced Analysis**
   - Real-time auth flow visualization
   - Interactive decision point timeline
   - Export to standard formats (HAR, OpenAPI)

4. **Error Recovery**
   - Graceful fallback if Custom Tabs fail
   - Retry logic for cookie sync
   - User notifications for failures

## Related Documentation

- [WebAuthn Specification](https://www.w3.org/TR/webauthn/)
- [Chrome Custom Tabs Guide](https://developer.chrome.com/docs/android/custom-tabs/)
- [Android WebView Documentation](https://developer.android.com/guide/webapps/webview)
- [Cookie Management](https://developer.android.com/reference/android/webkit/CookieManager)

## Contributing

Found a bug or have a suggestion? Please open an issue or PR!

### Testing Checklist

Before submitting changes:

- [ ] Test with WebAuthn demo sites
- [ ] Verify cookie transfer
- [ ] Check decision point logging
- [ ] Test with different Chrome versions
- [ ] Validate on different Android versions (API 34+)
