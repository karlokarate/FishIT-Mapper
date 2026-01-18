package dev.fishit.mapper.engine.api

import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Umfassende Flow-Tests für die Engine-Typen.
 *
 * Testet alle kritischen Pfade:
 * 1. EngineExchange Erstellung und Eigenschaften
 * 2. EngineRequest URL-Parsing
 * 3. EngineResponse Status-Klassifizierung
 * 4. EngineCorrelatedAction mit Exchange-Referenzen
 * 5. EngineWebsiteMap Aggregation
 * 6. Type Aliases Kompatibilität
 */
class EngineTypesFlowTest {

    // ========================================================================
    // EngineExchange Tests
    // ========================================================================

    @Test
    fun testEngineExchangeCreation() {
        val now = Clock.System.now()

        val exchange = EngineExchange(
            exchangeId = "ex-001",
            startedAt = now,
            completedAt = now,
            request = EngineRequest(
                method = "POST",
                url = "https://api.example.com/login",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                body = """{"username":"test","password":"secret"}"""
            ),
            response = EngineResponse(
                status = 200,
                statusMessage = "OK",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Set-Cookie" to "session=abc123"
                ),
                body = """{"token":"jwt-token-here"}"""
            )
        )

        assertEquals("ex-001", exchange.exchangeId)
        assertEquals("POST", exchange.request.method)
        assertEquals(200, exchange.response?.status)
        assertEquals("application/json", exchange.requestContentType)
        assertEquals("application/json", exchange.responseContentType)
        assertNotNull(exchange.requestBody)
        assertNotNull(exchange.responseBody)
    }

    @Test
    fun testEngineExchangeWithoutResponse() {
        val now = Clock.System.now()

        val exchange = EngineExchange(
            exchangeId = "ex-timeout",
            startedAt = now,
            request = EngineRequest(
                method = "GET",
                url = "https://slow-server.example.com/timeout"
            ),
            response = null // Timeout - keine Response
        )

        assertNull(exchange.response)
        assertNull(exchange.completedAt)
        assertNull(exchange.responseContentType)
        assertNull(exchange.responseBody)
    }

    @Test
    fun testEngineExchangeContentTypeHelpers() {
        val now = Clock.System.now()

        // Test mit Content-Type in contentType-Feld
        val exchange1 = EngineExchange(
            exchangeId = "ex-1",
            startedAt = now,
            request = EngineRequest(
                method = "GET",
                url = "https://example.com",
                contentType = "text/html"
            ),
            response = EngineResponse(
                status = 200,
                contentType = "text/html"
            )
        )
        assertEquals("text/html", exchange1.requestContentType)
        assertEquals("text/html", exchange1.responseContentType)

        // Test mit Content-Type nur in Headers
        val exchange2 = EngineExchange(
            exchangeId = "ex-2",
            startedAt = now,
            request = EngineRequest(
                method = "POST",
                url = "https://example.com",
                headers = mapOf("content-type" to "application/xml")
            ),
            response = EngineResponse(
                status = 200,
                headers = mapOf("Content-Type" to "application/xml")
            )
        )
        assertEquals("application/xml", exchange2.requestContentType)
        assertEquals("application/xml", exchange2.responseContentType)
    }

    // ========================================================================
    // EngineRequest URL Parsing Tests
    // ========================================================================

    @Test
    fun testEngineRequestHostExtraction() {
        val request1 = EngineRequest(
            method = "GET",
            url = "https://api.example.com/v1/users"
        )
        assertEquals("api.example.com", request1.host)

        val request2 = EngineRequest(
            method = "GET",
            url = "http://localhost:8080/api"
        )
        assertEquals("localhost", request2.host)

        val request3 = EngineRequest(
            method = "GET",
            url = "https://example.com"
        )
        assertEquals("example.com", request3.host)
    }

    @Test
    fun testEngineRequestPathExtraction() {
        val request1 = EngineRequest(
            method = "GET",
            url = "https://api.example.com/v1/users/123"
        )
        assertEquals("/v1/users/123", request1.path)

        val request2 = EngineRequest(
            method = "GET",
            url = "https://api.example.com/search?q=test&limit=10"
        )
        assertEquals("/search", request2.path)

        val request3 = EngineRequest(
            method = "GET",
            url = "https://example.com"
        )
        assertEquals("/", request3.path)
    }

    // ========================================================================
    // EngineResponse Status Tests
    // ========================================================================

    @Test
    fun testEngineResponseStatusClassification() {
        // Redirect
        val redirect = EngineResponse(status = 302, redirectLocation = "/new-location")
        assertTrue(redirect.isRedirect())
        assertFalse(redirect.isSuccess())

        // Success
        val success = EngineResponse(status = 200)
        assertTrue(success.isSuccess())
        assertFalse(success.isRedirect())

        val created = EngineResponse(status = 201)
        assertTrue(created.isSuccess())

        val noContent = EngineResponse(status = 204)
        assertTrue(noContent.isSuccess())

        // Client Error
        val notFound = EngineResponse(status = 404)
        assertTrue(notFound.isClientError())
        assertFalse(notFound.isSuccess())

        val unauthorized = EngineResponse(status = 401)
        assertTrue(unauthorized.isClientError())

        // Server Error
        val serverError = EngineResponse(status = 500)
        assertTrue(serverError.isServerError())
        assertFalse(serverError.isClientError())

        val serviceUnavailable = EngineResponse(status = 503)
        assertTrue(serviceUnavailable.isServerError())
    }

    // ========================================================================
    // EngineCorrelatedAction Tests
    // ========================================================================

    @Test
    fun testEngineCorrelatedActionWithExchanges() {
        val now = Clock.System.now()

        val action = EngineCorrelatedAction(
            actionId = "action-001",
            timestamp = now,
            actionType = "click",
            payload = mapOf(
                "target" to "button#submit",
                "pageUrl" to "https://example.com/form"
            ),
            exchanges = listOf(
                EngineExchangeReference(
                    exchangeId = "ex-001",
                    url = "https://api.example.com/submit",
                    method = "POST",
                    status = 200
                ),
                EngineExchangeReference(
                    exchangeId = "ex-002",
                    url = "https://api.example.com/redirect",
                    method = "GET",
                    status = 302,
                    isRedirect = true
                )
            )
        )

        assertEquals("action-001", action.actionId)
        assertEquals("click", action.actionType)
        assertEquals(2, action.exchanges.size)
        assertEquals(listOf("ex-001", "ex-002"), action.exchangeIds)

        // Prüfe Redirect-Erkennung
        val redirectExchange = action.exchanges.find { it.isRedirect }
        assertNotNull(redirectExchange)
        assertEquals(302, redirectExchange.status)
    }

    @Test
    fun testEngineCorrelatedActionWithNavigation() {
        val now = Clock.System.now()

        val action = EngineCorrelatedAction(
            actionId = "nav-001",
            timestamp = now,
            actionType = "navigation",
            navigationOutcome = EngineNavigationOutcome(
                fromUrl = "https://example.com/home",
                toUrl = "https://example.com/dashboard",
                timestamp = now
            ),
            exchanges = listOf(
                EngineExchangeReference(
                    exchangeId = "ex-page",
                    url = "https://example.com/dashboard",
                    method = "GET",
                    status = 200
                )
            )
        )

        assertNotNull(action.navigationOutcome)
        assertEquals("https://example.com/home", action.navigationOutcome?.fromUrl)
        assertEquals("https://example.com/dashboard", action.navigationOutcome?.toUrl)
    }

    // ========================================================================
    // EngineWebsiteMap Tests
    // ========================================================================

    @Test
    fun testEngineWebsiteMapAggregation() {
        val now = Clock.System.now()

        val exchanges = listOf(
            createTestExchange("ex-1", "https://example.com", 200),
            createTestExchange("ex-2", "https://api.example.com/data", 200),
            createTestExchange("ex-3", "https://cdn.example.com/style.css", 200)
        )

        val actions = listOf(
            EngineCorrelatedAction(
                actionId = "action-1",
                timestamp = now,
                actionType = "click",
                exchanges = listOf(
                    EngineExchangeReference("ex-2", "https://api.example.com/data", "GET", 200)
                )
            )
        )

        val websiteMap = EngineWebsiteMap(
            sessionId = "session-001",
            generatedAt = now,
            exchanges = exchanges,
            actions = actions,
            totalExchanges = 3,
            correlatedExchanges = 1,
            uncorrelatedExchanges = 2
        )

        assertEquals("session-001", websiteMap.sessionId)
        assertEquals(3, websiteMap.exchanges.size)
        assertEquals(1, websiteMap.actions.size)
        assertEquals(3, websiteMap.totalExchanges)
        assertEquals(1, websiteMap.correlatedExchanges)
        assertEquals(2, websiteMap.uncorrelatedExchanges)
    }

    // ========================================================================
    // Type Alias Compatibility Tests
    // ========================================================================

    @Test
    fun testTypeAliasCompatibility() {
        val now = Clock.System.now()

        // CapturedExchange sollte gleich EngineExchange sein
        val capturedExchange: CapturedExchange = EngineExchange(
            exchangeId = "cap-001",
            startedAt = now,
            request = EngineRequest(method = "GET", url = "https://example.com")
        )

        // CapturedRequest sollte gleich EngineRequest sein
        val capturedRequest: CapturedRequest = EngineRequest(
            method = "POST",
            url = "https://api.example.com"
        )

        // CapturedResponse sollte gleich EngineResponse sein
        val capturedResponse: CapturedResponse = EngineResponse(
            status = 200
        )

        // CorrelatedAction sollte gleich EngineCorrelatedAction sein
        val correlatedAction: CorrelatedAction = EngineCorrelatedAction(
            actionId = "act-001",
            timestamp = now,
            actionType = "submit"
        )

        // WebsiteMap sollte gleich EngineWebsiteMap sein
        val websiteMap: WebsiteMap = EngineWebsiteMap(
            sessionId = "sess-001",
            generatedAt = now,
            exchanges = listOf(capturedExchange),
            actions = listOf(correlatedAction),
            totalExchanges = 1,
            correlatedExchanges = 1,
            uncorrelatedExchanges = 0
        )

        // ExchangeReference sollte gleich EngineExchangeReference sein
        val exchangeRef: ExchangeReference = EngineExchangeReference(
            exchangeId = "ref-001",
            url = "https://example.com",
            method = "GET"
        )

        // Alle Assertions
        assertEquals("cap-001", capturedExchange.exchangeId)
        assertEquals("POST", capturedRequest.method)
        assertEquals(200, capturedResponse.status)
        assertEquals("submit", correlatedAction.actionType)
        assertEquals(1, websiteMap.totalExchanges)
        assertEquals("ref-001", exchangeRef.exchangeId)
    }

    // ========================================================================
    // Integration Flow Test
    // ========================================================================

    @Test
    fun testCompleteAnalysisFlow() {
        val now = Clock.System.now()

        // Simuliere einen typischen Capture-Flow:
        // 1. User navigiert zur Login-Seite
        // 2. User gibt Credentials ein und klickt Submit
        // 3. Server antwortet mit Redirect zum Dashboard
        // 4. Dashboard wird geladen

        val loginPageExchange = createTestExchange(
            "ex-login-page",
            "https://app.example.com/login",
            200,
            contentType = "text/html"
        )

        val loginApiExchange = EngineExchange(
            exchangeId = "ex-login-api",
            startedAt = now,
            completedAt = now,
            request = EngineRequest(
                method = "POST",
                url = "https://api.example.com/auth/login",
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"email":"user@test.com","password":"secret"}"""
            ),
            response = EngineResponse(
                status = 302,
                headers = mapOf(
                    "Location" to "/dashboard",
                    "Set-Cookie" to "session=xyz123"
                ),
                redirectLocation = "/dashboard"
            )
        )

        val dashboardExchange = createTestExchange(
            "ex-dashboard",
            "https://app.example.com/dashboard",
            200,
            contentType = "text/html"
        )

        val dashboardApiExchange = createTestExchange(
            "ex-dashboard-api",
            "https://api.example.com/user/profile",
            200,
            contentType = "application/json"
        )

        // Actions
        val loginAction = EngineCorrelatedAction(
            actionId = "action-login",
            timestamp = now,
            actionType = "submit",
            payload = mapOf(
                "target" to "form#login",
                "pageUrl" to "https://app.example.com/login"
            ),
            navigationOutcome = EngineNavigationOutcome(
                fromUrl = "https://app.example.com/login",
                toUrl = "https://app.example.com/dashboard",
                timestamp = now,
                isRedirect = true
            ),
            exchanges = listOf(
                EngineExchangeReference(
                    exchangeId = "ex-login-api",
                    url = "https://api.example.com/auth/login",
                    method = "POST",
                    status = 302,
                    isRedirect = true
                ),
                EngineExchangeReference(
                    exchangeId = "ex-dashboard",
                    url = "https://app.example.com/dashboard",
                    method = "GET",
                    status = 200
                )
            )
        )

        // WebsiteMap erstellen
        val allExchanges = listOf(
            loginPageExchange,
            loginApiExchange,
            dashboardExchange,
            dashboardApiExchange
        )

        val websiteMap = EngineWebsiteMap(
            sessionId = "flow-test-session",
            generatedAt = now,
            exchanges = allExchanges,
            actions = listOf(loginAction),
            totalExchanges = 4,
            correlatedExchanges = 2, // login-api + dashboard
            uncorrelatedExchanges = 2 // login-page + dashboard-api
        )

        // Assertions für den kompletten Flow
        assertEquals(4, websiteMap.exchanges.size)
        assertEquals(1, websiteMap.actions.size)

        // Login API sollte Redirect sein
        val loginExchange = websiteMap.exchanges.find { it.exchangeId == "ex-login-api" }
        assertNotNull(loginExchange)
        assertTrue(loginExchange.response?.isRedirect() == true)
        assertEquals("/dashboard", loginExchange.response?.redirectLocation)

        // Login Action sollte 2 korrelierte Exchanges haben
        val action = websiteMap.actions.first()
        assertEquals(2, action.exchanges.size)
        assertTrue(action.exchanges.any { it.isRedirect })

        // Navigation Outcome prüfen
        assertNotNull(action.navigationOutcome)
        assertTrue(action.navigationOutcome?.isRedirect == true)
    }

    // ========================================================================
    // Helper Functions
    // ========================================================================

    private fun createTestExchange(
        id: String,
        url: String,
        status: Int,
        method: String = "GET",
        contentType: String? = null
    ): EngineExchange {
        val now = Clock.System.now()
        return EngineExchange(
            exchangeId = id,
            startedAt = now,
            completedAt = now,
            request = EngineRequest(
                method = method,
                url = url,
                contentType = contentType
            ),
            response = EngineResponse(
                status = status,
                contentType = contentType
            )
        )
    }
}
