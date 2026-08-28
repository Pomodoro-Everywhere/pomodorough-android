package me.egigoka.pomodorough.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CentralizedSyncRuntimeTest {
    @Test
    fun responseUsesAttemptCapturedBeforeHttpSuspension() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val response = CompletableDeferred<SyncResponse>()
        val firstAttempt = syncAttempt(accountGeneration = 7)
        var preparedAttempt = firstAttempt
        val accepted = mutableListOf<CentralizedSyncRuntimeEvent>()
        val host = FakeCentralizedSyncRuntimeHost(
            prepare = { preparedAttempt },
            accept = { accepted += it },
        )
        val runtime = runtime(dispatcher, host) { request ->
            assertSame(firstAttempt.request, request)
            response.await()
        }

        runtime.requestSync(force = true)
        runCurrent()
        preparedAttempt = syncAttempt(accountGeneration = 8)
        response.complete(syncResponse(revision = 11))
        runCurrent()

        val event = accepted.filterIsInstance<CentralizedSyncRuntimeEvent.SyncResponseReady>().single()
        assertSame(firstAttempt, event.attempt)
        assertEquals(11, event.response.revision)
        assertEquals(300, event.receivedPhysicalMs)
        assertEquals(400, event.receivedElapsedRealtimeMs)
        runtime.shutdown()
    }

    @Test
    fun shutdownCancelsRetryBackoffWithoutAnotherRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val accepted = mutableListOf<CentralizedSyncRuntimeEvent>()
        val host = FakeCentralizedSyncRuntimeHost(
            prepare = { syncAttempt(accountGeneration = 1) },
            accept = { accepted += it },
        )
        val runtime = runtime(dispatcher, host) {
            calls += 1
            throw IOException("offline")
        }

        runtime.requestSync(force = true)
        runCurrent()
        assertEquals(
            listOf(CentralizedSyncRuntimeEvent.Retrying(SyncAttemptIdentity(1, "attempt-id"), 1_000)),
            accepted,
        )

        runtime.shutdown()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun retryBackoffDoublesAndCapsAtSixtySeconds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val accepted = mutableListOf<CentralizedSyncRuntimeEvent>()
        val host = FakeCentralizedSyncRuntimeHost(
            prepare = { syncAttempt(accountGeneration = 1) },
            accept = { accepted += it },
        )
        val runtime = runtime(dispatcher, host) {
            calls += 1
            if (calls <= 8) throw IOException("offline-$calls")
            syncResponse(revision = 12)
        }
        val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)

        runtime.requestSync(force = true)
        runCurrent()
        delays.forEach { delayMs ->
            advanceTimeBy(delayMs)
            runCurrent()
        }

        assertEquals(9, calls)
        assertEquals(
            delays,
            accepted.filterIsInstance<CentralizedSyncRuntimeEvent.Retrying>().map { it.delayMs },
        )
        runtime.shutdown()
    }

    @Test
    fun shutdownWaitsForInFlightRequestCancellationCleanup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cleanupFinished = CompletableDeferred<Unit>()
        val host = FakeCentralizedSyncRuntimeHost(
            prepare = { syncAttempt(accountGeneration = 1) },
            accept = {},
        )
        val runtime = runtime(dispatcher, host) {
            try {
                awaitCancellation()
            } finally {
                cleanupFinished.complete(Unit)
            }
        }

        runtime.requestSync(force = true)
        runCurrent()
        runtime.shutdown()

        assertEquals(true, cleanupFinished.isCompleted)
    }

    private fun runtime(
        dispatcher: TestDispatcher,
        host: CentralizedSyncRuntimeHost,
        executeSync: suspend (SyncRequest) -> SyncResponse,
    ) = CentralizedSyncRuntime(
        initialized = CompletableDeferred(Unit),
        initialRetryDelayMs = 1_000,
        remoteSyncIntervalMs = 3_600_000,
        currentTimeMillis = { 300 },
        elapsedRealtimeMillis = { 400 },
        initialOnline = true,
        dispatcher = dispatcher,
        json = Json,
        host = host,
        executeSync = executeSync,
        openRevisionStream = { _: EventSourceListener -> error("Unused") },
        newAttemptId = { "attempt-id" },
    )
}

private class FakeCentralizedSyncRuntimeHost(
    private val prepare: suspend (SyncAttemptIdentity) -> SyncAttempt?,
    private val accept: suspend (CentralizedSyncRuntimeEvent) -> Unit,
) : CentralizedSyncRuntimeHost {
    var snapshot = CentralizedSyncRuntimeSnapshot(
        signedIn = true,
        centralized = true,
        resolutionPending = false,
        accountSwitchPending = false,
        terminalSyncError = false,
        pendingQueuesEmpty = false,
        localRevision = 0,
    )

    override fun snapshot(): CentralizedSyncRuntimeSnapshot = snapshot

    override fun accountGeneration(): Long = 1

    override suspend fun prepareSyncAttempt(identity: SyncAttemptIdentity): SyncAttempt? = prepare(identity)

    override suspend fun accept(event: CentralizedSyncRuntimeEvent) {
        if (event is CentralizedSyncRuntimeEvent.SyncResponseReady) {
            snapshot = snapshot.copy(pendingQueuesEmpty = true)
        }
        accept.invoke(event)
    }
}

internal fun syncAttempt(accountGeneration: Long) = SyncAttempt(
    identity = SyncAttemptIdentity(accountGeneration, "attempt-id"),
    request = SyncRequest(
        deviceId = "device",
        lastRevision = 3,
        commands = emptyList(),
        durationOperations = emptyList(),
    ),
    sentPhysicalMs = 100,
    sentElapsedRealtimeMs = 200,
    selectedPhaseAtSend = TimerPhase.Focus,
    selectedPhaseGenerationAtSend = 4,
)

internal fun syncResponse(revision: Long) = SyncResponse(
    acknowledgements = emptyList(),
    revision = revision,
    canonicalTimer = null,
    history = emptyList(),
    serverTime = "1970-01-01T00:00:00Z",
    serverHlcWallMs = 0,
    serverHlcCounter = 0,
    durationAcknowledgements = emptyList(),
    durationsMs = DurationsMs(),
    taskAcknowledgements = emptyList(),
    tasks = emptyList(),
)
