package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    var discardCount = 0
    var captureCount = 0
    var startCount = 0
    var stopCount = 0
    var closeCount = 0
    var syncCount = 0
    var joinCount = 0
    var clearCount = 0
    val modes = mutableListOf<ReplicationMode>()
    val discardedRooms = mutableListOf<String>()
    val createdNames = mutableListOf<String?>()
    val startedContexts = mutableListOf<IrohServiceContext>()

    val orchestration = IrohRoomOrchestration(
        service = IrohRoomServicePort(
            state = state,
            publishState = { state.value = it },
            start = { context, _ ->
                startCount += 1
                startedContexts += context.copy(roomSecret = context.roomSecret.copyOf())
                startFailure?.let { throw it }
                "endpoint-ticket"
            },
            stop = { stopCount += 1 },
            close = { closeCount += 1 },
            stopIf = { predicate -> if (predicate()) stopCount += 1 },
            join = {
                joinCount += 1
                capturedProjection
            },
            endpointIdForTicket = { "endpoint-test0001" },
            syncNow = { syncCount += 1 },
        ),
        persistence = IrohRoomPersistencePort(
            localState = { local },
            room = { id -> room?.takeIf { it.roomId == id } },
            replicationSettings = { settings },
            discardIncompleteRooms = { discardCount += 1 },
            setMode = { mode ->
                modes += mode
                settings = ReplicationSettingsEntity(mode = mode.name, activeRoomId = settings.activeRoomId)
                state.value = state.value.copy(mode = mode)
            },
            snapshot = { snapshot },
            captureLocalOperations = {
                captureCount += 1
                capturedProjection
            },
            createRoom = { name ->
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
                preparedRoom ?: (room(invite.roomId, invite.roomName) to true)
            },
            discardIncompleteInactiveRoom = { discardedRooms += it },
            leaveActiveRoom = {
                settings = ReplicationSettingsEntity(mode = ReplicationMode.OFFLINE.name)
                room = null
            },
            clearAccountData = { clearCount += 1 },
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
