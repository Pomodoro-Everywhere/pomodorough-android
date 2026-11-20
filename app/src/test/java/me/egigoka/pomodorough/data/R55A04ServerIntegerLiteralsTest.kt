package me.egigoka.pomodorough.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.api.PomodoroughApi
import me.egigoka.pomodorough.data.api.ServerIntegerLiterals
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * R55-A04: every integer field of a server response must be a plain JSON
 * integer literal. A quoted number, a fraction, an exponent, -0 and a boolean
 * fail decoding with a SerializationException, the failure a malformed
 * serverHlc* raises today. The bodies are real 0.57 server responses (R55-S09
 * Go handler, plain integer literals only) and go through PomodoroughApi with
 * the application's API decoder.
 */
@OptIn(ExperimentalSerializationApi::class)
class R55A04ServerIntegerLiteralsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PomodoroughApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = PomodoroughApi(
            baseUrl = server.url("/api/v1").toString(),
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun realServerBodiesStillDecode() = runTest {
        enqueue(fixture("sync-start"))
        val started = api.sync("token", EmptySync)
        assertEquals(1L, started.revision)
        assertEquals(1795179131284L to 0L, started.canonicalHead())
        assertEquals(1_500_000L, started.canonicalTimer?.plannedDurationMs)

        enqueue(fixture("sync-finish"))
        val finished = api.sync("token", EmptySync)
        assertEquals(1795179251431L to 0L, finished.serverHlcWallMs to finished.serverHlcCounter)
        assertEquals(60_000L, finished.history.single().elapsedMs)
        assertEquals(360_000L, finished.durationsMs.shortBreak)

        enqueue(fixture("bootstrap"))
        val bootstrap = api.bootstrap("token")
        assertEquals(2L, bootstrap.revision)
        assertEquals(1795179191284L to 0L, bootstrap.canonicalHead())
    }

    @Test
    fun everyIntegerFieldRejectsEveryNonIntegerForm() = runTest {
        val accepted = mutableListOf<String>()
        for (field in IntegerFields) {
            for (form in NonIntegerForms) {
                enqueue(replaced(fixture("sync-finish"), field, form).toString())
                try {
                    api.sync("token", EmptySync)
                    accepted += "${field.joinToString(".")}=$form"
                } catch (_: SerializationException) {
                    // fails closed
                }
            }
        }
        assertEquals("accepted non-integer forms", emptyList<String>(), accepted)
    }

    @Test
    fun theRejectionNamesTheField() = runTest {
        enqueue(replaced(fixture("bootstrap"), listOf("serverHlcWallMs"), "\"1795179251450\"").toString())

        val error = try {
            api.bootstrap("token")
            null
        } catch (error: SerializationException) {
            error
        }
        assertEquals("Server field response.serverHlcWallMs must be a plain JSON integer", error?.message)
    }

    @Test
    fun aLaterHistoryEntryIsCheckedLikeTheFirst() = runTest {
        // Only the second entry is bad, so a check of the first item alone passes it.
        val body = Json.parseToJsonElement(fixture("sync-finish")).jsonObject
        val first = body.getValue("history").jsonArray.single().jsonObject
        val second = JsonObject(first + ("id" to JsonPrimitive("timer-000002")) + ("elapsedMs" to JsonPrimitive("5")))
        enqueue(JsonObject(body + ("history" to JsonArray(listOf(first, second)))).toString())

        val error = try {
            api.sync("token", EmptySync)
            null
        } catch (error: SerializationException) {
            error
        }
        assertEquals("Server field response.history[1].elapsedMs must be a plain JSON integer", error?.message)
    }

    @Test
    fun plainIntegersOutsideTheValidRangeStillDecodeForValidation() = runTest {
        // Range checks stay in TimerSyncValidation with their own messages.
        val negative = replaced(fixture("sync-finish"), listOf("serverHlcCounter"), "-1")
        enqueue(replaced(negative.toString(), listOf("canonicalHlcWallMs"), "9007199254740992").toString())

        val response = api.sync("token", EmptySync)
        assertEquals(-1L, response.serverHlcCounter)
        assertEquals(9_007_199_254_740_992L, response.canonicalHlcWallMs)
    }

    @Test
    fun everyIntegerKindIsChecked() {
        for (descriptor in listOf(Byte.serializer(), Short.serializer(), Int.serializer(), Long.serializer())) {
            assertThrows(SerializationException::class.java) {
                ServerIntegerLiterals.requirePlainIntegers(descriptor.descriptor, JsonPrimitive("5"), "field")
            }
            ServerIntegerLiterals.requirePlainIntegers(descriptor.descriptor, JsonUnquotedLiteral("5"), "field")
        }
    }

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("r55a04-server-$name.json")) { name }
            .bufferedReader().use { it.readText() }

    private fun replaced(body: String, path: List<String>, literal: String): JsonObject =
        replaced(Json.parseToJsonElement(body), path, JsonUnquotedLiteral(literal)).jsonObject

    private fun replaced(element: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        val key = path.firstOrNull() ?: return value
        val index = key.toIntOrNull()
        if (index != null) {
            val items = element.jsonArray.toMutableList()
            items[index] = replaced(items[index], path.drop(1), value)
            return JsonArray(items)
        }
        val fields = element.jsonObject
        if (key !in fields) fail("fixture has no field $key")
        return JsonObject(fields + (key to replaced(fields.getValue(key), path.drop(1), value)))
    }

    private companion object {
        val EmptySync = SyncRequest("device-1", 0, emptyList(), emptyList())
        val IntegerFields = listOf(
            listOf("revision"),
            listOf("serverHlcWallMs"),
            listOf("serverHlcCounter"),
            listOf("canonicalHlcWallMs"),
            listOf("canonicalHlcCounter"),
            listOf("canonicalTimer", "plannedDurationMs"),
            listOf("canonicalTimer", "elapsedAtAnchorMs"),
            listOf("history", "0", "plannedDurationMs"),
            listOf("history", "0", "elapsedMs"),
            listOf("durationsMs", "focus"),
            listOf("durationsMs", "short_break"),
            listOf("durationsMs", "long_break"),
        )

        // Raw JSON tokens put in place of the plain integer.
        val NonIntegerForms = listOf("\"5\"", "5.0", "1e0", "1E3", "1.5", "-0", "true", "\"-0\"")
    }
}
