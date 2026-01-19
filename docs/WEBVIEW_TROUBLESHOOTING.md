# WebView Troubleshooting Guide

## Overview

This document explains common WebView issues in FishIT-Mapper, particularly related to consent pages, login flows, and captcha systems. It also provides guidance on when to use Custom Tabs or AppAuth instead of WebView.

## Common Failure Causes

### 1. Grey Overlay / Blocked Interactions

**Symptoms:**
- Grey overlay appears on consent/login pages
- Touch events don't reach the underlying content
- Buttons and forms appear but cannot be clicked

**Common Causes:**
- **Missing Multiple Windows Support**: Some OAuth providers (especially WebAuthn/FIDO2) require multiple window support
- **Third-Party Cookies Disabled**: SSO flows often require cookies from identity providers
- **JavaScript Dialogs Not Handled**: Some auth flows use alert/confirm/prompt dialogs
- **Permission Requests Not Granted**: WebAuthn requires specific permissions

**Solutions in FishIT-Mapper:**
```kotlin
// Multiple windows support (REQUIRED for WebAuthn/FIDO2)
settings.setSupportMultipleWindows(true)
settings.javaScriptCanOpenWindowsAutomatically = true

// Third-party cookies (REQUIRED for OAuth/SSO)
CookieManager.getInstance().apply {
    setAcceptCookie(true)
    setAcceptThirdPartyCookies(webView, true)
}

// Permission requests (REQUIRED for WebAuthn)
override fun onPermissionRequest(request: PermissionRequest?) {
    request?.grant(request.resources)
}
```

### 2. SSO (Single Sign-On) Failures

**Symptoms:**
- Login succeeds but session is not maintained
- User is logged out immediately after redirect
- "Session expired" errors on legitimate sessions

**Common Causes:**
- **Cookies Not Persisted**: Session cookies are lost during navigation
- **Third-Party Cookies Blocked**: Identity provider cookies cannot be set
- **Cookie Synchronization Issues**: Cookies from OAuth domain not synced to app domain

**Solutions:**
```kotlin
// Persist cookies after OAuth flows
AuthAwareCookieManager.persistCookies()

// Sync cookies between domains if needed
AuthAwareCookieManager.syncCookiesForDomain(fromUrl, toUrl)

// Register session domains for special handling
AuthAwareCookieManager.registerSessionDomain("login.microsoftonline.com")
```

### 3. Anti-Bot / CAPTCHA Issues

**Symptoms:**
- CAPTCHA always fails in WebView
- "Suspicious activity detected" errors
- Requests are blocked by Cloudflare/reCAPTCHA

**Common Causes:**
- **WebView User Agent Detection**: Sites detect WebView and block it
- **Missing Browser Features**: CAPTCHA systems expect full browser capabilities
- **Request Headers**: WebView sends different headers than real browsers

**Limitations:**
- Some anti-bot systems explicitly block WebView
- reCAPTCHA v3 often gives low scores to WebView traffic
- Cloudflare Bot Management may challenge or block WebView

**Workarounds:**
```kotlin
// Use Chrome-like user agent (hide WebView signature)
settings.userAgentString = settings.userAgentString.replace("; wv", "")

// Enable all necessary features
settings.javaScriptEnabled = true
settings.domStorageEnabled = true
settings.databaseEnabled = true
```

**When WebView Won't Work:**
- ⛔ Sites with aggressive bot detection (Cloudflare in "I'm Under Attack" mode)
- ⛔ Banking apps with WebView blocking
- ⛔ Services that explicitly ban WebView (check Terms of Service)

### 4. OAuth / OpenID Connect Flows

**Symptoms:**
- Authorization flow starts but never completes
- Redirect back to app fails
- Access tokens are not received

**Common Causes:**
- **Custom URL Scheme Handling**: WebView might not handle app-specific redirects
- **State Parameter Mismatch**: PKCE/state validation fails
- **Cookie/Session Loss**: Session lost during redirect chain

**Best Practice: Use AppAuth Library**

For production OAuth flows, **use AppAuth-Android instead of WebView**:

```gradle
dependencies {
    implementation 'net.openid:appauth:0.11.1'
}
```

**Why AppAuth is Better:**
- ✅ Uses Chrome Custom Tabs (full browser security)
- ✅ Proper PKCE support
- ✅ Session isolation (doesn't share cookies with main app)
- ✅ Better security (prevents token interception)
- ✅ Recommended by OAuth 2.0 for Native Apps (RFC 8252)

## When to Use Custom Tabs vs WebView

### Use Custom Tabs When:
- ✅ Implementing OAuth/OpenID Connect login
- ✅ Handling sensitive authentication (banking, payment)
- ✅ Site explicitly blocks WebView
- ✅ User needs access to saved passwords/autofill
- ✅ CAPTCHA or anti-bot measures are present
- ✅ Site requires full browser features (WebRTC, WebAuthn)

### Use WebView When:
- ✅ Traffic capture/analysis is required (like in FishIT-Mapper)
- ✅ Custom JavaScript injection is needed
- ✅ Full control over navigation is required
- ✅ Site doesn't use aggressive anti-bot measures
- ✅ Embedded web content within app UI

## Custom Tabs Implementation

For scenarios where WebView doesn't work, use Chrome Custom Tabs:

```kotlin
import androidx.browser.customtabs.CustomTabsIntent
import android.net.Uri

fun openWithCustomTabs(url: String, context: Context) {
    val builder = CustomTabsIntent.Builder()
    builder.setShowTitle(true)
    builder.setToolbarColor(ContextCompat.getColor(context, R.color.primary))
    
    val customTabsIntent = builder.build()
    customTabsIntent.launchUrl(context, Uri.parse(url))
}
```

**Benefits:**
- Shares cookies/session with Chrome
- Full browser security model
- Better user experience for auth flows
- No WebView restrictions

## Debugging WebView Issues

### 1. Check WebView Diagnostics Screen

Navigate to: **Settings → WebView Diagnostics**

This screen shows:
- WebView package and version
- Enabled settings (JS, cookies, etc.)
- Console logs (last 50 messages)
- Error logs (HTTP errors, SSL errors, etc.)
- Cookie state

### 2. Enable Remote Debugging

```kotlin
if (BuildConfig.DEBUG) {
    WebView.setWebContentsDebuggingEnabled(true)
}
```

Then connect Chrome DevTools:
1. Open `chrome://inspect` in Chrome
2. Connect Android device via USB
3. Click "Inspect" on your WebView
4. Use Console/Network/Elements tabs to debug

### 3. Check Android Logs

```bash
# Filter WebView logs
adb logcat -s TrafficInterceptWebView:* AuthAwareCookieManager:*

# Check SSL/Certificate errors
adb logcat | grep -i "ssl\|certificate"

# Monitor cookie operations
adb logcat | grep -i cookie
```

### 4. Common Log Messages

```
✅ "Third-party cookies enabled for OAuth/SSO support"
✅ "Multiple windows support enabled for WebAuthn/OAuth dialogs"
✅ "OAuth page finished, persisting cookies"

⚠️ "SSL Error: Certificate hostname mismatch"
⚠️ "HTTP Error: 403 Forbidden"
⚠️ "Permission requested: [android.webkit.resource.PROTECTED_MEDIA_ID]"

❌ "WebView error: ERR_NAME_NOT_RESOLVED"
❌ "JS Injection failed: ReferenceError"
```

## Security Considerations

### SSL Certificate Errors

**Current Behavior:** FishIT-Mapper proceeds through SSL errors for development/testing.

```kotlin
override fun onReceivedSslError(
    view: WebView?,
    handler: SslErrorHandler?,
    error: SslError?
) {
    // ⚠️ For development only - allows traffic capture
    handler?.proceed()
}
```

**⚠️ Production Warning:** In production apps, **never** automatically proceed through SSL errors. Show a user dialog explaining the risk.

### Cookie Security

**Best Practices:**
- Only enable third-party cookies when necessary
- Clear cookies after sensitive operations
- Use `Secure` and `HttpOnly` flags for sensitive cookies
- Implement cookie consent UI (GDPR compliance)

### JavaScript Injection

When injecting JavaScript for traffic capture:
- Validate all user inputs
- Sanitize data before injection
- Use `evaluateJavascript()` instead of `loadUrl("javascript:...")`
- Implement CSP (Content Security Policy) if possible

## Architecture Recommendations

### For API Discovery (FishIT-Mapper Use Case)
✅ **Use WebView** with full logging enabled
- Allows JavaScript injection for traffic capture
- Complete control over HTTP requests
- Can bypass some bot detection for testing

### For Production Authentication
✅ **Use Custom Tabs** or **AppAuth**
- Better security (session isolation)
- User's saved passwords available
- No WebView restrictions
- Recommended by OAuth 2.0 for Native Apps

### Hybrid Approach
```kotlin
// Authentication: Use Custom Tabs
fun login() {
    openWithCustomTabs("https://example.com/oauth/authorize")
}

// API Testing: Use WebView
fun testApi() {
    webView.loadUrl("https://example.com/api/test")
}
```

## References

- [OAuth 2.0 for Native Apps (RFC 8252)](https://tools.ietf.org/html/rfc8252)
- [AppAuth for Android](https://github.com/openid/AppAuth-Android)
- [Chrome Custom Tabs](https://developer.chrome.com/docs/android/custom-tabs/)
- [WebView Best Practices](https://developer.android.com/develop/ui/views/layout/webapps/best-practices)

## Support

For WebView issues in FishIT-Mapper:
1. Check WebView Diagnostics screen for errors
2. Review console logs for JavaScript errors
3. Verify all required settings are enabled
4. Consider using Custom Tabs if WebView is blocked
5. Use AppAuth for production OAuth implementations
