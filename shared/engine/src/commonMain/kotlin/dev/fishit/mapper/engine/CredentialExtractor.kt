package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlinx.datetime.Instant

/**
 * Extracts authentication credentials from recording sessions.
 * 
 * Features:
 * - Detects login forms from UserActionEvents
 * - Extracts HTTP Authorization headers
 * - Identifies session cookies
 * - Supports privacy-aware storage (hashed passwords)
 */
object CredentialExtractor {
    
    // Configuration constants
    private const val TOKEN_TRUNCATE_LENGTH = 50
    private const val COOKIE_VALUE_TRUNCATE_LENGTH = 50
    
    /**
     * Extracts all credentials from a recording session.
     */
    fun extractCredentials(session: RecordingSession): List<StoredCredential> {
        val credentials = mutableListOf<StoredCredential>()
        
        // Extract from user actions (form submits)
        credentials.addAll(extractFromUserActions(session))
        
        // Extract from HTTP Authorization headers (captured by proxy in response events)
        credentials.addAll(extractFromHttpHeaders(session))
        
        // Extract from HTTP responses (Set-Cookie headers)
        credentials.addAll(extractFromHttpResponses(session))
        
        return credentials
    }
    
    /**
     * Extracts credentials from form submissions.
     */
    private fun extractFromUserActions(session: RecordingSession): List<StoredCredential> {
        val credentials = mutableListOf<StoredCredential>()
        
        session.events.filterIsInstance<UserActionEvent>().forEach { event ->
            // Check if this is a form submit
            val formInfo = FormAnalyzer.parseFormSubmit(event)
            if (formInfo != null) {
                val pattern = FormAnalyzer.detectFormPattern(formInfo.fields)
                
                if (pattern == FormAnalyzer.FormPattern.LOGIN || 
                    pattern == FormAnalyzer.FormPattern.REGISTRATION) {
                    
                    val usernameField = formInfo.fields.find { 
                        it.type == FormAnalyzer.FieldType.EMAIL || 
                        it.name?.lowercase()?.contains("username") == true ||
                        it.name?.lowercase()?.contains("user") == true
                    }
                    
                    val passwordField = formInfo.fields.find { 
                        it.type == FormAnalyzer.FieldType.PASSWORD 
                    }
                    
                    if (usernameField?.value != null || passwordField?.value != null) {
                        credentials.add(
                            StoredCredential(
                                id = "cred-${event.id.value}",
                                sessionId = session.id,
                                type = CredentialType.UsernamePassword,
                                url = event.target ?: "unknown",
                                username = usernameField?.value,
                                passwordHash = passwordField?.value?.let { hashPassword(it) },
                                token = null,
                                metadata = mapOf(
                                    "formPattern" to pattern.name,
                                    "formId" to (formInfo.formId ?: "unknown")
                                ),
                                capturedAt = event.at,
                                isEncrypted = false
                            )
                        )
                    }
                }
            }
        }
        
        return credentials
    }
    
    /**
     * Extracts credentials from HTTP Authorization headers captured by the proxy.
     * Note: The MITM proxy captures request headers in ResourceResponseEvent for correlation.
     */
    private fun extractFromHttpHeaders(session: RecordingSession): List<StoredCredential> {
        val credentials = mutableListOf<StoredCredential>()
        
        session.events.filterIsInstance<ResourceResponseEvent>().forEach { event ->
            // Check for Authorization header in the response headers (captured by proxy)
            val authHeader = event.headers["authorization"] 
                ?: event.headers["Authorization"]
                ?: event.headers["x-auth-token"]
                ?: event.headers["x-api-key"]
            
            if (authHeader != null && authHeader.isNotBlank()) {
                val type = when {
                    authHeader.startsWith("Bearer ", ignoreCase = true) -> CredentialType.Token
                    authHeader.startsWith("Basic ", ignoreCase = true) -> CredentialType.UsernamePassword
                    authHeader.startsWith("OAuth ", ignoreCase = true) -> CredentialType.OAuth
                    else -> CredentialType.Header
                }
                
                credentials.add(
                    StoredCredential(
                        id = "cred-${event.id.value}-auth",
                        sessionId = session.id,
                        type = type,
                        url = event.url,
                        username = null,
                        passwordHash = null,
                        token = authHeader.take(TOKEN_TRUNCATE_LENGTH),
                        metadata = mapOf(
                            "headerType" to type.name,
                            "statusCode" to event.statusCode.toString()
                        ),
                        capturedAt = event.at,
                        isEncrypted = false
                    )
                )
            }
        }
        
        return credentials
    }
    
    /**
     * Extracts session cookies from HTTP responses.
     */
    private fun extractFromHttpResponses(session: RecordingSession): List<StoredCredential> {
        val credentials = mutableListOf<StoredCredential>()
        
        session.events.filterIsInstance<ResourceResponseEvent>().forEach { event ->
            // Check for Set-Cookie header
            val setCookieHeader = event.headers["set-cookie"] 
                ?: event.headers["Set-Cookie"]
            
            if (setCookieHeader != null && setCookieHeader.isNotBlank()) {
                // Look for session-related cookies
                val lowerCookie = setCookieHeader.lowercase()
                if (lowerCookie.contains("session") || 
                    lowerCookie.contains("token") ||
                    lowerCookie.contains("auth") ||
                    lowerCookie.contains("jwt")) {
                    
                    // Extract cookie name and value (simplified parsing)
                    val cookieParts = setCookieHeader.split(";").firstOrNull()?.split("=", limit = 2)
                    val cookieName = cookieParts?.getOrNull(0)?.trim()
                    val cookieValue = cookieParts?.getOrNull(1)?.trim()
                    
                    if (cookieName != null && cookieValue != null) {
                        credentials.add(
                            StoredCredential(
                                id = "cred-${event.id.value}-cookie",
                                sessionId = session.id,
                                type = CredentialType.Cookie,
                                url = event.url,
                                username = null,
                                passwordHash = null,
                                token = cookieValue.take(COOKIE_VALUE_TRUNCATE_LENGTH),
                                metadata = mapOf(
                                    "cookieName" to cookieName,
                                    "statusCode" to event.statusCode.toString()
                                ),
                                capturedAt = event.at,
                                isEncrypted = false
                            )
                        )
                    }
                }
            }
        }
        
        return credentials
    }
    
    /**
     * Simple password hashing for privacy.
     * 
     * MVP-Implementierung: Bietet grundlegende Obfuskation für Entwicklungs- und Analyse-Zwecke.
     * Die Implementierung ist BEWUSST einfach gehalten, da:
     * - Passwörter nur zur Analyse von Login-Flows benötigt werden
     * - Die gehashten Werte nur lokal gespeichert und nicht übertragen werden
     * - Dies eine Entwickler-Tool ist, kein Production-System
     * 
     * WARNUNG: Für Production-Systeme würde bcrypt/PBKDF2/Argon2 benötigt werden.
     * Für den aktuellen Anwendungsfall (lokale API-Analyse) ist die Implementierung ausreichend.
     */
    private fun hashPassword(password: String): String {
        // Bewusst einfache Implementierung für MVP - ausreichend für lokale Entwickler-Analysen
        // Bietet Basis-Schutz gegen versehentliche Passwort-Exposition in Logs/Exports
        return "hash_len${password.length}_code${password.hashCode()}"
    }
    
    /**
     * Detects if a session contains login activity.
     */
    fun hasLoginActivity(session: RecordingSession): Boolean {
        return session.events.filterIsInstance<UserActionEvent>().any { event ->
            val formInfo = FormAnalyzer.parseFormSubmit(event)
            if (formInfo != null) {
                val pattern = FormAnalyzer.detectFormPattern(formInfo.fields)
                pattern == FormAnalyzer.FormPattern.LOGIN || 
                pattern == FormAnalyzer.FormPattern.REGISTRATION
            } else {
                false
            }
        }
    }
    
    /**
     * Groups credentials by domain for better organization.
     */
    fun groupByDomain(credentials: List<StoredCredential>): Map<String, List<StoredCredential>> {
        return credentials.groupBy { credential ->
            extractDomain(credential.url) ?: "unknown"
        }
    }
    
    private fun extractDomain(url: String): String? {
        return try {
            val cleanUrl = url.substringAfter("://")
            val domain = cleanUrl.substringBefore("/").substringBefore(":")
            domain.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
