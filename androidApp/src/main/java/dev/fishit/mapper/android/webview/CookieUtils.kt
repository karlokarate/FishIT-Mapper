package dev.fishit.mapper.android.webview

/**
 * Utility-Funktionen für Cookie-Parsing und -Manipulation.
 * 
 * Zentrale Cookie-Verarbeitung, um Duplikation zu vermeiden und
 * konsistente Behandlung von Cookie-Attributen sicherzustellen.
 */
object CookieUtils {
    
    /**
     * Bekannte Cookie-Attribute, die nicht als eigene Cookies interpretiert werden sollen.
     */
    private val COOKIE_ATTRIBUTES = setOf(
        "path",
        "domain",
        "expires",
        "max-age",
        "secure",
        "httponly",
        "samesite"
    )
    
    /**
     * Parst einen Cookie-String zu einer Map von Cookie-Namen zu -Werten.
     * 
     * Filtert Cookie-Attribute (Path, Domain, Secure, etc.) heraus und
     * gibt nur tatsächliche Cookie-Namen/-Werte zurück.
     * 
     * @param cookieString Cookie-String aus CookieManager.getCookie()
     * @return Map von Cookie-Namen zu Werten (ohne Attribute)
     */
    fun parseCookieString(cookieString: String?): Map<String, String> {
        if (cookieString.isNullOrBlank()) return emptyMap()

        return cookieString.split(";")
            .mapNotNull { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) return@mapNotNull null

                val parts = trimmed.split("=", limit = 2)
                
                // Attribute ohne "=" (z.B. "Secure", "HttpOnly") ignorieren
                if (parts.size != 2) {
                    return@mapNotNull null
                }

                val name = parts[0].trim()
                val value = parts[1].trim()
                
                if (name.isEmpty()) {
                    return@mapNotNull null
                }

                // Cookie-Attribute (Path, Domain, etc.) ignorieren
                if (COOKIE_ATTRIBUTES.contains(name.lowercase())) {
                    return@mapNotNull null
                }

                name to value
            }
            .toMap()
    }
    
    /**
     * Erstellt einen minimalen Cookie-String ohne Attribute.
     * CookieManager verwaltet Domain/Path automatisch basierend auf URL.
     * 
     * @param name Cookie-Name
     * @param value Cookie-Wert
     * @return Cookie-String im Format "name=value"
     */
    fun createSimpleCookieString(name: String, value: String): String {
        return "$name=$value"
    }
}
