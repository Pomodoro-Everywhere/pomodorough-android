package me.egigoka.pomodorough.data.iroh

typealias ReplicationMode = me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode
typealias IrohConnectionStatus = me.egigoka.pomodorough.data.iroh.protocol.IrohConnectionStatus
typealias IrohNetworkState = me.egigoka.pomodorough.data.iroh.protocol.IrohNetworkState
typealias IrohProtocolException = me.egigoka.pomodorough.data.iroh.protocol.IrohProtocolException
typealias IrohProtocolV1 = me.egigoka.pomodorough.data.iroh.protocol.IrohProtocolV1
typealias Base64Url = me.egigoka.pomodorough.data.iroh.protocol.Base64Url
typealias IrohRoomInvite = me.egigoka.pomodorough.data.iroh.protocol.IrohRoomInvite
typealias IrohFrameCodec = me.egigoka.pomodorough.data.iroh.protocol.IrohFrameCodec
typealias IrohDomain = me.egigoka.pomodorough.data.iroh.protocol.IrohDomain
typealias IrohGenesis = me.egigoka.pomodorough.data.iroh.protocol.IrohGenesis
typealias IrohOperationRecord = me.egigoka.pomodorough.data.iroh.protocol.IrohOperationRecord
typealias IrohInventoryReference = me.egigoka.pomodorough.data.iroh.protocol.IrohInventoryReference
typealias IrohInventoryEntry = me.egigoka.pomodorough.data.iroh.protocol.IrohInventoryEntry
typealias IrohHello = me.egigoka.pomodorough.data.iroh.protocol.IrohHello
typealias IrohInventoryRequest = me.egigoka.pomodorough.data.iroh.protocol.IrohInventoryRequest
typealias IrohInventoryResult = me.egigoka.pomodorough.data.iroh.protocol.IrohInventoryResult
typealias IrohOperationsRequest = me.egigoka.pomodorough.data.iroh.protocol.IrohOperationsRequest
typealias IrohOperationsResult = me.egigoka.pomodorough.data.iroh.protocol.IrohOperationsResult
typealias IrohErrorResponse = me.egigoka.pomodorough.data.iroh.protocol.IrohErrorResponse

sealed interface IrohRpcMessage {
    val requestId: String

    data class Hello(val value: IrohHello) : IrohRpcMessage { override val requestId = value.requestId }
    data class Inventory(val value: IrohInventoryRequest) : IrohRpcMessage { override val requestId = value.requestId }
    data class InventoryResult(val value: IrohInventoryResult) : IrohRpcMessage { override val requestId = value.requestId }
    data class Operations(val value: IrohOperationsRequest) : IrohRpcMessage { override val requestId = value.requestId }
    data class OperationsResult(val value: IrohOperationsResult) : IrohRpcMessage { override val requestId = value.requestId }
    data class Error(val value: IrohErrorResponse) : IrohRpcMessage { override val requestId = value.requestId }
}

typealias IrohMessageCodec = me.egigoka.pomodorough.data.iroh.protocol.IrohMessageCodec
typealias JsonCanonicalizer = me.egigoka.pomodorough.data.iroh.protocol.JsonCanonicalizer
typealias IrohConflictEvidence = me.egigoka.pomodorough.data.iroh.protocol.IrohConflictEvidence
internal typealias IrohJson = me.egigoka.pomodorough.data.iroh.protocol.IrohJson
