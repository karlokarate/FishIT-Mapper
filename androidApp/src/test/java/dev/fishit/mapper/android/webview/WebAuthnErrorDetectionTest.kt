package dev.fishit.mapper.android.webview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit Tests für WebAuthn Error Detection.
 * 
 * Testet die Pattern-Matching-Logik für WebAuthn-Fehler, die in Console Messages
 * auftreten können.
 */
class WebAuthnErrorDetectionTest {

    /**
     * Simuliert die detectWebAuthnError Logik aus TrafficInterceptWebView.
     * Diese Methode ist privat, daher testen wir die Logik separat.
     */
    private fun detectWebAuthnError(message: String): Boolean {
        val webAuthnErrorPatterns = listOf(
            "webauthn.*not supported",
            "publickeycredential.*not.*defined",
            "navigator\\.credentials.*undefined",
            "webauthn.*unavailable",
            "fido.*not supported"
        )
        
        val lowerMessage = message.lowercase()
        return webAuthnErrorPatterns.any { pattern ->
            lowerMessage.contains(Regex(pattern))
        }
    }

    @Test
    fun `detectWebAuthnError recognizes WebAuthn not supported error`() {
        val message = "Uncaught (in promise) Error: WebAuthn is not supported in this browser"
        assertTrue(detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes PublicKeyCredential undefined error`() {
        val message = "PublicKeyCredential is not defined"
        assertTrue(detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes navigator credentials undefined error`() {
        val message = "TypeError: navigator.credentials is undefined"
        assertTrue(detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes WebAuthn unavailable error`() {
        val message = "WebAuthn is unavailable in this environment"
        assertTrue(detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes FIDO not supported error`() {
        val message = "FIDO2 is not supported"
        assertTrue(detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError is case insensitive`() {
        val message = "WEBAUTHN IS NOT SUPPORTED"
        assertTrue(detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError ignores non-WebAuthn errors`() {
        val messages = listOf(
            "Uncaught TypeError: Cannot read property 'name' of undefined",
            "Failed to fetch",
            "Network error occurred",
            "Authentication failed",
            "Invalid credentials"
        )
        
        messages.forEach { message ->
            assertFalse(detectWebAuthnError(message), "Should not detect as WebAuthn error: $message")
        }
    }

    @Test
    fun `detectWebAuthnError handles empty string`() {
        assertFalse(detectWebAuthnError(""))
    }

    @Test
    fun `detectWebAuthnError handles real-world error from kitaplus`() {
        // This is the actual error from the issue description
        val message = "Uncaught (in promise) Error: WebAuthn is not supported in this browser"
        assertTrue(detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError handles variations with extra context`() {
        val messages = listOf(
            "Error in authentication: WebAuthn is not supported",
            "Failed: navigator.credentials is undefined in WebView",
            "Cannot authenticate: PublicKeyCredential is not defined on window"
        )
        
        messages.forEach { message ->
            assertTrue(detectWebAuthnError(message), "Should detect as WebAuthn error: $message")
        }
    }
}
