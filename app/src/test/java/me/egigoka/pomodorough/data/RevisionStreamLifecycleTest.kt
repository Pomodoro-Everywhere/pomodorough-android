package me.egigoka.pomodorough.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RevisionStreamLifecycleTest {
    @Test
    fun backgroundCloseCompletesBeforeNextForegroundOpen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val log = mutableListOf<String>()
        val streams = mutableListOf<FakeEventSource>()
        val lifecycle = lifecycle(dispatcher) { listener ->
            FakeEventSource("stream-${streams.size + 1}", listener, log).also(streams::add)
        }

        lifecycle.onForeground()
        runCurrent()
        lifecycle.onBackground()
        lifecycle.onForeground()
        runCurrent()

        assertEquals(listOf("open:stream-1", "cancel:stream-1", "open:stream-2"), log)
        assertTrue(streams.first().cancelled)
        assertFalse(streams.last().cancelled)
        lifecycle.shutdown()
    }

    @Test
    fun staleFailureCannotClearOrReconnectReplacementStream() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val log = mutableListOf<String>()
        val streams = mutableListOf<FakeEventSource>()
        val lifecycle = lifecycle(dispatcher) { listener ->
            FakeEventSource("stream-${streams.size + 1}", listener, log).also(streams::add)
        }

        lifecycle.onForeground()
        runCurrent()
        val stale = streams.single()
        lifecycle.onBackground()
        lifecycle.onForeground()
        runCurrent()
        stale.listener.onFailure(stale, null, null)
        testScheduler.advanceTimeBy(10_000)
        runCurrent()

        assertEquals(2, streams.size)
        assertFalse(streams.last().cancelled)
        lifecycle.shutdown()
    }

    @Test
    fun revisionPayloadsBecomeTypedEventsInArrivalOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val events = mutableListOf<RevisionStreamEvent>()
        lateinit var stream: FakeEventSource
        val lifecycle = lifecycle(dispatcher, events::add) { listener ->
            FakeEventSource("stream", listener, mutableListOf()).also { stream = it }
        }

        lifecycle.onForeground()
        runCurrent()
        stream.listener.onEvent(stream, null, null, "17")
        stream.listener.onEvent(stream, null, null, "{\"revision\":19}")

        assertEquals(
            listOf(
                RevisionStreamEvent.RevisionObserved(17),
                RevisionStreamEvent.RevisionObserved(19),
            ),
            events,
        )
        lifecycle.shutdown()
    }

    @Test
    fun transientOpenFailureRetriesWithCapturedGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val lifecycle = lifecycle(dispatcher) { listener ->
            calls += 1
            if (calls == 1) throw IOException("stream unavailable")
            FakeEventSource("stream", listener, mutableListOf())
        }

        lifecycle.onForeground()
        runCurrent()
        assertEquals(1, calls)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, calls)
        lifecycle.shutdown()
    }

    @Test
    fun authenticationFailureAdmittedBeforeDeletionCarriesStaleGeneration() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var accountGeneration = 7L
        var authenticationAdvances = 0
        val expiredGenerations = mutableListOf<Long>()
        val lifecycle = lifecycle(
            dispatcher = dispatcher,
            eligible = {
                val admitted = true
                accountGeneration += 1 // Account deletion is prepared after this admission decision.
                admitted
            },
            accountGeneration = { accountGeneration },
            expireAuthentication = { admittedGeneration ->
                expiredGenerations += admittedGeneration
                if (admittedGeneration == accountGeneration) authenticationAdvances += 1
            },
        ) { throw AuthenticationRequired() }

        lifecycle.onForeground()
        runCurrent()

        assertEquals(8L, accountGeneration)
        assertEquals(listOf(7L), expiredGenerations)
        assertEquals(0, authenticationAdvances)
        lifecycle.shutdown()
    }

    private fun lifecycle(
        dispatcher: TestDispatcher,
        emit: (RevisionStreamEvent) -> Unit = {},
        eligible: () -> Boolean = { true },
        accountGeneration: () -> Long = { 0L },
        expireAuthentication: suspend (Long) -> Unit = {},
        open: suspend (EventSourceListener) -> EventSource,
    ): RevisionStreamLifecycle {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return RevisionStreamLifecycle(
            scope = scope,
            initialized = CompletableDeferred(Unit),
            json = Json,
            reconnectDelayMs = 5_000,
            initialOnline = true,
            admission = {
                val generation = accountGeneration()
                RevisionStreamAdmission(generation, eligible())
            },
            open = open,
            emit = emit,
            expireAuthentication = expireAuthentication,
        )
    }
}

private class FakeEventSource(
    private val name: String,
    val listener: EventSourceListener,
    log: MutableList<String>,
) : EventSource {
    var cancelled = false
        private set
    private val log = log.also { it += "open:$name" }

    override fun request(): Request = Request.Builder().url("https://example.test/$name").build()

    override fun cancel() {
        cancelled = true
        log += "cancel:$name"
    }
}
