package me.egigoka.pomodorough.unit.negative

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.api.PomodoroughApi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ExpandedApiNegativeUnitTest {
    @Test
    fun successfulJsonEndpointRejectsEmptyResponse() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(200))
            val api = PomodoroughApi(
                baseUrl = server.url("/api/v1/").toString(),
                client = OkHttpClient(),
                json = Json,
            )

            try {
                api.me("access-token")
                fail("Expected an empty response failure")
            } catch (error: IOException) {
                assertEquals("Server returned an empty response", error.message)
            }
        } finally {
            server.shutdown()
        }
    }
}
