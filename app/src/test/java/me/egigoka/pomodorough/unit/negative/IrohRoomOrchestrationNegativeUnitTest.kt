package me.egigoka.pomodorough.unit.negative

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.iroh.IrohRoomOrchestrationHarness
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IrohRoomOrchestrationNegativeUnitTest {
    @Test
    fun initializationFailsClosedWhenPersistedIrohRoomIsMissing() = runTest {
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "missing-room",
        )
        harness.room = null

        harness.orchestration.initialize()

        assertEquals(listOf(ReplicationMode.OFFLINE), harness.modes)
        assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
        assertNull(harness.state.value.roomId)
        assertEquals(0, harness.captureCount)
        assertEquals(0, harness.startCount)
    }
}
