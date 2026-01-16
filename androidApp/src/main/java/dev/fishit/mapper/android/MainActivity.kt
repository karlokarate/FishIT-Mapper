package dev.fishit.mapper.android

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.fishit.mapper.android.di.AppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Log WebView provider information at startup for debugging
        logWebViewProviderInfo()

        val container = AppContainer(applicationContext)

        setContent {
            FishitApp(container = container)
        }
    }

    /**
     * Logs detailed WebView provider information to help diagnose WebView issues.
     * This is part of the investigation for WebView provider conflicts on Android 14+.
     */
    private fun logWebViewProviderInfo() {
        try {
            Log.i(TAG, "=== WebView Provider Investigation ===")
            Log.i(TAG, "Android Version: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            Log.i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val packageInfo = WebView.getCurrentWebViewPackage()
                if (packageInfo != null) {
                    Log.i(TAG, "WebView Provider Package: ${packageInfo.packageName}")
                    Log.i(TAG, "WebView Provider Version Name: ${packageInfo.versionName}")
                    @Suppress("DEPRECATION")
                    Log.i(TAG, "WebView Provider Version Code: ${packageInfo.versionCode}")
                    
                    // Compare with known device state: Android System WebView 143.0.7499.192
                    Log.i(TAG, "Expected Device WebView: Android System WebView 143.0.7499.192")
                    
                    if (packageInfo.versionName != "143.0.7499.192") {
                        Log.w(TAG, "⚠️ WebView version mismatch detected!")
                        Log.w(TAG, "   Device reports: 143.0.7499.192")
                        Log.w(TAG, "   Runtime reports: ${packageInfo.versionName}")
                    } else {
                        Log.i(TAG, "✓ WebView version matches device-reported version")
                    }
                } else {
                    Log.w(TAG, "⚠️ Could not retrieve WebView package info (null)")
                }
            } else {
                Log.w(TAG, "⚠️ WebView.getCurrentWebViewPackage() not available on Android < 8.0")
            }
            
            Log.i(TAG, "======================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging WebView provider info", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
