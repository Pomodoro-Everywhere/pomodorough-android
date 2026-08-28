package me.egigoka.pomodorough.unit.negative

import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.domain.LegacyTimerReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerReducerNegativeTest {
    @Test
    fun commandsThatRequireCurrentTimerAreIgnoredWhenTimerIsMissing() {
        val commandTypes = listOf(
            CommandType.Pause,
            CommandType.Resume,
            CommandType.Finish,
            CommandType.Cancel,
            CommandType.Clear,
        )

        commandTypes.forEachIndexed { index, type ->
            val projection = LegacyTimerReducer.replay(
                canonicalTimer = null,
                canonicalHistory = emptyList(),
                commands = listOf(command(index.toLong(), type)),
            )

            assertNull("$type must not create a timer", projection.timer)
            assertTrue("$type must not create history", projection.history.isEmpty())
        }
    }

    @Test
    fun terminalTimerAcceptsLatestAction() {
        val terminal = timer(TimerStatus.Superseded)
        val expectations = listOf(
            CommandType.Pause to TimerStatus.Paused,
            CommandType.Finish to TimerStatus.Completed,
            CommandType.Cancel to TimerStatus.Cancelled,
        )

        expectations.forEachIndexed { index, (type, expectedStatus) ->
            val projection = LegacyTimerReducer.replay(
                canonicalTimer = terminal,
                canonicalHistory = emptyList(),
                commands = listOf(command(index.toLong(), type)),
            )

            assertEquals("$type must be latest", expectedStatus, projection.timer?.status)
            assertEquals("$type must record intent", "command-$index", projection.timer?.lastIntent?.commandId)
        }
    }

    @Test
    fun clearWithDifferentTimerIdPreservesTerminalTimer() {
        val terminal = timer(TimerStatus.Completed)
        val clear = command(sequence = 1, type = CommandType.Clear, timerId = "other-timer")

        val projection = LegacyTimerReducer.replay(terminal, emptyList(), listOf(clear))

        assertSame(terminal, projection.timer)
    }

    private fun timer(status: String) = CanonicalTimer(
        id = "timer-1",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 60_000,
        anchorAt = "2026-01-01T00:01:00Z",
    )

    private fun command(
        sequence: Long,
        type: String,
        timerId: String = "timer-1",
    ) = TimerCommand(
        id = "command-$sequence",
        deviceSequence = sequence,
        timerId = timerId,
        type = type,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:05:00Z",
        hlcWallMs = 1_767_225_900_000 + sequence,
        hlcCounter = 0,
        observedElapsedMs = 300_000,
    )
}
