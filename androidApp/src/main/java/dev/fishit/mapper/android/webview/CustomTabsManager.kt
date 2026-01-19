package dev.fishit.mapper.android.webview

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chrome Custom Tabs Manager für Hybrid Authentication Flow.
 *
 * Verwaltet den Übergang von WebView zu Chrome Custom Tabs und zurück,
 * insbesondere für WebAuthn/FIDO2-Authentifizierung die in WebView nicht funktioniert.
 *
 * ## Flow
 * 1. **WebView → Custom Tabs**:
 *    - Cookies aus WebView extrahieren
 *    - Custom Tab mit gleichem Session-State öffnen
 *    - WebAuthn-Flow in Custom Tab ausführen
 *
 * 2. **Custom Tabs → WebView**:
 *    - Cookies aus Custom Tab zurück zu WebView synchronisieren
 *    - Session-State wiederherstellen
 *    - Navigation in WebView fortsetzen
 *
 * ## Cookie-Synchronisation
 * Chrome Custom Tabs teilt Cookies mit Chrome Browser,
 * aber NICHT automatisch mit WebView. Manuelle Synchronisation erforderlich.
 *
 * ## Verwendung
 * ```kotlin
 * val manager = CustomTabsManager(context)
 *
 * // Zu Custom Tabs wechseln
 * manager.launchCustomTab(url, currentCookies)
 *
 * // Nach Rückkehr: Cookies synchronisieren
 * manager.syncCookiesFromCustomTabs(targetUrl)
 * webView.loadUrl(targetUrl)
 * ```
 */
class CustomTabsManager(private val context: Context) {

    /**
     * Status des Custom Tab Flows.
     */
    data class CustomTabStatus(
        val isActive: Boolean = false,
        val launchedUrl: String? = null,
        val returnUrl: String? = null,
        val cookiesSynced: Boolean = false
    )

    private val _status = MutableStateFlow(CustomTabStatus())
    val status: StateFlow<CustomTabStatus> = _status.asStateFlow()

    private val cookieManager = CookieManager.getInstance()

    /**
     * Prüft ob Chrome Custom Tabs verfügbar ist.
     */
    fun isCustomTabsAvailable(): Boolean {
        val packageName = getCustomTabsPackage()
        return packageName != null
    }

    /**
     * Startet Chrome Custom Tab mit Cookie-Synchronisation.
     *
     * @param url URL die im Custom Tab geladen werden soll
     * @param transferCookies Cookies die von WebView übertragen werden sollen
     * @param returnUrl Optional: URL zu der nach Auth zurückgekehrt werden soll
     */
    fun launchCustomTab(
        url: String,
        transferCookies: Map<String, String> = emptyMap(),
        returnUrl: String? = null
    ) {
        Log.d(TAG, "Launching Custom Tab for URL: $url")
        Log.d(TAG, "Transfer cookies: ${transferCookies.keys.joinToString()}")

        // Cookies zu Custom Tab übertragen (via Chrome Browser Sync)
        if (transferCookies.isNotEmpty()) {
            syncCookiesToCustomTabs(url, transferCookies)
        }

        // Custom Tab Intent konfigurieren
        val builder = CustomTabsIntent.Builder()

        // Color Scheme
        val colorScheme = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.parseColor("#1976D2")) // Material Blue
            .build()
        builder.setDefaultColorSchemeParams(colorScheme)

        // Share Action aktivieren
        builder.setShareState(CustomTabsIntent.SHARE_STATE_ON)

        // Show Title
        builder.setShowTitle(true)

        // Start Animations
        builder.setStartAnimations(context, android.R.anim.fade_in, android.R.anim.fade_out)
        builder.setExitAnimations(context, android.R.anim.fade_in, android.R.anim.fade_out)

        // Custom Tab öffnen
        val customTabsIntent = builder.build()
        val packageName = getCustomTabsPackage()
        if (packageName != null) {
            customTabsIntent.intent.setPackage(packageName)
        }

        try {
            customTabsIntent.launchUrl(context, Uri.parse(url))
            _status.value = CustomTabStatus(
                isActive = true,
                launchedUrl = url,
                returnUrl = returnUrl,
                cookiesSynced = false
            )
            Log.d(TAG, "Custom Tab launched successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Custom Tab", e)
        }
    }

    /**
     * Synchronisiert Cookies von WebView zu Chrome Custom Tabs.
     * Da Custom Tabs Chrome Browser nutzt, werden die Cookies dort gesetzt.
     */
    private fun syncCookiesToCustomTabs(url: String, cookies: Map<String, String>) {
        Log.d(TAG, "Syncing ${cookies.size} cookies to Custom Tabs for $url")

        cookies.forEach { (name, value) ->
            try {
                // Cookie-String erstellen
                val cookieString = "$name=$value; Path=/; Domain=${extractDomain(url)}"
                cookieManager.setCookie(url, cookieString)
                Log.d(TAG, "Cookie set: $name")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set cookie $name", e)
            }
        }

        // Cookies sofort persistieren
        cookieManager.flush()
        Log.d(TAG, "Cookies flushed to storage")
    }

    /**
     * Synchronisiert Cookies von Chrome Custom Tabs zurück zu WebView.
     * Wird aufgerufen nachdem User von Custom Tab zurückkehrt.
     *
     * @param url URL von der Cookies gelesen werden sollen
     * @return Map von Cookie-Namen zu Werten
     */
    fun syncCookiesFromCustomTabs(url: String): Map<String, String> {
        Log.d(TAG, "Syncing cookies from Custom Tabs for $url")

        try {
            // Alle Cookies für URL abrufen
            val cookieString = cookieManager.getCookie(url)
            if (cookieString != null) {
                Log.d(TAG, "Retrieved cookies: ${cookieString.take(200)}")

                // Cookie-String parsen (Issue #10: use shared utility with attribute filtering)
                val cookies = CookieUtils.parseCookieString(cookieString)

                Log.d(TAG, "Parsed ${cookies.size} cookies: ${cookies.keys.joinToString()}")
                _status.value = _status.value.copy(cookiesSynced = true)
                return cookies
            } else {
                Log.w(TAG, "No cookies found for $url")
            }

            _status.value = _status.value.copy(cookiesSynced = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cookies from Custom Tabs", e)
        }

        return emptyMap()
    }

    /**
     * Markiert Custom Tab Flow als abgeschlossen.
     */
    fun completeCustomTabFlow() {
        Log.d(TAG, "Custom Tab flow completed")
        _status.value = CustomTabStatus()
    }

    /**
     * Extrahiert Domain aus URL für Cookie-Scope.
     */
    private fun extractDomain(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Findet das beste Chrome Custom Tabs Package.
     * Priorisiert Chrome, dann Chrome Beta, dann Chrome Dev.
     */
    private fun getCustomTabsPackage(): String? {
        val pm = context.packageManager
        val activityIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))

        // Alle Apps finden die HTTP URLs öffnen können
        val resolvedActivities = pm.queryIntentActivities(activityIntent, 0)

        // Priorisierte Liste von Chrome Packages
        val preferredPackages = listOf(
            "com.android.chrome",           // Chrome Stable
            "com.chrome.beta",              // Chrome Beta
            "com.chrome.dev",               // Chrome Dev
            "com.chrome.canary",            // Chrome Canary
            "org.chromium.chrome"           // Chromium
        )

        // Prüfe ob eines der bevorzugten Packages Custom Tabs unterstützt
        for (packageName in preferredPackages) {
            if (resolvedActivities.any { it.activityInfo.packageName == packageName }) {
                // Prüfe ob Package Custom Tabs Service hat
                val serviceIntent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
                serviceIntent.setPackage(packageName)
                if (pm.resolveService(serviceIntent, 0) != null) {
                    Log.d(TAG, "Using Custom Tabs package: $packageName")
                    return packageName
                }
            }
        }

        // Fallback: Erstes Package das Custom Tabs unterstützt
        for (info in resolvedActivities) {
            val serviceIntent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
            serviceIntent.setPackage(info.activityInfo.packageName)
            if (pm.resolveService(serviceIntent, 0) != null) {
                Log.d(TAG, "Using fallback Custom Tabs package: ${info.activityInfo.packageName}")
                return info.activityInfo.packageName
            }
        }

        Log.w(TAG, "No Custom Tabs package found")
        return null
    }

    companion object {
        private const val TAG = "CustomTabsManager"
    }
}
