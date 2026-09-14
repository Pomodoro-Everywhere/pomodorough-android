package me.egigoka.pomodorough.data.iroh

import computer.iroh.Endpoint
import computer.iroh.EndpointId
import computer.iroh.NoHandle
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
class IrohEndpointLifecycleCloseTest {
    @Test
    fun alreadyCancelledCloseCleansUpBeforePropagatingAndRetryIsSafe() = runTest {
        val fixture = CloseFixture(StandardTestDispatcher(testScheduler))
        fixture.start()
        runCurrent()
        var failure: Throwable? = null
        val closing = launch {
            currentCoroutineContext().job.cancel()
            failure = runCatching { fixture.lifecycle.close() }.exceptionOrNull()
        }
        runCurrent()
        assertTrue(closing.isCompleted)
        assertTrue(failure is CancellationException)
        fixture.assertClosed()
        fixture.lifecycle.close()
        fixture.assertClosed()
        assertTrue(runCatching { fixture.start() }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun cancelledCloseWaitsForLifecycleMutexAndCleansActivatedEndpoint() = runTest {
        val fixture = CloseFixture(StandardTestDispatcher(testScheduler))
        fixture.bindGate = CompletableDeferred()
        val starting = launch { fixture.start() }
        runCurrent()
        var failure: Throwable? = null
        val closing = launch {
            failure = runCatching { fixture.lifecycle.close() }.exceptionOrNull()
        }
        runCurrent()
        closing.cancel()
        runCurrent()
        assertFalse(closing.isCompleted)
        fixture.bindGate.complete(Unit)
        runCurrent()
        assertTrue(starting.isCompleted)
        assertTrue(closing.isCompleted)
        assertTrue(failure is CancellationException)
        fixture.assertClosed()
        fixture.lifecycle.close()
        fixture.assertClosed()
    }

    @Test
    fun cancelledCloseAndConcurrentRetryWaitForBothChildCleanups() = runTest {
        val fixture = CloseFixture(StandardTestDispatcher(testScheduler))
        fixture.childGate = CompletableDeferred()
        fixture.start()
        runCurrent()
        var failure: Throwable? = null
        val closing = launch {
            failure = runCatching { fixture.lifecycle.close() }.exceptionOrNull()
        }
        runCurrent()
        closing.cancel()
        val retry = launch { fixture.lifecycle.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertFalse(retry.isCompleted)
        assertEquals(2, fixture.jobs.size)
        assertTrue(fixture.jobs.all { it.isCancelled && !it.isCompleted })
        assertEquals(0, fixture.endpoint.shutdownCount)
        fixture.childGate.complete(Unit)
        runCurrent()
        assertTrue(closing.isCompleted)
        assertTrue(retry.isCompleted)
        assertTrue(failure is CancellationException)
        fixture.assertClosed()
    }

    @Test
    fun cancellationDuringEndpointShutdownWaitsBeforeClosingHandle() = runTest {
        val fixture = CloseFixture(StandardTestDispatcher(testScheduler))
        fixture.endpoint.shutdownGate = CompletableDeferred()
        fixture.start()
        runCurrent()
        var failure: Throwable? = null
        val closing = launch {
            failure = runCatching { fixture.lifecycle.close() }.exceptionOrNull()
        }
        runCurrent()
        assertEquals(1, fixture.endpoint.shutdownCount)
        closing.cancel()
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, fixture.endpoint.closeCount)
        fixture.endpoint.shutdownGate.complete(Unit)
        runCurrent()
        assertTrue(failure is CancellationException)
        fixture.assertClosed()
        fixture.lifecycle.close()
        fixture.assertClosed()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class CloseFixture(dispatcher: CoroutineDispatcher) : IrohEndpointBinding {
    val endpoint = CloseTestEndpoint()
    val context = IrohServiceContext("room", ByteArray(32) { 42 }, "device", null)
    val jobs = mutableListOf<Job>()
    val parents = mutableListOf<Job>()
    var bindGate = CompletableDeferred(Unit)
    var childGate = CompletableDeferred(Unit)
    val lifecycle = IrohEndpointLifecycle(this, {}, dispatcher)

    override suspend fun bind(): Endpoint {
        bindGate.await()
        return endpoint
    }

    override fun ticket(endpoint: Endpoint) = "test-ticket"

    suspend fun start() = lifecycle.start(context, true, { _, _ -> runChild() }, { runChild() })

    private suspend fun runChild() {
        val job = currentCoroutineContext().job
        jobs += job
        parents += checkNotNull(job.parent)
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { childGate.await() }
        }
    }

    fun assertClosed() {
        assertNull(lifecycle.session())
        assertTrue(context.roomSecret.all { it == 0.toByte() })
        assertTrue(jobs.all { it.isCancelled && it.isCompleted })
        assertTrue(parents.all { it.isCancelled && it.isCompleted })
        assertEquals(1, endpoint.shutdownCount)
        assertEquals(1, endpoint.closeCount)
    }
}

private class CloseTestEndpoint : Endpoint(NoHandle) {
    var shutdownCount = 0
    var closeCount = 0
    var shutdownGate = CompletableDeferred(Unit)

    override fun isClosed() = closeCount > 0

    override suspend fun shutdown() {
        shutdownCount += 1
        shutdownGate.await()
    }

    override fun close() {
        closeCount += 1
        super.close()
    }

    override fun id(): EndpointId = object : EndpointId(NoHandle) {
        override fun fmtShort() = "test-endpoint"
    }
}
