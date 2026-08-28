package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.timer.shouldStopCompletionAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmCoordinatorTest {
    @Test
    fun accountClearCancelsAlarmBeforeAlertPersistencePublicationAndNotification() {
        val order = mutableListOf<String>()
        val fixture = fixture("timer-1", order)

        fixture.coordinator.cancelForAccountClear()

        assertEquals(
            listOf("alarm.cancel", "alert.save:null", "event:null", "notification.cancel"),
            order,
        )
        assertNull(fixture.coordinator.completionAlertTimerId)
    }

    @Test
    fun mismatchedCompletionDoesNotMutateOrPublish() {
        val order = mutableListOf<String>()
        val fixture = fixture("timer-1", order)

        val transition = fixture.coordinator.stopCompletionAlert("timer-2")

        assertFalse(transition.changed)
        assertEquals(emptyList<String>(), order)
        assertEquals("timer-1", fixture.coordinator.completionAlertTimerId)
    }

    @Test
    fun markPersistsBeforePublicationAndStopPublishesBeforeNotificationCancellation() {
        val order = mutableListOf<String>()
        val fixture = fixture(null, order)

        fixture.coordinator.markCompletionAlert("timer-1")
        val stopped = fixture.coordinator.stopCompletionAlert("timer-1")

        assertTrue(stopped.changed)
        assertEquals(
            listOf(
                "alert.save:timer-1",
                "event:timer-1",
                "alert.save:null",
                "event:null",
                "notification.cancel",
            ),
            order,
        )
    }

    @Test
    fun schedulePublishesOnlyOwnedTimerToSystemScheduler() {
        val scheduled = mutableListOf<String?>()
        val coordinator = AlarmCoordinator(
            scheduler = RecordingScheduler(scheduled = scheduled),
            alertStore = MemoryAlertStore(null),
            notificationCanceller = CompletionNotificationCanceller {},
            completionAlertPolicy = CompletionAlertPolicy(::shouldStopCompletionAlert),
            eventSink = AlarmCoordinatorEventSink {},
        )
        val timer = timer("timer-1")

        coordinator.schedule(timer, "timer-1")
        coordinator.schedule(timer, "timer-2")

        assertEquals(listOf("timer-1", null), scheduled)
    }

    private fun fixture(initialAlert: String?, order: MutableList<String>): Fixture {
        val coordinator = AlarmCoordinator(
            scheduler = RecordingScheduler(order),
            alertStore = MemoryAlertStore(initialAlert, order),
            notificationCanceller = CompletionNotificationCanceller {
                order += "notification.cancel"
            },
            completionAlertPolicy = CompletionAlertPolicy(::shouldStopCompletionAlert),
            eventSink = AlarmCoordinatorEventSink { event ->
                val timerId = (event as AlarmCoordinatorEvent.CompletionAlertChanged).timerId
                order += "event:$timerId"
            },
        )
        return Fixture(coordinator)
    }

    private fun timer(id: String) = CanonicalTimer(
        id = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-01-01T00:00:00Z",
    )

    private data class Fixture(val coordinator: AlarmCoordinator)

    private class RecordingScheduler(
        private val order: MutableList<String>? = null,
        private val scheduled: MutableList<String?>? = null,
    ) : AlarmSchedulerPort {
        override fun update(timer: CanonicalTimer?) {
            scheduled?.add(timer?.id)
            order?.add("alarm.update:${timer?.id}")
        }

        override fun cancel() {
            order?.add("alarm.cancel")
        }
    }

    private class MemoryAlertStore(
        initial: String?,
        private val order: MutableList<String>? = null,
    ) : CompletionAlertStore {
        private var timerId = initial

        override fun load(): String? = timerId

        override fun save(timerId: String?) {
            this.timerId = timerId
            order?.add("alert.save:$timerId")
        }
    }
}
