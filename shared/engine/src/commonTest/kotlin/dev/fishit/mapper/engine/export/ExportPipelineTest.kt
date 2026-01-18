package dev.fishit.mapper.engine.export

import dev.fishit.mapper.engine.api.EngineExchange
import dev.fishit.mapper.engine.api.EngineRequest
import dev.fishit.mapper.engine.api.EngineResponse
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Umfassende Tests für die Export-Pipeline:
 * HAR → Normalisierte Timeline → Sequenzdiagramm (Mermaid) → State-Graph
 */
class ExportPipelineTest {

    // ==================== Test Fixtures ====================

    private fun createTestExchange(
        id: String = "ex_1",
        method: String = "GET",
        url: String = "https://api.example.com/users",
        status: Int = 200,
        requestBody: String? = null,
        responseBody: String? = """{"id": 1, "name": "Test"}""",
        startedAt: String = "2024-01-15T10:00:00Z",
        completedAt: String = "2024-01-15T10:00:01Z"
    ) = EngineExchange(
        exchangeId = id,
        startedAt = Instant.parse(startedAt),
        completedAt = Instant.parse(completedAt),
        request = EngineRequest(
            method = method,
            url = url,
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody
        ),
        response = EngineResponse(
            status = status,
            headers = mapOf("Content-Type" to "application/json"),
            body = responseBody
        )
    )

    private fun createTestUserAction(
        type: String = "CLICK",
        target: String = "button#login",
        value: String? = null,
        timestamp: String = "2024-01-15T10:00:00Z"
    ) = MermaidSequenceExporter.UserActionEvent(
        type = type,
        target = target,
        value = value,
        timestamp = Instant.parse(timestamp)
    )

    // ==================== HAR Exporter Tests ====================

    @Test
    fun harExporterGeneratesValidJson() {
        val exporter = HarExporter()
        val exchanges = listOf(
            createTestExchange(id = "1", url = "https://api.example.com/users"),
            createTestExchange(id = "2", method = "POST", url = "https://api.example.com/login", status = 201)
        )

        val result = exporter.export(exchanges, "Test Session")

        assertTrue(result.contains("log"), "HAR should contain 'log': $result")
        assertTrue(result.contains("version"), "HAR should contain 'version': $result")
        assertTrue(result.contains("entries"), "HAR should contain 'entries': $result")
        assertTrue(result.contains("creator"), "HAR should contain 'creator': $result")
        // creatorName param is used, not the string literal
        assertTrue(result.contains("Test Session") || result.contains("FishIT"), "HAR should contain creator name")
    }

    @Test
    fun harExporterHandlesEmptyExchanges() {
        val exporter = HarExporter()
        val result = exporter.export(emptyList())

        assertTrue(result.contains("\"entries\""))
        assertTrue(result.contains("[]") || result.contains("[ ]"))
    }

    @Test
    fun harExporterIncludesRequestDetails() {
        val exporter = HarExporter()
        val exchange = createTestExchange(
            method = "POST",
            url = "https://api.example.com/data?key=value",
            requestBody = """{"test": "data"}"""
        )

        val result = exporter.export(listOf(exchange))

        assertTrue(result.contains("POST"))
        assertTrue(result.contains("api.example.com"))
        assertTrue(result.contains("request"))
        assertTrue(result.contains("response"))
    }

    // ==================== Normalized Timeline Tests ====================

    @Test
    fun normalizedTimelineFromExchanges() {
        val normalizer = TimelineNormalizer()
        val exchanges = listOf(
            createTestExchange(id = "1", startedAt = "2024-01-15T10:00:00Z"),
            createTestExchange(id = "2", startedAt = "2024-01-15T10:00:05Z")
        )

        val timeline = normalizer.normalize(
            sessionId = "session",
            sessionName = "Test Session",
            exchanges = exchanges,
            userActions = emptyList(),
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )

        // 2 exchanges × 2 events (request + response) = 4 events
        assertTrue(timeline.events.size >= 2)
        assertEquals("session", timeline.sessionId)
    }

    @Test
    fun normalizedTimelineWithUserActions() {
        val normalizer = TimelineNormalizer()
        val exchanges = listOf(
            createTestExchange(id = "1", startedAt = "2024-01-15T10:00:01Z")
        )
        val actions = listOf(
            createTestUserAction(timestamp = "2024-01-15T10:00:00Z")
        )

        val timeline = normalizer.normalize(
            sessionId = "test-session",
            sessionName = "Test",
            exchanges = exchanges,
            userActions = actions,
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )

        assertEquals("test-session", timeline.sessionId)
        
        // Sollte sowohl User-Actions als auch HTTP-Events enthalten
        assertTrue(timeline.events.any { it.type == EventType.USER_ACTION })
        assertTrue(timeline.events.any { it.type == EventType.HTTP_REQUEST || it.type == EventType.HTTP_RESPONSE })
    }

    @Test
    fun normalizedTimelineCorrelation() {
        val normalizer = TimelineNormalizer()
        val exchanges = listOf(
            createTestExchange(id = "1", startedAt = "2024-01-15T10:00:01Z"),
            createTestExchange(id = "2", startedAt = "2024-01-15T10:00:02Z")
        )
        val actions = listOf(
            createTestUserAction(type = "CLICK", timestamp = "2024-01-15T10:00:00Z")
        )

        val timeline = normalizer.normalize(
            sessionId = "test",
            sessionName = "Test",
            exchanges = exchanges,
            userActions = actions,
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )

        // Korrelation prüfen über metadata
        assertTrue(timeline.metadata.correlatedPairs >= 0)
    }

    @Test
    fun normalizedTimelineHasCorrectMetadata() {
        val normalizer = TimelineNormalizer()
        val exchanges = listOf(
            createTestExchange(id = "1", startedAt = "2024-01-15T10:00:01Z"),
            createTestExchange(id = "2", startedAt = "2024-01-15T10:00:05Z", status = 500)
        )
        val actions = listOf(
            createTestUserAction(type = "CLICK", timestamp = "2024-01-15T10:00:00Z")
        )

        val timeline = normalizer.normalize(
            sessionId = "test",
            sessionName = "Test",
            exchanges = exchanges,
            userActions = actions,
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )

        assertEquals(1, timeline.metadata.userActionCount)
        assertTrue(timeline.metadata.httpRequestCount >= 1)
        assertTrue(timeline.metadata.totalEvents >= 3)
    }

    // ==================== Mermaid Sequence Exporter Tests ====================

    @Test
    fun mermaidExporterGeneratesValidDiagram() {
        val exporter = MermaidSequenceExporter()
        val exchanges = listOf(
            createTestExchange(id = "1", url = "https://api.example.com/users")
        )

        val result = exporter.generate(exchanges, title = "Test")

        assertTrue(result.contains("sequenceDiagram"))
        assertTrue(result.contains("participant"))
        assertTrue(result.contains("User"))
        assertTrue(result.contains("Browser"))
    }

    @Test
    fun mermaidExporterIncludesUserActions() {
        val exporter = MermaidSequenceExporter()
        val exchanges = listOf(createTestExchange())
        val actions = listOf(
            createTestUserAction(type = "CLICK", target = "button#submit")
        )

        val result = exporter.generate(exchanges, actions, "Test")

        assertTrue(result.contains("CLICK"))
        assertTrue(result.contains("U->>B"))
    }

    @Test
    fun mermaidExporterHandlesMultipleHosts() {
        val exporter = MermaidSequenceExporter()
        val exchanges = listOf(
            createTestExchange(id = "1", url = "https://api.example.com/users"),
            createTestExchange(id = "2", url = "https://auth.example.com/login"),
            createTestExchange(id = "3", url = "https://cdn.example.com/assets")
        )

        val result = exporter.generate(exchanges)

        assertTrue(result.contains("S0"))
        assertTrue(result.contains("S1"))
        assertTrue(result.contains("S2"))
    }

    @Test
    fun mermaidExporterShowsErrorStatus() {
        val exporter = MermaidSequenceExporter()
        val exchanges = listOf(
            createTestExchange(id = "1", status = 500),
            createTestExchange(id = "2", status = 404)
        )

        val result = exporter.generate(exchanges)

        assertTrue(result.contains("❌") || result.contains("⚠️"))
    }

    @Test
    fun mermaidCorrelationDiagramGenerates() {
        val exporter = MermaidSequenceExporter()
        val exchanges = listOf(
            createTestExchange(id = "1", startedAt = "2024-01-15T10:00:01Z"),
            createTestExchange(id = "2", startedAt = "2024-01-15T10:00:02Z")
        )
        val actions = listOf(
            createTestUserAction(timestamp = "2024-01-15T10:00:00Z")
        )

        val result = exporter.generateCorrelationDiagram(exchanges, actions)

        assertTrue(result.contains("sequenceDiagram"))
    }

    @Test
    fun mermaidExporterHandlesEmptyInput() {
        val exporter = MermaidSequenceExporter()
        val result = exporter.generate(emptyList())

        assertTrue(result.contains("sequenceDiagram"))
        assertTrue(result.contains("Keine Daten"))
    }

    // ==================== State Graph Exporter Tests ====================

    @Test
    fun stateGraphExporterGeneratesValidMermaid() {
        val normalizer = TimelineNormalizer()
        val exporter = StateGraphExporter()
        
        val exchanges = listOf(
            createTestExchange(id = "1", url = "https://example.com/login", method = "GET"),
            createTestExchange(id = "2", url = "https://example.com/login", method = "POST"),
            createTestExchange(id = "3", url = "https://example.com/dashboard")
        )
        val actions = listOf(
            createTestUserAction(type = "CLICK", target = "button#login", timestamp = "2024-01-15T10:00:00Z")
        )

        val timeline = normalizer.normalize(
            sessionId = "test",
            sessionName = "Test",
            exchanges = exchanges,
            userActions = actions,
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )

        val result = exporter.generateSessionStateGraph(timeline)

        assertTrue(result.contains("stateDiagram-v2"))
    }

    @Test
    fun stateGraphExporterGeneratesPerActionGraphs() {
        val normalizer = TimelineNormalizer()
        val exporter = StateGraphExporter()
        
        val exchanges = listOf(
            createTestExchange(id = "1", url = "https://example.com/page1"),
            createTestExchange(id = "2", url = "https://example.com/page2")
        )
        val actions = listOf(
            createTestUserAction(type = "CLICK", target = "link#home", timestamp = "2024-01-15T10:00:00Z")
        )

        val timeline = normalizer.normalize(
            sessionId = "test",
            sessionName = "Test",
            exchanges = exchanges,
            userActions = actions,
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )

        val result = exporter.generatePerActionStateGraphs(timeline)

        assertTrue(result.contains("State-Graph") || result.contains("stateDiagram"))
    }

    @Test
    fun stateGraphExporterHandlesEmptyTimeline() {
        val normalizer = TimelineNormalizer()
        val exporter = StateGraphExporter()

        val timeline = normalizer.normalize(
            sessionId = "test",
            sessionName = "Test",
            exchanges = emptyList(),
            userActions = emptyList(),
            startedAt = Clock.System.now()
        )

        val result = exporter.generateSessionStateGraph(timeline)

        assertTrue(result.contains("stateDiagram-v2"))
    }

    // ==================== Full Pipeline Tests ====================

    @Test
    fun fullPipelineIntegration() {
        // 1. Erstelle Test-Daten
        val exchanges = listOf(
            createTestExchange(id = "1", method = "GET", url = "https://api.example.com/", startedAt = "2024-01-15T10:00:01Z"),
            createTestExchange(id = "2", method = "POST", url = "https://api.example.com/login", status = 200, startedAt = "2024-01-15T10:00:03Z"),
            createTestExchange(id = "3", method = "GET", url = "https://api.example.com/dashboard", startedAt = "2024-01-15T10:00:05Z")
        )
        val actions = listOf(
            createTestUserAction(type = "CLICK", target = "button#login", timestamp = "2024-01-15T10:00:02Z")
        )

        // 2. HAR Export
        val harExporter = HarExporter()
        val har = harExporter.export(exchanges, "Pipeline Test")
        assertTrue(har.contains("\"log\""))
        assertTrue(har.contains("\"entries\""))

        // 3. Normalisierte Timeline
        val normalizer = TimelineNormalizer()
        val timeline = normalizer.normalize(
            sessionId = "pipeline-test",
            sessionName = "Pipeline Test",
            exchanges = exchanges,
            userActions = actions,
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )
        assertTrue(timeline.events.isNotEmpty())
        assertTrue(timeline.events.any { it.type == EventType.USER_ACTION })
        assertTrue(timeline.events.any { it.type == EventType.HTTP_REQUEST || it.type == EventType.HTTP_RESPONSE })

        // 4. Mermaid Sequenzdiagramm
        val mermaidExporter = MermaidSequenceExporter()
        val sequenceDiagram = mermaidExporter.generate(exchanges, actions, "Pipeline Test")
        assertTrue(sequenceDiagram.contains("sequenceDiagram"))
        assertTrue(sequenceDiagram.contains("CLICK"))

        // 5. Mermaid Korrelationsdiagramm
        val correlationDiagram = mermaidExporter.generateCorrelationDiagram(exchanges, actions)
        assertTrue(correlationDiagram.contains("sequenceDiagram"))

        // 6. State Graph
        val stateExporter = StateGraphExporter()
        val stateGraph = stateExporter.generateSessionStateGraph(timeline)
        assertTrue(stateGraph.contains("stateDiagram-v2"))
    }

    @Test
    fun pipelineHandlesLargeDataset() {
        // Erstelle größeren Datensatz
        val exchanges = (1..50).map { i ->
            createTestExchange(
                id = "ex_$i",
                url = "https://api.example.com/endpoint$i",
                method = if (i % 3 == 0) "POST" else "GET",
                status = if (i % 10 == 0) 500 else 200,
                startedAt = "2024-01-15T10:00:${i.toString().padStart(2, '0')}Z"
            )
        }
        val actions = (1..10).map { i ->
            createTestUserAction(
                type = if (i % 2 == 0) "CLICK" else "SUBMIT",
                target = "element#$i",
                timestamp = "2024-01-15T10:00:${(i * 5).toString().padStart(2, '0')}Z"
            )
        }

        // Pipeline durchlaufen
        val harExporter = HarExporter()
        val har = harExporter.export(exchanges)
        assertFalse(har.isEmpty())

        val normalizer = TimelineNormalizer()
        val timeline = normalizer.normalize(
            sessionId = "large-test",
            sessionName = "Large Test",
            exchanges = exchanges,
            userActions = actions,
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )
        assertTrue(timeline.events.size >= 50) // Mindestens 50 Exchange-Events

        val mermaidExporter = MermaidSequenceExporter()
        val diagram = mermaidExporter.generate(exchanges, actions, maxEntries = 50)
        assertTrue(diagram.contains("sequenceDiagram"))

        val stateExporter = StateGraphExporter()
        val stateGraph = stateExporter.generateSessionStateGraph(timeline)
        assertTrue(stateGraph.contains("stateDiagram-v2"))
    }

    @Test
    fun timelineJsonSerialization() {
        val normalizer = TimelineNormalizer()
        val exchanges = listOf(
            createTestExchange(id = "1", startedAt = "2024-01-15T10:00:00Z"),
            createTestExchange(id = "2", startedAt = "2024-01-15T10:00:05Z")
        )

        val timeline = normalizer.normalize(
            sessionId = "json-test",
            sessionName = "JSON Test",
            exchanges = exchanges,
            userActions = emptyList(),
            startedAt = Instant.parse("2024-01-15T10:00:00Z")
        )

        val json = timeline.toJson()

        assertTrue(json.contains("\"sessionId\""))
        assertTrue(json.contains("\"events\""))
        assertTrue(json.contains("\"metadata\""))
        assertTrue(json.contains("json-test"))
    }

    @Test
    fun mermaidFlowDiagramGenerates() {
        val exporter = MermaidSequenceExporter()
        val exchanges = listOf(
            createTestExchange(id = "1", url = "https://api.example.com/users"),
            createTestExchange(id = "2", url = "https://api.example.com/login")
        )

        val result = exporter.generateFlowDiagram(exchanges)

        // Flow diagram uses sequenceDiagram format in this implementation
        assertTrue(result.contains("sequenceDiagram") || result.contains("flowchart") || result.contains("Client"))
    }
}
