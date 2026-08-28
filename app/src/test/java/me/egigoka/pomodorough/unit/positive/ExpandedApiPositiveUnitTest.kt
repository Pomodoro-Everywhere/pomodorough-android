package me.egigoka.pomodorough.unit.positive

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.api.PomodoroughApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpandedApiPositiveUnitTest {
    @Test
    fun logoutUsesAuthenticatedEmptyPostAndAcceptsNoContent() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(204))
            val api = PomodoroughApi(
                baseUrl = server.url("/api/v1").toString(),
                client = OkHttpClient(),
                json = Json,
            )

            api.logout("logout-access")
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/api/v1/auth/logout", request.path)
            assertEquals("Bearer logout-access", request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
            assertNull(request.getHeader("Content-Type"))
        } finally {
            server.shutdown()
        }
    }
}
