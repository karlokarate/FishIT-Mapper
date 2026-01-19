package dev.fishit.mapper.android.webview

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * WebAuthn-Erkennung für Hybrid Authentication Flow.
 *
 * Erkennt ob eine Website WebAuthn/FIDO2-Funktionalität benötigt,
 * indem JavaScript-APIs zur Laufzeit geprüft werden.
 *
 * ## Erkennungs-Strategie
 * 1. **API-Verfügbarkeit prüfen**: window.PublicKeyCredential, navigator.credentials
 * 2. **Usage Detection**: Tatsächliche Aufrufe von WebAuthn-APIs überwachen
 * 3. **URL-Pattern Matching**: Bekannte WebAuthn-Provider erkennen
 *
 * ## Verwendung
 * ```kotlin
 * val detector = WebAuthnDetector()
 * detector.injectDetectionScript(webView)
 *
 * detector.webAuthnRequired.collect { required ->
 *     if (required) {
 *         // Zu Chrome Custom Tabs wechseln
 *     }
 * }
 * ```
 */
class WebAuthnDetector {

    /**
     * WebAuthn-Status: Verfügbar, benötigt, verwendet
     */
    data class WebAuthnStatus(
        val apiAvailable: Boolean = false,
        val apiUsed: Boolean = false,
        val credentialsApiAvailable: Boolean = false,
        val detectedAt: Instant? = null,
        val triggerUrl: String? = null,
        val triggerElement: String? = null
    )

    private val _webAuthnStatus = MutableStateFlow(WebAuthnStatus())
    val webAuthnStatus: StateFlow<WebAuthnStatus> = _webAuthnStatus.asStateFlow()

    /**
     * Gibt an ob WebAuthn aktuell benötigt wird.
     * True wenn API verfügbar ist UND verwendet wird.
     */
    private val _webAuthnRequired = MutableStateFlow(false)
    val webAuthnRequired: StateFlow<Boolean> = _webAuthnRequired.asStateFlow()

    /**
     * Bekannte WebAuthn/FIDO2 Provider URLs.
     */
    private val knownWebAuthnProviders = setOf(
        "webauthn.io",
        "webauthn.me",
        "passkeys.io",
        "passkey",
        "fido",
        "biometric",
        "authenticator",
        "security-key"
    )

    /**
     * Injiziert Detection-Script in WebView.
     * Muss nach Page Load aufgerufen werden.
     */
    fun injectDetectionScript(webView: android.webkit.WebView) {
        webView.evaluateJavascript(DETECTION_SCRIPT, null)
        Log.d(TAG, "WebAuthn detection script injected")
    }

    /**
     * Analysiert URL auf bekannte WebAuthn-Pattern.
     * Kann als Fallback verwendet werden wenn JS-Detection fehlschlägt.
     */
    fun analyzeUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return knownWebAuthnProviders.any { lowerUrl.contains(it) }
    }

    /**
     * Wird von JavaScript Bridge aufgerufen wenn WebAuthn API erkannt wurde.
     */
    fun reportWebAuthnApiAvailable(available: Boolean, credentialsAvailable: Boolean) {
        _webAuthnStatus.value = _webAuthnStatus.value.copy(
            apiAvailable = available,
            credentialsApiAvailable = credentialsAvailable,
            detectedAt = if (available) Clock.System.now() else null
        )
        Log.d(TAG, "WebAuthn API available: $available, credentials: $credentialsAvailable")
    }

    /**
     * Wird von JavaScript Bridge aufgerufen wenn WebAuthn API VERWENDET wird.
     * Dies ist der Trigger für Chrome Custom Tabs.
     */
    fun reportWebAuthnApiUsed(url: String?, element: String?) {
        _webAuthnStatus.value = _webAuthnStatus.value.copy(
            apiUsed = true,
            detectedAt = Clock.System.now(),
            triggerUrl = url,
            triggerElement = element
        )
        _webAuthnRequired.value = true
        Log.w(TAG, "⚠️ WebAuthn API USED - should switch to Custom Tabs! URL: $url, Element: $element")
    }

    /**
     * Setzt den Status zurück (z.B. nach Navigation).
     */
    fun reset() {
        _webAuthnStatus.value = WebAuthnStatus()
        _webAuthnRequired.value = false
        Log.d(TAG, "WebAuthn detector reset")
    }

    companion object {
        private const val TAG = "WebAuthnDetector"

        /**
         * JavaScript für WebAuthn-Detection.
         *
         * Strategie:
         * 1. API-Verfügbarkeit prüfen (window.PublicKeyCredential, navigator.credentials)
         * 2. API-Aufrufe abfangen (navigator.credentials.create/get)
         * 3. UI-Events monitoren (Button-Klicks auf bekannte Patterns)
         */
        const val DETECTION_SCRIPT = """
(function() {
    'use strict';
    
    // Verhindere doppelte Injection
    if (window.__fishit_webauthn_detector) return;
    window.__fishit_webauthn_detector = true;
    
    var bridge = window.FishIT;
    if (!bridge) {
        console.warn('[WebAuthn Detector] Bridge not found');
        return;
    }
    
    // ==================== API AVAILABILITY CHECK ====================
    var hasPublicKeyCredential = typeof window.PublicKeyCredential !== 'undefined';
    var hasCredentialsApi = typeof navigator.credentials !== 'undefined';
    
    try {
        bridge.reportWebAuthnApiAvailable(hasPublicKeyCredential, hasCredentialsApi);
        console.log('[WebAuthn] API available:', hasPublicKeyCredential, 'Credentials:', hasCredentialsApi);
    } catch(e) {
        console.error('[WebAuthn] Failed to report availability:', e);
    }
    
    // ==================== API USAGE DETECTION ====================
    if (navigator.credentials) {
        var originalCreate = navigator.credentials.create;
        var originalGet = navigator.credentials.get;
        
        // Intercept create() - für Registration
        if (originalCreate) {
            navigator.credentials.create = function(options) {
                console.warn('[WebAuthn] ⚠️ navigator.credentials.create() called!');
                try {
                    var element = document.activeElement;
                    var elementInfo = element ? element.tagName + (element.id ? '#' + element.id : '') : 'unknown';
                    bridge.reportWebAuthnApiUsed(window.location.href, 'create:' + elementInfo);
                } catch(e) {
                    console.error('[WebAuthn] Failed to report usage:', e);
                }
                return originalCreate.apply(this, arguments);
            };
        }
        
        // Intercept get() - für Authentication
        if (originalGet) {
            navigator.credentials.get = function(options) {
                console.warn('[WebAuthn] ⚠️ navigator.credentials.get() called!');
                try {
                    var element = document.activeElement;
                    var elementInfo = element ? element.tagName + (element.id ? '#' + element.id : '') : 'unknown';
                    bridge.reportWebAuthnApiUsed(window.location.href, 'get:' + elementInfo);
                } catch(e) {
                    console.error('[WebAuthn] Failed to report usage:', e);
                }
                return originalGet.apply(this, arguments);
            };
        }
    }
    
    // ==================== UI PATTERN DETECTION ====================
    // Erkenne Buttons/Links die auf WebAuthn hindeuten
    var webAuthnKeywords = [
        'passkey', 'fido', 'biometric', 'fingerprint', 'face id',
        'security key', 'authenticator', 'webauthn', 'passwordless'
    ];
    
    document.addEventListener('click', function(e) {
        var target = e.target;
        var text = target.textContent || target.value || target.title || target.alt || '';
        text = text.toLowerCase();
        
        // Prüfe ob Text WebAuthn-Keywords enthält
        var matchesKeyword = webAuthnKeywords.some(function(keyword) {
            return text.includes(keyword);
        });
        
        if (matchesKeyword && hasPublicKeyCredential) {
            console.warn('[WebAuthn] ⚠️ Potential WebAuthn button clicked:', text.substring(0, 50));
            try {
                var elementInfo = target.tagName + (target.id ? '#' + target.id : '');
                bridge.reportWebAuthnApiUsed(window.location.href, 'click:' + elementInfo);
            } catch(e) {
                console.error('[WebAuthn] Failed to report click:', e);
            }
        }
    }, true);
    
    console.log('[WebAuthn Detector] Initialized');
})();
"""
    }
}
