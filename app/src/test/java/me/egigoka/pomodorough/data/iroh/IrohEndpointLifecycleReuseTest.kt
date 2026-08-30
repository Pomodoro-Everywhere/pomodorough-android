package me.egigoka.pomodorough.data.iroh

import computer.iroh.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrohEndpointLifecycleReuseTest {
    @Test
    fun cancelledSyncJobRestartsOnSameRoomWithoutRebinding() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        try {
            val ticket = fixture.start()
            runCurrent()
            val session = checkNotNull(fixture.lifecycle.session())
            val cancelled = fixture.runs.single()
            cancelled.cancelAndJoin()
            assertEquals(ticket, fixture.start())
            runCurrent()
            assertEquals(2, fixture.runs.size)
            assertNotSame(cancelled, fixture.runs.last())
            assertTrue(fixture.runs.last().isActive)
            assertSame(session.endpoint, fixture.lifecycle.session()?.endpoint)
            assertEquals(session.owner, fixture.lifecycle.generation())
            assertEquals(1, fixture.bindCount)
        } finally {
            fixture.lifecycle.close()
        }
    }

    @Test
    fun activeSyncJobIsReusedAcrossConcurrentStarts() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.start()
            repeat(10) { launch { fixture.start() } }
            runCurrent()
            assertEquals(1, fixture.bindCount)
            assertEquals(1, fixture.runs.size)
            assertTrue(fixture.runs.single().isActive)
        } finally {
            fixture.lifecycle.close()
        }
    }

    @Test
    fun completedSyncJobRestartsOnSameRoom() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        fixture.syncAction = {}
        try {
            fixture.start()
            runCurrent()
            assertTrue(fixture.runs.single().isCompleted)
            fixture.start()
            runCurrent()
            assertEquals(2, fixture.runs.size)
            assertEquals(1, fixture.bindCount)
        } finally {
            fixture.lifecycle.close()
        }
    }

    @Test
    fun reuseWithoutPeriodicRequestDoesNotRestartCancelledJob() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.start()
            runCurrent()
            fixture.runs.single().cancelAndJoin()
            fixture.start(periodic = false)
            runCurrent()
            assertEquals(1, fixture.runs.size)
            fixture.start()
            runCurrent()
            assertEquals(2, fixture.runs.size)
        } finally {
            fixture.lifecycle.close()
        }
    }

    @Test
    fun cancellingJobFinishesCleanupBeforeReplacementStarts() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        val cleanup = CompletableDeferred<Unit>()
        fixture.delayCancellationUntil(cleanup)
        try {
            fixture.start()
            runCurrent()
            val cancelled = fixture.runs.single()
            cancelled.cancel()
            runCurrent()
            val restart = launch { fixture.start() }
            runCurrent()
            assertFalse(restart.isCompleted)
            assertEquals(1, fixture.runs.size)
            cleanup.complete(Unit)
            runCurrent()
            assertTrue(cancelled.isCompleted)
            assertTrue(restart.isCompleted)
            assertEquals(2, fixture.runs.size)
        } finally {
            cleanup.complete(Unit)
            fixture.lifecycle.close()
        }
    }

    @Test
    fun closeDuringCancelledJobCleanupPreventsResurrection() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        val cleanup = CompletableDeferred<Unit>()
        fixture.delayCancellationUntil(cleanup)
        try {
            fixture.start()
            runCurrent()
            fixture.runs.single().cancel()
            var failure: Throwable? = null
            launch { failure = runCatching { fixture.start() }.exceptionOrNull() }
            runCurrent()
            val closing = launch { fixture.lifecycle.close() }
            runCurrent()
            cleanup.complete(Unit)
            runCurrent()
            assertTrue(closing.isCompleted)
            assertTrue(failure is IllegalStateException)
            assertEquals(1, fixture.runs.size)
            assertNull(fixture.lifecycle.session())
            assertTrue(runCatching { fixture.start() }.isFailure)
        } finally {
            cleanup.complete(Unit)
            fixture.lifecycle.close()
        }
    }

    @Test
    fun cancelledRestartCallerDoesNotLaunchReplacement() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        val cleanup = CompletableDeferred<Unit>()
        fixture.delayCancellationUntil(cleanup)
        try {
            fixture.start()
            runCurrent()
            fixture.runs.single().cancel()
            val restart = launch { fixture.start() }
            runCurrent()
            restart.cancelAndJoin()
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.runs.size)
            fixture.start()
            runCurrent()
            assertEquals(2, fixture.runs.size)
        } finally {
            cleanup.complete(Unit)
            fixture.lifecycle.close()
        }
    }

    @Test
    fun stopThenNewRoomRejectsStaleOwnerQuarantine() = runTest {
        val fixture = LifecycleFixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.start()
            runCurrent()
            val stale = checkNotNull(fixture.lifecycle.session())
            fixture.lifecycle.stop()
            assertNull(fixture.lifecycle.session())
            assertTrue(fixture.runs.single().isCancelled)
            fixture.start(roomId = "next-room")
            runCurrent()
            fixture.lifecycle.quarantine("room", stale.owner)
            runCurrent()
            assertEquals("next-room", fixture.lifecycle.session()?.context?.roomId)
            assertEquals(2, fixture.bindCount)
            assertEquals(2, fixture.runs.size)
            assertTrue(fixture.runs.last().isActive)
        } finally {
            fixture.lifecycle.close()
        }
    }
}

private class LifecycleFixture(dispatcher: CoroutineDispatcher) : IrohEndpointBinding {
    var bindCount = 0
    val runs = mutableListOf<Job>()
    var syncAction: suspend () -> Unit = { awaitCancellation() }
    val lifecycle = IrohEndpointLifecycle(this, {}, dispatcher)

    override suspend fun bind(): Endpoint {
        bindCount += 1
        return IrohSyncTestEndpoint()
    }

    override fun ticket(endpoint: Endpoint) = "test-ticket-$bindCount"

    suspend fun start(roomId: String = "room", periodic: Boolean = true): String = lifecycle.start(
        IrohServiceContext(roomId, ByteArray(32) { 42 }, "device", null), periodic,
        { _, _ -> awaitCancellation() },
        {
            runs += currentCoroutineContext().job
            syncAction()
        },
    )

    fun delayCancellationUntil(cleanup: CompletableDeferred<Unit>) {
        syncAction = {
            try {
                awaitCancellation()
            } catch (error: CancellationException) {
                withContext(NonCancellable) { cleanup.await() }
                throw error
            }
        }
    }
}
