package dev.fishit.mapper.engine.recording

import dev.fishit.mapper.engine.api.*
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests für Chainpoint-Benennung und Session-Bearbeitung.
 *
 * Testet:
 * 1. Chainpoint Label setzen und verbrauchen
 * 2. Chainpoint Label für Actions aktualisieren
 * 3. Exchanges löschen
 * 4. Exchanges bearbeiten (URL, Body)
 * 5. Actions löschen
 */
class ChainpointAndEditingTest {

    // ========================================================================
    // Chainpoint Label Tests
    // ========================================================================

    @Test
    fun testSetNextChainpointLabel() {
        val manager = SimulatedSessionManager()

        // Starte Session
        manager.startSession("Test Session", "https://example.com")

        // Setze Label für nächsten Chainpoint
        manager.setNextChainpointLabel("Login Button")

        assertEquals("Login Button", manager.nextChainpointLabel)
    }

    @Test
    fun testChainpointLabelIsConsumedOnClick() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")
        manager.setNextChainpointLabel("Login Button")

        // Simuliere einen Klick
        val action = manager.addClickAction("button#login")

        // Label sollte der Action zugewiesen sein
        assertEquals("Login Button", manager.getChainpointLabel(action.actionId))

        // Nächstes Label sollte jetzt null sein (verbraucht)
        assertNull(manager.nextChainpointLabel)
    }

    @Test
    fun testChainpointLabelNotConsumedOnNonClickAction() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")
        manager.setNextChainpointLabel("Login Button")

        // Simuliere eine Navigation (kein Click)
        val navAction = manager.addNavigationAction("https://example.com/page")

        // Label sollte NICHT der Navigation zugewiesen sein
        assertNull(manager.getChainpointLabel(navAction.actionId))

        // Label sollte noch ausstehend sein
        assertEquals("Login Button", manager.nextChainpointLabel)

        // Jetzt ein Click
        val clickAction = manager.addClickAction("button#submit")

        // Jetzt sollte das Label zugewiesen sein
        assertEquals("Login Button", manager.getChainpointLabel(clickAction.actionId))
        assertNull(manager.nextChainpointLabel)
    }

    @Test
    fun testClearChainpointLabel() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")
        manager.setNextChainpointLabel("Login Button")

        // Label löschen
        manager.setNextChainpointLabel(null)

        assertNull(manager.nextChainpointLabel)

        // Click sollte kein Label bekommen
        val action = manager.addClickAction("button#test")
        assertNull(manager.getChainpointLabel(action.actionId))
    }

    @Test
    fun testUpdateChainpointLabelAfterwards() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        // Click ohne vorheriges Label
        val action = manager.addClickAction("button#test")
        assertNull(manager.getChainpointLabel(action.actionId))

        // Label nachträglich hinzufügen
        manager.updateChainpointLabel(action.actionId, "Nachträgliches Label")
        assertEquals("Nachträgliches Label", manager.getChainpointLabel(action.actionId))

        // Label ändern
        manager.updateChainpointLabel(action.actionId, "Geändertes Label")
        assertEquals("Geändertes Label", manager.getChainpointLabel(action.actionId))

        // Label entfernen
        manager.updateChainpointLabel(action.actionId, null)
        assertNull(manager.getChainpointLabel(action.actionId))
    }

    @Test
    fun testMultipleChainpointsInSequence() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        // Erster Chainpoint
        manager.setNextChainpointLabel("Step 1: Open Form")
        val click1 = manager.addClickAction("button#openForm")

        // Zweiter Chainpoint
        manager.setNextChainpointLabel("Step 2: Fill Username")
        val click2 = manager.addClickAction("input#username")

        // Dritter Chainpoint
        manager.setNextChainpointLabel("Step 3: Submit")
        val click3 = manager.addClickAction("button#submit")

        // Alle Labels prüfen
        assertEquals("Step 1: Open Form", manager.getChainpointLabel(click1.actionId))
        assertEquals("Step 2: Fill Username", manager.getChainpointLabel(click2.actionId))
        assertEquals("Step 3: Submit", manager.getChainpointLabel(click3.actionId))
    }

    // ========================================================================
    // Exchange Editing Tests
    // ========================================================================

    @Test
    fun testDeleteExchange() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        val ex1 = manager.addExchange("https://api.example.com/data1", "GET", 200)
        val ex2 = manager.addExchange("https://api.example.com/data2", "GET", 200)
        val ex3 = manager.addExchange("https://api.example.com/data3", "GET", 200)

        assertEquals(3, manager.exchangeCount)

        // Lösche mittleren Exchange
        manager.deleteExchange(ex2.exchangeId)

        assertEquals(2, manager.exchangeCount)
        assertTrue(manager.exchanges.any { it.exchangeId == ex1.exchangeId })
        assertTrue(manager.exchanges.none { it.exchangeId == ex2.exchangeId })
        assertTrue(manager.exchanges.any { it.exchangeId == ex3.exchangeId })
    }

    @Test
    fun testDeleteMultipleExchanges() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        val ex1 = manager.addExchange("https://api.example.com/data1", "GET", 200)
        val ex2 = manager.addExchange("https://api.example.com/data2", "GET", 200)
        val ex3 = manager.addExchange("https://api.example.com/data3", "GET", 200)
        val ex4 = manager.addExchange("https://api.example.com/data4", "GET", 200)

        assertEquals(4, manager.exchangeCount)

        // Lösche mehrere Exchanges
        manager.deleteExchanges(setOf(ex1.exchangeId, ex3.exchangeId))

        assertEquals(2, manager.exchangeCount)
        assertTrue(manager.exchanges.none { it.exchangeId == ex1.exchangeId })
        assertTrue(manager.exchanges.any { it.exchangeId == ex2.exchangeId })
        assertTrue(manager.exchanges.none { it.exchangeId == ex3.exchangeId })
        assertTrue(manager.exchanges.any { it.exchangeId == ex4.exchangeId })
    }

    @Test
    fun testUpdateExchangeUrl() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        val ex = manager.addExchange(
            "https://api.example.com/users/123?token=secret123",
            "GET",
            200
        )

        // URL aktualisieren (sensitive Daten entfernen)
        manager.updateExchangeUrl(ex.exchangeId, "https://api.example.com/users/{id}?token=REDACTED")

        val updated = manager.exchanges.find { it.exchangeId == ex.exchangeId }
        assertEquals("https://api.example.com/users/{id}?token=REDACTED", updated?.request?.url)
    }

    @Test
    fun testUpdateExchangeRequestBody() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        val ex = manager.addExchange(
            url = "https://api.example.com/login",
            method = "POST",
            status = 200,
            requestBody = """{"username":"admin","password":"secret123"}"""
        )

        // Request Body aktualisieren (Passwort entfernen)
        manager.updateExchangeRequestBody(
            ex.exchangeId,
            """{"username":"admin","password":"REDACTED"}"""
        )

        val updated = manager.exchanges.find { it.exchangeId == ex.exchangeId }
        assertEquals("""{"username":"admin","password":"REDACTED"}""", updated?.request?.body)
    }

    @Test
    fun testUpdateExchangeResponseBody() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        val ex = manager.addExchange(
            url = "https://api.example.com/user",
            method = "GET",
            status = 200,
            responseBody = """{"id":123,"email":"user@example.com","ssn":"123-45-6789"}"""
        )

        // Response Body aktualisieren (SSN entfernen)
        manager.updateExchangeResponseBody(
            ex.exchangeId,
            """{"id":123,"email":"user@example.com","ssn":"REDACTED"}"""
        )

        val updated = manager.exchanges.find { it.exchangeId == ex.exchangeId }
        assertEquals("""{"id":123,"email":"user@example.com","ssn":"REDACTED"}""", updated?.response?.body)
    }

    @Test
    fun testClearExchangeBody() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        val ex = manager.addExchange(
            url = "https://api.example.com/data",
            method = "POST",
            status = 200,
            requestBody = "some data",
            responseBody = "response data"
        )

        // Bodies komplett löschen
        manager.updateExchangeRequestBody(ex.exchangeId, null)
        manager.updateExchangeResponseBody(ex.exchangeId, null)

        val updated = manager.exchanges.find { it.exchangeId == ex.exchangeId }
        assertNull(updated?.request?.body)
        assertNull(updated?.response?.body)
    }

    // ========================================================================
    // Action Editing Tests
    // ========================================================================

    @Test
    fun testDeleteAction() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        val action1 = manager.addClickAction("button#first")
        val action2 = manager.addClickAction("button#second")
        val action3 = manager.addClickAction("button#third")

        assertEquals(3, manager.actionCount)

        // Lösche mittlere Action
        manager.deleteUserAction(action2.actionId)

        assertEquals(2, manager.actionCount)
        assertTrue(manager.userActions.any { it.actionId == action1.actionId })
        assertTrue(manager.userActions.none { it.actionId == action2.actionId })
        assertTrue(manager.userActions.any { it.actionId == action3.actionId })
    }

    @Test
    fun testDeleteActionRemovesChainpointLabel() {
        val manager = SimulatedSessionManager()

        manager.startSession("Test Session", "https://example.com")

        manager.setNextChainpointLabel("Important Click")
        val action = manager.addClickAction("button#important")

        // Label ist gesetzt
        assertEquals("Important Click", manager.getChainpointLabel(action.actionId))

        // Action löschen
        manager.deleteUserAction(action.actionId)

        // Label sollte auch weg sein
        assertNull(manager.getChainpointLabel(action.actionId))
    }

    // ========================================================================
    // Integration Test
    // ========================================================================

    @Test
    fun testCompleteEditingWorkflow() {
        val manager = SimulatedSessionManager()

        // 1. Session starten
        manager.startSession("API Mapping Session", "https://shop.example.com")

        // 2. Navigation zur Login-Seite
        manager.addNavigationAction("https://shop.example.com/login")
        manager.addExchange("https://shop.example.com/login", "GET", 200)

        // 3. Login-Flow mit Chainpoints
        manager.setNextChainpointLabel("Enter Credentials")
        val inputAction = manager.addClickAction("input#email")

        manager.setNextChainpointLabel("Submit Login")
        val submitAction = manager.addClickAction("button#login")

        val loginExchange = manager.addExchange(
            url = "https://api.shop.example.com/auth/login",
            method = "POST",
            status = 200,
            requestBody = """{"email":"test@example.com","password":"secret123"}"""
        )

        // 4. Dashboard laden
        manager.addExchange("https://shop.example.com/dashboard", "GET", 200)
        manager.addExchange(
            url = "https://api.shop.example.com/user/profile",
            method = "GET",
            status = 200,
            responseBody = """{"id":1,"email":"test@example.com","creditCard":"1234-5678-9012-3456"}"""
        )

        // Assertions vor Editing
        assertEquals(4, manager.exchangeCount)
        assertEquals(3, manager.actionCount) // 1 nav + 2 clicks
        assertEquals("Enter Credentials", manager.getChainpointLabel(inputAction.actionId))
        assertEquals("Submit Login", manager.getChainpointLabel(submitAction.actionId))

        // 5. Sensitive Daten bearbeiten

        // Passwort im Login-Request entfernen
        manager.updateExchangeRequestBody(
            loginExchange.exchangeId,
            """{"email":"test@example.com","password":"REDACTED"}"""
        )

        // Kreditkarte im Profil entfernen
        val profileExchange = manager.exchanges.find { it.request.url.contains("/user/profile") }!!
        manager.updateExchangeResponseBody(
            profileExchange.exchangeId,
            """{"id":1,"email":"test@example.com","creditCard":"REDACTED"}"""
        )

        // 6. Ungewollten Exchange löschen (z.B. Tracking-Request)
        val dashboardExchange = manager.exchanges.find { it.request.url.contains("/dashboard") }!!
        manager.deleteExchange(dashboardExchange.exchangeId)

        // 7. Chainpoint-Label aktualisieren
        manager.updateChainpointLabel(submitAction.actionId, "Submit Login Form")

        // Final Assertions
        assertEquals(3, manager.exchangeCount) // 4 - 1 gelöscht

        val updatedLogin = manager.exchanges.find { it.exchangeId == loginExchange.exchangeId }!!
        assertTrue(updatedLogin.request.body?.contains("REDACTED") == true)

        val updatedProfile = manager.exchanges.find { it.request.url.contains("/user/profile") }!!
        assertTrue(updatedProfile.response?.body?.contains("REDACTED") == true)

        assertEquals("Submit Login Form", manager.getChainpointLabel(submitAction.actionId))
    }
}

// ============================================================================
// Test Helper: Simulated Session Manager
// ============================================================================

/**
 * Simuliert den CaptureSessionManager für Unit-Tests.
 */
class SimulatedSessionManager {
    private var _sessionActive = false
    private var _sessionName: String? = null
    private var _targetUrl: String? = null

    private val _exchanges = mutableListOf<EngineExchange>()
    private val _userActions = mutableListOf<EngineCorrelatedAction>()
    private val _chainpointLabels = mutableMapOf<String, String>()
    private var _nextChainpointLabel: String? = null

    val exchanges: List<EngineExchange> get() = _exchanges.toList()
    val userActions: List<EngineCorrelatedAction> get() = _userActions.toList()
    val exchangeCount: Int get() = _exchanges.size
    val actionCount: Int get() = _userActions.size
    val nextChainpointLabel: String? get() = _nextChainpointLabel

    fun startSession(name: String, targetUrl: String?) {
        _sessionActive = true
        _sessionName = name
        _targetUrl = targetUrl
        _exchanges.clear()
        _userActions.clear()
        _chainpointLabels.clear()
        _nextChainpointLabel = null
    }

    fun setNextChainpointLabel(label: String?) {
        _nextChainpointLabel = label
    }

    fun getChainpointLabel(actionId: String): String? {
        return _chainpointLabels[actionId]
    }

    fun updateChainpointLabel(actionId: String, label: String?) {
        if (label == null) {
            _chainpointLabels.remove(actionId)
        } else {
            _chainpointLabels[actionId] = label
        }
    }

    fun addClickAction(target: String): EngineCorrelatedAction {
        val action = EngineCorrelatedAction(
            actionId = java.util.UUID.randomUUID().toString(),
            timestamp = Clock.System.now(),
            actionType = "click",
            payload = mapOf("target" to target)
        )

        // Chainpoint-Label zuweisen wenn gesetzt
        _nextChainpointLabel?.let { label ->
            _chainpointLabels[action.actionId] = label
            _nextChainpointLabel = null
        }

        _userActions.add(action)
        return action
    }

    fun addNavigationAction(url: String): EngineCorrelatedAction {
        val action = EngineCorrelatedAction(
            actionId = java.util.UUID.randomUUID().toString(),
            timestamp = Clock.System.now(),
            actionType = "navigation",
            payload = mapOf("target" to url)
        )
        // Navigation verbraucht KEIN Chainpoint-Label
        _userActions.add(action)
        return action
    }

    fun addExchange(
        url: String,
        method: String,
        status: Int,
        requestBody: String? = null,
        responseBody: String? = null
    ): EngineExchange {
        val exchange = EngineExchange(
            exchangeId = java.util.UUID.randomUUID().toString(),
            startedAt = Clock.System.now(),
            completedAt = Clock.System.now(),
            request = EngineRequest(
                method = method,
                url = url,
                body = requestBody
            ),
            response = EngineResponse(
                status = status,
                body = responseBody
            )
        )
        _exchanges.add(exchange)
        return exchange
    }

    fun deleteExchange(exchangeId: String) {
        _exchanges.removeAll { it.exchangeId == exchangeId }
    }

    fun deleteExchanges(exchangeIds: Set<String>) {
        _exchanges.removeAll { it.exchangeId in exchangeIds }
    }

    fun deleteUserAction(actionId: String) {
        _userActions.removeAll { it.actionId == actionId }
        _chainpointLabels.remove(actionId)
    }

    fun updateExchangeUrl(exchangeId: String, newUrl: String) {
        val index = _exchanges.indexOfFirst { it.exchangeId == exchangeId }
        if (index >= 0) {
            val old = _exchanges[index]
            _exchanges[index] = old.copy(
                request = old.request.copy(url = newUrl)
            )
        }
    }

    fun updateExchangeRequestBody(exchangeId: String, newBody: String?) {
        val index = _exchanges.indexOfFirst { it.exchangeId == exchangeId }
        if (index >= 0) {
            val old = _exchanges[index]
            _exchanges[index] = old.copy(
                request = old.request.copy(body = newBody)
            )
        }
    }

    fun updateExchangeResponseBody(exchangeId: String, newBody: String?) {
        val index = _exchanges.indexOfFirst { it.exchangeId == exchangeId }
        if (index >= 0) {
            val old = _exchanges[index]
            _exchanges[index] = old.copy(
                response = old.response?.copy(body = newBody)
            )
        }
    }
}
