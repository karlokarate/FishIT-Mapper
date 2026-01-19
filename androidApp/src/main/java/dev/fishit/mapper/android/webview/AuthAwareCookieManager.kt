package dev.fishit.mapper.android.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.util.Log

/**
 * Manager für Cookie-Persistenz und OAuth-Flow-Unterstützung.
 *
 * Löst das Problem, dass OAuth-Authentifizierung (z.B. Microsoft Azure AD)
 * nicht korrekt funktioniert, wenn zwischen verschiedenen Domains navigiert wird.
 *
 * ## Das Problem
 * - User authentifiziert sich bei Portal A (z.B. kolping-hochschule.de)
 * - Portal A leitet zu Azure AD weiter für SSO
 * - Azure AD setzt Session-Cookies und leitet zurück
 * - Bei Navigation zu Portal B (z.B. cms.kolping-hochschule.de) werden Cookies benötigt
 * - WebView verliert Cookies wenn nicht korrekt konfiguriert
 *
 * ## Die Lösung
 * - Third-Party Cookies erlauben
 * - Cookie-Persistenz aktivieren
 * - Alle Cookies für OAuth-Domains akzeptieren
 * - Flush nach jeder Auth-Redirect-Kette
 */
object AuthAwareCookieManager {

    private const val TAG = "AuthAwareCookieManager"

    // Bekannte OAuth/SSO-Provider Domains
    private val OAUTH_DOMAINS = setOf(
        "login.microsoftonline.com",
        "login.windows.net",
        "login.live.com",
        "accounts.google.com",
        "auth0.com",
        "okta.com",
        "cognito-idp",
        "sts.windows.net"
    )

    // Domains die Session-Cookies teilen müssen
    private val SESSION_SHARE_DOMAINS = mutableSetOf<String>()

    /**
     * Initialisiert den CookieManager für OAuth-Flows.
     * MUSS vor dem ersten WebView-Zugriff aufgerufen werden!
     */
    fun initialize(context: Context) {
        val cookieManager = CookieManager.getInstance()

        // Cookies grundsätzlich aktivieren
        cookieManager.setAcceptCookie(true)

        // WICHTIG: Flush um sicherzustellen dass Cookies persistiert werden
        cookieManager.flush()

        Log.i(TAG, "CookieManager initialized for OAuth support")
    }

    /**
     * Konfiguriert einen WebView für OAuth-kompatibles Cookie-Handling.
     *
     * @param webView Der zu konfigurierende WebView
     */
    fun configureWebViewForOAuth(webView: android.webkit.WebView) {
        val cookieManager = CookieManager.getInstance()

        // Third-Party Cookies für diesen WebView erlauben
        // Notwendig für Cross-Domain OAuth (Azure AD → App)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        Log.d(TAG, "WebView configured for OAuth cookies")
    }

    /**
     * Registriert eine Domain für Session-Sharing.
     * Cookies dieser Domain werden besonders geschützt.
     */
    fun registerSessionDomain(domain: String) {
        SESSION_SHARE_DOMAINS.add(domain.lowercase())
        Log.d(TAG, "Registered session domain: $domain")
    }

    /**
     * Prüft ob eine URL zu einem OAuth-Provider gehört.
     */
    fun isOAuthUrl(url: String): Boolean {
        val host = try {
            android.net.Uri.parse(url).host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }

        return OAUTH_DOMAINS.any { oauthDomain ->
            host.contains(oauthDomain)
        }
    }

    /**
     * Prüft ob ein Redirect ein OAuth-Redirect ist.
     * OAuth-Redirects haben typische Parameter wie:
     * - code (Authorization Code)
     * - id_token
     * - access_token
     * - state
     */
    fun isOAuthRedirect(url: String): Boolean {
        if (isOAuthUrl(url)) return true

        val uri = try {
            android.net.Uri.parse(url)
        } catch (e: Exception) {
            return false
        }

        // Prüfe auf typische OAuth-Parameter im Fragment oder Query
        val fragment = uri.fragment ?: ""
        val query = uri.query ?: ""

        val oauthParams = listOf("code=", "id_token=", "access_token=", "state=", "session_state=")
        return oauthParams.any { param ->
            fragment.contains(param) || query.contains(param)
        }
    }

    /**
     * Kopiert Cookies von einer OAuth-Domain zur Ziel-Domain.
     * Nützlich wenn SSO-Token geteilt werden müssen.
     */
    fun syncCookiesForDomain(fromUrl: String, toUrl: String) {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(fromUrl)

            if (cookies != null) {
                Log.d(TAG, "Syncing cookies from $fromUrl to $toUrl")

                // Jedes Cookie einzeln setzen
                cookies.split(";").forEach { cookie ->
                    val trimmed = cookie.trim()
                    if (trimmed.isNotEmpty()) {
                        cookieManager.setCookie(toUrl, trimmed)
                    }
                }

                // Sofort persistieren
                cookieManager.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync cookies: ${e.message}", e)
        }
    }

    /**
     * Speichert alle aktuellen Cookies persistent.
     * Sollte nach OAuth-Flows aufgerufen werden.
     */
    fun persistCookies() {
        try {
            CookieManager.getInstance().flush()
            Log.d(TAG, "Cookies persisted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist cookies: ${e.message}", e)
        }
    }

    /**
     * Gibt alle Cookies für eine URL zurück (für Debugging).
     */
    fun getCookiesForUrl(url: String): String? {
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cookies for $url: ${e.message}")
            null
        }
    }

    /**
     * Löscht alle Cookies. VORSICHT: Loggt User aus!
     */
    fun clearAllCookies(callback: (() -> Unit)? = null) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies { success ->
            Log.d(TAG, "Cookies cleared: $success")
            cookieManager.flush()
            callback?.invoke()
        }
    }

    /**
     * Löscht Session-Storage und Caches, behält aber Cookies.
     * Nützlich wenn nur "frische" Daten geladen werden sollen.
     */
    fun clearStorageKeepCookies() {
        try {
            WebStorage.getInstance().deleteAllData()
            Log.d(TAG, "WebStorage cleared (cookies preserved)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear storage: ${e.message}", e)
        }
    }

    /**
     * Debug: Loggt alle Cookies für wichtige Domains.
     */
    fun debugLogAllCookies(domains: List<String>) {
        domains.forEach { domain ->
            val url = "https://$domain"
            val cookies = getCookiesForUrl(url)
            Log.d(TAG, "Cookies for $domain: ${cookies?.take(200) ?: "none"}")
        }
    }
}
