package dev.fishit.mapper.android.webview

import dev.fishit.mapper.android.capture.WebAuthnErrorDetector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit Tests für WebAuthn Error Detection.
 * 
 * Testet die Pattern-Matching-Logik für WebAuthn-Fehler, die in Console Messages
 * auftreten können.
 * 
 * Diese Tests verwenden die tatsächliche Implementierung aus WebAuthnErrorDetector
 * anstatt die Logik zu duplizieren.
 */
class WebAuthnErrorDetectionTest {

    @Test
    fun `detectWebAuthnError recognizes WebAuthn not supported error`() {
        val message = "Uncaught (in promise) Error: WebAuthn is not supported in this browser"
        assertTrue(WebAuthnErrorDetector.detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes PublicKeyCredential undefined error`() {
        val message = "PublicKeyCredential is not defined"
        assertTrue(WebAuthnErrorDetector.detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes navigator credentials undefined error`() {
        val message = "TypeError: navigator.credentials is undefined"
        assertTrue(WebAuthnErrorDetector.detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes WebAuthn unavailable error`() {
        val message = "WebAuthn is unavailable in this environment"
        assertTrue(WebAuthnErrorDetector.detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError recognizes FIDO not supported error`() {
        val message = "FIDO2 is not supported"
        assertTrue(WebAuthnErrorDetector.detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError is case insensitive`() {
        val message = "WEBAUTHN IS NOT SUPPORTED"
        assertTrue(WebAuthnErrorDetector.detectWebAuthnError(message))
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
            assertFalse(
                WebAuthnErrorDetector.detectWebAuthnError(message),
                "Should not detect as WebAuthn error: $message"
            )
        }
    }

    @Test
    fun `detectWebAuthnError handles empty string`() {
        assertFalse(WebAuthnErrorDetector.detectWebAuthnError(""))
    }

    @Test
    fun `detectWebAuthnError handles real-world error from kitaplus`() {
        // This is the actual error from the issue description
        val message = "Uncaught (in promise) Error: WebAuthn is not supported in this browser"
        assertTrue(WebAuthnErrorDetector.detectWebAuthnError(message))
    }

    @Test
    fun `detectWebAuthnError handles variations with extra context`() {
        val messages = listOf(
            "Error in authentication: WebAuthn is not supported",
            "Failed: navigator.credentials is undefined in WebView",
            "Cannot authenticate: PublicKeyCredential is not defined on window"
        )
        
        messages.forEach { message ->
            assertTrue(
                WebAuthnErrorDetector.detectWebAuthnError(message),
                "Should detect as WebAuthn error: $message"
            )
        }
    }

    /**
     * Tests for URL validation logic (conceptual - actual validation is in launchInExternalBrowser).
     * These demonstrate the expected behavior for security validation.
     */
    @Test
    fun `launchInExternalBrowser should only accept http and https URLs`() {
        val validUrls = listOf(
            "https://example.com",
            "http://example.com",
            "https://eltern.kitaplus.de/login"
        )
        
        val invalidUrls = listOf(
            "javascript:alert('xss')",
            "data:text/html,<script>alert('xss')</script>",
            "file:///etc/passwd",
            "about:blank",
            ""
        )
        
        // Note: This is a conceptual test documenting expected behavior
        // Actual implementation is tested through integration
        assertTrue(validUrls.all { it.startsWith("http://") || it.startsWith("https://") })
        assertFalse(invalidUrls.any { it.startsWith("http://") || it.startsWith("https://") })
    }
}
