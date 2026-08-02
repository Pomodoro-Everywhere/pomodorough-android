package me.egigoka.pomodorough.domain

import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReducerTest {
    @Test
    fun durationProjectionIsPhaseScopedInputOrderIndependentAndIdempotent() {
        val operations = listOf(
            duration("operation-z-old-wall", TimerPhase.Focus, 26, wall = 1, counter = 9),
            duration("operation-z-old-counter", TimerPhase.Focus, 27, wall = 2, counter = 0),
            duration("operation-a", TimerPhase.Focus, 28, wall = 2, counter = 1),
            duration("operation-z", TimerPhase.Focus, 29, wall = 2, counter = 1),
            duration("operation-short", TimerPhase.ShortBreak, 7, wall = 1, counter = 0),
        )
        val expected = TimerSettings()
            .withDuration(TimerPhase.Focus, 29 * 60_000L)
            .withDuration(TimerPhase.ShortBreak, 7 * 60_000L)

        permutations(operations).forEach { input ->
            assertEquals(
                input.map(DurationOperation::id).toString(),
                expected,
                SettingsReducer.replayDurations(TimerSettings(), input),
            )
        }
        assertEquals(
            expected,
            SettingsReducer.replayDurations(TimerSettings(), operations + operations),
        )
    }

    @Test
    fun autoStartProjectionUsesCompleteLwwTupleAcrossEveryPermutation() {
        val operations = listOf(
            autoStart("00000000-0000-4000-8000-000000000009", "device-z", true, wall = 1, counter = 9),
            autoStart("00000000-0000-4000-8000-000000000008", "device-z", true, wall = 2, counter = 0),
            autoStart("00000000-0000-4000-8000-000000000007", "device-z", true, wall = 2, counter = 1),
            autoStart("00000000-0000-4000-8000-000000000006", "device-a", true, wall = 2, counter = 1),
            autoStart("ffffffff-ffff-4fff-bfff-ffffffffffff", "device-z", false, wall = 2, counter = 1),
        )

        permutations(operations).forEach { input ->
            assertFalse(
                input.map { "${it.deviceId}/${it.id}" }.toString(),
                SettingsReducer.replayAutoStart(true, input),
            )
        }
        assertFalse(SettingsReducer.replayAutoStart(true, operations + operations))
    }

    @Test
    fun invalidOperationsNeverAffectProjection() {
        val invalidDuration = duration("invalid", "unknown", 30, wall = 1, counter = 0)
        val invalidAutoStart = autoStart("not-a-uuid", "device-z", false, wall = 1, counter = 0)

        assertEquals(
            TimerSettings(),
            SettingsReducer.replayDurations(TimerSettings(), listOf(invalidDuration)),
        )
        assertTrue(SettingsReducer.replayAutoStart(true, listOf(invalidAutoStart)))
    }

    private fun duration(
        id: String,
        phase: String,
        minutes: Long,
        wall: Long,
        counter: Long,
    ) = DurationOperation(
        id = id,
        phase = phase,
        durationMs = minutes * 60_000,
        occurredAt = "2026-07-21T00:00:00Z",
        hlcWallMs = wall,
        hlcCounter = counter,
    )

    private fun autoStart(
        id: String,
        deviceId: String,
        enabled: Boolean,
        wall: Long,
        counter: Long,
    ) = AutoStartOperation(
        id = id,
        deviceId = deviceId,
        enabled = enabled,
        occurredAt = "2026-07-21T00:00:00Z",
        hlcWallMs = wall,
        hlcCounter = counter,
    )

    private fun <T> permutations(values: List<T>): List<List<T>> = when (values.size) {
        0, 1 -> listOf(values)
        else -> values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidate, _ -> candidate != index })
                .map { listOf(value) + it }
        }
    }
}
