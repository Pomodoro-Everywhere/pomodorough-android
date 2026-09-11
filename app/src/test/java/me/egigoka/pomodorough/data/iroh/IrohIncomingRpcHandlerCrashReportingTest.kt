package me.egigoka.pomodorough.data.iroh

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.Incoming
import computer.iroh.NoHandle
import computer.iroh.RecvStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.crash.CrashReporter
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IrohIncomingRpcHandlerCrashReportingTest {
    @Test
    fun handshakeIOExceptionStaysSilent() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handler()
            val endpoint = FakeEndpoint(FakeIncoming(IOException("peer reset")))
            handler.acceptLoop(endpoint, 1L)
            testScheduler.advanceUntilIdle()
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun handshakeIllegalArgumentStaysSilent() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handler()
            val endpoint = FakeEndpoint(FakeIncoming(IllegalArgumentException("Wrong ALPN")))
            handler.acceptLoop(endpoint, 1L)
            testScheduler.advanceUntilIdle()
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun handshakeUnexpectedFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handler()
            val endpoint = FakeEndpoint(FakeIncoming(IllegalStateException("invariant broken")))
            handler.acceptLoop(endpoint, 1L)
            testScheduler.advanceUntilIdle()
            assertTrue(reported.size == 1)
            assertTrue(reported.single() is IllegalStateException)
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun servingIOExceptionStaysSilent() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handlerWithSession()
            handler.handleIncoming(FakeConnection(IOException("peer gone")), 1L)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun servingIllegalArgumentStaysSilent() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handlerWithSession()
            handler.handleIncoming(FakeConnection(IllegalArgumentException("Wrong room")), 1L)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun servingUnexpectedFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handlerWithSession()
            handler.handleIncoming(FakeConnection(IllegalStateException("invariant broken")), 1L)
            assertTrue(reported.size == 1)
            assertTrue(reported.single() is IllegalStateException)
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun malformedFrameIOExceptionStaysSilent() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handler()
            val result = handler.readAuthenticatedRequest(
                FakeRecvStream(IOException("truncated")),
                IrohServiceContext("room", ByteArray(32) { 1 }, "device", null),
            )
            assertNull(result)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun malformedFrameIllegalArgumentStaysSilent() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handler()
            val result = handler.readAuthenticatedRequest(
                FakeRecvStream(IllegalArgumentException("Invalid Iroh frame")),
                IrohServiceContext("room", ByteArray(32) { 1 }, "device", null),
            )
            assertNull(result)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun malformedFrameUnexpectedFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handler()
            val result = handler.readAuthenticatedRequest(
                FakeRecvStream(IllegalStateException("codec broken")),
                IrohServiceContext("room", ByteArray(32) { 1 }, "device", null),
            )
            assertNull(result)
            assertTrue(reported.size == 1)
            assertTrue(reported.single() is IllegalStateException)
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun inventoryCancellationPropagatesWithoutErrorResponse() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val cancellation = CancellationException("gone")
            val handler = handlerWithDependencies(
                inventory = { _, _, _ -> throw cancellation },
                operations = { _, _ -> emptyList() },
            )
            val context = IrohServiceContext("room", ByteArray(32) { 1 }, "device", null)
            val message = inventoryRequest("room", "request-1")
            val failure = runCatching { handler.response(message, context) }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun operationsCancellationPropagatesWithoutErrorResponse() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val cancellation = CancellationException("gone")
            val handler = handlerWithDependencies(
                inventory = { _, _, _ -> emptyList<IrohInventoryEntry>() to null },
                operations = { _, _ -> throw cancellation },
            )
            val context = IrohServiceContext("room", ByteArray(32) { 1 }, "device", null)
            val message = operationsRequest("room", "request-2")
            val failure = runCatching { handler.response(message, context) }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun mappingFailureStillReturnsErrorResponse() = runTest {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val handler = handlerWithDependencies(
                inventory = { _, _, _ -> throw IllegalStateException("store broken") },
                operations = { _, _ -> emptyList() },
            )
            val context = IrohServiceContext("room", ByteArray(32) { 1 }, "device", null)
            val result = handler.response(inventoryRequest("room", "request-3"), context)
            assertTrue(result is IrohRpcMessage.Error)
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    private fun handler(): IrohIncomingRpcHandler {
        val transport = IrohEndpointTransport()
        val authentication = IrohPeerAuthentication(
            transport,
            IrohPeerAuthorization(IrohEndpointTicketIdentity { it }),
            { "local-ticket" },
            {},
        )
        return IrohIncomingRpcHandler(
            FakeSessions(),
            authentication,
            transport,
            IrohIncomingRpcDependencies(
                { _, _, _ -> emptyList<IrohInventoryEntry>() to null },
                { _, _ -> emptyList() },
            ),
            {},
        )
    }

    private fun handlerWithSession(): IrohIncomingRpcHandler {
        val transport = IrohEndpointTransport()
        val authentication = IrohPeerAuthentication(
            transport,
            IrohPeerAuthorization(IrohEndpointTicketIdentity { it }),
            { "local-ticket" },
            {},
        )
        val context = IrohServiceContext("room", ByteArray(32) { 1 }, "device", null)
        val sessions = SessionFixture(context)
        return IrohIncomingRpcHandler(
            sessions,
            authentication,
            transport,
            IrohIncomingRpcDependencies(
                { _, _, _ -> emptyList<IrohInventoryEntry>() to null },
                { _, _ -> emptyList() },
            ),
            {},
        )
    }

    private fun handlerWithDependencies(
        inventory: suspend (String, String?, Int) -> Pair<List<IrohInventoryEntry>, String?>,
        operations: suspend (String, List<IrohInventoryReference>) -> List<IrohOperationRecord>,
    ): IrohIncomingRpcHandler {
        val transport = IrohEndpointTransport()
        val authentication = IrohPeerAuthentication(
            transport,
            IrohPeerAuthorization(IrohEndpointTicketIdentity { it }),
            { "local-ticket" },
            {},
        )
        return IrohIncomingRpcHandler(
            FakeSessions(),
            authentication,
            transport,
            IrohIncomingRpcDependencies(inventory, operations),
            {},
        )
    }

    private fun inventoryRequest(roomId: String, requestId: String) = IrohRpcMessage.Inventory(
        IrohInventoryRequest(
            protocolVersion = IrohProtocolV1.Version,
            roomId = roomId,
            requestId = requestId,
            kind = "inventory",
            after = null,
            limit = 10,
        ),
    )

    private fun operationsRequest(roomId: String, requestId: String) = IrohRpcMessage.Operations(
        IrohOperationsRequest(
            protocolVersion = IrohProtocolV1.Version,
            roomId = roomId,
            requestId = requestId,
            kind = "operations",
            refs = emptyList(),
        ),
    )

    private class FakeSessions : IrohEndpointSessionSource {
        override fun session() = null
        override fun generation() = 1L
    }

    private class SessionFixture(private val context: IrohServiceContext) : IrohEndpointSessionSource {
        private val endpoint = IrohSyncTestEndpoint()
        override fun session() = IrohEndpointSession(endpoint, context, "local-ticket", 1L)
        override fun generation() = 1L
    }

    private class FakeEndpoint(private val first: Incoming) : Endpoint(NoHandle) {
        private var calls = 0
        override suspend fun acceptNext(): Incoming? {
            calls += 1
            return if (calls == 1) first else null
        }
        override fun isClosed() = false
    }

    private class FakeIncoming(private val failure: Exception) : Incoming(NoHandle) {
        override suspend fun accept() = throw failure
        override suspend fun ignore() = Unit
    }

    private class FakeConnection(private val failure: Exception) : Connection(NoHandle) {
        override fun alpn(): ByteArray = IrohProtocolV1.Alpn.copyOf()
        override suspend fun acceptBi(): BiStream = throw failure
    }

    private class FakeRecvStream(private val failure: Exception) : RecvStream(NoHandle) {
        override suspend fun readExact(length: UInt): ByteArray = throw failure
    }
}
