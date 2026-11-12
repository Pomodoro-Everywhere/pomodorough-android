package me.egigoka.pomodorough.data

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.iroh.protocol.IrohJson
import me.egigoka.pomodorough.data.local.BootstrapDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.TimerWorkspaceDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * UX round 2, batch C: a history row records `elapsedMs`, the time the session
 * really ran. Stored rows with and without the field load through the strict
 * local decoder, and writing them back adds no defaulted value. The sync
 * validator accepts 0 through the plan and fails closed outside it.
 *
 * The rows are decoded from JSON so that the test names no new model field.
 */
class HistoryElapsedStorageTest {
    @Test
    fun storedRowsWithAndWithoutElapsedLoadAndWriteBackUnchanged() = runBlocking {
        val raw = "[${row("early", elapsed = "1200000")},${row("legacy", elapsed = null)}]"
        val local = LocalStateEntity(
            deviceId = "device",
            settingsJson = json.encodeToString(TimerSettings()),
            historyJson = raw,
        )

        val history = initializer(local).load().decoded.history

        assertEquals(listOf("early", "legacy"), history.map(HistoryItem::id))
        assertEquals(raw, json.encodeToString(history))
    }

    @Test
    fun roomProjectionEncodingKeepsElapsedAndWritesNoDefault() {
        val raw = "[${row("early", elapsed = "1200000")},${row("legacy", elapsed = null)}]"

        val history = IrohJson.strict.decodeFromString<List<HistoryItem>>(raw)
        val encoded = IrohJson.strict.encodeToString(history)

        val rows = Json.parseToJsonElement(encoded).jsonArray.map { it.jsonObject }
        assertEquals(listOf(JsonPrimitive(1_200_000L), null), rows.map { it["elapsedMs"] })
        assertEquals(history, IrohJson.strict.decodeFromString<List<HistoryItem>>(encoded))
    }

    @Test
    fun syncValidationAcceptsElapsedFromZeroThroughThePlan() {
        listOf("0", "1200000", "1500000", null).forEach { elapsed ->
            TimerSyncValidation.validateCanonicalResponse(response(elapsed), "sync")
        }
    }

    @Test
    fun syncValidationFailsClosedOnElapsedOutsideThePlan() {
        listOf("-1", "1500001").forEach { elapsed ->
            val error = assertThrows(SyncProtocolException::class.java) {
                TimerSyncValidation.validateCanonicalResponse(response(elapsed), "sync")
            }
            assertEquals(elapsed, "sync returned an invalid history elapsed time", error.message)
        }
    }

    private fun response(elapsed: String?) = SyncResponse(
        acknowledgements = emptyList(),
        revision = 1L,
        canonicalTimer = null,
        history = listOf(strictJson.decodeFromString(row("history-1", elapsed))),
        serverTime = At,
        serverHlcWallMs = 1_767_225_600_000L,
        serverHlcCounter = 1L,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
    )

    private fun row(id: String, elapsed: String?): String {
        val field = elapsed?.let { ",\"elapsedMs\":$it" }.orEmpty()
        return "{\"id\":\"$id\",\"timerId\":\"timer-$id\",\"commandId\":\"finish-$id\"," +
            "\"phase\":\"focus\",\"status\":\"completed\",\"plannedDurationMs\":1500000," +
            "\"completedAt\":\"$At\",\"endedAt\":\"$At\"$field}"
    }

    private fun initializer(local: LocalStateEntity) = TimerLocalInitializer(
        workspace = proxy(TimerWorkspaceDao::class.java, workspaceValues(local)),
        bootstrap = proxy(BootstrapDao::class.java, workspaceValues(local)),
        json = json,
        strictJson = strictJson,
        validateUser = {},
    )

    private fun workspaceValues(local: LocalStateEntity): Map<String, Any?> = mapOf(
        "localState" to local,
        "pendingCommands" to emptyList<Any>(),
        "pendingTaskOperations" to emptyList<Any>(),
        "pendingDurationOperations" to emptyList<Any>(),
        "pendingAutoStartOperations" to emptyList<Any>(),
        "pendingSelectedTaskOperations" to emptyList<Any>(),
        "pendingCommandsCapped" to emptyList<Any>(),
        "pendingTaskOperationsCapped" to emptyList<Any>(),
        "pendingDurationOperationsCapped" to emptyList<Any>(),
        "pendingAutoStartOperationsCapped" to emptyList<Any>(),
        "pendingSelectedTaskOperationsCapped" to emptyList<Any>(),
        "pendingBootstrapResolution" to null,
    )

    private companion object {
        const val At = "2026-01-01T00:25:00Z"

        // The same configuration as the application JSON and TimerRepository.strictJson.
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val strictJson = Json(json) { ignoreUnknownKeys = false }

        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(type: Class<T>, values: Map<String, Any?>): T = Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, _ -> values[method.name] } as T
    }
}
