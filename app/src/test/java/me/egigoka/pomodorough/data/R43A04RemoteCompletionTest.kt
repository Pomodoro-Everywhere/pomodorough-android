package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.local.LocalStateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R43-A04: remote completion phase advancement.
 * Receiving another device's completed timer with no local Finish must advance
 * selected phase once through Core, preserving explicit choices.
 */
class R43A04RemoteCompletionTest {
    private val json = Json { explicitNulls = false }
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")),
        )
    }
    private val dispatch by lazy {
        { operation: String, input: String -> core.dispatch(operation, input) }
    }
    private val coordinator by lazy {
        val projection = CoreProjectionDispatcher(dispatch)
        CentralizedSyncCoordinator(
            json = json,
            bootstrapDispatcher = CoreBootstrapDispatcher(dispatch),
            reconciliationDispatcher = CoreReconciliationDispatcher(dispatch, projection),
            projectionDispatcher = projection,
            completionDispatcher = CoreCompletionDispatcher(dispatch),
            batchPlanner = CoreBatchPlanDispatcher(dispatch),
            zoneId = ZoneId.of("UTC"),
        )
    }

    @Test
    fun remoteFocusAdvancesToShortBreak() {
        val snapshot = remoteSnapshot(
            selectedPhase = TimerPhase.Focus,
            canonicalHistory = emptyList(),
        )
        val attempt = attempt(snapshot)
        val response = response(
            history = listOf(
                completed("history-remote-1", "remote-focus-1", "remote-finish-1", TimerPhase.Focus, RemoteTime),
            ),
        )

        val result = coordinator.applySync(applicationInput(snapshot, attempt, response))

        assertEquals(TimerPhase.ShortBreak, result.projected.settings.selectedPhase)
    }

    @Test
    fun remoteFourthFocusAdvancesToLongBreak() {
        val snapshot = remoteSnapshot(
            selectedPhase = TimerPhase.Focus,
            canonicalHistory = listOf(
                completed("history-one", "focus-one", "finish-one", TimerPhase.Focus, "2026-07-20T00:01:00Z"),
                completed("history-two", "focus-two", "finish-two", TimerPhase.Focus, "2026-07-20T00:02:00Z"),
                completed("history-three", "focus-three", "finish-three", TimerPhase.Focus, "2026-07-20T00:03:00Z"),
            ),
        )
        val attempt = attempt(snapshot)
        val response = response(
            history = snapshot.canonicalHistory + listOf(
                completed("history-four", "focus-four", "remote-finish-four", TimerPhase.Focus, RemoteTime),
            ),
        )

        val result = coordinator.applySync(applicationInput(snapshot, attempt, response))

        assertEquals(TimerPhase.LongBreak, result.projected.settings.selectedPhase)
    }

    @Test
    fun remoteBreakAdvancesToFocus() {
        val snapshot = remoteSnapshot(
            selectedPhase = TimerPhase.ShortBreak,
            canonicalHistory = emptyList(),
        )
        val attempt = attempt(snapshot)
        val response = response(
            history = listOf(
                completed("history-break-1", "remote-break-1", "remote-finish-break-1", TimerPhase.ShortBreak, RemoteTime),
            ),
        )

        val result = coordinator.applySync(applicationInput(snapshot, attempt, response))

        assertEquals(TimerPhase.Focus, result.projected.settings.selectedPhase)
    }

    @Test
    fun repeatedIdenticalSyncStaysStable() {
        val snapshot = remoteSnapshot(
            selectedPhase = TimerPhase.Focus,
            canonicalHistory = emptyList(),
        )
        val attempt = attempt(snapshot)
        val response = response(
            history = listOf(
                completed("history-remote-1", "remote-focus-1", "remote-finish-1", TimerPhase.Focus, RemoteTime),
            ),
        )

        val first = coordinator.applySync(applicationInput(snapshot, attempt, response))
        assertEquals(TimerPhase.ShortBreak, first.projected.settings.selectedPhase)

        val updated = snapshot.copy(
            canonicalHistory = first.canonical.history,
            canonicalTimer = first.canonical.timer,
            settings = first.projected.settings,
        )
        val secondAttempt = attempt(updated)
        val second = coordinator.applySync(applicationInput(updated, secondAttempt, response))

        assertEquals(TimerPhase.ShortBreak, second.projected.settings.selectedPhase)
    }

    @Test
    fun restartKeepsAdvancedPhase() {
        val snapshot = remoteSnapshot(
            selectedPhase = TimerPhase.Focus,
            canonicalHistory = emptyList(),
        )
        val attempt = attempt(snapshot)
        val response = response(
            history = listOf(
                completed("history-remote-1", "remote-focus-1", "remote-finish-1", TimerPhase.Focus, RemoteTime),
            ),
        )

        val first = coordinator.applySync(applicationInput(snapshot, attempt, response))
        assertEquals(TimerPhase.ShortBreak, first.projected.settings.selectedPhase)

        val restarted = remoteSnapshot(
            selectedPhase = first.projected.settings.selectedPhase,
            canonicalHistory = first.canonical.history,
        ).copy(canonicalTimer = first.canonical.timer)
        val restartedAttempt = attempt(restarted)
        val restartedResult = coordinator.applySync(applicationInput(restarted, restartedAttempt, response))

        assertEquals(TimerPhase.ShortBreak, restartedResult.projected.settings.selectedPhase)
    }

    @Test
    fun explicitChoicePreserved() {
        val snapshot = remoteSnapshot(
            selectedPhase = TimerPhase.Focus,
            canonicalHistory = emptyList(),
        )
        val attempt = attempt(snapshot)
        val explicit = snapshot.copy(
            settings = snapshot.settings.copy(selectedPhase = TimerPhase.LongBreak),
            selectedPhaseGeneration = snapshot.selectedPhaseGeneration + 1,
        )
        val response = response(
            history = listOf(
                completed("history-remote-1", "remote-focus-1", "remote-finish-1", TimerPhase.Focus, RemoteTime),
            ),
        )

        val result = coordinator.applySync(applicationInput(explicit, attempt, response))

        assertEquals(TimerPhase.LongBreak, result.projected.settings.selectedPhase)
    }

    @Test
    fun bootstrapInstallationAdvancesRemoteFocus() {
        val snapshot = remoteSnapshot(
            selectedPhase = TimerPhase.Focus,
            canonicalHistory = emptyList(),
        )
        val response = response(
            history = listOf(
                completed("history-remote-1", "remote-focus-1", "remote-finish-1", TimerPhase.Focus, RemoteTime),
            ),
        )

        val result = coordinator.applyBootstrapInstallation(
            CentralizedBootstrapInstallationInput(
                snapshot = snapshot,
                profile = User("user-1", "user@example.com", "User", ""),
                response = response,
                clearLocal = true,
                sampledLocal = snapshot.local,
                localizedTimer = response.canonicalTimer,
                localizedHistory = response.history,
                projectionNow = Instant.parse(response.serverTime),
            ),
        )

        assertEquals(TimerPhase.ShortBreak, result.projected.settings.selectedPhase)
    }

    private fun remoteSnapshot(
        selectedPhase: String,
        canonicalHistory: List<HistoryItem>,
    ): CentralizedSyncSnapshot {
        val settings = TimerSettings(selectedPhase = selectedPhase, autoStartBreaks = true)
        return CentralizedSyncSnapshot(
            local = LocalStateEntity(
                deviceId = "device-1",
                deviceSequence = 2,
                hlcWallMs = ServerWallMs,
                hlcCounter = 0,
                revision = 3,
                settingsJson = json.encodeToString(settings),
                ownedTimerId = null,
            ),
            queues = PendingSyncQueues(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
            neverSent = CoreNeverSentProof(),
            dependencies = emptyMap(),
            canonicalTimer = null,
            canonicalHistory = canonicalHistory,
            canonicalTasks = emptyList(),
            canonicalAutoStartBreaks = true,
            knownTasks = emptyMap(),
            settings = settings,
            selectedPhaseGeneration = 4,
        )
    }

    private fun attempt(snapshot: CentralizedSyncSnapshot) = coordinator.prepareSyncAttempt(
        CentralizedSyncAttemptInput(
            identity = SyncAttemptIdentity(9, "remote-attempt"),
            snapshot = snapshot,
            sentPhysicalMs = ServerWallMs,
            sentElapsedRealtimeMs = 10_000,
        ),
    )

    private fun applicationInput(
        snapshot: CentralizedSyncSnapshot,
        attempt: SyncAttempt,
        response: SyncResponse,
    ) = CentralizedSyncApplicationInput(
        snapshot = snapshot,
        attempt = attempt,
        response = response,
        sampledLocal = snapshot.local.copy(hlcWallMs = ServerWallMs, hlcCounter = 3),
        localizedTimer = response.canonicalTimer,
        localizedHistory = response.history,
        projectionNow = Instant.parse(response.serverTime),
    )

    private fun completed(
        id: String,
        timerId: String,
        commandId: String,
        phase: String,
        completedAt: String,
    ) = HistoryItem(
        id = id,
        timerId = timerId,
        commandId = commandId,
        phase = phase,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000,
        completedAt = completedAt,
    )

    private fun response(history: List<HistoryItem>) = SyncResponse(
        acknowledgements = emptyList(),
        revision = 7,
        canonicalTimer = null,
        history = history,
        serverTime = "2026-07-20T00:06:00Z",
        serverHlcWallMs = ServerWallMs,
        serverHlcCounter = 2,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
        autoStartBreaks = true,
    )

    private companion object {
        const val RemoteTime = "2026-07-20T00:04:00Z"
        val ServerWallMs = Instant.parse("2026-07-20T00:06:00Z").toEpochMilli()
    }
}
