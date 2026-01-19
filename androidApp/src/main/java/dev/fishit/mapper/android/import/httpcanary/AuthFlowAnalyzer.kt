package dev.fishit.mapper.android.import.httpcanary

import android.util.Log
import dev.fishit.mapper.android.webview.HybridAuthFlowManager
// Explicit import to avoid confusion with TrafficInterceptWebView.CapturedExchange
import dev.fishit.mapper.android.import.httpcanary.CapturedExchange as HttpCanaryCapturedExchange

/**
 * Auth Flow Analyzer für HTTP Canary Daten.
 *
 * Analysiert importierte HTTP Canary Requests auf:
 * - OAuth/OIDC Redirects
 * - WebAuthn-relevante JS Bundles
 * - Session-Cookie Übergänge
 * - Auth-Flow Decision Points
 *
 * Kann mit HybridAuthFlowManager Decision Points korreliert werden
 * für vollständige Flow-Visualisierung.
 *
 * ## Verwendung
 * ```kotlin
 * val analyzer = AuthFlowAnalyzer()
 * val analysis = analyzer.analyzeAuthFlow(exchanges, decisionPoints)
 * ```
 */
class AuthFlowAnalyzer {

    /**
     * Auth Flow Analyse Ergebnis.
     */
    data class AuthFlowAnalysis(
        val redirectChain: List<RedirectStep>,
        val webAuthnIndicators: List<WebAuthnIndicator>,
        val sessionTransitions: List<SessionTransition>,
        val decisionPointCorrelations: List<DecisionPointCorrelation>
    )

    /**
     * Redirect-Schritt im Auth-Flow.
     */
    data class RedirectStep(
        val fromUrl: String,
        val toUrl: String,
        val statusCode: Int,
        val timestamp: Long,
        val cookies: List<String>,
        val isOAuth: Boolean,
        val isWebAuthn: Boolean
    )

    /**
     * WebAuthn Indikator - Hinweis dass WebAuthn benötigt wird.
     */
    data class WebAuthnIndicator(
        val url: String,
        val type: WebAuthnIndicatorType,
        val evidence: String,
        val timestamp: Long
    )

    enum class WebAuthnIndicatorType {
        JS_BUNDLE,           // JavaScript Bundle enthält WebAuthn Code
        RESPONSE_HEADER,     // Response Header deutet auf WebAuthn hin
        REQUEST_PATTERN,     // Request URL Pattern (z.B. /fido2/, /webauthn/)
        COOKIE_NAME          // Cookie Name deutet auf WebAuthn hin (z.B. fido_session)
    }

    /**
     * Session-Übergang (Cookie-Änderung).
     */
    data class SessionTransition(
        val url: String,
        val timestamp: Long,
        val addedCookies: List<String>,
        val removedCookies: List<String>,
        val modifiedCookies: List<String>
    )

    /**
     * Korrelation zwischen Decision Point und HTTP Requests.
     */
    data class DecisionPointCorrelation(
        val decisionPoint: HybridAuthFlowManager.DecisionPoint,
        val correlatedRequests: List<HttpCanaryCapturedExchange>,
        val timeWindowMs: Long
    )

    /**
     * Analysiert Auth-Flow aus HTTP Canary Daten.
     *
     * @param exchanges HTTP Canary Exchanges
     * @param decisionPoints Decision Points vom HybridAuthFlowManager (optional)
     * @return Auth Flow Analyse
     */
    fun analyzeAuthFlow(
        exchanges: List<HttpCanaryCapturedExchange>,
        decisionPoints: List<HybridAuthFlowManager.DecisionPoint> = emptyList()
    ): AuthFlowAnalysis {
        Log.d(TAG, "Analyzing auth flow from ${exchanges.size} exchanges")

        // 1. Redirect-Chain analysieren
        val redirectChain = analyzeRedirectChain(exchanges)
        Log.d(TAG, "Found ${redirectChain.size} redirect steps")

        // 2. WebAuthn Indikatoren finden
        val webAuthnIndicators = findWebAuthnIndicators(exchanges)
        Log.d(TAG, "Found ${webAuthnIndicators.size} WebAuthn indicators")

        // 3. Session-Übergänge analysieren
        val sessionTransitions = analyzeSessionTransitions(exchanges)
        Log.d(TAG, "Found ${sessionTransitions.size} session transitions")

        // 4. Decision Points korrelieren
        val correlations = correlateDecisionPoints(exchanges, decisionPoints)
        Log.d(TAG, "Correlated ${correlations.size} decision points")

        return AuthFlowAnalysis(
            redirectChain = redirectChain,
            webAuthnIndicators = webAuthnIndicators,
            sessionTransitions = sessionTransitions,
            decisionPointCorrelations = correlations
        )
    }

    /**
     * Analysiert Redirect-Chain.
     * Findet 301/302/303/307/308 Redirects und baut Chain auf.
     */
    private fun analyzeRedirectChain(exchanges: List<HttpCanaryCapturedExchange>): List<RedirectStep> {
        val redirects = mutableListOf<RedirectStep>()
        
        exchanges.sortedBy { it.startedAt.toEpochMilliseconds() }.forEach { exchange ->
            val response = exchange.response ?: return@forEach
            val statusCode = response.status
            
            // Prüfe ob Redirect
            if (statusCode in 300..399) {
                val locationHeader = response.redirectLocation
                    ?: response.headers.entries.firstOrNull { 
                        it.key.equals("Location", ignoreCase = true) 
                    }?.value
                
                if (locationHeader != null) {
                    val cookies = response.headers.entries
                        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                        .map { it.value }
                    
                    val requestUrl = exchange.request.url
                    val isOAuth = isOAuthUrl(requestUrl) || isOAuthUrl(locationHeader)
                    val isWebAuthn = isWebAuthnUrl(requestUrl) || isWebAuthnUrl(locationHeader)
                    
                    redirects.add(
                        RedirectStep(
                            fromUrl = requestUrl,
                            toUrl = locationHeader,
                            statusCode = statusCode,
                            timestamp = exchange.startedAt.toEpochMilliseconds(),
                            cookies = cookies,
                            isOAuth = isOAuth,
                            isWebAuthn = isWebAuthn
                        )
                    )
                }
            }
        }
        
        return redirects
    }

    /**
     * Findet WebAuthn Indikatoren in Requests und Responses.
     */
    private fun findWebAuthnIndicators(exchanges: List<HttpCanaryCapturedExchange>): List<WebAuthnIndicator> {
        val indicators = mutableListOf<WebAuthnIndicator>()
        
        exchanges.forEach { exchange ->
            val requestUrl = exchange.request.url
            
            // Prüfe URL Pattern
            if (isWebAuthnUrl(requestUrl)) {
                indicators.add(
                    WebAuthnIndicator(
                        url = requestUrl,
                        type = WebAuthnIndicatorType.REQUEST_PATTERN,
                        evidence = "URL contains WebAuthn pattern",
                        timestamp = exchange.startedAt.toEpochMilliseconds()
                    )
                )
            }
            
            // Prüfe Response Body auf WebAuthn Keywords (z.B. in JS Bundles)
            exchange.response?.body?.let { body ->
                if (containsWebAuthnCode(body)) {
                    indicators.add(
                        WebAuthnIndicator(
                            url = requestUrl,
                            type = WebAuthnIndicatorType.JS_BUNDLE,
                            evidence = "Response body contains WebAuthn/FIDO2 code",
                            timestamp = exchange.startedAt.toEpochMilliseconds()
                        )
                    )
                }
            }
            
            // Prüfe Cookies
            exchange.response?.headers?.entries
                ?.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.forEach { header ->
                    if (isWebAuthnCookie(header.value)) {
                        indicators.add(
                            WebAuthnIndicator(
                                url = requestUrl,
                                type = WebAuthnIndicatorType.COOKIE_NAME,
                                evidence = "Cookie name suggests WebAuthn: ${header.value.take(50)}",
                                timestamp = exchange.startedAt.toEpochMilliseconds()
                            )
                        )
                    }
                }
        }
        
        return indicators
    }

    /**
     * Analysiert Session-Übergänge anhand von Cookie-Änderungen.
     */
    private fun analyzeSessionTransitions(exchanges: List<HttpCanaryCapturedExchange>): List<SessionTransition> {
        val transitions = mutableListOf<SessionTransition>()
        var previousCookies = setOf<String>()
        
        exchanges.sortedBy { it.startedAt.toEpochMilliseconds() }.forEach { exchange ->
            val response = exchange.response ?: return@forEach
            
            // Cookies aus Set-Cookie Headers extrahieren
            val currentCookies = response.headers.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .map { extractCookieName(it.value) }
                .toSet()
            
            if (currentCookies.isNotEmpty()) {
                val added = currentCookies - previousCookies
                val removed = previousCookies - currentCookies
                val modified = currentCookies.intersect(previousCookies)
                
                if (added.isNotEmpty() || removed.isNotEmpty()) {
                    transitions.add(
                        SessionTransition(
                            url = exchange.request.url,
                            timestamp = exchange.startedAt.toEpochMilliseconds(),
                            addedCookies = added.toList(),
                            removedCookies = removed.toList(),
                            modifiedCookies = modified.toList()
                        )
                    )
                }
                
                previousCookies = currentCookies
            }
        }
        
        return transitions
    }

    /**
     * Korreliert Decision Points mit HTTP Requests.
     * Findet alle Requests die in zeitlicher Nähe zu einem Decision Point stattfanden.
     */
    private fun correlateDecisionPoints(
        exchanges: List<HttpCanaryCapturedExchange>,
        decisionPoints: List<HybridAuthFlowManager.DecisionPoint>
    ): List<DecisionPointCorrelation> {
        val correlations = mutableListOf<DecisionPointCorrelation>()
        
        decisionPoints.forEach { dp ->
            val dpTime = dp.timestamp.toEpochMilliseconds()
            
            // Finde Requests im Zeitfenster (±5 Sekunden)
            val correlatedRequests = exchanges.filter { exchange ->
                val exchangeTime = exchange.startedAt.toEpochMilliseconds()
                val timeDiff = kotlin.math.abs(exchangeTime - dpTime)
                timeDiff <= 5000 // 5 Sekunden Fenster
            }
            
            if (correlatedRequests.isNotEmpty()) {
                correlations.add(
                    DecisionPointCorrelation(
                        decisionPoint = dp,
                        correlatedRequests = correlatedRequests,
                        timeWindowMs = 5000
                    )
                )
            }
        }
        
        return correlations
    }

    // Helper-Funktionen

    private fun isOAuthUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("oauth") ||
               lower.contains("authorize") ||
               lower.contains("token") ||
               lower.contains("login.microsoftonline") ||
               lower.contains("accounts.google")
    }

    private fun isWebAuthnUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("webauthn") ||
               lower.contains("fido") ||
               lower.contains("passkey") ||
               lower.contains("/credential") ||
               lower.contains("/authenticator")
    }

    private fun containsWebAuthnCode(jsCode: String): Boolean {
        val keywords = listOf(
            "PublicKeyCredential",
            "navigator.credentials",
            "webauthn",
            "fido2",
            "attestation",
            "assertion"
        )
        return keywords.any { jsCode.contains(it, ignoreCase = true) }
    }

    private fun isWebAuthnCookie(cookieString: String): Boolean {
        val lower = cookieString.lowercase()
        return lower.contains("fido") ||
               lower.contains("webauthn") ||
               lower.contains("passkey") ||
               lower.contains("authenticator")
    }

    private fun extractCookieName(cookieString: String): String {
        return cookieString.split("=")[0].trim()
    }

    companion object {
        private const val TAG = "AuthFlowAnalyzer"
    }
}
