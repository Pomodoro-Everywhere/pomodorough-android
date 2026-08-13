package me.egigoka.pomodorough.timer

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerAlarmDeliveryPolicyTest {
    @Test
    fun successfulCompletionShowsExactlyOneNotification() = runTest {
        var completionCalls = 0
        var notificationCalls = 0
        val policy = TimerAlarmDeliveryPolicy(
            completion = ExpiredTimerCompleting {
                completionCalls += 1
                true
            },
            notification = TimerCompletionNotifying {
                notificationCalls += 1
                true
            },
        )

        val result = policy.deliver()

        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, result)
        assertEquals(1, completionCalls)
        assertEquals(1, notificationCalls)
    }

    @Test
    fun duplicateOrEarlyDeliveryDoesNotNotify() = runTest {
        val completionResults = ArrayDeque(listOf(true, false))
        var notificationCalls = 0
        val policy = TimerAlarmDeliveryPolicy(
            completion = ExpiredTimerCompleting { completionResults.removeFirst() },
            notification = TimerCompletionNotifying {
                notificationCalls += 1
                true
            },
        )

        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, policy.deliver())
        assertEquals(TimerAlarmDeliveryResult.NotExpired, policy.deliver())
        assertEquals(1, notificationCalls)
    }

    @Test
    fun completionFailurePropagatesWithoutNotification() = runTest {
        val failure = IOException("database unavailable")
        var notified = false
        val policy = TimerAlarmDeliveryPolicy(
            completion = ExpiredTimerCompleting { throw failure },
            notification = TimerCompletionNotifying {
                notified = true
                true
            },
        )

        val thrown = runCatching { policy.deliver() }.exceptionOrNull()

        assertSame(failure, thrown)
        assertFalse(notified)
    }

    @Test
    fun notificationFailureDoesNotUndoCompletedTimer() = runTest {
        val policy = TimerAlarmDeliveryPolicy(
            completion = ExpiredTimerCompleting { true },
            notification = TimerCompletionNotifying { throw IOException("notifications unavailable") },
        )

        assertEquals(TimerAlarmDeliveryResult.CompletedWithoutNotification, policy.deliver())
    }

    @Test
    fun deniedNotificationReportsCompletedWithoutNotification() = runTest {
        val policy = TimerAlarmDeliveryPolicy(
            completion = ExpiredTimerCompleting { true },
            notification = TimerCompletionNotifying { false },
        )

        assertEquals(TimerAlarmDeliveryResult.CompletedWithoutNotification, policy.deliver())
    }

    @Test
    fun autoStartedTimerSuppressesCompletionNotification() = runTest {
        var notificationCalls = 0
        val policy = TimerAlarmDeliveryPolicy(
            completion = ExpiredTimerCompleting { true },
            notification = TimerCompletionNotifying {
                notificationCalls += 1
                true
            },
            shouldNotify = { shouldPostCompletionAlert(timer(TimerStatus.Running, "next-break")) },
        )

        assertEquals(TimerAlarmDeliveryResult.CompletedWithActiveReplacement, policy.deliver())
        assertEquals(0, notificationCalls)
    }

    @Test
    fun differentActiveTimerStopsExistingCompletionAlert() {
        assertTrue(shouldStopCompletionAlert("completed-focus", timer(TimerStatus.Running, "next-break")))
        assertTrue(shouldStopCompletionAlert("completed-focus", timer(TimerStatus.Paused, "next-break")))
        assertFalse(shouldStopCompletionAlert("same-timer", timer(TimerStatus.Running, "same-timer")))
        assertFalse(shouldStopCompletionAlert("completed-focus", timer(TimerStatus.Completed, "next-break")))
    }

    @Test
    fun concurrentDuplicateDeliveriesCompleteAndNotifyOnce() = runTest {
        val completionMutex = Mutex()
        val completed = AtomicBoolean(false)
        val notificationCalls = AtomicInteger(0)
        val ready = AtomicInteger(0)
        val start = CompletableDeferred<Unit>()
        val policy = TimerAlarmDeliveryPolicy(
            completion = ExpiredTimerCompleting {
                ready.incrementAndGet()
                start.await()
                completionMutex.withLock {
                    if (completed.get()) false else {
                        completed.set(true)
                        true
                    }
                }
            },
            notification = TimerCompletionNotifying {
                notificationCalls.incrementAndGet()
                true
            },
        )

        val attempts = List(8) { async(Dispatchers.Default) { policy.deliver() } }
        while (ready.get() < attempts.size) Thread.yield()
        start.complete(Unit)
        val results = attempts.awaitAll()

        assertEquals(1, results.count { it == TimerAlarmDeliveryResult.CompletedAndNotified })
        assertEquals(7, results.count { it == TimerAlarmDeliveryResult.NotExpired })
        assertEquals(1, notificationCalls.get())
    }

    @Test
    fun notificationPermissionPolicyMatchesPlatformRequirement() {
        assertTrue(SystemTimerCompletionNotifier.canPostNotification(32, permissionGranted = false))
        assertFalse(SystemTimerCompletionNotifier.canPostNotification(33, permissionGranted = false))
        assertTrue(SystemTimerCompletionNotifier.canPostNotification(33, permissionGranted = true))
    }

    @Test
    fun stopSoundBroadcastIsRecognizedExactly() {
        assertTrue(TimerAlarmReceiver.isStopSoundAction(TimerAlarmReceiver.StopSoundAction))
        assertFalse(TimerAlarmReceiver.isStopSoundAction(null))
        assertFalse(TimerAlarmReceiver.isStopSoundAction("me.egigoka.pomodorough.TIMER_ALARM"))
    }

    private fun timer(status: String, id: String) = CanonicalTimer(
        id = id,
        phase = "focus",
        status = status,
        plannedDurationMs = 60_000,
        elapsedAtAnchorMs = 0,
        anchorAt = "2026-01-01T00:00:00Z",
    )
}
