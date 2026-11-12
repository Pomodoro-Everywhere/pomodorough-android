package me.egigoka.pomodorough.timer

import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerSurfaceTimingTest {
    private val anchorMs = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    @Test
    fun runningTimerEndsAtAnchorPlusRemainingPlan() {
        val timer = timer(TimerStatus.Running, plannedMs = 1_500_000, elapsedAtAnchorMs = 300_000)

        val timing = TimerSurfaceTiming.from(timer, nowMs = anchorMs + 120_000)

        assertEquals(TimerSurfaceTiming.Running(endsAtMs = anchorMs + 1_200_000), timing)
    }

    @Test
    fun pausedTimerKeepsRemainingIndependentOfClock() {
        val timer = timer(TimerStatus.Paused, plannedMs = 1_500_000, elapsedAtAnchorMs = 400_000)

        val timing = TimerSurfaceTiming.from(timer, nowMs = anchorMs + 999_999)

        assertEquals(TimerSurfaceTiming.Paused(remainingMs = 1_100_000), timing)
    }

    @Test
    fun completedOrMissingTimerIsIdle() {
        assertEquals(TimerSurfaceTiming.Idle, TimerSurfaceTiming.from(null, anchorMs))
        assertEquals(
            TimerSurfaceTiming.Idle,
            TimerSurfaceTiming.from(timer(TimerStatus.Completed, 60_000, 60_000), anchorMs),
        )
        assertEquals(
            TimerSurfaceTiming.Idle,
            TimerSurfaceTiming.from(timer(TimerStatus.Cancelled, 60_000, 0), anchorMs),
        )
    }

    @Test
    fun expiredRunningTimerEndsNow() {
        val timer = timer(TimerStatus.Running, plannedMs = 60_000, elapsedAtAnchorMs = 0)
        val nowMs = anchorMs + 90_000

        assertEquals(TimerSurfaceTiming.Running(endsAtMs = nowMs), TimerSurfaceTiming.from(timer, nowMs))
    }

    @Test
    fun clockFormatRoundsUpLikeTheInAppCountdown() {
        assertEquals("25:00", TimerSurfaceTiming.formatClock(1_500_000))
        assertEquals("04:59", TimerSurfaceTiming.formatClock(298_200))
        assertEquals("00:01", TimerSurfaceTiming.formatClock(1))
        assertEquals("00:00", TimerSurfaceTiming.formatClock(0))
        assertEquals("00:00", TimerSurfaceTiming.formatClock(-5))
    }

    private fun timer(status: String, plannedMs: Long, elapsedAtAnchorMs: Long) = CanonicalTimer(
        id = "t1",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = plannedMs,
        elapsedAtAnchorMs = elapsedAtAnchorMs,
        anchorAt = "2026-01-01T00:00:00Z",
    )
}
