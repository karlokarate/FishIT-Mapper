package dev.fishit.mapper.android.webview

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Hybrid Authentication Flow Manager.
 *
 * Orchestriert den Übergang zwischen WebView und Chrome Custom Tabs
 * für WebAuthn/FIDO2-Authentifizierung.
 *
 * ## Konzept
 * - **WebView First**: Normale Navigation läuft in WebView für Traffic-Capture
 * - **Custom Tabs on Demand**: Nur bei WebAuthn-Bedarf zu Custom Tabs wechseln
 * - **Seamless Resume**: Nach erfolgreicher Auth zurück zu WebView mit Session
 *
 * ## Decision Points
 * Jeder Übergang wird als "Decision Point" geloggt mit:
 * - Zeitstempel
 * - Auslöser (WebAuthn API call, URL pattern, user action)
 * - Context (URL, Cookies, Headers)
 * - Ergebnis (Success, Failure, User Cancelled)
 *
 * Diese Decision Points können später mit HTTP Canary Daten korreliert werden
 * für vollständige Auth-Flow-Analyse.
 *
 * ## Verwendung
 * ```kotlin
 * val manager = HybridAuthFlowManager(context)
 *
 * // WebAuthn erkannt
 * manager.onWebAuthnRequired(url, trigger)
 *
 * // Nach Custom Tab Rückkehr
 * manager.onCustomTabReturned(webView)
 * ```
 */
class HybridAuthFlowManager(
    private val context: Context
) {

    /**
     * Flow-Status.
     */
    enum class FlowState {
        WEBVIEW_ACTIVE,      // Normale WebView-Navigation
        WEBAUTHN_DETECTED,   // WebAuthn erkannt, noch in WebView
        CUSTOM_TAB_ACTIVE,   // Custom Tab läuft für WebAuthn
        CUSTOM_TAB_COMPLETE, // Custom Tab fertig, zurück zu WebView
        SESSION_RESTORED     // Session in WebView wiederhergestellt
    }

    /**
     * Decision Point - Entscheidungspunkt im Auth-Flow.
     */
    data class DecisionPoint(
        val id: String,
        val timestamp: Instant,
        val state: FlowState,
        val trigger: String,           // Was hat die Entscheidung ausgelöst?
        val url: String,               // Aktuelle URL
        val cookies: Map<String, String>, // Cookies zu diesem Zeitpunkt
        val outcome: String? = null    // Ergebnis (für spätere Updates)
    )

    /**
     * Flow-Kontext mit allen relevanten Daten.
     */
    data class FlowContext(
        val state: FlowState = FlowState.WEBVIEW_ACTIVE,
        val currentUrl: String? = null,
        val webAuthnTrigger: String? = null,
        val decisionPoints: List<DecisionPoint> = emptyList(),
        val cookiesBeforeTransition: Map<String, String> = emptyMap(),
        val customTabLaunchedAt: Instant? = null
    )

    private val _flowContext = MutableStateFlow(FlowContext())
    val flowContext: StateFlow<FlowContext> = _flowContext.asStateFlow()

    private val webAuthnDetector = WebAuthnDetector()
    private val customTabsManager = CustomTabsManager(context)

    val webAuthnStatus = webAuthnDetector.webAuthnStatus
    val customTabStatus = customTabsManager.status

    /**
     * Wird aufgerufen wenn WebView eine neue Seite lädt.
     * Injiziert WebAuthn-Detection und aktualisiert State.
     */
    fun onPageStarted(webView: android.webkit.WebView, url: String) {
        Log.d(TAG, "Page started: $url")

        // State aktualisieren
        _flowContext.value = _flowContext.value.copy(
            currentUrl = url
        )

        // WebAuthn-Detection injizieren
        webView.post {
            webAuthnDetector.injectDetectionScript(webView)
        }
    }

    /**
     * Wird aufgerufen wenn WebAuthn-Verwendung erkannt wurde.
     * Entscheidet ob zu Custom Tabs gewechselt werden soll.
     */
    fun onWebAuthnRequired(
        url: String,
        trigger: String,
        currentCookies: Map<String, String>
    ): Boolean {
        Log.w(TAG, "⚠️ WebAuthn required! URL: $url, Trigger: $trigger")

        // Decision Point loggen
        val decisionPoint = DecisionPoint(
            id = "dp_${Clock.System.now().toEpochMilliseconds()}",
            timestamp = Clock.System.now(),
            state = FlowState.WEBAUTHN_DETECTED,
            trigger = trigger,
            url = url,
            cookies = currentCookies
        )

        _flowContext.value = _flowContext.value.copy(
            state = FlowState.WEBAUTHN_DETECTED,
            currentUrl = url,
            webAuthnTrigger = trigger,
            cookiesBeforeTransition = currentCookies,
            decisionPoints = _flowContext.value.decisionPoints + decisionPoint
        )

        // Prüfe ob Custom Tabs verfügbar ist
        if (!customTabsManager.isCustomTabsAvailable()) {
            Log.e(TAG, "Custom Tabs not available - cannot handle WebAuthn")
            return false
        }

        // Zu Custom Tabs wechseln
        launchCustomTabsForWebAuthn(url, currentCookies)
        return true
    }

    /**
     * Startet Custom Tab für WebAuthn-Flow.
     */
    private fun launchCustomTabsForWebAuthn(
        url: String,
        cookies: Map<String, String>
    ) {
        Log.d(TAG, "Launching Custom Tab for WebAuthn at $url")

        // Custom Tab starten
        customTabsManager.launchCustomTab(
            url = url,
            transferCookies = cookies,
            returnUrl = url // Zurück zur gleichen URL nach Auth
        )

        // Decision Point loggen
        val decisionPoint = DecisionPoint(
            id = "dp_${Clock.System.now().toEpochMilliseconds()}",
            timestamp = Clock.System.now(),
            state = FlowState.CUSTOM_TAB_ACTIVE,
            trigger = "custom_tab_launched",
            url = url,
            cookies = cookies
        )

        _flowContext.value = _flowContext.value.copy(
            state = FlowState.CUSTOM_TAB_ACTIVE,
            customTabLaunchedAt = Clock.System.now(),
            decisionPoints = _flowContext.value.decisionPoints + decisionPoint
        )
    }

    /**
     * Wird aufgerufen wenn User von Custom Tab zurückkehrt.
     * Synchronisiert Session zurück zu WebView.
     *
     * @return true wenn Session erfolgreich wiederhergestellt wurde
     */
    fun onCustomTabReturned(webView: android.webkit.WebView): Boolean {
        Log.d(TAG, "Custom Tab returned, restoring session")

        val currentUrl = _flowContext.value.currentUrl ?: return false

        // Cookies von Custom Tab synchronisieren
        val updatedCookies = customTabsManager.syncCookiesFromCustomTabs(currentUrl)

        Log.d(TAG, "Synced ${updatedCookies.size} cookies from Custom Tab")

        // Cookies zu WebView übertragen
        val cookieManager = android.webkit.CookieManager.getInstance()
        updatedCookies.forEach { (name, value) ->
            val cookieString = "$name=$value; Path=/; Domain=${extractDomain(currentUrl)}"
            cookieManager.setCookie(currentUrl, cookieString)
        }
        cookieManager.flush()

        // Decision Point loggen
        val decisionPoint = DecisionPoint(
            id = "dp_${Clock.System.now().toEpochMilliseconds()}",
            timestamp = Clock.System.now(),
            state = FlowState.SESSION_RESTORED,
            trigger = "custom_tab_returned",
            url = currentUrl,
            cookies = updatedCookies,
            outcome = "success"
        )

        _flowContext.value = _flowContext.value.copy(
            state = FlowState.SESSION_RESTORED,
            decisionPoints = _flowContext.value.decisionPoints + decisionPoint
        )

        // WebView zur URL neu laden (mit neuen Cookies)
        webView.loadUrl(currentUrl)

        // Custom Tab Flow abschließen
        customTabsManager.completeCustomTabFlow()

        return true
    }

    /**
     * Wird aufgerufen bei User-Cancel des Custom Tabs.
     */
    fun onCustomTabCancelled() {
        Log.d(TAG, "Custom Tab cancelled by user")

        val decisionPoint = DecisionPoint(
            id = "dp_${Clock.System.now().toEpochMilliseconds()}",
            timestamp = Clock.System.now(),
            state = FlowState.WEBVIEW_ACTIVE,
            trigger = "custom_tab_cancelled",
            url = _flowContext.value.currentUrl ?: "unknown",
            cookies = emptyMap(),
            outcome = "cancelled"
        )

        _flowContext.value = _flowContext.value.copy(
            state = FlowState.WEBVIEW_ACTIVE,
            decisionPoints = _flowContext.value.decisionPoints + decisionPoint
        )

        customTabsManager.completeCustomTabFlow()
    }

    /**
     * Exportiert alle Decision Points für Analyse.
     * Kann mit HTTP Canary Daten korreliert werden.
     */
    fun exportDecisionPoints(): List<DecisionPoint> {
        return _flowContext.value.decisionPoints
    }

    /**
     * Setzt Flow zurück (z.B. bei neuer Session).
     */
    fun reset() {
        Log.d(TAG, "Resetting hybrid auth flow")
        _flowContext.value = FlowContext()
        webAuthnDetector.reset()
    }

    /**
     * Injiziert Bridge-Methoden für WebAuthn-Detection in WebView.
     */
    fun setupWebViewBridge(webView: android.webkit.WebView) {
        // JavaScript Interface für WebAuthn Detection
        webView.addJavascriptInterface(WebAuthnBridge(), "FishIT")
    }

    /**
     * JavaScript Bridge für WebAuthn-Callbacks.
     */
    private inner class WebAuthnBridge {
        @android.webkit.JavascriptInterface
        fun reportWebAuthnApiAvailable(available: Boolean, credentialsAvailable: Boolean) {
            webAuthnDetector.reportWebAuthnApiAvailable(available, credentialsAvailable)
        }

        @android.webkit.JavascriptInterface
        fun reportWebAuthnApiUsed(url: String?, element: String?) {
            webAuthnDetector.reportWebAuthnApiUsed(url, element)

            // Automatisch zu Custom Tabs wechseln
            val currentUrl = url ?: _flowContext.value.currentUrl ?: return
            val trigger = "webauthn_api_used:$element"

            // Cookies aus WebView extrahieren
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookieString = cookieManager.getCookie(currentUrl)
            val cookies = parseCookieString(cookieString)

            // Zu Custom Tabs wechseln
            onWebAuthnRequired(currentUrl, trigger, cookies)
        }
    }

    /**
     * Parst Cookie-String zu Map.
     */
    private fun parseCookieString(cookieString: String?): Map<String, String> {
        if (cookieString.isNullOrBlank()) return emptyMap()

        return cookieString.split(";")
            .mapNotNull { cookie ->
                val parts = cookie.trim().split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    /**
     * Extrahiert Domain aus URL.
     */
    private fun extractDomain(url: String): String {
        return try {
            android.net.Uri.parse(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }

    companion object {
        private const val TAG = "HybridAuthFlowManager"
    }
}
