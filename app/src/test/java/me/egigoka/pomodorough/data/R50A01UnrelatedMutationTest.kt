package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R50-A01: unrelated local mutations must not replay a duration that safe
 * projection excluded. Retry queues stay complete; mutation planning
 * projects safe retained inputs plus the new operation only.
 */
class R50A01UnrelatedMutationTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val projection = CoreProjectionDispatcher { operation, input -> core.dispatch(operation, input) }
    private val coordinator by lazy {
        val dispatch = { operation: String, input: String -> core.dispatch(operation, input) }
        TimerMutationCoordinator(
            json = json,
            projectionDispatcher = projection,
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            zoneId = ZoneId.of("UTC"),
        )
    }

    @Test
    fun unrelatedAutoStartKeepsSafe25WhileRetainingUnsafe30() {
        val unsafe = unsafeDuration()
        val state = stateWithUnsafeDuration(unsafe, CoreNeverSentProof())
        val reservation = reservation(
            counters = listOf(0L),
            ids = listOf("00000000-0000-7000-8000-000000000101"),
            lastUuidV7 = "00000000-0000-7000-8000-000000000101",
            occurredAt = NewAt,
        )

        val transition = coordinator.autoStart(AutoStartMutationInput(state, true, reservation))
        val plan = (transition as TimerMutationTransition.Planned).plan

        assertEquals(Focus25, plan.projection.durationsMs.focus)
        assertEquals(true, plan.projection.autoStartBreaks)
        assertEquals(Focus25, plan.settings.durationMsFor(TimerPhase.Focus))
        val expected = projection.apply(
            base = CoreProjectionBase(durationsMs = DurationsMs()),
            pending = CoreProjectionPending(
                commands = emptyList(),
                taskOperations = emptyList(),
                durationOperations = emptyList(),
                autoStartOperations = listOf(DeviceOperation(plan.operation.deviceId, plan.operation)),
                selectedTaskOperations = emptyList(),
            ),
            now = Instant.parse(NewAt),
        )
        assertEquals(expected, plan.projection)
        assertEquals(listOf(plan.operation.id), plan.operations.map(AutoStartOperation::id))
        val stored = PendingSyncQueues(
            emptyList(), emptyList(), listOf(unsafe), plan.operations, emptyList(),
        )
        assertEquals(listOf(unsafe.id), stored.durationOperations.map(DurationOperation::id))
        assertEquals(listOf(plan.operation.id), stored.autoStartOperations.map(AutoStartOperation::id))
    }

    @Test
    fun rawFullQueuesReplayUnsafe30() {
        val unsafe = unsafeDuration()
        val fresh = freshAutoStart()
        val full = PendingSyncQueues(
            emptyList(), emptyList(), listOf(unsafe), listOf(fresh), emptyList(),
        )
        val projected = projection.apply(
            base = CoreProjectionBase(durationsMs = DurationsMs()),
            pending = CoreProjectionPending(
                commands = emptyList(),
                taskOperations = emptyList(),
                durationOperations = full.durationOperations.map { DeviceOperation(DeviceId, it) },
                autoStartOperations = full.autoStartOperations.map { DeviceOperation(it.deviceId, it) },
                selectedTaskOperations = emptyList(),
            ),
            now = Instant.parse(ServerTime),
        )
        assertEquals(Focus30, projected.durationsMs.focus)
    }

    @Test
    fun reloadFromStoredInputsStaysSafe25() {
        val unsafe = unsafeDuration()
        val fresh = freshAutoStart()
        val stored = PendingSyncQueues(
            emptyList(), emptyList(), listOf(unsafe), listOf(fresh), emptyList(),
        )
        assertEquals(listOf(unsafe), stored.durationOperations)
        assertEquals(listOf(fresh), stored.autoStartOperations)
        val proof = CoreNeverSentProof(autoStartOperations = listOf(fresh.id))
        val safe = SafeProjectionPolicy.safeQueues(stored, proof, Head)
        assertTrue(safe.durationOperations.isEmpty())
        assertEquals(listOf(fresh), safe.autoStartOperations)
        val projected = projection.apply(
            base = CoreProjectionBase(durationsMs = DurationsMs()),
            pending = CoreProjectionPending(
                commands = emptyList(),
                taskOperations = emptyList(),
                durationOperations = safe.durationOperations.map { DeviceOperation(DeviceId, it) },
                autoStartOperations = safe.autoStartOperations.map { DeviceOperation(it.deviceId, it) },
                selectedTaskOperations = emptyList(),
            ),
            now = Instant.parse(ServerTime),
        )
        assertEquals(Focus25, projected.durationsMs.focus)
        assertEquals(true, projected.autoStartBreaks)
    }

    private fun stateWithUnsafeDuration(
        unsafe: DurationOperation,
        proof: CoreNeverSentProof,
    ): TimerMutationState {
        val settings = TimerSettings()
        return TimerMutationState(
            local = LocalStateEntity(
                deviceId = DeviceId,
                deviceSequence = 9,
                hlcWallMs = OpWallMs,
                hlcCounter = 0L,
                revision = 3L,
                settingsJson = json.encodeToString(settings),
                safeBaseDurationsJson = json.encodeToString(DurationsMs()),
                safeCanonicalHeadWallMs = Head.first,
                safeCanonicalHeadCounter = Head.second,
            ),
            settings = settings,
            projection = TimerProjection(null, emptyList()),
            projectionBase = CoreProjectionBase(durationsMs = DurationsMs()),
            queues = PendingSyncQueues(
                emptyList(), emptyList(), listOf(unsafe), emptyList(), emptyList(),
            ),
            neverSentProof = proof,
            dependencies = emptyMap(),
            knownTasks = emptyMap(),
            visibleTasks = emptyList(),
            selectedTaskId = null,
        )
    }

    private fun unsafeDuration() = DurationOperation(
        id = "duration-unsafe-30",
        phase = TimerPhase.Focus,
        durationMs = Focus30,
        occurredAt = "2026-07-20T00:04:00Z",
        hlcWallMs = OpWallMs,
        hlcCounter = 0L,
    )

    private fun freshAutoStart() = AutoStartOperation(
        id = "auto-unrelated-1",
        deviceId = DeviceId,
        enabled = true,
        occurredAt = NewAt,
        hlcWallMs = NewWallMs,
        hlcCounter = 0L,
    )

    private fun reservation(
        counters: List<Long>,
        ids: List<String>,
        lastUuidV7: String,
        occurredAt: String,
    ): TimerMutationReservation {
        val wallMs = Instant.parse(occurredAt).toEpochMilli()
        return TimerMutationReservation(
            stamps = counters.map { counter ->
                SyncWireBounds.MutationStamp(
                    deviceSequence = null,
                    wallMs = wallMs,
                    counter = counter,
                    occurredAt = occurredAt,
                )
            },
            uuids = ids.map(UUID::fromString),
            lastUuidV7 = lastUuidV7,
        )
    }

    private companion object {
        const val DeviceId = "device-1"
        const val ServerTime = "2026-07-20T00:08:00Z"
        const val NewAt = "2026-07-20T00:07:00Z"
        const val NewWallMs = 1_784_506_020_000L
        const val OpWallMs = 1_784_505_840_000L
        const val Focus25 = 1_500_000L
        const val Focus30 = 1_800_000L
        val Head = 1_784_505_960_000L to 0L
    }
}
