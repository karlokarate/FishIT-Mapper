package dev.fishit.mapper.android.webview

import dev.fishit.mapper.android.import.httpcanary.CapturedExchange
import dev.fishit.mapper.android.import.httpcanary.CapturedRequest
import dev.fishit.mapper.android.import.httpcanary.CapturedResponse
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit Tests für Hybrid Authentication Flow.
 * Testet WebAuthn Detection, Custom Tabs Integration, und Flow Orchestration.
 */
class HybridAuthFlowTest {

    // ==================== WebAuthn Detector Tests ====================

    @Test
    fun `WebAuthnDetector detects API availability`() {
        val detector = WebAuthnDetector()
        
        // Initial state
        assertFalse(detector.webAuthnStatus.value.apiAvailable)
        assertFalse(detector.webAuthnStatus.value.apiUsed)
        
        // Report API available
        detector.reportWebAuthnApiAvailable(available = true, credentialsAvailable = true)
        
        // Verify state
        assertTrue(detector.webAuthnStatus.value.apiAvailable)
        assertTrue(detector.webAuthnStatus.value.credentialsApiAvailable)
        assertFalse(detector.webAuthnStatus.value.apiUsed)
        assertFalse(detector.webAuthnRequired.value)
    }

    @Test
    fun `WebAuthnDetector triggers on API usage`() {
        val detector = WebAuthnDetector()
        
        // Report API available first
        detector.reportWebAuthnApiAvailable(available = true, credentialsAvailable = true)
        
        // Report API used
        detector.reportWebAuthnApiUsed(
            url = "https://example.com/login",
            element = "button#login"
        )
        
        // Verify state
        assertTrue(detector.webAuthnStatus.value.apiUsed)
        assertTrue(detector.webAuthnRequired.value)
        assertEquals("https://example.com/login", detector.webAuthnStatus.value.triggerUrl)
        assertEquals("button#login", detector.webAuthnStatus.value.triggerElement)
    }

    @Test
    fun `WebAuthnDetector recognizes WebAuthn URLs`() {
        val detector = WebAuthnDetector()
        
        assertTrue(detector.analyzeUrl("https://example.com/webauthn/login"))
        assertTrue(detector.analyzeUrl("https://example.com/fido2/register"))
        assertTrue(detector.analyzeUrl("https://example.com/passkey/authenticate"))
        assertFalse(detector.analyzeUrl("https://example.com/normal/login"))
    }

    @Test
    fun `WebAuthnDetector resets state`() {
        val detector = WebAuthnDetector()
        
        // Set state
        detector.reportWebAuthnApiAvailable(true, true)
        detector.reportWebAuthnApiUsed("https://example.com", "button")
        
        // Reset
        detector.reset()
        
        // Verify reset
        assertFalse(detector.webAuthnStatus.value.apiAvailable)
        assertFalse(detector.webAuthnStatus.value.apiUsed)
        assertFalse(detector.webAuthnRequired.value)
    }

    // ==================== Auth Flow Analyzer Tests ====================

    @Test
    fun `AuthFlowAnalyzer detects OAuth redirects`() {
        val analyzer = dev.fishit.mapper.android.import.httpcanary.AuthFlowAnalyzer()
        
        val exchanges = listOf(
            createTestExchange(
                url = "https://example.com/oauth/authorize",
                statusCode = 302,
                redirectLocation = "https://login.microsoftonline.com/auth"
            )
        )
        
        val analysis = analyzer.analyzeAuthFlow(exchanges)
        
        // Verify redirect chain
        assertEquals(1, analysis.redirectChain.size)
        assertTrue(analysis.redirectChain[0].isOAuth)
        assertEquals("https://example.com/oauth/authorize", analysis.redirectChain[0].fromUrl)
        assertEquals("https://login.microsoftonline.com/auth", analysis.redirectChain[0].toUrl)
    }

    @Test
    fun `AuthFlowAnalyzer detects WebAuthn indicators in URLs`() {
        val analyzer = dev.fishit.mapper.android.import.httpcanary.AuthFlowAnalyzer()
        
        val exchanges = listOf(
            createTestExchange(url = "https://example.com/webauthn/register"),
            createTestExchange(url = "https://example.com/fido2/authenticate")
        )
        
        val analysis = analyzer.analyzeAuthFlow(exchanges)
        
        // Verify WebAuthn indicators
        assertTrue(analysis.webAuthnIndicators.size >= 2)
    }

    @Test
    fun `AuthFlowAnalyzer detects WebAuthn indicators in JS bundles`() {
        val analyzer = dev.fishit.mapper.android.import.httpcanary.AuthFlowAnalyzer()
        
        val exchanges = listOf(
            createTestExchange(
                url = "https://example.com/bundle.js",
                responseBody = "function authenticate() { navigator.credentials.get({ publicKey: {...} }); }"
            )
        )
        
        val analysis = analyzer.analyzeAuthFlow(exchanges)
        
        // Verify WebAuthn indicator
        assertTrue(analysis.webAuthnIndicators.isNotEmpty())
    }

    // ==================== Helper Functions ====================

    private fun createTestExchange(
        url: String,
        statusCode: Int = 200,
        redirectLocation: String? = null,
        cookies: List<String> = emptyList(),
        responseBody: String? = null,
        timestamp: Instant = Clock.System.now()  // Issue #2: Use current time instead of hardcoded 2024
    ): CapturedExchange {
        val headers = mutableMapOf<String, String>()
        redirectLocation?.let { headers["Location"] = it }
        cookies.forEach { cookie ->
            headers["Set-Cookie"] = cookie
        }
        
        return CapturedExchange(
            exchangeId = "ex_${System.currentTimeMillis()}",
            startedAt = timestamp,
            request = CapturedRequest(
                method = "GET",
                url = url,
                headers = emptyMap(),
                body = null
            ),
            response = CapturedResponse(
                status = statusCode,
                headers = headers,
                body = responseBody,
                redirectLocation = redirectLocation
            )
        )
    }
}
