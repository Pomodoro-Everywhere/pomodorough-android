package me.egigoka.pomodorough.data.iroh.protocol

import kotlinx.serialization.Serializable
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem

enum class ReplicationMode { OFFLINE, IROH, CENTRALIZED }

enum class IrohConnectionStatus {
    STOPPED,
    STARTING,
    LISTENING,
    SYNCING,
    WAITING_FOR_PEERS,
    CONFLICT,
    UNAVAILABLE,
}

data class IrohNetworkState(
    val mode: ReplicationMode = ReplicationMode.CENTRALIZED,
    val status: IrohConnectionStatus = IrohConnectionStatus.STOPPED,
    val roomId: String? = null,
    val roomName: String? = null,
    val invite: String? = null,
    val peerCount: Int = 0,
    val operationCount: Int = 0,
    val endpointMark: String? = null,
    val message: String? = null,
    val conflict: IrohConflictEvidence? = null,
    val transitioning: Boolean = false,
)

class IrohProtocolException(message: String) : Exception(message)

object IrohProtocolV1 {
    const val Version = 1
    const val InvitePrefix = "pomodorough1."
    const val MaxFrameBodyBytes = 16 * 1_024 * 1_024
    const val MaxHelloBodyBytes = 32 * 1_024
    const val MaxOperationBytes = 64 * 1_024
    const val MaxEndpointTicketBytes = 16 * 1_024
    const val MaxInventoryEntries = 1_024
    const val MaxOperationReferences = 255
    const val MaxPeers = 64
    val Alpn = "me.egigoka.pomodorough/sync/1".encodeToByteArray()

    fun roomId(roomSecret: ByteArray): String = EndpointIdentity.roomId(roomSecret)

    fun isIdentifier(value: String): Boolean = EndpointIdentity.isIdentifier(value)

    fun isRoomId(value: String): Boolean = EndpointIdentity.isRoomId(value)

    fun isDisplayName(value: String?): Boolean = EndpointIdentity.isDisplayName(value)

    fun isRequestId(value: String): Boolean = EndpointIdentity.isRequestId(value)

    fun requestId(nowMs: Long = System.currentTimeMillis()): String =
        EndpointIdentity.requestId(nowMs)

    fun utf8Compare(left: String, right: String): Int =
        EndpointIdentity.utf8Compare(left, right)
}

@Serializable
enum class IrohDomain {
    genesis,
    timer,
    task,
    duration,
    autoStart,
    selectedTask,
}

@Serializable
data class IrohGenesis(
    val canonicalTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val tasks: List<FocusTask>,
    val durationsMs: DurationsMs,
    val autoStartBreaks: Boolean,
    val selectedTaskId: String? = null,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

@Serializable
data class IrohInventoryReference(val domain: IrohDomain, val id: String)

@Serializable
data class IrohInventoryEntry(val domain: IrohDomain, val id: String, val digest: String) {
    val reference get() = IrohInventoryReference(domain, id)
}

@Serializable
data class IrohHello(
    val protocolVersion: Int,
    val roomId: String,
    val requestId: String,
    val kind: String,
    val deviceId: String,
    val endpointTicket: String,
    val platform: String,
    val displayName: String? = null,
)

@Serializable
data class IrohInventoryRequest(
    val protocolVersion: Int,
    val roomId: String,
    val requestId: String,
    val kind: String,
    val after: String?,
    val limit: Int,
)

@Serializable
data class IrohInventoryResult(
    val protocolVersion: Int,
    val roomId: String,
    val requestId: String,
    val kind: String,
    val entries: List<IrohInventoryEntry>,
    val next: String?,
)

@Serializable
data class IrohOperationsRequest(
    val protocolVersion: Int,
    val roomId: String,
    val requestId: String,
    val kind: String,
    val refs: List<IrohInventoryReference>,
)

data class IrohOperationsResult(
    val protocolVersion: Int,
    val roomId: String,
    val requestId: String,
    val records: List<IrohOperationRecord>,
)

@Serializable
data class IrohErrorResponse(
    val protocolVersion: Int,
    val roomId: String,
    val requestId: String,
    val kind: String,
    val code: String,
    val message: String,
    val retryable: Boolean,
)

@Serializable
data class IrohConflictEvidence(
    val domain: IrohDomain,
    val id: String,
    val localDigest: String,
    val receivedDigest: String,
    val detectedAtMs: Long,
)
