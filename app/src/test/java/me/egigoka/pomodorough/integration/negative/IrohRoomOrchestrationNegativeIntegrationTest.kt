package me.egigoka.pomodorough.integration.negative

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.IrohRoomInvite
import me.egigoka.pomodorough.data.iroh.IrohRoomOrchestrationHarness
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IrohRoomOrchestrationNegativeIntegrationTest {
    @Test
    fun failedEndpointStartRollsBackNewJoinAndRestoresPersistedRoute() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        harness.enterForeground()
        harness.startFailure = IllegalStateException("endpoint unavailable")
        val encodedInvite = harness.invite()
        val joinedRoomId = IrohRoomInvite.decode(encodedInvite).roomId

        harness.orchestration.joinRoom(encodedInvite)

        assertEquals(listOf(joinedRoomId), harness.discardedRooms)
        assertEquals(0, harness.joinCount)
        assertEquals(1, harness.startCount)
        assertEquals(1, harness.stopCount)
        assertEquals(ReplicationMode.CENTRALIZED, harness.state.value.mode)
        assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
        assertEquals("endpoint unavailable", harness.state.value.message)
        assertFalse(harness.state.value.transitioning)
    }
}
