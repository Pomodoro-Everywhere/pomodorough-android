package me.egigoka.pomodorough.unit.positive

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.iroh.IrohRoomOrchestrationHarness
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class IrohRoomOrchestrationPositiveUnitTest {
    @Test
    fun initializationIsIdempotentAndKeepsThePersistedCentralizedRoute() = runTest {
        val harness = IrohRoomOrchestrationHarness()

        harness.orchestration.initialize()
        harness.orchestration.initialize()
        harness.orchestration.setMode(ReplicationMode.CENTRALIZED)

        assertEquals(1, harness.discardCount)
        assertEquals(ReplicationMode.CENTRALIZED, harness.state.value.mode)
        assertEquals(emptyList<ReplicationMode>(), harness.modes)
        assertEquals(0, harness.startCount)
    }
}
