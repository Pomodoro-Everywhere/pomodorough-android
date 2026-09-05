package me.egigoka.pomodorough.data.local

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceLoadBoundsTest {
    @Test
    fun boundedCommandLoadPreservesOrderUnderLimit() = runTest {
        val rows = listOf(command("command-0001"), command("command-0002"))
        val dao = workspaceProxy(mapOf("pendingCommandsCapped" to rows))

        assertEquals(rows, dao.loadCommandsBounded())
    }

    @Test
    fun oversizedCommandQueueFailsClosedInsteadOfOom() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxPendingCommands + 1) { command("command-$it") }
        val dao = workspaceProxy(mapOf("pendingCommandsCapped" to rows))

        expectBoundedFailure { dao.loadCommandsBounded() }
    }

    @Test
    fun oversizedTaskQueueFailsClosed() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxPendingTaskOperations + 1) { taskOp("task-op-$it") }
        val dao = workspaceProxy(mapOf("pendingTaskOperationsCapped" to rows))

        expectBoundedFailure { dao.loadTaskOperationsBounded() }
    }

    @Test
    fun oversizedDurationQueueFailsClosed() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxPendingDurationOperations + 1) { durationOp("duration-$it") }
        val dao = workspaceProxy(mapOf("pendingDurationOperationsCapped" to rows))

        expectBoundedFailure { dao.loadDurationOperationsBounded() }
    }

    @Test
    fun oversizedAutoStartQueueFailsClosed() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxPendingAutoStartOperations + 1) { autoOp("auto-$it") }
        val dao = workspaceProxy(mapOf("pendingAutoStartOperationsCapped" to rows))

        expectBoundedFailure { dao.loadAutoStartOperationsBounded() }
    }

    @Test
    fun oversizedSelectedTaskQueueFailsClosed() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxPendingSelectedTaskOperations + 1) { selectedOp("selected-$it") }
        val dao = workspaceProxy(mapOf("pendingSelectedTaskOperationsCapped" to rows))

        expectBoundedFailure { dao.loadSelectedTaskOperationsBounded() }
    }

    @Test
    fun boundedIrohLoadPreservesOrderUnderLimit() = runTest {
        val rows = listOf(irohOp("operation-0001"), irohOp("operation-0002"))
        val dao = recordsProxy(mapOf("irohOperationsCapped" to rows))

        assertEquals(rows, dao.loadOperationsBounded("room"))
    }

    @Test
    fun largeRoomFailsClosedInsteadOfOom() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxIrohOperations + 1) { irohOp("operation-$it") }
        val dao = recordsProxy(mapOf("irohOperationsCapped" to rows))

        expectBoundedFailure { dao.loadOperationsBounded("room") }
    }

    @Test
    fun oversizedPeerListFailsClosed() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxIrohPeers + 1) { peer("endpoint-$it") }
        val dao = peersProxy(mapOf("irohPeersCapped" to rows))

        expectBoundedFailure { dao.loadPeersBounded("room") }
    }

    @Test
    fun oversizedRoomListFailsClosed() = runTest {
        val rows = List(WorkspaceLoadBounds.MaxIrohRooms + 1) { room("room-$it") }
        val dao = roomsProxy(mapOf("irohRoomsCapped" to rows))

        expectBoundedFailure { dao.loadRoomsBounded() }
    }
}

private suspend fun expectBoundedFailure(block: suspend () -> Unit) {
    try {
        block()
    } catch (error: IllegalArgumentException) {
        return
    }
    fail("Expected IllegalArgumentException for oversized load")
}

private fun command(id: String) = DaoBoundaryFixtures.command.copy(id = id)

private fun taskOp(id: String) = DaoBoundaryFixtures.task.copy(id = id)

private fun durationOp(id: String) = DaoBoundaryFixtures.duration.copy(id = id)

private fun autoOp(id: String) = DaoBoundaryFixtures.autoStart.copy(id = id)

private fun selectedOp(id: String) = DaoBoundaryFixtures.selected.copy(id = id)

private fun irohOp(id: String) = IrohOperationEntity("room", "timer", id, "device", "{}", "digest-$id", 1L, 0L, null)

private fun peer(endpoint: String) = IrohPeerEntity("room", endpoint, "ticket", null, null, null)

private fun room(id: String) = IrohRoomEntity(id, null, byteArrayOf(1), "return", "state", 1L)

@Suppress("UNCHECKED_CAST")
private fun workspaceProxy(values: Map<String, Any?>): TimerWorkspaceDao =
    Proxy.newProxyInstance(
        TimerWorkspaceDao::class.java.classLoader,
        arrayOf(TimerWorkspaceDao::class.java),
    ) { _, method, _ -> values[method.name] } as TimerWorkspaceDao

@Suppress("UNCHECKED_CAST")
private fun recordsProxy(values: Map<String, Any?>): IrohRecordsDao =
    Proxy.newProxyInstance(
        IrohRecordsDao::class.java.classLoader,
        arrayOf(IrohRecordsDao::class.java),
    ) { _, method, _ -> values[method.name] } as IrohRecordsDao

@Suppress("UNCHECKED_CAST")
private fun peersProxy(values: Map<String, Any?>): IrohPeersDao =
    Proxy.newProxyInstance(
        IrohPeersDao::class.java.classLoader,
        arrayOf(IrohPeersDao::class.java),
    ) { _, method, _ -> values[method.name] } as IrohPeersDao

@Suppress("UNCHECKED_CAST")
private fun roomsProxy(values: Map<String, Any?>): IrohRoomMetadataDao =
    Proxy.newProxyInstance(
        IrohRoomMetadataDao::class.java.classLoader,
        arrayOf(IrohRoomMetadataDao::class.java),
    ) { _, method, _ -> values[method.name] } as IrohRoomMetadataDao
