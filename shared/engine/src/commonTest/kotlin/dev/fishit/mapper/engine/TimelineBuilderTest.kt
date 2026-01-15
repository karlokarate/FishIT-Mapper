package dev.fishit.mapper.engine

import dev.fishit.mapper.contract.*
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineBuilderTest {
    
    @Test
    fun testBuildBasicTimeline() {
        // Create a simple session with navigation events
        val now = Clock.System.now()
        val session = RecordingSession(
            id = SessionId("test-session"),
            projectId = ProjectId("test-project"),
            startedAt = now,
            endedAt = null,
            initialUrl = "https://example.com",
            events = listOf(
                NavigationEvent(
                    id = EventId("nav1"),
                    at = now,
                    url = "https://example.com",
                    fromUrl = null,
                    title = "Example",
                    isRedirect = false
                ),
                ResourceRequestEvent(
                    id = EventId("req1"),
                    at = now,
                    url = "https://example.com/api/data",
                    initiatorUrl = "https://example.com",
                    method = "GET",
                    resourceKind = ResourceKind.Fetch
                )
            )
        )
        
        val graph = MapGraph(
            nodes = listOf(
                MapNode(
                    id = NodeId("node1"),
                    kind = NodeKind.Page,
                    url = "https://example.com",
                    title = "Example"
                )
            ),
            edges = emptyList()
        )
        
        // Build timeline
        val timeline = TimelineBuilder.buildTimeline(session, graph)
        
        // Verify timeline structure
        assertEquals(session.id, timeline.sessionId)
        assertEquals(2, timeline.entries.size)
        
        // Verify first entry (navigation) has no parent
        val firstEntry = timeline.entries[0]
        assertEquals(0, firstEntry.depth)
        assertEquals(null, firstEntry.parentEventId)
        
        // Verify second entry (request) has navigation as parent
        // After navigation is added to stack, request depth is navigationStack.size + 1 = 1 + 1 = 2
        val secondEntry = timeline.entries[1]
        assertEquals(2, secondEntry.depth)
        assertNotNull(secondEntry.parentEventId)
    }
    
    @Test
    fun testEventCorrelation() {
        val now = Clock.System.now()
        val reqId = EventId("req1")
        
        val session = RecordingSession(
            id = SessionId("test-session"),
            projectId = ProjectId("test-project"),
            startedAt = now,
            endedAt = null,
            initialUrl = "https://example.com",
            events = listOf(
                ResourceRequestEvent(
                    id = reqId,
                    at = now,
                    url = "https://example.com/api/data",
                    initiatorUrl = null,
                    method = "GET",
                    resourceKind = ResourceKind.Fetch
                ),
                ResourceResponseEvent(
                    id = EventId("res1"),
                    at = now,
                    requestId = reqId,
                    url = "https://example.com/api/data",
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = emptyMap(),
                    contentType = "application/json",
                    contentLength = null,
                    body = null,
                    bodyTruncated = false,
                    responseTimeMs = 100,
                    isRedirect = false,
                    redirectLocation = null
                )
            )
        )
        
        val timeline = TimelineBuilder.buildTimeline(session, MapGraph())
        
        // Verify response is correlated with request
        val responseEntry = timeline.entries.find { it.event.id.value == "res1" }
        assertNotNull(responseEntry)
        assertEquals(reqId, responseEntry.correlatedEventId)
    }
    
    @Test
    fun testTreeStructure() {
        val now = Clock.System.now()
        
        val session = RecordingSession(
            id = SessionId("test-session"),
            projectId = ProjectId("test-project"),
            startedAt = now,
            endedAt = null,
            initialUrl = "https://example.com",
            events = listOf(
                NavigationEvent(
                    id = EventId("nav1"),
                    at = now,
                    url = "https://example.com",
                    fromUrl = null,
                    title = "Home",
                    isRedirect = false
                ),
                NavigationEvent(
                    id = EventId("nav2"),
                    at = now,
                    url = "https://example.com/page1",
                    fromUrl = "https://example.com",
                    title = "Page 1",
                    isRedirect = false
                )
            )
        )
        
        val graph = MapGraph(
            nodes = listOf(
                MapNode(
                    id = NodeId("node1"),
                    kind = NodeKind.Page,
                    url = "https://example.com",
                    title = "Home"
                ),
                MapNode(
                    id = NodeId("node2"),
                    kind = NodeKind.Page,
                    url = "https://example.com/page1",
                    title = "Page 1"
                )
            ),
            edges = listOf(
                MapEdge(
                    id = EdgeId("edge1"),
                    kind = EdgeKind.Link,
                    from = NodeId("node1"),
                    to = NodeId("node2")
                )
            )
        )
        
        val timeline = TimelineBuilder.buildTimeline(session, graph)
        
        // Verify tree structure
        assertTrue(timeline.treeNodes.isNotEmpty())
        
        // Find root node
        val rootNode = timeline.treeNodes.find { it.depth == 0 }
        assertNotNull(rootNode)
        assertEquals(null, rootNode.parentNodeId)
        
        // Find child node
        val childNode = timeline.treeNodes.find { it.depth == 1 }
        assertNotNull(childNode)
        assertNotNull(childNode.parentNodeId)
    }
}
