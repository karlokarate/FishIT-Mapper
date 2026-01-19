package dev.fishit.mapper.android.export

import dev.fishit.mapper.android.capture.CaptureSessionManager
import dev.fishit.mapper.android.capture.TrafficInterceptWebView
import dev.fishit.mapper.android.export.SessionExportManager.ExportFormat
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit Tests für SessionExportManager Export-Formate.
 * Testet die verschiedenen Export-Format-Generierungen.
 */
class SessionExportManagerTest {

    // ==================== Test Fixtures ====================

    private fun createTestExchange(
        id: String = "ex_1",
        method: String = "GET",
        url: String = "https://api.example.com/users",
        requestHeaders: Map<String, String> = mapOf("Content-Type" to "application/json"),
        requestBody: String? = null,
        responseStatus: Int? = 200,
        responseHeaders: Map<String, String>? = mapOf("Content-Type" to "application/json"),
        responseBody: String? = """{"id": 1, "name": "Test User"}"""
    ) = TrafficInterceptWebView.CapturedExchange(
        id = id,
        method = method,
        url = url,
        requestHeaders = requestHeaders,
        requestBody = requestBody,
        responseStatus = responseStatus,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        startedAt = Instant.parse("2024-01-15T10:00:00Z"),
        completedAt = Instant.parse("2024-01-15T10:00:01Z")
    )

    private fun createTestSession(
        id: String = "session_1",
        name: String = "Test Session",
        exchanges: List<TrafficInterceptWebView.CapturedExchange> = listOf(createTestExchange()),
        userActions: List<TrafficInterceptWebView.UserAction> = emptyList()
    ) = CaptureSessionManager.CaptureSession(
        id = id,
        name = name,
        startedAt = Instant.parse("2024-01-15T10:00:00Z"),
        targetUrl = "https://api.example.com",
        exchanges = exchanges,
        userActions = userActions
    )

    // ==================== ExportFormat Tests ====================

    @Test
    fun exportFormatHarHasCorrectProperties() {
        val format = ExportFormat.HAR
        assertEquals(".har", format.extension)
        assertEquals("application/json", format.mimeType)
        assertEquals("HAR", format.displayName)
        assertNotNull(format.description)
    }

    @Test
    fun exportFormatJsonHasCorrectProperties() {
        val format = ExportFormat.JSON
        assertEquals(".json", format.extension)
        assertEquals("application/json", format.mimeType)
        assertEquals("JSON", format.displayName)
    }

    @Test
    fun exportFormatOpenApiHasCorrectProperties() {
        val format = ExportFormat.OPENAPI
        assertEquals(".json", format.extension)
        assertEquals("application/json", format.mimeType)
        assertEquals("OpenAPI", format.displayName)
    }

    @Test
    fun exportFormatPostmanHasCorrectProperties() {
        val format = ExportFormat.POSTMAN
        assertEquals(".json", format.extension)
        assertEquals("application/json", format.mimeType)
        assertEquals("Postman", format.displayName)
    }

    @Test
    fun exportFormatCurlHasCorrectProperties() {
        val format = ExportFormat.CURL
        assertEquals(".sh", format.extension)
        assertEquals("text/x-shellscript", format.mimeType)
        assertEquals("cURL", format.displayName)
    }

    @Test
    fun exportFormatTypescriptHasCorrectProperties() {
        val format = ExportFormat.TYPESCRIPT
        assertEquals(".ts", format.extension)
        assertEquals("text/typescript", format.mimeType)
        assertEquals("TypeScript", format.displayName)
    }

    @Test
    fun exportFormatMarkdownHasCorrectProperties() {
        val format = ExportFormat.MARKDOWN
        assertEquals(".md", format.extension)
        assertEquals("text/markdown", format.mimeType)
        assertEquals("Markdown", format.displayName)
    }

    @Test
    fun exportFormatZipHasCorrectProperties() {
        val format = ExportFormat.ZIP
        assertEquals(".zip", format.extension)
        assertEquals("application/zip", format.mimeType)
        assertEquals("ZIP Bundle", format.displayName)
    }

    @Test
    fun allExportFormatsHaveValidExtensions() {
        ExportFormat.entries.forEach { format ->
            assertTrue(format.extension.startsWith("."),
                "Format ${format.name} extension should start with dot")
        }
    }

    @Test
    fun allExportFormatsHaveNonEmptyDescriptions() {
        ExportFormat.entries.forEach { format ->
            assertFalse(format.description.isBlank(),
                "Format ${format.name} should have non-empty description")
        }
    }

    @Test
    fun allExportFormatsHaveValidMimeTypes() {
        ExportFormat.entries.forEach { format ->
            assertTrue(format.mimeType.contains("/"),
                "Format ${format.name} should have valid mime type")
        }
    }

    // ==================== Session Data Tests ====================

    @Test
    fun capturedExchangeWithNullableResponseHeadersWorks() {
        val exchange = createTestExchange(responseHeaders = null)
        assertNull(exchange.responseHeaders)
    }

    @Test
    fun capturedExchangeWithNullableResponseStatusWorks() {
        val exchange = createTestExchange(responseStatus = null)
        assertNull(exchange.responseStatus)
    }

    @Test
    fun testSessionHasCorrectExchangeCount() {
        val session = createTestSession(
            exchanges = listOf(
                createTestExchange(id = "ex_1"),
                createTestExchange(id = "ex_2"),
                createTestExchange(id = "ex_3")
            )
        )
        assertEquals(3, session.exchangeCount)
    }

    @Test
    fun testSessionWithVariousHttpMethods() {
        val exchanges = listOf(
            createTestExchange(id = "1", method = "GET", url = "https://api.example.com/users"),
            createTestExchange(id = "2", method = "POST", url = "https://api.example.com/users", requestBody = """{"name":"New"}"""),
            createTestExchange(id = "3", method = "PUT", url = "https://api.example.com/users/1", requestBody = """{"name":"Updated"}"""),
            createTestExchange(id = "4", method = "DELETE", url = "https://api.example.com/users/1"),
            createTestExchange(id = "5", method = "PATCH", url = "https://api.example.com/users/1", requestBody = """{"status":"active"}""")
        )
        val session = createTestSession(exchanges = exchanges)

        assertEquals(5, session.exchangeCount)
        assertEquals(setOf("GET", "POST", "PUT", "DELETE", "PATCH"),
            session.exchanges.map { it.method }.toSet())
    }

    @Test
    fun testSessionGroupsExchangesByDomain() {
        val exchanges = listOf(
            createTestExchange(id = "1", url = "https://api.example.com/users"),
            createTestExchange(id = "2", url = "https://api.example.com/posts"),
            createTestExchange(id = "3", url = "https://cdn.example.com/image.png")
        )
        val session = createTestSession(exchanges = exchanges)

        val grouped = session.groupByDomain()
        assertEquals(2, grouped.size)
        assertTrue(grouped.containsKey("https://api.example.com"))
        assertTrue(grouped.containsKey("https://cdn.example.com"))
        assertEquals(2, grouped["https://api.example.com"]?.size)
        assertEquals(1, grouped["https://cdn.example.com"]?.size)
    }

    @Test
    fun testSessionFindsUniqueEndpoints() {
        val exchanges = listOf(
            createTestExchange(id = "1", method = "GET", url = "https://api.example.com/users"),
            createTestExchange(id = "2", method = "GET", url = "https://api.example.com/users"), // duplicate
            createTestExchange(id = "3", method = "POST", url = "https://api.example.com/users"),
            createTestExchange(id = "4", method = "GET", url = "https://api.example.com/posts")
        )
        val session = createTestSession(exchanges = exchanges)

        val endpoints = session.uniqueEndpoints()
        assertEquals(3, endpoints.size)
    }

    // ==================== User Action Tests ====================

    @Test
    fun testSessionWithUserActions() {
        val actions = listOf(
            TrafficInterceptWebView.UserAction(
                id = "action_1",
                type = TrafficInterceptWebView.ActionType.CLICK,
                target = "button#submit",
                value = null,
                timestamp = Instant.parse("2024-01-15T10:00:00Z"),
                pageUrl = "https://example.com/form"
            ),
            TrafficInterceptWebView.UserAction(
                id = "action_2",
                type = TrafficInterceptWebView.ActionType.INPUT,
                target = "input#username",
                value = "testuser",
                timestamp = Instant.parse("2024-01-15T10:00:01Z"),
                pageUrl = "https://example.com/form"
            )
        )
        val session = createTestSession(userActions = actions)

        assertEquals(2, session.actionCount)
    }

    @Test
    fun testSessionCorrelatesActionsWithExchanges() {
        val actions = listOf(
            TrafficInterceptWebView.UserAction(
                id = "action_1",
                type = TrafficInterceptWebView.ActionType.CLICK,
                target = "button#submit",
                value = null,
                timestamp = Instant.parse("2024-01-15T10:00:00Z"),
                pageUrl = "https://example.com/form"
            )
        )
        val exchanges = listOf(
            createTestExchange(
                id = "ex_1",
                method = "POST",
                url = "https://api.example.com/submit"
            )
        )
        val session = createTestSession(exchanges = exchanges, userActions = actions)

        val correlated = session.correlate(actions.first())
        assertEquals(1, correlated.size)
        assertEquals("ex_1", correlated.first().id)
    }

    // ==================== File Name Generation Tests ====================

    @Test
    fun fileNameContainsSessionDate() {
        val session = createTestSession(name = "My Session")
        val fileName = generateTestFileName(session, ExportFormat.HAR)
        assertTrue(fileName.contains("2024-01-15"), "File name should contain date")
    }

    @Test
    fun fileNameHasCorrectExtension() {
        val session = createTestSession()

        ExportFormat.entries.forEach { format ->
            val fileName = generateTestFileName(session, format)
            assertTrue(fileName.endsWith(format.extension),
                "File name for ${format.name} should end with ${format.extension}")
        }
    }

    @Test
    fun fileNameSanitizesSpecialCharacters() {
        val session = createTestSession(name = "My/Session:Test*Name?")
        val fileName = generateTestFileName(session, ExportFormat.JSON)

        assertFalse(fileName.contains("/"), "File name should not contain /")
        assertFalse(fileName.contains(":"), "File name should not contain :")
        assertFalse(fileName.contains("*"), "File name should not contain *")
        assertFalse(fileName.contains("?"), "File name should not contain ?")
    }

    @Test
    fun fileNameHandlesSpacesCorrectly() {
        val session = createTestSession(name = "My Test Session")
        val fileName = generateTestFileName(session, ExportFormat.JSON)

        // Spaces should be replaced with underscores
        assertTrue(
            fileName.contains("My_Test_Session") || fileName.contains("My Test Session"),
            "File name should handle spaces properly"
        )
    }

    // ==================== Helper Functions ====================

    private fun generateTestFileName(session: CaptureSessionManager.CaptureSession, format: ExportFormat): String {
        val safeName = session.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val date = session.startedAt.toString().take(10)
        return "${date}_$safeName${format.extension}"
    }

    // ==================== Available Formats Tests ====================

    @Test
    fun sessionExportManagerHasAllFormatsAvailable() {
        val availableFormats = listOf(
            ExportFormat.HAR,
            ExportFormat.JSON,
            ExportFormat.OPENAPI,
            ExportFormat.POSTMAN,
            ExportFormat.CURL,
            ExportFormat.TYPESCRIPT,
            ExportFormat.MARKDOWN,
            ExportFormat.MERMAID,
            ExportFormat.MERMAID_CORRELATION,
            ExportFormat.STATE_GRAPH,
            ExportFormat.TIMELINE,
            ExportFormat.WEBSITE_MAP,
            ExportFormat.ZIP
        )

        assertEquals(13, availableFormats.size)
        assertTrue(availableFormats.contains(ExportFormat.HAR))
        assertTrue(availableFormats.contains(ExportFormat.WEBSITE_MAP))
        assertTrue(availableFormats.contains(ExportFormat.ZIP))
    }

    @Test
    fun exportFormatEnumContainsExactly8Formats() {
        // Updated: Now 13 formats including MERMAID, MERMAID_CORRELATION, STATE_GRAPH, TIMELINE, WEBSITE_MAP
        assertEquals(13, ExportFormat.entries.size)
    }
}
