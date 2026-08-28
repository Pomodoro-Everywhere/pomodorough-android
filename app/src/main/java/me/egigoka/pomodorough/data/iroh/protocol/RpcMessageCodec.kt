package me.egigoka.pomodorough.data.iroh.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.iroh.IrohRpcMessage

object IrohMessageCodec {
    fun encode(message: IrohRpcMessage): ByteArray {
        val element = when (message) {
            is IrohRpcMessage.Hello -> IrohJson.strict.encodeToJsonElement(message.value)
            is IrohRpcMessage.Inventory -> IrohJson.strict.encodeToJsonElement(message.value)
                .jsonObject.withRequiredNull("after", message.value.after)
            is IrohRpcMessage.InventoryResult -> IrohJson.strict.encodeToJsonElement(message.value)
                .jsonObject.withRequiredNull("next", message.value.next)
            is IrohRpcMessage.Operations -> IrohJson.strict.encodeToJsonElement(message.value)
            is IrohRpcMessage.Error -> IrohJson.strict.encodeToJsonElement(message.value)
            is IrohRpcMessage.OperationsResult -> JsonObject(
                mapOf(
                    "protocolVersion" to JsonPrimitive(message.value.protocolVersion),
                    "roomId" to JsonPrimitive(message.value.roomId),
                    "requestId" to JsonPrimitive(message.value.requestId),
                    "kind" to JsonPrimitive("operationsResult"),
                    "records" to JsonArray(message.value.records.map(IrohOperationRecord::toJson)),
                ),
            )
        }
        return element.toString().encodeToByteArray()
    }

    fun decode(body: ByteArray): IrohRpcMessage {
        val envelope = decodeEnvelope(body)
        return when (envelope.kind) {
            "hello" -> decodeHello(envelope.value)
            "inventory" -> decodeInventory(envelope.value)
            "inventoryResult" -> decodeInventoryResult(envelope.value)
            "operations" -> decodeOperations(envelope.value)
            "operationsResult" -> decodeOperationsResult(envelope)
            "error" -> decodeError(envelope.value)
            else -> throw SerializationException("Unknown Iroh message kind")
        }
    }

    private data class MessageEnvelope(
        val value: JsonObject,
        val version: Int,
        val roomId: String,
        val requestId: String,
        val kind: String,
    )

    private fun decodeEnvelope(body: ByteArray): MessageEnvelope {
        require(body.size <= IrohProtocolV1.MaxFrameBodyBytes)
        val value = IrohJson.strict.parseToJsonElement(strictJson(body)).jsonObject
        val version = value["protocolVersion"]?.jsonInt()
        val roomId = value["roomId"]?.jsonString()
        val requestId = value["requestId"]?.jsonString()
        val kind = value["kind"]?.jsonString()
        require(version == IrohProtocolV1.Version && roomId != null && IrohProtocolV1.isRoomId(roomId) &&
            requestId != null && IrohProtocolV1.isRequestId(requestId) && kind != null
        ) { "Iroh message envelope is invalid" }
        return MessageEnvelope(value, version, roomId, requestId, kind)
    }

    private fun decodeHello(value: JsonObject): IrohRpcMessage.Hello {
        requireExactKeys(
            value,
            setOf(
                "protocolVersion", "roomId", "requestId", "kind", "deviceId",
                "endpointTicket", "platform",
            ),
            setOf("displayName"),
        )
        requireOmittedNulls(value, setOf("displayName"))
        val hello = IrohJson.strict.decodeFromJsonElement<IrohHello>(value)
        require(hello.kind == "hello" && IrohProtocolV1.isIdentifier(hello.deviceId) &&
            hello.endpointTicket.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes &&
            hello.platform in setOf("ios", "macos", "android", "linux", "windows") &&
            IrohProtocolV1.isDisplayName(hello.displayName)
        ) { "Iroh hello is invalid" }
        return IrohRpcMessage.Hello(hello)
    }

    private fun decodeInventory(value: JsonObject): IrohRpcMessage.Inventory {
        requireExactKeys(
            value, setOf("protocolVersion", "roomId", "requestId", "kind", "after", "limit"),
        )
        val request = IrohJson.strict.decodeFromJsonElement<IrohInventoryRequest>(value)
        require(request.limit in 1..IrohProtocolV1.MaxInventoryEntries &&
            (request.after?.let(::validCursor) ?: true)
        ) { "Iroh inventory request is invalid" }
        return IrohRpcMessage.Inventory(request)
    }

    private fun decodeInventoryResult(value: JsonObject): IrohRpcMessage.InventoryResult {
        requireExactKeys(
            value, setOf("protocolVersion", "roomId", "requestId", "kind", "entries", "next"),
        )
        val entries = value["entries"] as? JsonArray
            ?: throw SerializationException("Inventory entries are invalid")
        entries.forEach { entry -> requireExactKeys(entry.jsonObject, setOf("domain", "id", "digest")) }
        val result = IrohJson.strict.decodeFromJsonElement<IrohInventoryResult>(value)
        require(result.entries.size <= IrohProtocolV1.MaxInventoryEntries &&
            (result.next?.let(::validCursor) ?: true) && entriesOrdered(result.entries) &&
            result.entries.all(::validInventoryEntry)
        ) { "Iroh inventory result is invalid" }
        return IrohRpcMessage.InventoryResult(result)
    }

    private fun decodeOperations(value: JsonObject): IrohRpcMessage.Operations {
        requireExactKeys(
            value, setOf("protocolVersion", "roomId", "requestId", "kind", "refs"),
        )
        val references = value["refs"] as? JsonArray
            ?: throw SerializationException("Operation references are invalid")
        references.forEach { requireExactKeys(it.jsonObject, setOf("domain", "id")) }
        val request = IrohJson.strict.decodeFromJsonElement<IrohOperationsRequest>(value)
        require(request.refs.isNotEmpty() && request.refs.size <= IrohProtocolV1.MaxOperationReferences &&
            request.refs.toSet().size == request.refs.size && request.refs.all(::validReference)
        ) { "Iroh operation references are invalid" }
        return IrohRpcMessage.Operations(request)
    }

    private fun decodeOperationsResult(envelope: MessageEnvelope): IrohRpcMessage.OperationsResult {
        requireExactKeys(
            envelope.value,
            setOf("protocolVersion", "roomId", "requestId", "kind", "records"),
        )
        val records = envelope.value["records"] as? JsonArray
            ?: throw SerializationException("Operation records are invalid")
        require(records.size <= IrohProtocolV1.MaxOperationReferences)
        return IrohRpcMessage.OperationsResult(
            IrohOperationsResult(
                envelope.version, envelope.roomId, envelope.requestId,
                records.map { IrohOperationRecord.fromJson(it.jsonObject) },
            ),
        )
    }

    private fun decodeError(value: JsonObject): IrohRpcMessage.Error {
        requireExactKeys(
            value,
            setOf(
                "protocolVersion", "roomId", "requestId", "kind", "code",
                "message", "retryable",
            ),
        )
        val error = IrohJson.strict.decodeFromJsonElement<IrohErrorResponse>(value)
        require(error.code in errorCodes && error.message.encodeToByteArray().size <= 1_024)
        return IrohRpcMessage.Error(error)
    }

    private fun validReference(value: IrohInventoryReference): Boolean =
        if (value.domain == IrohDomain.genesis) value.id == "genesis" else
            IrohProtocolV1.isIdentifier(value.id)

    private fun validInventoryEntry(value: IrohInventoryEntry): Boolean = validReference(value.reference) &&
        runCatching { Base64Url.decode(value.digest).size == 32 }.getOrDefault(false)

    private fun validCursor(value: String): Boolean {
        val split = value.split('\u0000')
        if (split.size != 2) return false
        val domain = runCatching { IrohDomain.valueOf(split[0]) }.getOrNull() ?: return false
        return validReference(IrohInventoryReference(domain, split[1]))
    }

    private fun entriesOrdered(entries: List<IrohInventoryEntry>): Boolean {
        if (entries.map(IrohInventoryEntry::reference).toSet().size != entries.size) return false
        return entries.zipWithNext().all { (left, right) ->
            compareReferences(left.reference, right.reference) < 0
        }
    }

    val referenceComparator = Comparator<IrohInventoryReference>(::compareReferences)

    private fun compareReferences(left: IrohInventoryReference, right: IrohInventoryReference): Int =
        IrohProtocolV1.utf8Compare(left.domain.name, right.domain.name).takeIf { it != 0 }
            ?: IrohProtocolV1.utf8Compare(left.id, right.id)

    private val errorCodes = setOf(
        "bad_frame", "unauthorized", "wrong_room", "unsupported_version", "invalid_request",
        "not_found", "immutable_conflict", "limit", "internal",
    )
}
