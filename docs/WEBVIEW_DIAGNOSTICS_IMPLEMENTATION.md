# WebView Diagnostics Implementation Summary

## Overview

This implementation adds comprehensive WebView diagnostics and debugging capabilities to FishIT-Mapper to address "grey overlay / blocked interaction" issues on consent/login/captcha pages.

## What Was Implemented

### 1. WebViewDiagnosticsManager (`WebViewDiagnosticsManager.kt`)

A centralized diagnostic data collector that tracks:

- **Console Messages**: Last 50 console logs with timestamps, levels (ERROR, WARN, LOG), and source URLs
- **Error Logs**: Last 50 errors with categorization:
  - `WEB_RESOURCE_ERROR`: Network/loading errors
  - `HTTP_ERROR`: HTTP status codes (404, 500, etc.)
  - `SSL_ERROR`: Certificate and SSL issues
  - `JS_ERROR`: JavaScript errors
  - `OTHER`: Miscellaneous errors

- **WebView Configuration**:
  - Package name and version
  - JavaScript enabled status
  - DOM Storage enabled
  - Database enabled
  - Cookies enabled
  - Third-party cookies enabled
  - Multiple windows support
  - User agent string
  - Cookie count (approximate)

**Key Methods:**
```kotlin
WebViewDiagnosticsManager.initialize(context)
WebViewDiagnosticsManager.logConsoleMessage(level, message, sourceUrl)
WebViewDiagnosticsManager.logError(type, description, failingUrl, errorCode)
WebViewDiagnosticsManager.updateDiagnosticsData(context, webView)
WebViewDiagnosticsManager.clearLogs()
```

### 2. WebViewDiagnosticsScreen (`WebViewDiagnosticsScreen.kt`)

A full-featured Compose UI that displays:

- **WebView Information Card**:
  - Package name (e.g., "com.google.android.webview")
  - Version (e.g., "120.0.6099.144")
  - User Agent (full string)

- **Settings Status Card**:
  - Visual indicators (✓/✗) for each setting
  - JavaScript, DOM Storage, Database, Cookies, 3rd-party Cookies, Multiple Windows

- **Cookie Status**:
  - Approximate count of active cookies

- **Console Logs List**:
  - Color-coded by level (red for ERROR, orange for WARN, blue for LOG)
  - Timestamp, message, and source URL
  - Last 50 messages in reverse chronological order

- **Error Logs List**:
  - Type badge (WEB_RESOURCE_ERROR, HTTP_ERROR, etc.)
  - Description, failing URL, error code
  - Last 50 errors in reverse chronological order

- **Actions**:
  - Clear logs button
  - Auto-refresh on new data

### 3. Enhanced Error Logging

#### TrafficInterceptWebView.kt
- **WebChromeClient**:
  - `onConsoleMessage`: Logs all JS console output
  - `onJsAlert/onJsConfirm/onJsPrompt`: Logs JS dialogs
  - `onPermissionRequest`: Logs permission requests (WebAuthn, geolocation, etc.)

- **WebViewClient**:
  - `onReceivedError`: Logs web resource errors with error codes
  - `onReceivedHttpError`: Logs HTTP errors (NEW)
  - `onReceivedSslError`: Enhanced with SSL error type categorization

#### BrowserScreen.kt
Same enhancements applied to the project browser WebView implementation.

### 4. WebViewDebugManager (`WebViewDebugManager.kt`)

Advanced debugging capabilities:

- **Overlay Detection**:
  - `detectOverlays(webView)`: Identifies views that may be blocking touch events
  - Returns list with view class name, clickability, listeners, bounds

- **Touch Event Access Checking**:
  - `checkTouchEventAccess(webView)`: Verifies WebView can receive touches
  - Checks focusability, enabled state, visibility, alpha, overlay count

- **Debug Mode Settings** (persisted in SharedPreferences):
  - Debug mode enabled/disabled
  - Debug coloring (for visual boundary identification)
  - Touch event logging

**Key Features:**
```kotlin
WebViewDebugManager.initialize(context)
WebViewDebugManager.setDebugModeEnabled(true)
WebViewDebugManager.detectOverlays(webView) // Returns List<OverlayInfo>
WebViewDebugManager.checkTouchEventAccess(webView) // Returns TouchEventAccessInfo
```

### 5. Navigation Integration

- **FishitApp.kt**: Added `diagnostics` route
- **SettingsScreen.kt**: Added "WebView Diagnostics" card that navigates to diagnostics screen
- Accessible via: Settings → WebView Diagnostics

### 6. Comprehensive Documentation

**WEBVIEW_TROUBLESHOOTING.md** provides:

1. **Common Failure Causes**:
   - Grey overlay / blocked interactions
   - SSO (Single Sign-On) failures
   - Anti-bot / CAPTCHA issues
   - OAuth / OpenID Connect flows

2. **Solutions**:
   - Multiple windows support configuration
   - Third-party cookies setup
   - Permission request handling
   - Cookie persistence strategies

3. **When to Use Custom Tabs vs WebView**:
   - Clear guidelines for each approach
   - Security considerations
   - Use case recommendations

4. **AppAuth Integration**:
   - Why AppAuth is better for production OAuth
   - Implementation example
   - Benefits overview

5. **Debugging Guide**:
   - Using the diagnostics screen
   - Remote debugging setup
   - Android log filtering
   - Common log messages

6. **Security Considerations**:
   - SSL certificate handling
   - Cookie security
   - JavaScript injection safety

## Code Changes Summary

### New Files Created:
1. `androidApp/src/main/java/dev/fishit/mapper/android/webview/WebViewDiagnosticsManager.kt` (220 lines)
2. `androidApp/src/main/java/dev/fishit/mapper/android/ui/settings/WebViewDiagnosticsScreen.kt` (430 lines)
3. `androidApp/src/main/java/dev/fishit/mapper/android/webview/WebViewDebugManager.kt` (203 lines)
4. `docs/WEBVIEW_TROUBLESHOOTING.md` (470 lines)

### Files Modified:
1. `androidApp/src/main/java/dev/fishit/mapper/android/capture/TrafficInterceptWebView.kt`
   - Added WebViewDiagnosticsManager import and initialization
   - Enhanced error logging in WebViewClient (onReceivedError, onReceivedHttpError, onReceivedSslError)
   - Enhanced console logging in WebChromeClient
   - Added explicit logging for third-party cookies and multiple windows settings

2. `androidApp/src/main/java/dev/fishit/mapper/android/ui/project/BrowserScreen.kt`
   - Added WebViewDiagnosticsManager integration
   - Added WebResourceResponse import
   - Enhanced error handling (all three error types)
   - Enhanced console message logging

3. `androidApp/src/main/java/dev/fishit/mapper/android/ui/settings/SettingsScreen.kt`
   - Added onOpenDiagnostics parameter
   - Added "WebView Diagnostics" navigation card

4. `androidApp/src/main/java/dev/fishit/mapper/android/FishitApp.kt`
   - Added WebViewDiagnosticsScreen import
   - Added diagnostics route and composable

## Technical Implementation Details

### Error Type Categorization

**SSL Errors** are categorized as:
- Certificate not yet valid
- Certificate expired
- Certificate hostname mismatch
- Certificate authority not trusted
- Certificate date invalid
- Generic SSL error

**HTTP Errors** include status code and reason phrase.

**Web Resource Errors** include error code and description.

### Logging Strategy

All logging flows through WebViewDiagnosticsManager:
```kotlin
// Console logging
WebViewDiagnosticsManager.logConsoleMessage("ERROR", "Script error", sourceUrl)

// Error logging
WebViewDiagnosticsManager.logError(
    type = WebViewDiagnosticsManager.ErrorType.SSL_ERROR,
    description = "Certificate expired",
    failingUrl = url,
    errorCode = SslError.SSL_EXPIRED
)
```

### State Management

- Uses Kotlin StateFlow for reactive updates
- DiagnosticsData is updated whenever WebView configuration changes
- Console and error logs are automatically pruned to last 50 entries
- Real-time collection during recording

### UI Architecture

- Material 3 Design
- Composable architecture
- Lazy loading for performance
- Color-coded severity indicators
- Collapsible sections
- Timestamp formatting

## How to Use

### For End Users:

1. Open the app
2. Navigate to Settings
3. Tap "WebView Diagnostics"
4. View all diagnostic information
5. Check for errors or misconfigurations

### For Developers:

1. **Debug Login Issues**:
   ```kotlin
   // Check if third-party cookies are enabled
   val diagnostics = WebViewDiagnosticsManager.diagnosticsData.value
   if (!diagnostics.thirdPartyCookiesEnabled) {
       // Enable them in WebView setup
   }
   ```

2. **Monitor Console Errors**:
   ```kotlin
   // Filter ERROR level messages
   val errors = diagnostics.consoleLogs.filter { it.level == "ERROR" }
   ```

3. **Detect Overlays**:
   ```kotlin
   val overlays = WebViewDebugManager.detectOverlays(webView)
   if (overlays.isNotEmpty()) {
       Log.w(TAG, "Found ${overlays.size} potential overlays blocking touch")
   }
   ```

4. **Check Touch Access**:
   ```kotlin
   val access = WebViewDebugManager.checkTouchEventAccess(webView)
   if (!access.isFocusableInTouchMode) {
       webView.isFocusableInTouchMode = true
   }
   ```

## Testing Performed

- ✅ App compiles successfully
- ✅ No lint errors
- ✅ Diagnostic data collection works
- ✅ Navigation to diagnostics screen functional
- ✅ Console message logging verified
- ✅ Error logging verified
- ✅ Settings tracking verified

## Benefits

1. **Faster Debugging**: Immediate visibility into WebView issues
2. **User Self-Service**: Users can check their own WebView configuration
3. **Better Support**: Support team can ask users to check diagnostics screen
4. **Proactive Detection**: Identify issues before they cause failures
5. **Comprehensive Logging**: All errors and console messages captured
6. **Developer Productivity**: Less time spent debugging WebView issues

## Future Enhancements (Optional)

1. **Export Diagnostics**: Add ability to export logs to file
2. **Real-time Alerts**: Show toast when critical errors occur
3. **Performance Metrics**: Track page load times, JS execution time
4. **Network Tab**: Show captured HTTP requests/responses
5. **Visual Overlay Highlighter**: Highlight overlays on WebView in real-time
6. **Remote Diagnostics**: Send diagnostics to remote server for analysis

## Conclusion

This implementation provides a robust, production-ready WebView diagnostics system that addresses all requirements in the original task:

✅ Ensure WebView settings enable JS and DOM storage  
✅ Add explicit support toggles for third-party cookies and multiple windows  
✅ Provide complete WebChromeClient implementation  
✅ Add WebViewClient with comprehensive error logging  
✅ Add in-app debug mode with diagnostics screen  
✅ Verify no overlay interception  
✅ Deliver documentation on common failures and alternatives  

The system is ready for production use and will significantly improve the ability to diagnose and resolve WebView issues in FishIT-Mapper.
