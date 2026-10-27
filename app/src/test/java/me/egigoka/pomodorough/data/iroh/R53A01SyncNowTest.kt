package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class R53A01SyncNowTest {
    @Test
    fun endpointFailurePreservesRoomAndRetrySucceeds() = runTest {
        withForegroundRoom { harness, reports ->
            val starts = harness.startCount
            harness.startFailure = IOException("secret invite user-text")
            harness.orchestration.syncNow()
            assertEquals(starts + 1, harness.startCount)
            assertEquals(0, harness.syncCount)
            assertRecoverable(harness)
            assertTrue(reports.isEmpty())
            harness.startFailure = null
            harness.orchestration.syncNow()
            assertEquals(starts + 2, harness.startCount)
            assertEquals(1, harness.syncCount)
            assertEquals(IrohConnectionStatus.LISTENING, harness.state.value.status)
            assertNull(harness.state.value.message)
        }
    }

    @Test
    fun serviceFailurePreservesRoomAndRetrySucceeds() = runTest {
        withForegroundRoom { harness, reports ->
            val starts = harness.startCount
            harness.syncFailure = IOException("secret invite user-text")
            harness.orchestration.syncNow()
            assertEquals(starts + 1, harness.startCount)
            assertEquals(1, harness.syncCount)
            assertRecoverable(harness)
            assertTrue(reports.isEmpty())
            harness.syncFailure = null
            harness.orchestration.syncNow()
            assertEquals(2, harness.syncCount)
            assertNull(harness.state.value.message)
        }
    }

    @Test
    fun endpointAndServiceVaultFailuresQuarantineWithoutReporting() = runTest {
        for (fromService in listOf(false, true)) {
            withForegroundRoom { harness, reports ->
                val error = IrohSecretVaultException(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
                if (fromService) harness.syncFailure = error else harness.startFailure = error
                harness.orchestration.syncNow()
                assertEquals(error.recoveryKind, harness.state.value.identityRecovery)
                assertEquals(1, harness.stopCount)
                assertNull(harness.state.value.invite)
                assertTrue(reports.isEmpty())
                assertEquals("room-test0001", harness.room?.roomId)
                assertNotNull(harness.roomSecret)
            }
        }
    }

    @Test
    fun endpointAndServiceCancellationPropagateUnchanged() = runTest {
        for (fromService in listOf(false, true)) {
            withForegroundRoom { harness, reports ->
                val before = harness.state.value
                val cancellation = CancellationException("cancel sync")
                if (fromService) harness.syncFailure = cancellation else harness.startFailure = cancellation
                assertSame(cancellation, runCatching { harness.orchestration.syncNow() }.exceptionOrNull())
                assertEquals(before, harness.state.value)
                assertTrue(reports.isEmpty())
                assertEquals(0, harness.stopCount)
            }
        }
    }

    @Test
    fun viewModelLaunchContainsProductionOrchestrationFailures() = runTest {
        Dispatchers.setMain(testScheduler.let { kotlinx.coroutines.test.StandardTestDispatcher(it) })
        try {
            withForegroundRoom { harness, _ ->
                val uiState = MutableStateFlow(AppState())
                val repository = Proxy.newProxyInstance(
                    TimerRepositoryContract::class.java.classLoader,
                    arrayOf(TimerRepositoryContract::class.java),
                ) { _, method, args ->
                    when (method.name) {
                        "getState" -> uiState
                        "initialize" -> Unit
                        "syncIrohNow" -> {
                            @Suppress("UNCHECKED_CAST")
                            val continuation = args.last() as kotlin.coroutines.Continuation<Unit>
                            val action: suspend () -> Unit = { harness.orchestration.syncNow() }
                            action.startCoroutineUninterceptedOrReturn(continuation)
                        }
                        else -> error("Unexpected repository call: ${method.name}")
                    }
                } as TimerRepositoryContract
                val viewModel = PomodoroughViewModel(repository)
                harness.startFailure = IOException("endpoint failed")
                viewModel.syncIrohNow()
                runCurrent()
                assertRecoverable(harness)
                harness.startFailure = null
                harness.syncFailure = IOException("service failed")
                viewModel.syncIrohNow()
                runCurrent()
                assertRecoverable(harness)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun withForegroundRoom(
        test: suspend (IrohRoomOrchestrationHarness, MutableList<Throwable>) -> Unit,
    ) {
        val previous = CrashReporter.delegate
        val reports = mutableListOf<Throwable>()
        CrashReporter.delegate = reports::add
        val harness = IrohRoomOrchestrationHarness(ReplicationMode.IROH, "room-test0001")
        try {
            harness.orchestration.initialize()
            harness.enterForeground()
            harness.await { harness.startCount > 0 }
            // Serialize with the foreground start before injecting failures.
            harness.orchestration.initialize()
            harness.state.value = harness.state.value.copy(invite = "retained-invite")
            val room = harness.room
            val settings = harness.settings
            val projection = harness.capturedProjection
            val secret = harness.roomSecret?.copyOf()
            test(harness, reports)
            assertEquals(room, harness.room)
            assertEquals(settings, harness.settings)
            assertEquals(projection, harness.capturedProjection)
            assertArrayEquals(secret, harness.roomSecret)
        } finally {
            harness.orchestration.close()
            CrashReporter.delegate = previous
        }
    }

    private fun assertRecoverable(harness: IrohRoomOrchestrationHarness) {
        assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
        assertEquals("Iroh sync could not be completed. Try again.", harness.state.value.message)
        assertEquals("retained-invite", harness.state.value.invite)
        assertFalse(harness.state.value.transitioning)
        assertEquals("room-test0001", harness.room?.roomId)
        assertEquals("room-test0001", harness.settings.activeRoomId)
        assertEquals(ReplicationMode.IROH.name, harness.settings.mode)
        assertEquals(2, harness.state.value.operationCount)
        assertEquals(5, harness.capturedProjection.operationCount)
        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, harness.roomSecret)
        assertEquals(0, harness.clearCount + harness.scrubCount + harness.discardedRooms.size)
    }
}
