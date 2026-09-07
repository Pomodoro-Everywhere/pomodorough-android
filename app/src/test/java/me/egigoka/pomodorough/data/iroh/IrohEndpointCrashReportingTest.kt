package me.egigoka.pomodorough.data.iroh

import computer.iroh.Endpoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrohEndpointCrashReportingTest {
    @Test
    fun unexpectedBindFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        val events = mutableListOf<IrohEndpointEvent>()
        val lifecycle = IrohEndpointLifecycle(
            FailingBinding(RuntimeException("native bind failed")),
            { events += it },
            StandardTestDispatcher(testScheduler),
        )
        try {
            val failure = runCatching {
                lifecycle.start(
                    IrohServiceContext("room", ByteArray(32) { 7 }, "device", null),
                    true,
                    { _, _ -> awaitCancellation() },
                    { awaitCancellation() },
                )
            }.exceptionOrNull()
            runCurrent()
            assertTrue(failure is RuntimeException)
            assertEquals(1, reported.size)
            assertTrue(events.any {
                it is IrohEndpointEvent.Status && it.status == IrohConnectionStatus.UNAVAILABLE
            })
        } finally {
            CrashReporter.delegate = previous
            lifecycle.close()
        }
    }

    private class FailingBinding(private val failure: Exception) : IrohEndpointBinding {
        override suspend fun bind(): Endpoint = throw failure
        override fun ticket(endpoint: Endpoint): String = error("unreachable")
    }
}
