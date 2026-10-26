package me.egigoka.pomodorough.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R52-A03: a refused claim-proof commit must retry with backoff,
 * never masquerade as successful empty work in a tight loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R52A03ClaimPersistenceFailureTest {
    @Test
    fun persistenceFailureBacksOffInsteadOfTightLoop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var prepares = 0
        var syncCalls = 0
        val accepted = mutableListOf<CentralizedSyncRuntimeEvent>()
        val host = r52A03Host(
            prepare = { prepares += 1; throw IOException("R52A03-store") },
            accept = { accepted += it },
        )
        val runtime = r52A03Runtime(dispatcher, host) { request ->
            syncCalls += 1
            syncResponse(request.lastRevision)
        }

        runtime.requestSync(force = true)
        runCurrent()
        assertEquals(1, prepares)
        assertEquals(0, syncCalls)
        val identity = SyncAttemptIdentity(1, "attempt-id")
        assertEquals(listOf(CentralizedSyncRuntimeEvent.Retrying(identity, 1_000)), accepted)
        runCurrent()
        assertEquals(1, prepares)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, prepares)
        assertEquals(0, syncCalls)
        runtime.shutdown()
    }

    @Test
    fun unexpectedPersistenceFailureStopsAsLocalFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var prepares = 0
        var syncCalls = 0
        val accepted = mutableListOf<CentralizedSyncRuntimeEvent>()
        val host = r52A03Host(
            prepare = { prepares += 1; throw RuntimeException("R52A03-boom") },
            accept = { accepted += it },
        )
        val runtime = r52A03Runtime(dispatcher, host) { request ->
            syncCalls += 1
            syncResponse(request.lastRevision)
        }

        runtime.requestSync(force = true)
        runCurrent()
        assertEquals(1, prepares)
        assertEquals(0, syncCalls)
        val event = accepted.filterIsInstance<CentralizedSyncRuntimeEvent.LocalFailure>().single()
        assertEquals(SyncAttemptIdentity(1, "attempt-id"), event.identity)
        assertTrue(accepted.none { it is CentralizedSyncRuntimeEvent.Retrying })
        runCurrent()
        assertEquals(1, prepares)
        runtime.shutdown()
    }

    @Test
    fun emptyPreparationStillSucceedsWithoutRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var syncCalls = 0
        val accepted = mutableListOf<CentralizedSyncRuntimeEvent>()
        val host = r52A03Host(prepare = { null }, accept = { accepted += it })
        host.snapshot = host.snapshot.copy(pendingQueuesEmpty = true)
        val runtime = r52A03Runtime(dispatcher, host) { request ->
            syncCalls += 1
            syncResponse(request.lastRevision)
        }

        runtime.requestSync(force = true)
        runCurrent()
        assertEquals(0, syncCalls)
        assertTrue(accepted.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun retireFailurePropagatesInsteadOfReturningNull() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
        val start = source.indexOf("timerStore.retireNeverSent(")
        assertTrue(start >= 0)
        val window = source.drop(start).take(500)
        assertTrue(window.contains("throw error"))
        assertFalse(window.contains("return null"))
    }
}

private fun r52A03Runtime(
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

private fun r52A03Host(
    prepare: suspend (SyncAttemptIdentity) -> SyncAttempt?,
    accept: suspend (CentralizedSyncRuntimeEvent) -> Unit,
) = R52A03Host(prepare, accept)

private class R52A03Host(
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

    override fun revisionStreamAdmission() = RevisionStreamAdmission(
        accountGeneration = accountGeneration(),
        eligible = true,
    )

    override suspend fun prepareSyncAttempt(identity: SyncAttemptIdentity): SyncAttempt? =
        prepare(identity)

    override suspend fun accept(event: CentralizedSyncRuntimeEvent) = accept.invoke(event)
}
