package me.egigoka.pomodorough.data

import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43-A03: unsafe retained queue replay.
 * Canonical focus stays 25 after Core excludes the unsafe 30-minute op.
 * Safe inputs persist apart from optimistic state; rebuilds recompute.
 */
class R43A03SafeProjectionTest {
    private val json = Json { explicitNulls = false }
    private val strictJson = Json { ignoreUnknownKeys = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatch by lazy {
        { operation: String, input: String -> core.dispatch(operation, input) }
    }
    private val projection = CoreProjectionDispatcher(dispatch)
    private val coordinator = CentralizedSyncCoordinator(
        json = json,
        bootstrapDispatcher = CoreBootstrapDispatcher(dispatch),
        reconciliationDispatcher = CoreReconciliationDispatcher(dispatch, projection),
        projectionDispatcher = projection,
        completionDispatcher = CoreCompletionDispatcher(dispatch),
        batchPlanner = CoreBatchPlanDispatcher(dispatch),
        zoneId = ZoneId.of("UTC"),
    )

    @Test
    fun unsafeDurationIsExcludedFromSafeQueues() {
        val queues = PendingSyncQueues(
            emptyList(), emptyList(), listOf(unsafeDuration()), emptyList(), emptyList(),
        )
        val safe = SafeProjectionPolicy.safeQueues(queues, CoreNeverSentProof(), Head)
        assertTrue(safe.durationOperations.isEmpty())
        assertEquals(queues.commands, safe.commands)
    }

    @Test
    fun safeRebuildKeepsCanonical25WhileRetainedReplays30() {
        val installed = installUnsafeDuration()
        assertEquals(Focus25, installed.projected.projection.durationsMs.focus)
        val retained = projectWith(installed.pending.queues, Canonical25)
        assertEquals(Focus30, retained.durationsMs.focus)
        val safe = projectWith(installed.pending.projectionQueues, Canonical25)
        assertEquals(Focus25, safe.durationsMs.focus)
    }

    @Test
    fun roomReopenRecomputesSafe25FromPersistedInputs() {
        val installed = installUnsafeDuration()
        val storedLocal = installed.local
        val storedQueues = installed.pending.queues
        assertEquals(Focus25, persistedBaseFocus(storedLocal))
        assertEquals(Head, SafeProjectionPolicy.headOrNull(storedLocal))
        val reopened = rebuildFromStored(storedLocal, storedQueues, CoreNeverSentProof())
        assertEquals(Focus25, reopened.durationsMs.focus)
        assertTrue(installed.pending.projectionQueues.durationOperations.isEmpty())
    }

    @Test
    fun unrelatedMutationKeepsCanonical25AndAppliesSafeDomain() {
        val installed = installUnsafeDuration()
        val unrelated = AutoStartOperation(
            id = "auto-unrelated-1",
            deviceId = DeviceId,
            enabled = true,
            occurredAt = "2026-07-20T00:07:00Z",
            hlcWallMs = 1_784_506_020_000L,
            hlcCounter = 0L,
        )
        val queues = installed.pending.queues.copy(
            autoStartOperations = listOf(unrelated),
        )
        val proof = CoreNeverSentProof(autoStartOperations = listOf(unrelated.id))
        val safe = SafeProjectionPolicy.safeQueues(queues, proof, Head)
        assertTrue(safe.durationOperations.isEmpty())
        assertEquals(listOf(unrelated), safe.autoStartOperations)
        val projected = projectWith(safe, Canonical25)
        assertEquals(Focus25, projected.durationsMs.focus)
        assertEquals(true, projected.autoStartBreaks)
    }

    private fun installUnsafeDuration(): CentralizedSyncApplication {
        val queues = PendingSyncQueues(
            emptyList(), emptyList(), listOf(unsafeDuration()), emptyList(), emptyList(),
        )
        val snapshot = CentralizedSyncSnapshot(
            local = LocalStateEntity(
                deviceId = DeviceId,
                deviceSequence = 9,
                hlcWallMs = OpWallMs,
                hlcCounter = 0L,
                revision = 3L,
                settingsJson = "{}",
            ),
            queues = queues,
            neverSent = CoreNeverSentProof(),
            dependencies = emptyMap(),
            canonicalTimer = null,
            canonicalHistory = emptyList(),
            canonicalTasks = emptyList(),
            canonicalAutoStartBreaks = false,
            knownTasks = emptyMap(),
            settings = TimerSettings(),
            selectedPhaseGeneration = 1,
        )
        return coordinator.applyBootstrapInstallation(
            CentralizedBootstrapInstallationInput(
                snapshot = snapshot,
                profile = User("user-1", "user@example.com", "User", ""),
                response = canonicalResponse(),
                clearLocal = false,
                sampledLocal = snapshot.local,
                localizedTimer = null,
                localizedHistory = emptyList(),
                projectionNow = java.time.Instant.parse(ServerTime),
            ),
        )
    }

    private fun rebuildFromStored(
        storedLocal: LocalStateEntity,
        storedQueues: PendingSyncQueues,
        proof: CoreNeverSentProof,
    ): CoreProjectionResult {
        val head = SafeProjectionPolicy.headOrNull(storedLocal)
        val safe = SafeProjectionPolicy.safeQueues(storedQueues, proof, head)
        val base = CoreProjectionBase(
            durationsMs = persistedBaseFocusDurations(storedLocal),
            autoStartBreaks = false,
            selectedTaskId = storedLocal.safeBaseSelectedTaskId,
        )
        return projectWith(safe, base)
    }

    private fun projectWith(
        queues: PendingSyncQueues,
        base: CoreProjectionBase,
    ): CoreProjectionResult = projection.apply(
        base = base,
        pending = CoreProjectionPending(
            commands = queues.commands.map { DeviceOperation(DeviceId, it) },
            taskOperations = queues.taskOperations.map { DeviceOperation(DeviceId, it) },
            durationOperations = queues.durationOperations.map { DeviceOperation(DeviceId, it) },
            autoStartOperations = queues.autoStartOperations.map { DeviceOperation(it.deviceId, it) },
            selectedTaskOperations = queues.selectedTaskOperations.map {
                DeviceOperation(DeviceId, it)
            },
        ),
        now = java.time.Instant.parse(ServerTime),
    )

    private fun persistedBaseFocus(local: LocalStateEntity): Long =
        persistedBaseFocusDurations(local).focus

    private fun persistedBaseFocusDurations(local: LocalStateEntity): DurationsMs =
        strictJson.decodeFromString(checkNotNull(local.safeBaseDurationsJson))

    private fun unsafeDuration() = DurationOperation(
        id = "duration-unsafe-30",
        phase = TimerPhase.Focus,
        durationMs = Focus30,
        occurredAt = "2026-07-20T00:04:00Z",
        hlcWallMs = OpWallMs,
        hlcCounter = 0L,
    )

    private fun canonicalResponse() = SyncResponse(
        acknowledgements = emptyList(),
        revision = 7L,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = ServerTime,
        serverHlcWallMs = Head.first,
        serverHlcCounter = Head.second,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartBreaks = false,
    )

    private companion object {
        const val DeviceId = "device-1"
        const val ServerTime = "2026-07-20T00:06:00Z"
        const val OpWallMs = 1_784_505_840_000L
        const val Focus25 = 1_500_000L
        const val Focus30 = 1_800_000L
        val Head = 1_784_505_960_000L to 0L
        val Canonical25 = CoreProjectionBase(durationsMs = DurationsMs())
    }
}
