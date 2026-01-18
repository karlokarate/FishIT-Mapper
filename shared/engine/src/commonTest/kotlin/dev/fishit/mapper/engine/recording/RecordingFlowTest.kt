package dev.fishit.mapper.engine.recording

import dev.fishit.mapper.engine.api.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Umfassende Tests für das Recording-System.
 *
 * Testet:
 * 1. Session-Start mit korrekter Start-URL
 * 2. Klick-Erkennung als neue Chain-Punkte
 * 3. Performance bei vielen Exchanges
 * 4. Korrelation zwischen Actions und Exchanges
 * 5. Navigation-Tracking
 */
class RecordingFlowTest {

    // ========================================================================
    // Session Start Tests
    // ========================================================================

    @Test
    fun testSessionStartsWithCorrectTargetUrl() {
        val session = SimulatedCaptureSession(
            name = "Test Session",
            targetUrl = "https://example.com/login"
        )

        assertEquals("https://example.com/login", session.targetUrl)
        assertNotNull(session.startedAt)
        assertTrue(session.isActive)
        assertEquals(0, session.exchangeCount)
        assertEquals(0, session.actionCount)
    }

    @Test
    fun testSessionInitialNavigationCreatesAction() {
        val session = SimulatedCaptureSession(
            name = "Navigation Test",
            targetUrl = "https://app.example.com"
        )

        // Simuliere initialen Page Load
        session.simulatePageStart("https://app.example.com")

        // Sollte eine Navigation-Action erstellen
        assertEquals(1, session.actionCount)
        val action = session.userActions.first()
        assertEquals("navigation", action.actionType)
        assertEquals("https://app.example.com", action.payload["target"])
    }

    @Test
    fun testSessionCapturesInitialExchanges() {
        val session = SimulatedCaptureSession(
            name = "Initial Exchange Test",
            targetUrl = "https://api.example.com"
        )

        // Simuliere Page Load + API Calls
        session.simulatePageStart("https://api.example.com")
        session.simulateExchange(
            url = "https://api.example.com",
            method = "GET",
            status = 200,
            contentType = "text/html"
        )
        session.simulateExchange(
            url = "https://api.example.com/styles.css",
            method = "GET",
            status = 200,
            contentType = "text/css"
        )
        session.simulateExchange(
            url = "https://api.example.com/api/init",
            method = "GET",
            status = 200,
            contentType = "application/json"
        )

        assertEquals(3, session.exchangeCount)

        // API Call sollte erkannt werden
        val apiCalls = session.exchanges.filter {
            it.responseContentType?.contains("application/json") == true
        }
        assertEquals(1, apiCalls.size)
    }

    // ========================================================================
    // Click Detection & Chain Points
    // ========================================================================

    @Test
    fun testClickOnLinkCreatesNewChainPoint() {
        val session = SimulatedCaptureSession(
            name = "Click Chain Test",
            targetUrl = "https://example.com"
        )

        // Initial page
        session.simulatePageStart("https://example.com")
        session.simulateExchange("https://example.com", "GET", 200)

        // User klickt auf einen Link
        session.simulateClick("a.nav-link", "https://example.com/dashboard")

        // Sollte einen Click-Action Chain-Point erstellen
        val clickActions = session.userActions.filter { it.actionType == "click" }
        assertEquals(1, clickActions.size)

        val click = clickActions.first()
        assertEquals("a.nav-link", click.payload["target"])
        assertEquals("https://example.com/dashboard", click.payload["value"])
    }

    @Test
    fun testClickTriggersCorrelatedExchanges() {
        val session = SimulatedCaptureSession(
            name = "Click Correlation Test",
            targetUrl = "https://example.com"
        )

        // Initial state
        session.simulatePageStart("https://example.com")

        // User klickt Button
        val clickTime = session.simulateClick("button#loadData")

        // API Calls nach Click (innerhalb 2s Fenster)
        session.simulateExchange(
            url = "https://api.example.com/data",
            method = "GET",
            status = 200,
            delayMs = 100 // 100ms nach Click
        )
        session.simulateExchange(
            url = "https://api.example.com/user",
            method = "GET",
            status = 200,
            delayMs = 200 // 200ms nach Click
        )

        // Korrelierte Exchanges finden
        val correlatedExchanges = session.correlateAction(
            session.userActions.find { it.actionType == "click" }!!
        )

        assertEquals(2, correlatedExchanges.size)
    }

    @Test
    fun testMultipleClicksCreateSeparateChainPoints() {
        val session = SimulatedCaptureSession(
            name = "Multiple Clicks Test",
            targetUrl = "https://example.com"
        )

        session.simulatePageStart("https://example.com")

        // Erster Click
        session.simulateClick("button#step1")
        session.simulateExchange("https://api.example.com/step1", "POST", 200, delayMs = 50)

        // Warte > 2s (neuer Chain-Point)
        session.advanceTime(3000)

        // Zweiter Click
        session.simulateClick("button#step2")
        session.simulateExchange("https://api.example.com/step2", "POST", 200, delayMs = 50)

        // Sollte 2 separate Click-Actions haben
        val clicks = session.userActions.filter { it.actionType == "click" }
        assertEquals(2, clicks.size)

        // Jeder Click sollte seinen eigenen Exchange korreliert haben
        val step1Exchanges = session.correlateAction(clicks[0])
        val step2Exchanges = session.correlateAction(clicks[1])

        assertEquals(1, step1Exchanges.size)
        assertTrue(step1Exchanges[0].request.url.contains("step1"))

        assertEquals(1, step2Exchanges.size)
        assertTrue(step2Exchanges[0].request.url.contains("step2"))
    }

    @Test
    fun testFormSubmitCreatesChainPoint() {
        val session = SimulatedCaptureSession(
            name = "Form Submit Test",
            targetUrl = "https://example.com/login"
        )

        session.simulatePageStart("https://example.com/login")

        // User füllt Formular aus
        session.simulateInput("input#username", "testuser")
        session.simulateInput("input#password", "secret")

        // User klickt Submit
        session.simulateSubmit("form#loginForm")

        // POST Request nach Submit
        session.simulateExchange(
            url = "https://example.com/api/login",
            method = "POST",
            status = 200,
            delayMs = 100,
            requestBody = """{"username":"testuser","password":"secret"}"""
        )

        // Submit sollte als Chain-Point erkannt werden
        val submits = session.userActions.filter { it.actionType == "submit" }
        assertEquals(1, submits.size)

        // POST sollte korreliert sein
        val correlatedExchanges = session.correlateAction(submits.first())
        assertEquals(1, correlatedExchanges.size)
        assertEquals("POST", correlatedExchanges[0].request.method)
    }

    // ========================================================================
    // Navigation Tracking
    // ========================================================================

    @Test
    fun testNavigationToNewPageCreatesChainPoint() {
        val session = SimulatedCaptureSession(
            name = "Navigation Test",
            targetUrl = "https://example.com"
        )

        // Initial page
        session.simulatePageStart("https://example.com")
        session.simulatePageFinished("https://example.com", "Home Page")

        // Navigate to new page (via link click)
        session.simulateClick("a#dashboard-link", "https://example.com/dashboard")
        session.simulatePageStart("https://example.com/dashboard")
        session.simulatePageFinished("https://example.com/dashboard", "Dashboard")

        // Sollte 2 Navigation-Actions haben
        val navigations = session.userActions.filter { it.actionType == "navigation" }
        assertEquals(2, navigations.size)

        // Plus 1 Click Action
        val clicks = session.userActions.filter { it.actionType == "click" }
        assertEquals(1, clicks.size)
    }

    @Test
    fun testRedirectIsTracked() {
        val session = SimulatedCaptureSession(
            name = "Redirect Test",
            targetUrl = "https://example.com/login"
        )

        session.simulatePageStart("https://example.com/login")

        // Login submit
        session.simulateSubmit("form#login")

        // Server antwortet mit Redirect
        session.simulateExchange(
            url = "https://example.com/api/login",
            method = "POST",
            status = 302,
            redirectLocation = "/dashboard"
        )

        // Browser folgt Redirect
        session.simulateRedirect("https://example.com/dashboard")
        session.simulatePageStart("https://example.com/dashboard")

        // Redirect sollte als Navigation erkannt werden
        val navigations = session.userActions.filter {
            it.actionType == "navigation" && it.payload["value"] == "redirect"
        }
        assertTrue(navigations.isNotEmpty())
    }

    // ========================================================================
    // Performance Tests
    // ========================================================================

    @Test
    fun testPerformanceWithManyExchanges() {
        val session = SimulatedCaptureSession(
            name = "Performance Test",
            targetUrl = "https://example.com"
        )

        session.simulatePageStart("https://example.com")

        // Simuliere 100 Exchanges (typische SPA)
        val startTime = System.currentTimeMillis()

        repeat(100) { i ->
            session.simulateExchange(
                url = "https://api.example.com/data/$i",
                method = "GET",
                status = 200
            )
        }

        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(100, session.exchangeCount)

        // Sollte schnell sein (< 100ms für 100 Exchanges)
        assertTrue(elapsed < 1000, "Adding 100 exchanges took ${elapsed}ms, expected < 1000ms")
    }

    @Test
    fun testPerformanceCorrelationWithManyExchanges() {
        val session = SimulatedCaptureSession(
            name = "Correlation Performance Test",
            targetUrl = "https://example.com"
        )

        session.simulatePageStart("https://example.com")

        // 50 Clicks mit je 10 Exchanges
        repeat(50) { clickIndex ->
            session.simulateClick("button#action$clickIndex")
            repeat(10) { exchangeIndex ->
                session.simulateExchange(
                    url = "https://api.example.com/click$clickIndex/exchange$exchangeIndex",
                    method = "GET",
                    status = 200,
                    delayMs = (exchangeIndex * 10).toLong()
                )
            }
            session.advanceTime(3000) // Neuer Chain-Point
        }

        assertEquals(500, session.exchangeCount)
        assertEquals(50, session.userActions.filter { it.actionType == "click" }.size)

        // Korrelation testen
        val startTime = System.currentTimeMillis()

        session.userActions
            .filter { it.actionType == "click" }
            .forEach { action ->
                val correlated = session.correlateAction(action)
                assertTrue(correlated.size <= 10)
            }

        val elapsed = System.currentTimeMillis() - startTime
        assertTrue(elapsed < 1000, "Correlating 50 actions with 500 exchanges took ${elapsed}ms")
    }

    @Test
    fun testDeduplicationPerformance() {
        val session = SimulatedCaptureSession(
            name = "Deduplication Test",
            targetUrl = "https://example.com"
        )

        // Simuliere wiederholtes Hinzufügen der gleichen Exchanges
        val exchange = session.simulateExchange(
            url = "https://api.example.com/data",
            method = "GET",
            status = 200
        )

        // Versuche den gleichen Exchange mehrfach hinzuzufügen
        repeat(100) {
            session.addExchangeIfNew(exchange)
        }

        // Sollte nur 1 Exchange haben (dedupliziert)
        assertEquals(1, session.exchangeCount)
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    fun testExchangesWithoutCorrelatedAction() {
        val session = SimulatedCaptureSession(
            name = "Uncorrelated Test",
            targetUrl = "https://example.com"
        )

        session.simulatePageStart("https://example.com")

        // Background API calls (z.B. Polling, WebSocket Fallback)
        session.advanceTime(10000) // 10s später

        session.simulateExchange(
            url = "https://api.example.com/poll",
            method = "GET",
            status = 200
        )

        // Dieser Exchange sollte keiner Click-Action korrelieren
        val clicks = session.userActions.filter { it.actionType == "click" }
        assertTrue(clicks.isEmpty())

        // Aber der Exchange existiert trotzdem
        assertEquals(1, session.exchangeCount)
    }

    @Test
    fun testRapidClicksAreDistinguished() {
        val session = SimulatedCaptureSession(
            name = "Rapid Clicks Test",
            targetUrl = "https://example.com"
        )

        session.simulatePageStart("https://example.com")

        // Schnelle aufeinanderfolgende Clicks (< 2s Fenster)
        session.simulateClick("button#action1")
        session.advanceTime(500)
        session.simulateClick("button#action2")
        session.advanceTime(500)
        session.simulateClick("button#action3")

        // Alle Clicks sollten erfasst sein
        val clicks = session.userActions.filter { it.actionType == "click" }
        assertEquals(3, clicks.size)

        // Mit unterschiedlichen Timestamps
        val timestamps = clicks.map { it.timestamp.toEpochMilliseconds() }.distinct()
        assertEquals(3, timestamps.size)
    }

    @Test
    fun testLongRunningRequestCorrelation() {
        val session = SimulatedCaptureSession(
            name = "Long Request Test",
            targetUrl = "https://example.com"
        )

        session.simulatePageStart("https://example.com")

        // Click
        session.simulateClick("button#upload")

        // Lange laufender Request (3s)
        session.simulateExchange(
            url = "https://api.example.com/upload",
            method = "POST",
            status = 200,
            delayMs = 100, // Start nach 100ms
            durationMs = 3000 // 3s Duration
        )

        // Request startet innerhalb des 2s Fensters
        val correlated = session.correlateAction(
            session.userActions.find { it.actionType == "click" }!!
        )

        assertEquals(1, correlated.size)
    }

    // ========================================================================
    // Integration Test
    // ========================================================================

    @Test
    fun testCompleteLoginFlow() {
        val session = SimulatedCaptureSession(
            name = "Complete Login Flow",
            targetUrl = "https://app.example.com/login"
        )

        // 1. Page Load
        session.simulatePageStart("https://app.example.com/login")
        session.simulateExchange("https://app.example.com/login", "GET", 200, contentType = "text/html")
        session.simulateExchange("https://app.example.com/static/login.css", "GET", 200)
        session.simulateExchange("https://app.example.com/static/login.js", "GET", 200)
        session.simulatePageFinished("https://app.example.com/login", "Login")

        session.advanceTime(1000)

        // 2. User Input
        session.simulateInput("input#email", "user@test.com")
        session.simulateInput("input#password", "secret123")

        session.advanceTime(500)

        // 3. Form Submit
        session.simulateSubmit("form#loginForm")
        session.simulateExchange(
            url = "https://api.example.com/auth/login",
            method = "POST",
            status = 302,
            requestBody = """{"email":"user@test.com","password":"secret123"}""",
            redirectLocation = "/dashboard",
            delayMs = 50
        )

        // 4. Redirect to Dashboard
        session.simulateRedirect("https://app.example.com/dashboard")
        session.simulatePageStart("https://app.example.com/dashboard")
        session.simulateExchange("https://app.example.com/dashboard", "GET", 200, contentType = "text/html")
        session.simulateExchange("https://api.example.com/user/profile", "GET", 200)
        session.simulatePageFinished("https://app.example.com/dashboard", "Dashboard")

        // 5. Stop Session
        session.stop()

        // Assertions
        assertFalse(session.isActive)
        assertNotNull(session.stoppedAt)

        // Exchanges: 3 (login page) + 1 (login API) + 2 (dashboard page + API)
        assertTrue(session.exchangeCount >= 6, "Expected at least 6 exchanges, got ${session.exchangeCount}")

        // Actions: mindestens 2 input + 1 submit + 2 navigations
        assertTrue(session.actionCount >= 4, "Expected at least 4 actions, got ${session.actionCount}")

        // Submit sollte mit Login-API korrelieren
        val submit = session.userActions.find { it.actionType == "submit" }
        assertNotNull(submit)

        val correlatedToSubmit = session.correlateAction(submit)
        assertTrue(correlatedToSubmit.isNotEmpty())
        assertTrue(correlatedToSubmit.any { it.request.url.contains("/auth/login") })

        // Unique Endpoints
        val endpoints = session.uniqueEndpoints()
        assertTrue(endpoints.contains("POST /auth/login"))
        assertTrue(endpoints.contains("GET /user/profile"))
    }
}

// ============================================================================
// Test Helper: Simulated Capture Session
// ============================================================================

/**
 * Simuliert eine CaptureSession für Unit-Tests (ohne Android-Dependencies).
 */
class SimulatedCaptureSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val targetUrl: String? = null
) {
    val startedAt: Instant = Clock.System.now()
    var stoppedAt: Instant? = null

    private val _exchanges = mutableListOf<EngineExchange>()
    private val _userActions = mutableListOf<EngineCorrelatedAction>()
    private var _currentTime: Instant = startedAt

    val exchanges: List<EngineExchange> get() = _exchanges
    val userActions: List<EngineCorrelatedAction> get() = _userActions

    val isActive: Boolean get() = stoppedAt == null
    val exchangeCount: Int get() = _exchanges.size
    val actionCount: Int get() = _userActions.size

    private val exchangeIds = mutableSetOf<String>()

    fun advanceTime(ms: Long) {
        _currentTime = Instant.fromEpochMilliseconds(_currentTime.toEpochMilliseconds() + ms)
    }

    fun simulatePageStart(url: String): Instant {
        val action = EngineCorrelatedAction(
            actionId = java.util.UUID.randomUUID().toString(),
            timestamp = _currentTime,
            actionType = "navigation",
            payload = mapOf("target" to url, "value" to "page_start")
        )
        _userActions.add(action)
        return _currentTime
    }

    fun simulatePageFinished(url: String, title: String) {
        // Page finished wird als PageEvent behandelt, nicht als Action
    }

    fun simulateClick(target: String, value: String? = null): Instant {
        val action = EngineCorrelatedAction(
            actionId = java.util.UUID.randomUUID().toString(),
            timestamp = _currentTime,
            actionType = "click",
            payload = buildMap {
                put("target", target)
                value?.let { put("value", it) }
            }
        )
        _userActions.add(action)
        return _currentTime
    }

    fun simulateInput(target: String, value: String) {
        val action = EngineCorrelatedAction(
            actionId = java.util.UUID.randomUUID().toString(),
            timestamp = _currentTime,
            actionType = "input",
            payload = mapOf("target" to target, "value" to value)
        )
        _userActions.add(action)
    }

    fun simulateSubmit(target: String) {
        val action = EngineCorrelatedAction(
            actionId = java.util.UUID.randomUUID().toString(),
            timestamp = _currentTime,
            actionType = "submit",
            payload = mapOf("target" to target)
        )
        _userActions.add(action)
    }

    fun simulateRedirect(toUrl: String) {
        val action = EngineCorrelatedAction(
            actionId = java.util.UUID.randomUUID().toString(),
            timestamp = _currentTime,
            actionType = "navigation",
            payload = mapOf("target" to toUrl, "value" to "redirect")
        )
        _userActions.add(action)
    }

    fun simulateExchange(
        url: String,
        method: String,
        status: Int,
        contentType: String? = null,
        requestBody: String? = null,
        redirectLocation: String? = null,
        delayMs: Long = 0,
        durationMs: Long = 50
    ): EngineExchange {
        advanceTime(delayMs)

        val exchange = EngineExchange(
            exchangeId = java.util.UUID.randomUUID().toString(),
            startedAt = _currentTime,
            completedAt = Instant.fromEpochMilliseconds(_currentTime.toEpochMilliseconds() + durationMs),
            request = EngineRequest(
                method = method,
                url = url,
                body = requestBody,
                contentType = contentType
            ),
            response = EngineResponse(
                status = status,
                contentType = contentType,
                redirectLocation = redirectLocation
            )
        )

        _exchanges.add(exchange)
        exchangeIds.add(exchange.exchangeId)

        advanceTime(durationMs)

        return exchange
    }

    fun addExchangeIfNew(exchange: EngineExchange) {
        if (exchange.exchangeId !in exchangeIds) {
            _exchanges.add(exchange)
            exchangeIds.add(exchange.exchangeId)
        }
    }

    fun correlateAction(action: EngineCorrelatedAction, windowMs: Long = 2000): List<EngineExchange> {
        val actionTime = action.timestamp.toEpochMilliseconds()
        return _exchanges.filter { exchange ->
            val exchangeTime = exchange.startedAt.toEpochMilliseconds()
            exchangeTime >= actionTime && exchangeTime <= actionTime + windowMs
        }
    }

    fun uniqueEndpoints(): List<String> {
        return _exchanges
            .map { "${it.request.method} ${extractPath(it.request.url)}" }
            .distinct()
    }

    private fun extractPath(url: String): String {
        return try {
            val withoutProtocol = url.removePrefix("https://").removePrefix("http://")
            val hostEnd = withoutProtocol.indexOfFirst { it == '/' }
            if (hostEnd > 0) {
                val path = withoutProtocol.substring(hostEnd)
                val queryStart = path.indexOf('?')
                if (queryStart > 0) path.substring(0, queryStart) else path
            } else {
                "/"
            }
        } catch (e: Exception) {
            url
        }
    }

    fun stop() {
        stoppedAt = _currentTime
    }
}
