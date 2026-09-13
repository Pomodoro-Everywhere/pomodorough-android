package me.egigoka.pomodorough.data.iroh

import computer.iroh.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrohEndpointLifecycleStopTest {
    @Test
    fun stopWithLiveJobsCompletesAndEmitsStopped() = runTest {
        val fixture = StopFixture(StandardTestDispatcher(testScheduler))
        try {
            fixture.start()
            runCurrent()
            assertTrue(fixture.runs.single().isActive)
            fixture.lifecycle.stop()
            runCurrent()
            assertNull(fixture.lifecycle.session())
            assertTrue(fixture.runs.single().isCancelled)
            assertTrue(fixture.events.any { it is IrohEndpointEvent.Stopped })
            assertEquals(1, fixture.lastEndpoint?.shutdownCount)
            assertTrue(checkNotNull(fixture.lastContext).roomSecret.all { it == 0.toByte() })
        } finally {
            fixture.lifecycle.close()
        }
    }

    @Test
    fun stopPropagatesCallerCancellation() = runTest {
        val fixture = StopFixture(StandardTestDispatcher(testScheduler))
        val cleanup = CompletableDeferred<Unit>()
        fixture.delayCancellationUntil(cleanup)
        try {
            fixture.start()
            runCurrent()
            var failure: Throwable? = null
            val stopping = launch {
                failure = runCatching { fixture.lifecycle.stop() }.exceptionOrNull()
            }
            runCurrent()
            assertFalse(stopping.isCompleted)
            stopping.cancel()
            runCurrent()
            cleanup.complete(Unit)
            runCurrent()
            assertTrue(stopping.isCompleted)
            assertTrue(failure is CancellationException)
        } finally {
            cleanup.complete(Unit)
            fixture.lifecycle.close()
        }
    }
}

private class StopFixture(dispatcher: CoroutineDispatcher) : IrohEndpointBinding {
    val runs = mutableListOf<Job>()
    val events = mutableListOf<IrohEndpointEvent>()
    var lastEndpoint: IrohSyncTestEndpoint? = null
    var lastContext: IrohServiceContext? = null
    var syncAction: suspend () -> Unit = { awaitCancellation() }
    val lifecycle = IrohEndpointLifecycle(this, { events += it }, dispatcher)

    override suspend fun bind(): Endpoint {
        return IrohSyncTestEndpoint().also { lastEndpoint = it }
    }

    override fun ticket(endpoint: Endpoint) = "test-ticket"

    suspend fun start(): String {
        val context = IrohServiceContext("room", ByteArray(32) { 42 }, "device", null)
        lastContext = context
        return lifecycle.start(
            context,
            true,
            { _, _ -> awaitCancellation() },
            {
                runs += currentCoroutineContext().job
                syncAction()
            },
        )
    }

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
