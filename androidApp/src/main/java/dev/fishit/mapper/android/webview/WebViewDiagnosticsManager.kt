package dev.fishit.mapper.android.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Zentraler Manager für WebView-Diagnose und Debugging.
 *
 * Sammelt alle wichtigen Informationen über WebView-Status, Fehler, Console-Logs
 * und Settings für Debug-Zwecke. Hilfreich bei der Diagnose von:
 * - OAuth/SSO Login-Problemen
 * - Blocked Interactions (graue Overlays)
 * - Cookie-Problemen
 * - JavaScript-Fehlern
 *
 * ## Verwendung
 * ```kotlin
 * // In WebView setup (nach vollständiger Konfiguration):
 * WebViewDiagnosticsManager.updateDiagnosticsData(context, webView)
 * 
 * // Logging während der Nutzung:
 * WebViewDiagnosticsManager.logConsoleMessage("INFO", "Page loaded", webView.url)
 *
 * // In Diagnostics Screen:
 * val diagnostics by WebViewDiagnosticsManager.diagnosticsData.collectAsState()
 * ```
 */
object WebViewDiagnosticsManager {

    /**
     * Console-Nachricht mit Timestamp und Level.
     */
    data class ConsoleLog(
        val timestamp: Instant,
        val level: String,
        val message: String,
        val sourceUrl: String?
    )

    /**
     * Fehler-Eintrag mit Details.
     */
    data class ErrorLog(
        val timestamp: Instant,
        val type: ErrorType,
        val description: String,
        val failingUrl: String?,
        val errorCode: Int?
    )

    enum class ErrorType {
        WEB_RESOURCE_ERROR,
        HTTP_ERROR,
        SSL_ERROR,
        JS_ERROR,
        OTHER
    }

    /**
     * Vollständige Diagnostik-Daten.
     */
    data class DiagnosticsData(
        val webViewPackage: String?,
        val webViewVersion: String?,
        val userAgent: String?,
        val javaScriptEnabled: Boolean,
        val domStorageEnabled: Boolean,
        val databaseEnabled: Boolean,
        val cookiesEnabled: Boolean,
        val thirdPartyCookiesEnabled: Boolean,
        val multipleWindowsSupported: Boolean,
        val consoleLogs: List<ConsoleLog>,
        val errorLogs: List<ErrorLog>,
        val cookieCount: Int,
        val lastUpdated: Instant
    )

    private val _consoleLogs = MutableStateFlow<List<ConsoleLog>>(emptyList())
    private val _errorLogs = MutableStateFlow<List<ErrorLog>>(emptyList())
    private val _diagnosticsData = MutableStateFlow<DiagnosticsData?>(null)

    val diagnosticsData: StateFlow<DiagnosticsData?> = _diagnosticsData.asStateFlow()

    private const val MAX_CONSOLE_LOGS = 50
    private const val MAX_ERROR_LOGS = 50

    /**
     * Loggt eine Console-Nachricht.
     */
    fun logConsoleMessage(level: String, message: String, sourceUrl: String? = null) {
        val log = ConsoleLog(
            timestamp = Clock.System.now(),
            level = level,
            message = message,
            sourceUrl = sourceUrl
        )

        _consoleLogs.value = (_consoleLogs.value + log).takeLast(MAX_CONSOLE_LOGS)
        updateLastUpdated()
    }

    /**
     * Loggt einen Fehler.
     */
    fun logError(
        type: ErrorType,
        description: String,
        failingUrl: String? = null,
        errorCode: Int? = null
    ) {
        val log = ErrorLog(
            timestamp = Clock.System.now(),
            type = type,
            description = description,
            failingUrl = failingUrl,
            errorCode = errorCode
        )

        _errorLogs.value = (_errorLogs.value + log).takeLast(MAX_ERROR_LOGS)
        updateLastUpdated()
    }

    /**
     * Aktualisiert die vollständigen Diagnostik-Daten.
     * Sollte aufgerufen werden wenn sich WebView-Settings ändern.
     */
    fun updateDiagnosticsData(context: Context, webView: WebView? = null) {
        val packageInfo = WebView.getCurrentWebViewPackage()
        val cookieManager = CookieManager.getInstance()

        // Cookie-Anzahl ermitteln (Approximation über bekannte Test-Domains)
        // HINWEIS: Dies ist nur eine grobe Schätzung, da die Android CookieManager API
        // keine Methode zum Zählen aller Cookies bereitstellt. Wir prüfen nur einige
        // bekannte Domains als Indikator. Die tatsächliche Anzahl kann höher sein.
        val cookieCount = try {
            // Zähle Cookies für bekannte Test-Domains
            val testDomains = listOf("google.com", "microsoft.com", "github.com")
            testDomains.sumOf { domain ->
                cookieManager.getCookie("https://$domain")?.split(";")?.size ?: 0
            }
        } catch (e: Exception) {
            0
        }

        val data = DiagnosticsData(
            webViewPackage = packageInfo?.packageName,
            webViewVersion = packageInfo?.versionName,
            userAgent = webView?.settings?.userAgentString,
            javaScriptEnabled = webView?.settings?.javaScriptEnabled ?: false,
            domStorageEnabled = webView?.settings?.domStorageEnabled ?: false,
            @Suppress("DEPRECATION")
            databaseEnabled = webView?.settings?.databaseEnabled ?: false,
            cookiesEnabled = cookieManager.acceptCookie(),
            thirdPartyCookiesEnabled = webView?.let { 
                cookieManager.acceptThirdPartyCookies(it) 
            } ?: false,
            multipleWindowsSupported = webView?.settings?.supportMultipleWindows() ?: false,
            consoleLogs = _consoleLogs.value,
            errorLogs = _errorLogs.value,
            cookieCount = cookieCount,
            lastUpdated = Clock.System.now()
        )

        _diagnosticsData.value = data
    }

    /**
     * Löscht alle gesammelten Logs.
     */
    fun clearLogs() {
        _consoleLogs.value = emptyList()
        _errorLogs.value = emptyList()
        updateLastUpdated()
    }

    /**
     * Gibt Debug-String mit allen wichtigen Infos zurück.
     */
    fun getDebugSummary(): String {
        val data = _diagnosticsData.value ?: return "No diagnostics data available"

        return buildString {
            appendLine("=== WebView Diagnostics ===")
            appendLine("Package: ${data.webViewPackage}")
            appendLine("Version: ${data.webViewVersion}")
            appendLine("JavaScript: ${data.javaScriptEnabled}")
            appendLine("DOM Storage: ${data.domStorageEnabled}")
            appendLine("Database: ${data.databaseEnabled}")
            appendLine("Cookies: ${data.cookiesEnabled}")
            appendLine("3rd Party Cookies: ${data.thirdPartyCookiesEnabled}")
            appendLine("Multiple Windows: ${data.multipleWindowsSupported}")
            appendLine("Cookie Count: ${data.cookieCount}")
            appendLine("\nConsole Logs: ${data.consoleLogs.size}")
            data.consoleLogs.takeLast(5).forEach {
                appendLine("  [${it.level}] ${it.message}")
            }
            appendLine("\nErrors: ${data.errorLogs.size}")
            data.errorLogs.takeLast(5).forEach {
                appendLine("  [${it.type}] ${it.description}")
            }
        }
    }

    private fun updateLastUpdated() {
        _diagnosticsData.value?.let { current ->
            _diagnosticsData.value = current.copy(
                consoleLogs = _consoleLogs.value,
                errorLogs = _errorLogs.value,
                lastUpdated = Clock.System.now()
            )
        }
    }
}
