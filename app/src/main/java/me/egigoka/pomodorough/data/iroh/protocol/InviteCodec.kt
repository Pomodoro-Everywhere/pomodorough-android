package me.egigoka.pomodorough.data.iroh.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
private data class InvitePayload(
    val v: Int,
    val roomId: String,
    val roomName: String? = null,
    val endpointTicket: String,
    val roomSecret: String,
)

data class IrohRoomInvite(
    val roomId: String,
    val roomName: String?,
    val endpointTicket: String,
    val roomSecret: ByteArray,
) {
    init {
        require(IrohProtocolV1.isRoomId(roomId)) { "Room ID is malformed" }
        require(IrohProtocolV1.roomId(roomSecret) == roomId) {
            "Room ID does not match room secret"
        }
        require(IrohProtocolV1.isDisplayName(roomName)) {
            "Room name must contain 1 through 64 Unicode scalars"
        }
        require(endpointTicket.isNotEmpty() &&
            endpointTicket.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes
        ) { "Endpoint ticket exceeds 16 KiB" }
    }

    fun encode(): String {
        val payload = JsonObject(
            buildMap {
                put("v", JsonPrimitive(IrohProtocolV1.Version))
                put("roomId", JsonPrimitive(roomId))
                roomName?.let { put("roomName", JsonPrimitive(it)) }
                put("endpointTicket", JsonPrimitive(endpointTicket))
                put("roomSecret", JsonPrimitive(Base64Url.encode(roomSecret)))
            },
        )
        return IrohProtocolV1.InvitePrefix + Base64Url.encode(
            payload.toString().encodeToByteArray(),
        )
    }

    companion object {
        fun decode(text: String): IrohRoomInvite {
            require(text.startsWith(IrohProtocolV1.InvitePrefix)) {
                "Invite must start with ${IrohProtocolV1.InvitePrefix}"
            }
            val encoded = text.removePrefix(IrohProtocolV1.InvitePrefix)
            val bytes = Base64Url.decode(encoded)
            val objectValue = runCatching {
                IrohJson.strict.parseToJsonElement(strictJson(bytes)).jsonObject
            }.getOrElse { throw IllegalArgumentException("Invite payload must be a JSON object", it) }
            val required = setOf("v", "roomId", "endpointTicket", "roomSecret")
            val allowed = required + "roomName"
            require(objectValue.keys.containsAll(required) && objectValue.keys.all { it in allowed }) {
                "Invite payload has missing or unknown fields"
            }
            require(objectValue["roomName"] !is JsonNull) { "roomName must be omitted instead of null" }
            val payload = runCatching {
                IrohJson.strict.decodeFromJsonElement<InvitePayload>(objectValue)
            }.getOrElse { throw IllegalArgumentException("Invite payload field types are invalid", it) }
            require(payload.v == IrohProtocolV1.Version) { "Unsupported invite version" }
            require(IrohProtocolV1.isRoomId(payload.roomId)) { "Room ID is malformed" }
            val secret = Base64Url.decode(payload.roomSecret)
            require(secret.size == 32) { "Room secret must contain 32 bytes" }
            return IrohRoomInvite(
                roomId = payload.roomId,
                roomName = payload.roomName,
                endpointTicket = payload.endpointTicket,
                roomSecret = secret,
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is IrohRoomInvite &&
        roomId == other.roomId && roomName == other.roomName &&
        endpointTicket == other.endpointTicket && roomSecret.contentEquals(other.roomSecret)

    override fun hashCode(): Int = 31 * (
        31 * (31 * roomId.hashCode() + (roomName?.hashCode() ?: 0)) + endpointTicket.hashCode()
        ) + roomSecret.contentHashCode()
}
