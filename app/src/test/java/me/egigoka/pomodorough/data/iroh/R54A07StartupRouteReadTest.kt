package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.CorruptStateReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A07: initialization reads the persisted replication route before it publishes any state.
 * A failed read of any of the four route parts throws one typed exception for the repository to
 * contain. Nothing is published, nothing starts, one bounded report names the route, and
 * initialization stays open, so the next call reads the route again. Actions that need the route
 * do not run while it is unreadable, and cancellation keeps its own path. An action contains only
 * the route-read failure; any other initialization failure still reaches its caller.
 */
class R54A07StartupRouteReadTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @Test
    fun discardReadFailureLeavesInitializationOpen() = runBlocking {
        assertRouteReadStaysOpen { harness, failure -> harness.discardFailure = failure }
    }

    @Test
    fun settingsReadFailureLeavesInitializationOpen() = runBlocking {
        assertRouteReadStaysOpen { harness, failure -> harness.settingsFailure = failure }
    }

    @Test
    fun roomReadFailureLeavesInitializationOpen() = runBlocking {
        assertRouteReadStaysOpen { harness, failure -> harness.roomFailure = failure }
    }

    @Test
    fun snapshotReadFailureLeavesInitializationOpen() = runBlocking {
        assertRouteReadStaysOpen { harness, failure -> harness.snapshotFailure = failure }
    }

    @Test
    fun cancelledRouteReadPropagatesWithoutReport() = runBlocking {
        withRetainedRoom { harness, reports ->
            val cancellation = CancellationException("r54 a07 cancelled route read")
            harness.discardFailure = cancellation
            assertSame(cancellation, runCatching { harness.orchestration.initialize() }.exceptionOrNull())
            assertTrue("cancellation is not a storage failure", reports.isEmpty())
            harness.discardFailure = null
            harness.orchestration.initialize()
            assertEquals(1, harness.captureCount)
            assertEquals(RoomId, harness.state.value.roomId)
        }
    }

    @Test
    fun syncNowDoesNotRunWhileRouteIsUnreadable() = runBlocking {
        withRetainedRoom { harness, reports ->
            harness.settingsFailure = IOException("r54 a07 route read of $RoomId")
            harness.orchestration.syncNow()
            assertEquals(0, harness.startCount + harness.syncCount + harness.captureCount)
            assertRouteReport(reports)
            harness.settingsFailure = null
            harness.orchestration.initialize()
            assertEquals(1, harness.captureCount)
        }
    }

    @Test
    fun foregroundActionDoesNotRunWhileRouteIsUnreadable() = runBlocking {
        withRetainedRoom { harness, reports ->
            harness.settingsFailure = IOException("r54 a07 route read of $RoomId")
            harness.enterForeground()
            // The report precedes the action's exit; Sync now then serializes behind it.
            harness.await { reports.isNotEmpty() }
            harness.settingsFailure = null
            harness.orchestration.syncNow()
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals("initialization and Sync now each bind once", 2, harness.startCount)
            assertEquals(1, harness.syncCount)
            assertEquals(IrohConnectionStatus.LISTENING, harness.state.value.status)
            assertRouteReport(reports)
        }
    }

    @Test
    fun actionInitializationFailureOtherThanTheRouteReadStillEscapes() = runBlocking {
        // An Iroh route without a room falls back to OFFLINE. Persisting that fallback fails, which
        // is not a route read, so Sync now must not turn it into a silent no-op.
        val harness = IrohRoomOrchestrationHarness(ReplicationMode.IROH, activeRoomId = null)
        val failure = IOException("r54 a07 set mode")
        harness.setModeFailure = failure
        try {
            assertSame(failure, runCatching { harness.orchestration.syncNow() }.exceptionOrNull())
            harness.setModeFailure = null
            harness.orchestration.syncNow()
            assertEquals("the next action retries the fallback", listOf(ReplicationMode.OFFLINE), harness.modes)
            assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
        } finally {
            harness.orchestration.close()
        }
    }

    private suspend fun assertRouteReadStaysOpen(arm: (IrohRoomOrchestrationHarness, Exception?) -> Unit) {
        withRetainedRoom { harness, reports ->
            val failure = IOException("r54 a07 route read of $RoomId")
            val unpublished = harness.state.value
            arm(harness, failure)
            repeat(2) {
                val error = runCatching { harness.orchestration.initialize() }.exceptionOrNull()
                assertTrue("a route read failure must be typed: $error", error is IrohRouteReadException)
                assertSame(failure, error?.cause)
            }
            assertEquals("each initialize() reads the route again", 2, harness.discardCount)
            assertEquals("nothing is published before the route is read", unpublished, harness.state.value)
            assertEquals(0, harness.startCount + harness.captureCount)
            assertRouteReport(reports)
            arm(harness, null)
            harness.orchestration.initialize()
            assertEquals(1, harness.captureCount)
            assertEquals(ReplicationMode.IROH, harness.state.value.mode)
            assertEquals(RoomId, harness.state.value.roomId)
            assertEquals(2, harness.state.value.operationCount)
            assertRouteReport(reports)
        }
    }

    private fun assertRouteReport(reports: List<Throwable>) {
        assertEquals(listOf(RouteReport), reports.map { it.message })
        val report = reports.single()
        assertNull("the report must not attach the storage cause", report.cause)
        listOf("r54 a07", RoomId, "SELECT").forEach { secret ->
            assertFalse("report leaked $secret", report.toString().contains(secret, ignoreCase = true))
        }
    }

    private suspend fun withRetainedRoom(
        test: suspend (IrohRoomOrchestrationHarness, List<Throwable>) -> Unit,
    ) {
        val previousReporter = CrashReporter.delegate
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val reports = CopyOnWriteArrayList<Throwable>()
        CrashReporter.delegate = reports::add
        CrashReportingRuntime.reportingEnabled = true
        CorruptStateReporter.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        val harness = IrohRoomOrchestrationHarness(ReplicationMode.IROH, RoomId)
        val settings = harness.settings
        val room = harness.room
        try {
            test(harness, reports)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
            assertEquals(settings, harness.settings)
            assertEquals(room, harness.room)
            assertTrue("the route must not change", harness.modes.isEmpty())
            assertEquals(0, harness.clearCount + harness.scrubCount + harness.discardedRooms.size)
        } finally {
            harness.orchestration.close()
            CrashReporter.delegate = previousReporter
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CorruptStateReporter.resetForTest()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private companion object {
        const val RoomId = "room-test0001"
        const val RouteReport = "Local storage read failed: replication route"
    }
}
