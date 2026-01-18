package dev.fishit.mapper.android.capture

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * Verwaltet Recording-Sessions für Traffic Capture.
 *
 * Eine Session gruppiert alle Exchanges und Actions die während
 * einer Analyse-Sitzung aufgenommen wurden.
 *
 * ## Speicherung als HAR
 * Sessions werden **direkt als HAR-Dateien** gespeichert:
 * - `stopSession()` → HAR wird automatisch erzeugt
 * - Manuell via `saveCurrentSession()` während Recording
 *
 * Speicherort: `{app_files}/fishit/har/`
 *
 * ## Warum HAR?
 * - **Standard-Format** - Chrome DevTools, Postman, etc. verstehen es
 * - **Kein Export nötig** - die Datei IST bereits der Export
 * - **Copilot-kompatibel** - AI versteht HAR nativ
 *
 * ## Verwendung
 * ```kotlin
 * val sessionManager = CaptureSessionManager(context)
 *
 * // Gespeicherte Sessions laden
 * sessionManager.loadSavedSessions()
 *
 * // Session starten
 * val session = sessionManager.startSession("Example.com API")
 *
 * // Exchanges hinzufügen
 * webView.capturedExchanges.collect { exchanges ->
 *     sessionManager.addExchanges(exchanges)
 * }
 *
 * // Session beenden → HAR wird automatisch gespeichert!
 * val completedSession = sessionManager.stopSession()
 *
 * // HAR-Datei direkt teilen
 * val harFile = sessionManager.getHarFile(session.id)
 * ```
 */
class CaptureSessionManager(context: Context? = null) {

    // HAR-basierter Store (Standard-Format!)
    private val harStore: HarSessionStore? = context?.let { HarSessionStore(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Eine Recording-Session mit allen gesammelten Daten.
     */
    data class CaptureSession(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val startedAt: Instant,
        val stoppedAt: Instant? = null,
        val targetUrl: String? = null,
        val exchanges: List<TrafficInterceptWebView.CapturedExchange> = emptyList(),
        val userActions: List<TrafficInterceptWebView.UserAction> = emptyList(),
        val pageEvents: List<TrafficInterceptWebView.PageEvent> = emptyList(),
        val notes: String? = null
    ) {
        val isActive: Boolean get() = stoppedAt == null
        val duration: Long? get() = stoppedAt?.let {
            it.toEpochMilliseconds() - startedAt.toEpochMilliseconds()
        }
        val exchangeCount: Int get() = exchanges.size
        val actionCount: Int get() = userActions.size

        /**
         * Findet Exchanges die zeitlich mit einer Action korrelieren.
         */
        fun correlate(action: TrafficInterceptWebView.UserAction, windowMs: Long = 2000): List<TrafficInterceptWebView.CapturedExchange> {
            val actionTime = action.timestamp.toEpochMilliseconds()
            return exchanges.filter { exchange ->
                val exchangeTime = exchange.startedAt.toEpochMilliseconds()
                exchangeTime >= actionTime && exchangeTime <= actionTime + windowMs
            }
        }

        /**
         * Gruppiert Exchanges nach Basis-URL (Domain).
         */
        fun groupByDomain(): Map<String, List<TrafficInterceptWebView.CapturedExchange>> {
            return exchanges.groupBy { exchange ->
                try {
                    val url = java.net.URL(exchange.url)
                    "${url.protocol}://${url.host}"
                } catch (e: Exception) {
                    "unknown"
                }
            }
        }

        /**
         * Findet alle eindeutigen API Endpoints.
         */
        fun uniqueEndpoints(): List<String> {
            return exchanges
                .map { "${it.method} ${normalizeUrl(it.url)}" }
                .distinct()
        }

        private fun normalizeUrl(url: String): String {
            return try {
                val parsed = java.net.URL(url)
                val path = parsed.path
                // IDs durch Placeholder ersetzen
                path.replace(Regex("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "/{id}")
                    .replace(Regex("/\\d+"), "/{id}")
            } catch (e: Exception) {
                url
            }
        }
    }

    // Aktive Session
    private val _currentSession = MutableStateFlow<CaptureSession?>(null)
    val currentSession: StateFlow<CaptureSession?> = _currentSession.asStateFlow()

    // Alle abgeschlossenen Sessions
    private val _completedSessions = MutableStateFlow<List<CaptureSession>>(emptyList())
    val completedSessions: StateFlow<List<CaptureSession>> = _completedSessions.asStateFlow()

    // Recording Status
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /**
     * Startet eine neue Recording-Session.
     *
     * @param name Beschreibender Name für die Session
     * @param targetUrl Optionale Ziel-URL
     * @return Die neue Session
     * @throws IllegalStateException wenn bereits eine Session aktiv ist
     */
    fun startSession(name: String, targetUrl: String? = null): CaptureSession {
        check(_currentSession.value == null) {
            "Session already active. Stop current session first."
        }

        val session = CaptureSession(
            name = name,
            startedAt = Clock.System.now(),
            targetUrl = targetUrl
        )

        _currentSession.value = session
        _isRecording.value = true

        return session
    }

    /**
     * Stoppt die aktive Session und gibt sie zurück.
     * Die Session wird automatisch als HAR-Datei gespeichert.
     *
     * @return Die abgeschlossene Session
     * @throws IllegalStateException wenn keine Session aktiv ist
     */
    fun stopSession(): CaptureSession {
        val session = _currentSession.value
            ?: throw IllegalStateException("No active session to stop")

        val completedSession = session.copy(
            stoppedAt = Clock.System.now()
        )

        _completedSessions.value = _completedSessions.value + completedSession
        _currentSession.value = null
        _isRecording.value = false

        // Automatisch als HAR speichern
        persistSession(completedSession)

        return completedSession
    }

    /**
     * Speichert die aktuelle Session ohne sie zu beenden.
     * Nützlich für Auto-Save während langer Aufnahmen.
     */
    fun saveCurrentSession() {
        val session = _currentSession.value ?: return
        persistSession(session)
    }

    /**
     * Lädt alle gespeicherten Sessions (HAR-Dateien).
     */
    suspend fun loadSavedSessions() {
        val store = this.harStore ?: return
        val summaries = store.listSessions()
        val sessions = summaries.mapNotNull { summary ->
            store.loadSession(summary.id)
        }
        _completedSessions.value = sessions
    }

    /**
     * Gibt die HAR-Datei einer Session zurück (zum Teilen).
     */
    suspend fun getHarFile(sessionId: String): java.io.File? {
        return harStore?.getHarFile(sessionId)
    }

    /**
     * Gibt den HAR-Inhalt als String zurück.
     */
    suspend fun getHarContent(sessionId: String): String? {
        return harStore?.getHarContent(sessionId)
    }

    /**
     * Gibt Speicherplatz-Info zurück.
     */
    suspend fun getStorageInfo(): String {
        return harStore?.getStorageSizeFormatted() ?: "Kein Speicher"
    }

    private fun persistSession(session: CaptureSession) {
        val store = this.harStore ?: return
        scope.launch {
            store.saveSession(session)
        }
    }

    /**
     * Fügt Exchanges zur aktiven Session hinzu.
     */
    fun addExchanges(exchanges: List<TrafficInterceptWebView.CapturedExchange>) {
        val session = _currentSession.value ?: return

        // Nur neue Exchanges hinzufügen (deduplizieren nach ID)
        val existingIds = session.exchanges.map { it.id }.toSet()
        val newExchanges = exchanges.filter { it.id !in existingIds }

        if (newExchanges.isNotEmpty()) {
            _currentSession.value = session.copy(
                exchanges = session.exchanges + newExchanges
            )
        }
    }

    /**
     * Fügt User Actions zur aktiven Session hinzu.
     */
    fun addUserActions(actions: List<TrafficInterceptWebView.UserAction>) {
        val session = _currentSession.value ?: return

        val existingIds = session.userActions.map { it.id }.toSet()
        val newActions = actions.filter { it.id !in existingIds }

        if (newActions.isNotEmpty()) {
            _currentSession.value = session.copy(
                userActions = session.userActions + newActions
            )
        }
    }

    /**
     * Fügt Page Events zur aktiven Session hinzu.
     */
    fun addPageEvents(events: List<TrafficInterceptWebView.PageEvent>) {
        val session = _currentSession.value ?: return

        _currentSession.value = session.copy(
            pageEvents = session.pageEvents + events
        )
    }

    /**
     * Aktualisiert die Notizen der aktiven Session.
     */
    fun updateNotes(notes: String) {
        val session = _currentSession.value ?: return

        _currentSession.value = session.copy(notes = notes)
    }

    /**
     * Löscht eine abgeschlossene Session (aus Speicher und HAR-Datei).
     */
    fun deleteSession(sessionId: String) {
        _completedSessions.value = _completedSessions.value.filter { it.id != sessionId }

        // Auch HAR-Datei löschen
        harStore?.let { s ->
            scope.launch { s.deleteSession(sessionId) }
        }
    }

    /**
     * Löscht alle abgeschlossenen Sessions (alle HAR-Dateien).
     */
    fun clearAllSessions() {
        _completedSessions.value = emptyList()

        // Auch alle HAR-Dateien löschen
        harStore?.let { s ->
            scope.launch { s.deleteAllSessions() }
        }
    }

    /**
     * Bricht die aktive Session ab ohne sie zu speichern.
     */
    fun cancelSession() {
        _currentSession.value = null
        _isRecording.value = false
    }

    /**
     * Exportiert eine Session in das Format für die Analyse-Engine.
     */
    fun exportForAnalysis(session: CaptureSession): SessionExport {
        return SessionExport(
            sessionId = session.id,
            sessionName = session.name,
            targetUrl = session.targetUrl,
            startedAt = session.startedAt,
            stoppedAt = session.stoppedAt,
            exchanges = session.exchanges.map { exchange ->
                ExchangeExport(
                    id = exchange.id,
                    method = exchange.method,
                    url = exchange.url,
                    requestHeaders = exchange.requestHeaders,
                    requestBody = exchange.requestBody,
                    responseStatus = exchange.responseStatus ?: 0,
                    responseHeaders = exchange.responseHeaders ?: emptyMap(),
                    responseBody = exchange.responseBody,
                    startedAt = exchange.startedAt,
                    completedAt = exchange.completedAt
                )
            },
            correlatedActions = session.userActions.map { action ->
                val relatedExchanges = session.correlate(action)
                CorrelatedActionExport(
                    actionId = action.id,
                    type = action.type.name,
                    target = action.target,
                    value = action.value,
                    timestamp = action.timestamp,
                    pageUrl = action.pageUrl,
                    exchangeIds = relatedExchanges.map { it.id }
                )
            }
        )
    }

    /**
     * Export-Format für die Analyse-Engine.
     */
    data class SessionExport(
        val sessionId: String,
        val sessionName: String,
        val targetUrl: String?,
        val startedAt: Instant,
        val stoppedAt: Instant?,
        val exchanges: List<ExchangeExport>,
        val correlatedActions: List<CorrelatedActionExport>
    )

    data class ExchangeExport(
        val id: String,
        val method: String,
        val url: String,
        val requestHeaders: Map<String, String>,
        val requestBody: String?,
        val responseStatus: Int,
        val responseHeaders: Map<String, String>,
        val responseBody: String?,
        val startedAt: Instant,
        val completedAt: Instant?
    )

    data class CorrelatedActionExport(
        val actionId: String,
        val type: String,
        val target: String,
        val value: String?,
        val timestamp: Instant,
        val pageUrl: String?,
        val exchangeIds: List<String>
    )
}
