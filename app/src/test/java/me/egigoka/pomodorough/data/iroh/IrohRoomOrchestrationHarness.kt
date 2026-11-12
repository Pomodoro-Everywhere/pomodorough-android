package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity

internal class IrohRoomOrchestrationHarness(
    initialMode: ReplicationMode = ReplicationMode.CENTRALIZED,
    activeRoomId: String? = null,
) {
    val state = MutableStateFlow(IrohNetworkState(mode = initialMode))
    val local = LocalStateEntity(
        deviceId = "device-test0001",
        settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
    )
    val workspace = LocalWorkspaceSnapshot(local)
    var settings = ReplicationSettingsEntity(mode = initialMode.name, activeRoomId = activeRoomId)
    var room: IrohRoomEntity? = activeRoomId?.let(::room)
    var roomSecret: ByteArray? = ByteArray(32) { (it + 1).toByte() }
    var snapshot = IrohNetworkState(
        mode = initialMode,
        roomId = activeRoomId,
        roomName = room?.roomName,
        operationCount = 2,
    )
    var capturedProjection = IrohRoomProjection(workspace, 5)
    var preparedRoom: Pair<IrohRoomEntity, Boolean>? = null
    var startFailure: Exception? = null
    var stopFailure: Exception? = null
    var startGate: CompletableDeferred<Unit>? = null
    var syncFailure: Exception? = null
    var setModeFailure: Exception? = null
    var createRoomFailure: Exception? = null
    var prepareJoinedRoomFailure: Exception? = null
    var leaveFailure: Exception? = null
    var settingsFailure: Exception? = null
    var yieldOnSettingsRead = false
    var joinRecovery: IrohIdentityRecoveryKind? = null
    var discardCount = 0
    var discardFailure: Exception? = null
    var roomFailure: Exception? = null
    var snapshotFailure: Exception? = null
    var discardInactiveFailure: Exception? = null
    var captureCount = 0
    var captureLocalOperationsFailure: Exception? = null
    // Runs before the orchestration's state collector receives each value, so a test can hold one
    // delivery while the service writes newer state.
    var deliveryGate: (suspend (IrohNetworkState) -> Unit)? = null
    // Runs before each orchestration write lands: inside the atomic change for transformState,
    // so again on each retry, and ahead of the replacement for publishState. A test lands a
    // service write here to check that the orchestration's write keeps it.
    var writeGate: ((IrohNetworkState) -> Unit)? = null
    var startCount = 0
    var stopCount = 0
    var closeCount = 0
    var syncCount = 0
    var joinCount = 0
    var clearCount = 0
    var scrubCount = 0
    val modes = mutableListOf<ReplicationMode>()
    val discardedRooms = mutableListOf<String>()
    val createdNames = mutableListOf<String?>()
    val startedContexts = mutableListOf<IrohServiceContext>()

    val orchestration = IrohRoomOrchestration(
        service = IrohRoomServicePort(
            state = GatedStateFlow(state) { deliveryGate },
            publishState = { value ->
                writeGate?.invoke(state.value)
                state.value = value
            },
            transformState = { transform ->
                state.update { current ->
                    writeGate?.invoke(current)
                    transform(current)
                }
            },
            start = { context, _ ->
                startCount += 1
                startedContexts += context.copy(roomSecret = context.roomSecret.copyOf())
                startGate?.await()
                startFailure?.let { throw it }
                "endpoint-ticket"
            },
            stop = {
                // Like IrohEndpointLifecycle.stop (A65), a stop in a cancelled job throws before it
                // stops anything.
                currentCoroutineContext().ensureActive()
                stopCount += 1
                stopFailure?.let { throw it }
            },
            close = { closeCount += 1 },
            stopIf = { predicate -> if (predicate()) stopCount += 1 },
            join = {
                joinCount += 1
                // The service's RecoveryRequired event: the endpoint refuses its identity mid-join.
                joinRecovery?.let { kind ->
                    state.value = state.value.copy(
                        status = IrohConnectionStatus.UNAVAILABLE,
                        endpointMark = null,
                        message = null,
                        transitioning = false,
                        identityRecovery = kind,
                        recoveryAttemptFailed = false,
                    )
                }
                capturedProjection
            },
            endpointIdForTicket = { "endpoint-test0001" },
            syncNow = {
                syncCount += 1
                syncFailure?.let { throw it }
                state.value = state.value.copy(status = IrohConnectionStatus.LISTENING, message = null)
            },
        ),
        persistence = IrohRoomPersistencePort(
            localState = { local },
            room = { id ->
                roomFailure?.let { throw it }
                room?.takeIf { it.roomId == id }
            },
            replicationSettings = {
                if (yieldOnSettingsRead) yield()
                settingsFailure?.let { throw it }
                settings
            },
            discardIncompleteRooms = {
                discardCount += 1
                discardFailure?.let { throw it }
            },
            setMode = { mode ->
                setModeFailure?.let { throw it }
                modes += mode
                settings = ReplicationSettingsEntity(mode = mode.name, activeRoomId = settings.activeRoomId)
                state.value = state.value.copy(mode = mode)
            },
            snapshot = {
                snapshotFailure?.let { throw it }
                snapshot
            },
            captureLocalOperations = {
                captureCount += 1
                captureLocalOperationsFailure?.let { throw it }
                capturedProjection
            },
            createRoom = { name ->
                createRoomFailure?.let { throw it }
                createdNames += name
                val secret = checkNotNull(roomSecret)
                val created = room(IrohProtocolV1.roomId(secret), name)
                room = created
                settings = ReplicationSettingsEntity(
                    mode = ReplicationMode.IROH.name,
                    activeRoomId = created.roomId,
                )
                created to capturedProjection
            },
            activeRoom = { room },
            activeRoomSecret = { roomSecret?.copyOf() },
            prepareJoinedRoom = { invite, _ ->
                prepareJoinedRoomFailure?.let { throw it }
                preparedRoom ?: (room(invite.roomId, invite.roomName) to true)
            },
            discardIncompleteInactiveRoom = { roomId ->
                discardedRooms += roomId
                discardInactiveFailure?.let { throw it }
            },
            leaveActiveRoom = {
                leaveFailure?.let { throw it }
                settings = ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name)
                room = null
            },
            clearAccountData = { clearCount += 1 },
            scrubDeletedAccount = {
                scrubCount += 1
                roomSecret?.fill(0)
                roomSecret = null
                room = null
                settings = ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name)
            },
        ),
    )

    suspend fun enterForeground() {
        orchestration.onForeground()
        await { discardCount > 0 }
    }

    suspend fun await(predicate: () -> Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                while (!predicate()) delay(1)
            }
        }
    }

    fun invite(secret: ByteArray = ByteArray(32) { (it + 11).toByte() }): String {
        return IrohRoomInvite(
            roomId = IrohProtocolV1.roomId(secret),
            roomName = "Joined room",
            endpointTicket = "endpoint-ticket",
            roomSecret = secret,
        ).encode()
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class GatedStateFlow(
        private val backing: StateFlow<IrohNetworkState>,
        private val gate: () -> (suspend (IrohNetworkState) -> Unit)?,
    ) : StateFlow<IrohNetworkState> by backing {
        override suspend fun collect(collector: FlowCollector<IrohNetworkState>): Nothing =
            backing.collect { value ->
                gate()?.invoke(value)
                collector.emit(value)
            }
    }

    private fun room(id: String, name: String? = "Test room") = IrohRoomEntity(
        roomId = id,
        roomName = name,
        encryptedRoomSecret = byteArrayOf(1, 2, 3),
        returnStateJson = "return",
        roomStateJson = "room",
        createdAtMs = 1,
        activated = true,
    )
}
