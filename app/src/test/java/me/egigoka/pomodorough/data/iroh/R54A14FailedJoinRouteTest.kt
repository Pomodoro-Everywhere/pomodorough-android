package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A14: a failed or cancelled join reads the persisted route to settle its state. When that
 * read fails, the state fell back to the failed room on the memory mode from before the join,
 * even after the join had activated that room as the IROH route. The join now settles on the
 * route it knows is persisted: the route it replaced, or, once activated, the joined room on
 * the IROH route, which Sync now binds again.
 */
class R54A14FailedJoinRouteTest {
    private val joinedRoomId = IrohProtocolV1.roomId(ByteArray(32) { (it + 11).toByte() })
    private val bindFailure = IOException("r54 a14 bind")
    private val readFailure = IOException("r54 a14 route read")

    @Test
    fun failedJoinBeforeActivationWithUnreadableRouteRestoresThePreviousRoute() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val beforeJoin = harness.state.value
            val join = holdJoin(harness, HeldBind.BeforeActivation)
            harness.startFailure = bindFailure
            harness.settingsFailure = readFailure
            join.bind.complete(Unit)
            assertNull(join.outcome.await())
            assertEquals(
                "the failed room must not stay on screen",
                beforeJoin.copy(status = IrohConnectionStatus.UNAVAILABLE, message = JoinFailure),
                harness.state.value,
            )
            assertEquals(listOf<Throwable>(bindFailure, readFailure), reports.toList())
        }
    }

    @Test
    fun failedJoinAfterActivationWithUnreadableRouteKeepsTheJoinedRoute() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val join = holdJoin(harness, HeldBind.AfterActivation)
            harness.startFailure = bindFailure
            harness.settingsFailure = readFailure
            join.bind.complete(Unit)
            assertNull(join.outcome.await())
            assertJoinedRoute(harness.state.value)
            assertEquals(IrohConnectionStatus.UNAVAILABLE, harness.state.value.status)
            assertEquals(JoinFailure, harness.state.value.message)
            assertEquals(listOf<Throwable>(bindFailure, readFailure), reports.toList())
            assertSyncNowBinds(harness, joinedRoomId)
        }
    }

    @Test
    fun cancelledJoinBeforeActivationWithUnreadableRouteRestoresThePreviousRoute() = runBlocking {
        withHarness(ReplicationMode.IROH, RoomId) { harness, reports ->
            harness.orchestration.initialize()
            harness.orchestration.onForeground()
            harness.await { harness.startCount == 1 }
            val beforeJoin = harness.state.value
            assertNotEquals(harness.capturedProjection.operationCount, beforeJoin.operationCount)
            val join = holdJoin(harness, HeldBind.BeforeActivation)
            harness.settingsFailure = readFailure
            join.caller.cancelAndJoin()
            assertTrue(join.outcome.await() is CancellationException)
            // The join flushed the previous room's local operations before it replaced that room.
            assertEquals(
                beforeJoin.copy(operationCount = harness.capturedProjection.operationCount),
                harness.state.value,
            )
            assertEquals(listOf<Throwable>(readFailure), reports.toList())
            assertSyncNowBinds(harness, RoomId)
        }
    }

    @Test
    fun cancelledJoinAfterActivationWithUnreadableRouteKeepsTheJoinedRoute() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val join = holdJoin(harness, HeldBind.AfterActivation)
            harness.settingsFailure = readFailure
            join.caller.cancelAndJoin()
            assertTrue(join.outcome.await() is CancellationException)
            assertJoinedRoute(harness.state.value)
            assertEquals(listOf<Throwable>(readFailure), reports.toList())
            assertSyncNowBinds(harness, joinedRoomId)
        }
    }

    @Test
    fun identityRecoveryDuringACancelledJoinKeepsItsPrompt() = runBlocking {
        withHarness(ReplicationMode.CENTRALIZED) { harness, reports ->
            harness.enterForeground()
            val join = holdJoin(harness, HeldBind.BeforeActivation)
            // The service's RecoveryRequired event: the endpoint refuses its identity in the bind.
            harness.state.value = harness.state.value.copy(
                status = IrohConnectionStatus.UNAVAILABLE,
                endpointMark = null,
                message = null,
                transitioning = false,
                identityRecovery = IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED,
                recoveryAttemptFailed = false,
            )
            val recovery = harness.state.value
            join.caller.cancelAndJoin()
            assertTrue(join.outcome.await() is CancellationException)
            assertEquals(recovery, harness.state.value)
            assertTrue(reports.isEmpty())
        }
    }

    private fun assertJoinedRoute(state: IrohNetworkState) {
        assertEquals("the memory mode must match the persisted route", ReplicationMode.IROH, state.mode)
        assertEquals(joinedRoomId, state.roomId)
        assertFalse(state.transitioning)
    }

    // Once the route is readable again, Sync now binds the persisted room. The gate that held the
    // join's bind is dropped first, so the new bind does not wait on it.
    private suspend fun assertSyncNowBinds(harness: IrohRoomOrchestrationHarness, roomId: String) {
        harness.startFailure = null
        harness.settingsFailure = null
        harness.startGate = null
        val startsBefore = harness.startCount
        harness.orchestration.syncNow()
        assertEquals("Sync now binds the persisted room", startsBefore + 1, harness.startCount)
        assertEquals(roomId, harness.startedContexts.last().roomId)
    }

    private enum class HeldBind { BeforeActivation, AfterActivation }

    private class HeldJoin(
        val caller: Job,
        val outcome: CompletableDeferred<Throwable?>,
        val bind: CompletableDeferred<Unit>,
    )

    // Starts a join and holds it in one of its two binds. In production service.join, between the
    // binds, persists the joined room as the IROH route. The harness join does not, so the second
    // case activates that route by hand.
    private suspend fun CoroutineScope.holdJoin(
        harness: IrohRoomOrchestrationHarness,
        held: HeldBind,
    ): HeldJoin {
        val startsBefore = harness.startCount
        val firstBind = CompletableDeferred<Unit>()
        harness.startGate = firstBind
        val outcome = CompletableDeferred<Throwable?>()
        val caller = launch(Dispatchers.IO) {
            try {
                harness.orchestration.joinRoom(harness.invite())
                outcome.complete(null)
            } catch (error: Throwable) {
                outcome.complete(error)
                throw error
            }
        }
        harness.await { harness.startCount == startsBefore + 1 }
        if (held == HeldBind.BeforeActivation) return HeldJoin(caller, outcome, firstBind)
        activate(harness)
        val secondBind = CompletableDeferred<Unit>()
        harness.startGate = secondBind
        firstBind.complete(Unit)
        harness.await { harness.startCount == startsBefore + 2 }
        return HeldJoin(caller, outcome, secondBind)
    }

    private fun activate(harness: IrohRoomOrchestrationHarness) {
        harness.settings = ReplicationSettingsEntity(mode = ReplicationMode.IROH.name, activeRoomId = joinedRoomId)
        harness.room = IrohRoomEntity(
            roomId = joinedRoomId,
            roomName = "Joined room",
            encryptedRoomSecret = byteArrayOf(1, 2, 3),
            returnStateJson = "return",
            roomStateJson = "room",
            createdAtMs = 1,
            activated = true,
        )
        harness.snapshot = IrohNetworkState(mode = ReplicationMode.IROH, roomId = joinedRoomId, roomName = "Joined room")
    }

    private suspend fun withHarness(
        mode: ReplicationMode,
        activeRoomId: String? = null,
        test: suspend CoroutineScope.(IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) {
        val previousReporter = CrashReporter.delegate
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val reports = CopyOnWriteArrayList<Throwable>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        CrashReporter.delegate = reports::add
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        val harness = IrohRoomOrchestrationHarness(mode, activeRoomId)
        try {
            coroutineScope { test(harness, reports) }
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            harness.orchestration.close()
            CrashReporter.delegate = previousReporter
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private companion object {
        const val RoomId = "room-test0001"
        const val JoinFailure = "Iroh room could not be joined"
    }
}
