package dev.fishit.mapper.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MapperNativeHttpRequestTest {
    @Test
    fun `resolvedUrl appends query parameters deterministically`() {
        val request = MapperNativeHttpRequest(
            url = "https://example.org/graphql?persisted=1",
            queryParams = listOf(
                "operationName" to "GetClusterList",
                "page" to "1",
            ),
        )

        assertEquals(
            "https://example.org/graphql?persisted=1&operationName=GetClusterList&page=1",
            request.resolvedUrl(),
        )
    }
}
