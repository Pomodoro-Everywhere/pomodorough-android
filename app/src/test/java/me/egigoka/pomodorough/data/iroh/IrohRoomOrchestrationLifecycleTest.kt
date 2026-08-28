package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrohRoomOrchestrationLifecycleTest {
    @Test
    fun accountQuarantineStopsEndpointBeforeReturning() = runTest {
        val harness = IrohRoomOrchestrationHarness()

        harness.orchestration.quarantineAccount()

        assertEquals(1, harness.stopCount)
    }

    @Test
    fun staleForegroundCannotRestartEndpointUntilAccountQuarantineIsReleased() = runTest {
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-test0001",
        )

        harness.orchestration.quarantineAccount()
        harness.orchestration.onForeground()
        withContext(Dispatchers.IO) { delay(100) }

        assertEquals(0, harness.startCount)

        harness.orchestration.releaseAccountQuarantine()
        harness.orchestration.onForeground()
        harness.await { harness.startCount > 0 }

        assertTrue(harness.startCount > 0)
    }

    @Test
    fun closeIsTerminalIdempotentAndJoinsLifecycleWork() = runTest {
        val harness = IrohRoomOrchestrationHarness()

        harness.orchestration.close()
        harness.orchestration.close()
        harness.orchestration.onForeground()
        harness.orchestration.onBackground()
        advanceUntilIdle()

        assertEquals(1, harness.closeCount)
        assertEquals(0, harness.startCount)
        assertEquals(0, harness.stopCount)
        assertEquals(0, harness.discardCount)
    }
}
