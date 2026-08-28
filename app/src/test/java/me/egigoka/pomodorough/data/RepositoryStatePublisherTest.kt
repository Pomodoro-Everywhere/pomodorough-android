package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoryStatePublisherTest {
    @Test
    fun publicationMapsProjectionQueuesAndStableTaskOrder() {
        val publisher = RepositoryStatePublisher()
        val timer = timer("owned")

        publisher.publish(publication(
            projection = TimerProjection(timer, emptyList()),
            knownTasks = listOf(
                FocusTask("2", "Beta"),
                FocusTask("1", "Alpha"),
            ),
            pendingCounts = listOf(1, 2, 0),
        ))

        assertEquals(timer, publisher.state.value.timer)
        assertEquals(listOf("Alpha", "Beta"), publisher.state.value.knownTasks.map(FocusTask::title))
        assertEquals(3, publisher.state.value.pendingCount)
        assertEquals(SyncStatus.Queued, publisher.state.value.syncStatus)
    }

    @Test
    fun conflictPrecedesOfflineAndAlarmEventPreservesOtherState() {
        val publisher = RepositoryStatePublisher()
        publisher.publish(publication(
            ready = true,
            online = false,
            historyResolution = HistoryResolutionState(1, 2),
            notice = "keep",
        ))

        assertEquals(SyncStatus.Conflict, publisher.state.value.syncStatus)

        publisher.accept(AlarmCoordinatorEvent.CompletionAlertChanged("timer-1"))
        assertEquals("timer-1", publisher.state.value.completionAlertTimerId)
        assertEquals("keep", publisher.state.value.notice)

        publisher.accept(AlarmCoordinatorEvent.CompletionAlertChanged(null))
        assertNull(publisher.state.value.completionAlertTimerId)
    }

    private fun publication(
        ready: Boolean = true,
        projection: TimerProjection = TimerProjection(null, emptyList()),
        knownTasks: Collection<FocusTask> = emptyList(),
        pendingCounts: List<Int> = emptyList(),
        online: Boolean = true,
        historyResolution: HistoryResolutionState? = null,
        notice: String? = null,
    ) = RepositoryPublication(
        ready = ready,
        authStatus = AuthStatus.SignedOut,
        localAccountResetRequired = false,
        user = null,
        projection = projection,
        completionAlertTimerId = null,
        tasks = emptyList(),
        knownTasks = knownTasks,
        selectedTaskId = null,
        settings = TimerSettings(),
        pendingCounts = pendingCounts,
        online = online,
        syncing = false,
        retrying = false,
        historyResolution = historyResolution,
        accountSwitch = null,
        conflict = null,
        notice = notice,
        deviceId = "device",
        network = IrohNetworkState(),
    )

    private fun timer(id: String) = CanonicalTimer(
        id = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-01-01T00:00:00Z",
    )
}
