package me.egigoka.pomodorough.data.storage

import java.io.File
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CoreNeverSentProof
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.PendingSyncQueues
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.retiredFor
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.RecordingCentralizedSyncDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmutablePersistenceTest {
    private val json = Json { explicitNulls = false }
    private val strictJson = Json { ignoreUnknownKeys = false }

    @Test
    fun newEntitiesCarryNeverSentProof() {
        val entity = PendingCommandEntity.from(command("command-1"), neverSent = true)
        assertTrue(entity.neverSent)
        assertEquals("command-1", entity.toModel().id)
    }

    @Test
    fun legacyEntitiesDefaultToPossiblyDelivered() {
        val entity = PendingCommandEntity(
            id = "command-legacy",
            deviceSequence = 1,
            timerId = "timer-1",
            type = CommandType.Start,
            phase = TimerPhase.Focus,
            plannedDurationMs = 1_500_000,
            occurredAt = At,
            hlcWallMs = WallMs,
            hlcCounter = 0,
            observedElapsedMs = 0,
        )
        assertFalse(entity.neverSent)
    }

    @Test
    fun fullSyncRetainsProofForRetained() {
        val dao = RecordingCentralizedSyncDao()
        val store = TimerStore(dao, json, strictJson) { }
        val retained = command("retained-1")
        val update = FullSyncStorageUpdate(
            local = LocalStateEntity(deviceId = "device-1", settingsJson = "{}"),
            acknowledged = SyncRequest("device-1", 7, listOf(command("acked-1")), emptyList()),
            acknowledgedDurationOperationIds = emptyList(),
            retained = PendingSyncQueues(listOf(retained), emptyList(), emptyList(), emptyList(), emptyList()),
            retainedCommandDependencies = emptyMap(),
            discardedCommands = emptyList(),
            discardedCommandDependencies = emptyMap(),
            retainedNeverSent = CoreNeverSentProof(commands = listOf(retained.id)),
        )
        kotlinx.coroutines.runBlocking { store.applyFullSync(update) }
        val updated = dao.calls.firstOrNull { it.name == "updateCommands" }
        assertTrue(updated != null)
        @Suppress("UNCHECKED_CAST")
        val entities = updated!!.arguments.single() as List<PendingCommandEntity>
        assertEquals(listOf(retained.id), entities.map { it.id })
        assertTrue(entities.single().neverSent)
    }

    @Test
    fun retireClearsProofForSentIds() {
        val dao = RecordingCentralizedSyncDao()
        val store = TimerStore(dao, json, strictJson) { }
        val request = SyncRequest("device-1", 7, listOf(command("command-1")), emptyList())
        kotlinx.coroutines.runBlocking { store.retireNeverSent(request) }
        assertTrue(dao.calls.any { it.name == "clearCommandNeverSent" })
    }

    @Test
    fun retireIsIdempotentAcrossRetry() {
        val dao = RecordingCentralizedSyncDao()
        val store = TimerStore(dao, json, strictJson) { }
        val request = SyncRequest("device-1", 7, listOf(command("command-1")), emptyList())
        kotlinx.coroutines.runBlocking {
            store.retireNeverSent(request)
            store.retireNeverSent(request)
        }
        assertEquals(2, dao.calls.count { it.name == "clearCommandNeverSent" })
        val proof = CoreNeverSentProof(commands = listOf("command-1"))
        assertEquals(CoreNeverSentProof(), proof.retiredFor(request).retiredFor(request))
    }

    @Test
    fun retireRunsInsideSingleTransaction() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/local/CentralizedSyncDao.kt",
        ).readText()
        val start = source.indexOf("fun retireNeverSent(")
        assertTrue(start >= 0)
        assertTrue(source.drop((start - 200).coerceAtLeast(0)).take(200).contains("@Transaction"))
    }

    @Test
    fun prepareSyncRetirePropagatesCancellation() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
        val start = source.indexOf("timerStore.retireNeverSent(")
        assertTrue(start >= 0)
        val window = source.drop((start - 600).coerceAtLeast(0)).take(900)
        assertTrue(window.contains("CancellationException"))
        assertTrue(window.contains("throw error"))
    }

    @Test
    fun selectedTaskExplicitNullPersists() {
        val entity = PendingSelectedTaskOperationEntity.from(
            SelectedTaskOperation("selected-1", null, At, WallMs, 0),
            neverSent = true,
        )
        assertEquals(null, entity.toModel().taskId)
        assertTrue(entity.neverSent)
    }

    private fun command(id: String) = TimerCommand(
        id = id,
        deviceSequence = 1,
        timerId = "timer-1",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = At,
        hlcWallMs = WallMs,
        hlcCounter = 0,
        observedElapsedMs = 0,
    )

    private companion object {
        const val At = "2026-01-01T00:00:00Z"
        const val WallMs = 1_767_225_600_000L
    }
}
