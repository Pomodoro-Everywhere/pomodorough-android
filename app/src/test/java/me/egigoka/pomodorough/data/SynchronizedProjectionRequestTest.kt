package me.egigoka.pomodorough.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SynchronizedProjectionRequestTest {
    @Test
    fun reorderedCommandsUseAuthoritativeHlcHorizon() {
        val earlierClock = command(
            id = "earlier-clock",
            wallMs = 100,
            occurredAt = "2026-01-01T00:59:00Z",
        )
        val laterClock = command(
            id = "later-clock",
            wallMs = 200,
            occurredAt = "2026-01-01T00:10:00Z",
        )
        val orders = listOf(
            listOf(earlierClock, laterClock),
            listOf(laterClock, earlierClock),
        )

        val horizons = orders.map { commands ->
            request(commands.map { DeviceOperation("device", it) }).horizon
        }
        val arrayDerivedHorizons = orders.map { Instant.parse(it.last().occurredAt) }

        assertNotEquals(arrayDerivedHorizons.first(), arrayDerivedHorizons.last())
        assertEquals(listOf(ExpectedHorizon, ExpectedHorizon), horizons)
    }

    @Test
    fun deviceAndOperationTiesUseSharedCoreUtf8Order() {
        val deviceTie = listOf(
            DeviceOperation("\uE000", command("z", 200, "2026-01-01T00:20:00Z")),
            DeviceOperation("😀", command("a", 200, "2026-01-01T00:30:00Z")),
        )
        val operationTie = listOf(
            DeviceOperation("device", command("\uE000", 300, "2026-01-01T00:40:00Z")),
            DeviceOperation("device", command("😀", 300, "2026-01-01T00:50:00Z")),
        )

        assertEquals(Instant.parse("2026-01-01T00:30:00Z"), request(deviceTie.reversed()).horizon)
        assertEquals(Instant.parse("2026-01-01T00:50:00Z"), request(operationTie.reversed()).horizon)
    }

    private fun request(
        commands: List<DeviceOperation<TimerCommand>>,
    ) = SynchronizedProjectionRequestFactory.create(
        base = CoreProjectionBase(),
        pending = CoreProjectionPending(commands = commands),
    )

    private fun command(
        id: String,
        wallMs: Long,
        occurredAt: String,
    ) = TimerCommand(
        id = id,
        deviceSequence = 1,
        timerId = "timer",
        type = CommandType.Start,
        phase = TimerPhase.Focus,
        plannedDurationMs = 60_000,
        occurredAt = occurredAt,
        hlcWallMs = wallMs,
        hlcCounter = 0,
        observedElapsedMs = 0,
    )

    private companion object {
        val ExpectedHorizon: Instant = Instant.parse("2026-01-01T00:10:00Z")
    }
}
