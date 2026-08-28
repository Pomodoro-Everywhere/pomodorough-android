package me.egigoka.pomodorough.integration.positive

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.IrohRoomOrchestrationHarness
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohRoomOrchestrationPositiveIntegrationTest {
    @Test
    fun roomCreationMutationSyncAndLeaveFollowOneForegroundLifecycle() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        harness.enterForeground()

        harness.orchestration.createRoom("  Team room  ")
        val invite = harness.state.value.invite

        assertEquals(listOf("Team room"), harness.createdNames)
        assertNotNull(invite)
        assertTrue(invite!!.startsWith("pomodorough1."))
        assertEquals(ReplicationMode.IROH, harness.state.value.mode)
        assertEquals(1, harness.startCount)

        harness.orchestration.afterLocalMutation()
        harness.orchestration.syncNow()

        assertEquals(1, harness.captureCount)
        assertEquals(2, harness.syncCount)
        assertEquals(5, harness.state.value.operationCount)
        val createdRoomId = requireNotNull(harness.room).roomId
        assertTrue(harness.startedContexts.all { it.roomId == createdRoomId })

        harness.orchestration.leaveRoom()

        assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
        assertEquals(IrohConnectionStatus.STOPPED, harness.state.value.status)
        assertEquals(2, harness.captureCount)
        assertEquals(1, harness.stopCount)
    }
}
