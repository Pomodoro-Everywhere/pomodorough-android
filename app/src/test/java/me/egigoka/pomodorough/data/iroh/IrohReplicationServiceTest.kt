package me.egigoka.pomodorough.data.iroh

import computer.iroh.Endpoint
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.local.IrohPeerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohReplicationServiceTest {
    @Test
    fun retryDelayCapsFinalJitteredValue() {
        assertEquals(15_000L, cappedRetryDelayMs(15_000L, 0L))
        assertEquals(60_000L, cappedRetryDelayMs(60_000L, 12_000L))
        assertEquals(60_000L, cappedRetryDelayMs(55_000L, 11_000L))
        assertThrows(IllegalArgumentException::class.java) {
            cappedRetryDelayMs(1_000L, -1L)
        }
    }

    @Test
    fun updateStateOrdinarySnapshotFailureStaysSilentAndPublishesStatus() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val service = IrohReplicationService(
                store = FakeStore(snapshotFailure = IOException("storage gone")),
                binding = NoopBinding(),
                onProjection = {},
            )
            service.updateState(
                IrohConnectionStatus.LISTENING,
                roomId = "room-test0001",
                endpointMark = "mark-1",
                message = "hello",
            )
            assertTrue(reported.isEmpty())
            assertEquals(IrohConnectionStatus.LISTENING, service.state.value.status)
            assertEquals("room-test0001", service.state.value.roomId)
            assertEquals("mark-1", service.state.value.endpointMark)
            assertEquals("hello", service.state.value.message)
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun updateStateCancellationPropagatesWithoutPublishing() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val cancellation = CancellationException("gone")
            val service = IrohReplicationService(
                store = FakeStore(snapshotFailure = cancellation),
                binding = NoopBinding(),
                onProjection = {},
            )
            val before = service.state.value
            val failure = runCatching {
                service.updateState(IrohConnectionStatus.LISTENING, roomId = "room-test0001")
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(reported.isEmpty())
            assertEquals(before, service.state.value)
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun updateStateConflictSnapshotForcesConflictStatus() = runTest {
        val service = IrohReplicationService(
            store = FakeStore(
                snapshotState = IrohNetworkState(
                    roomId = "room-test0001",
                    conflict = IrohConflictEvidence(
                        domain = IrohDomain.timer,
                        id = "timer-1",
                        localDigest = "local",
                        receivedDigest = "remote",
                        detectedAtMs = 1,
                    ),
                ),
            ),
            binding = NoopBinding(),
            onProjection = {},
        )
        service.updateState(IrohConnectionStatus.LISTENING, roomId = "room-test0001")
        assertEquals(IrohConnectionStatus.CONFLICT, service.state.value.status)
        assertEquals("room-test0001", service.state.value.roomId)
    }

    private class FakeStore(
        private val snapshotState: IrohNetworkState = IrohNetworkState(),
        private val snapshotFailure: Throwable? = null,
    ) : IrohReplicationStore {
        override suspend fun upsertPeer(peer: IrohPeerEntity) = Unit

        override suspend fun inventory(
            roomId: String,
            after: String?,
            limit: Int,
        ): Pair<List<IrohInventoryEntry>, String?> = emptyList<IrohInventoryEntry>() to null

        override suspend fun operations(
            roomId: String,
            references: List<IrohInventoryReference>,
        ): List<IrohOperationRecord> = emptyList()

        override suspend fun peers(roomId: String): List<IrohPeerEntity> = emptyList()

        override suspend fun snapshot(roomId: String): IrohNetworkState {
            snapshotFailure?.let { throw it }
            return snapshotState
        }

        override suspend fun hasGenesis(roomId: String): Boolean = true

        override suspend fun missingReferences(
            roomId: String,
            remote: List<IrohInventoryEntry>,
        ): List<IrohInventoryReference> = emptyList()

        override suspend fun insertRemoteRecords(roomId: String, records: List<IrohOperationRecord>) = Unit

        override suspend fun refreshProjection(roomId: String): IrohRoomProjection =
            error("unreachable")

        override suspend fun activateJoinedRoom(roomId: String): IrohRoomProjection =
            error("unreachable")
    }

    private class NoopBinding : IrohEndpointBinding {
        override suspend fun bind(): Endpoint = error("unreachable")

        override fun ticket(endpoint: Endpoint): String = error("unreachable")
    }
}
