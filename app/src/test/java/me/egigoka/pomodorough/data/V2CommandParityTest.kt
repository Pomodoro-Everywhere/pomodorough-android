package me.egigoka.pomodorough.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Forged-output parity with Core delivery.rs: frozen ops must match
// byte-for-byte, with no Start exemption for phase/duration rewrites.
class V2CommandParityTest {
    @Test
    fun exactStartPasses() {
        assertTrue(v2TimerCommandUnchanged(start(), start()))
    }

    @Test
    fun forgedStartPhaseDriftIsRejected() {
        assertFalse(v2TimerCommandUnchanged(start().copy(phase = TimerPhase.ShortBreak), start()))
    }

    @Test
    fun forgedStartDurationDriftIsRejected() {
        assertFalse(v2TimerCommandUnchanged(start().copy(plannedDurationMs = 900_000), start()))
    }

    @Test
    fun forgedStartTaskDriftIsRejected() {
        assertFalse(v2TimerCommandUnchanged(start().copy(taskId = null), start()))
    }

    @Test
    fun exactRetargetPasses() {
        val original = start().copy(id = "retarget-1", type = CommandType.Retarget)
        assertTrue(v2TimerCommandUnchanged(original, original))
    }

    @Test
    fun neverSentBreakPromotionPasses() {
        val promoted = start().copy(phase = TimerPhase.LongBreak, plannedDurationMs = 900_000)
        assertTrue(v2NeverSentCommandNormalized(promoted, start()))
    }

    @Test
    fun neverSentClockDriftIsRejected() {
        val drifted = start().copy(phase = TimerPhase.LongBreak, hlcWallMs = start().hlcWallMs + 1)
        assertFalse(v2NeverSentCommandNormalized(drifted, start()))
    }

    @Test
    fun neverSentIdentityDriftIsRejected() {
        val drifted = start().copy(phase = TimerPhase.LongBreak, taskId = null)
        assertFalse(v2NeverSentCommandNormalized(drifted, start()))
    }

    private fun start() = TimerCommand(
        id = "start-1",
        deviceSequence = 1,
        timerId = "timer-1",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_000L,
        hlcCounter = 0,
        observedElapsedMs = 0,
        taskId = "task-a",
    )
}
