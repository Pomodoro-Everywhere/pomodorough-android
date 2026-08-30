package me.egigoka.pomodorough.data.iroh

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrohPeerSynchronizationTimeoutTest {
    @Test
    fun peerDeadlineContinuesToHealthyPeerAndPeriodicRetries() = runTest {
        val fixture = PeerFixture { peer -> if (peer.endpointId == "stalled") awaitCancellation() }
        val job = backgroundScope.launch { fixture.sync.periodicSyncLoop(1L) }
        advanceTimeBy(45_000)
        runCurrent()
        assertTrue(job.isActive)
        assertEquals(listOf("stalled", "healthy"), fixture.attempts)
        assertEquals(IrohConnectionStatus.LISTENING, fixture.statuses.last())
        advanceTimeBy(180_000)
        runCurrent()
        assertTrue(job.isActive)
        assertTrue(fixture.attempts.count { it == "healthy" } >= 3)
    }

    @Test
    fun repeatedPeerDeadlinesKeepRetryingWithoutHealthyPeers() = runTest {
        val fixture = PeerFixture(peerIds = listOf("stalled")) { awaitCancellation() }
        val job = backgroundScope.launch { fixture.sync.periodicSyncLoop(1L) }
        advanceTimeBy(240_000)
        runCurrent()
        assertTrue(job.isActive)
        assertTrue(fixture.attempts.size >= 4)
        assertFalse(fixture.statuses.contains(IrohConnectionStatus.LISTENING))
        assertTrue(fixture.statuses.contains(IrohConnectionStatus.WAITING_FOR_PEERS))
    }

    @Test
    fun cancellingPeriodicCallerSkipsHealthyPeerAndFurtherRetries() = runTest {
        val fixture = PeerFixture { awaitCancellation() }
        val job = backgroundScope.launch { fixture.sync.periodicSyncLoop(1L) }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(240_000)
        assertTrue(job.isCancelled)
        assertEquals(listOf("stalled"), fixture.attempts)
        assertTrue(fixture.statuses.isEmpty())
    }

    @Test
    fun outerCallerTimeoutIsNotClassifiedAsPeerFailure() = runTest {
        val fixture = PeerFixture { awaitCancellation() }
        var failure: Throwable? = null
        val job = launch {
            try {
                withTimeout(1_000) { fixture.sync.periodicSyncLoop(1L) }
            } catch (error: CancellationException) {
                failure = error
                throw error
            }
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(listOf("stalled"), fixture.attempts)
    }

    @Test
    fun syncNowDeadlineStillCancelsSecondPeer() = runTest {
        val fixture = PeerFixture { awaitCancellation() }
        val job = launch { fixture.sync.syncNow() }
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf("stalled", "healthy"), fixture.attempts)
        assertTrue(fixture.statuses.isEmpty())
    }

    @Test
    fun nestedPeerRequestTimeoutContinuesToHealthyPeer() = runTest {
        val fixture = PeerFixture { peer ->
            if (peer.endpointId == "stalled") withTimeout(30_000) { awaitCancellation() }
        }
        val job = backgroundScope.launch { fixture.sync.periodicSyncLoop(1L) }
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(job.isActive)
        assertEquals(listOf("stalled", "healthy"), fixture.attempts)
    }

    @Test
    fun explicitCancellationFromPeerIsRethrownUnchanged() = runTest {
        val cancellation = CancellationException("service stopped")
        val fixture = PeerFixture { throw cancellation }
        val failure = runCatching { fixture.sync.syncNow() }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertEquals(listOf("stalled"), fixture.attempts)
    }

    @Test
    fun ordinaryPeerFailureStillContinuesToHealthyPeer() = runTest {
        val fixture = PeerFixture { peer ->
            if (peer.endpointId == "stalled") throw IOException("connection failed")
        }
        fixture.sync.syncNow()
        assertEquals(listOf("stalled", "healthy"), fixture.attempts)
        assertEquals(IrohConnectionStatus.LISTENING, fixture.statuses.last())
    }

    @Test
    fun generationChangeDuringTimedOutAttemptSkipsRemainingPeers() = runTest {
        val fixture = PeerFixture { awaitCancellation() }
        val job = backgroundScope.launch { fixture.sync.periodicSyncLoop(1L) }
        runCurrent()
        fixture.owner += 1
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(listOf("stalled"), fixture.attempts)
    }

    @Test
    fun peerStorageCancellationPropagatesWithoutUnavailableStatus() = runTest {
        val cancellation = CancellationException("storage cancelled")
        val fixture = PeerFixture {}
        fixture.peersFailure = cancellation
        assertSame(cancellation, runCatching { fixture.sync.syncNow() }.exceptionOrNull())
        assertTrue(fixture.attempts.isEmpty())
        assertTrue(fixture.statuses.isEmpty())
    }
}

private class PeerFixture(
    private val peerIds: List<String> = listOf("stalled", "healthy"),
    exchange: suspend (IrohPeerEntity) -> Unit,
) : IrohEndpointSessionSource {
    var owner = 1L
    var peersFailure: CancellationException? = null
    val attempts = mutableListOf<String>()
    val statuses = mutableListOf<IrohConnectionStatus>()
    private val endpoint = IrohSyncTestEndpoint()
    private val context = IrohServiceContext("room", ByteArray(32) { 42 }, "device", null)
    private val transport = IrohEndpointTransport()
    private val authentication = IrohPeerAuthentication(
        transport, IrohPeerAuthorization(IrohEndpointTicketIdentity { it }), { "local-ticket" }, {},
    )
    private val incoming = IrohIncomingRpcHandler(
        this, authentication, transport,
        IrohIncomingRpcDependencies({ _, _, _ -> emptyList<IrohInventoryEntry>() to null }, { _, _ -> emptyList() }),
        {},
    )
    val sync = IrohPeerSynchronization(
        this, transport, authentication, incoming, dependencies(),
        { event -> if (event is IrohPeerSyncEvent.Status) statuses += event.status }, Random(1),
        exchangePeer = { _, _, _, peer ->
            attempts += peer.endpointId
            exchange(peer)
        },
    )

    override fun session() = IrohEndpointSession(endpoint, context, "local-ticket", owner)

    override fun generation() = owner

    private fun dependencies() = IrohPeerSyncDependencies(
        peers = {
            peersFailure?.let { throw it }
            peerIds.map { IrohPeerEntity("room", it, it, null, null, null) }
        },
        snapshot = { IrohNetworkState() },
        hasGenesis = { true },
        missingReferences = { _, _ -> emptyList() },
        insertRemoteRecords = { _, _ -> },
        refreshProjection = { error("No records changed") },
        onProjection = {},
    )
}
