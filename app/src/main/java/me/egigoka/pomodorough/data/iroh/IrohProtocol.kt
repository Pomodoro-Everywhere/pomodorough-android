package me.egigoka.pomodorough.data.iroh

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationLimits
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.DurationsMs
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.UuidV7
import me.egigoka.pomodorough.domain.TaskReducer

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

    private val roomPrefix = "pomodorough-room-v1\u0000".encodeToByteArray()

    fun roomId(roomSecret: ByteArray): String {
        require(roomSecret.size == 32) { "Room secret must contain 32 bytes" }
        return Base64Url.encode(
            MessageDigest.getInstance("SHA-256").digest(roomPrefix + roomSecret),
        )
    }

    fun isIdentifier(value: String): Boolean {
        return SyncWireBounds.isIdentifier(value)
    }

    fun isRoomId(value: String): Boolean = runCatching {
        val decoded = Base64Url.decode(value)
        decoded.size == 32 && Base64Url.encode(decoded) == value
    }.getOrDefault(false)

    fun isDisplayName(value: String?): Boolean = value == null ||
        value.hasWellFormedUtf16() && value.codePointCount() in 1..64

    fun isRequestId(value: String): Boolean = runCatching {
        UuidV7.parts(UUID.fromString(value))
        true
    }.getOrDefault(false)

    fun requestId(nowMs: Long = System.currentTimeMillis()): String =
        UuidV7.reserve(nowMs, previous = null).single().toString()

    fun utf8Compare(left: String, right: String): Int {
        return SyncWireBounds.compareUtf8(left, right)
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private fun String.hasWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val current = this[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index += 1
            }
        }
        return true
    }
}

object Base64Url {
    private val pattern = Regex("^[A-Za-z0-9_-]+$")

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String): ByteArray {
        require(value.matches(pattern) && value.length % 4 != 1) { "Malformed base64url" }
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("Malformed base64url", it) }
        require(encode(decoded) == value) { "Malformed base64url" }
        return decoded
    }
}

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

object IrohFrameCodec {
    private val macPrefix = "pomodorough-iroh-frame-v1\u0000".encodeToByteArray()

    fun encode(body: ByteArray, roomSecret: ByteArray): ByteArray {
        require(roomSecret.size == 32 && body.size <= IrohProtocolV1.MaxFrameBodyBytes) {
            "Invalid Iroh frame"
        }
        val mac = hmac(roomSecret, macPrefix + body)
        return ByteBuffer.allocate(4 + mac.size + body.size)
            .putInt(body.size)
            .put(mac)
            .put(body)
            .array()
    }

    fun decode(frame: ByteArray, roomSecret: ByteArray): ByteArray {
        require(roomSecret.size == 32 && frame.size >= 36) { "Invalid Iroh frame" }
        val bodyLength = ByteBuffer.wrap(frame, 0, 4).int
        require(bodyLength in 0..IrohProtocolV1.MaxFrameBodyBytes && frame.size == 36 + bodyLength) {
            "Invalid Iroh frame"
        }
        val supplied = frame.copyOfRange(4, 36)
        val body = frame.copyOfRange(36, frame.size)
        require(MessageDigest.isEqual(supplied, hmac(roomSecret, macPrefix + body))) {
            "Iroh frame authentication failed"
        }
        return body
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
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
data class IrohOperationRecord(
    val domain: IrohDomain,
    val deviceId: String,
    val operation: JsonObject,
) {
    val id: String
        get() = if (domain == IrohDomain.genesis) "genesis" else
            operation["id"]?.jsonString() ?: throw IrohProtocolException("Operation ID is missing")

    val hlcWallMs: Long
        get() = operation["hlcWallMs"]?.jsonLong()
            ?: throw IrohProtocolException("Operation clock is missing")

    val hlcCounter: Long
        get() = operation["hlcCounter"]?.jsonLong()
            ?: throw IrohProtocolException("Operation clock is missing")

    val deviceSequence: Long?
        get() = operation["deviceSequence"]?.jsonLong()

    fun digest(): String = Base64Url.encode(
        MessageDigest.getInstance("SHA-256").digest(JsonCanonicalizer.encode(toJson())),
    )

    fun operationByteCount(): Int = JsonCanonicalizer.encode(toJson()).size

    fun validate() {
        require(IrohProtocolV1.isIdentifier(deviceId)) { "Origin device ID is invalid" }
        require(operationByteCount() <= IrohProtocolV1.MaxOperationBytes) {
            "Operation exceeds 64 KiB"
        }
        when (domain) {
            IrohDomain.genesis -> validateGenesis(decodeOperation())
            IrohDomain.timer -> validateTimer(decodeOperation())
            IrohDomain.task -> validateTask(decodeOperation())
            IrohDomain.duration -> validateDuration(decodeOperation())
            IrohDomain.autoStart -> validateAutoStart(decodeAutoStart(), deviceId)
            IrohDomain.selectedTask -> validateSelectedTask(decodeOperation())
        }
    }

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "domain" to JsonPrimitive(domain.name),
            "deviceId" to JsonPrimitive(deviceId),
            "operation" to operation,
        ),
    )

    internal inline fun <reified T> decodeOperation(): T =
        IrohJson.strict.decodeFromJsonElement(operation)

    fun decodeAutoStart(): AutoStartOperation = IrohJson.strict
        .decodeFromJsonElement<IrohAutoStartOperation>(operation)
        .let { value ->
            AutoStartOperation(
                id = value.id,
                deviceId = deviceId,
                enabled = value.enabled,
                occurredAt = value.occurredAt,
                hlcWallMs = value.hlcWallMs,
                hlcCounter = value.hlcCounter,
            )
        }

    companion object {
        fun genesis(deviceId: String, value: IrohGenesis): IrohOperationRecord {
            val wireValue = value.copy(
                canonicalTimer = value.canonicalTimer?.let { timer ->
                    timer.copy(lastIntent = timer.lastIntent?.copy(deviceId = null))
                },
            )
            val operation = IrohJson.strict.encodeToJsonElement(wireValue).jsonObject.toMutableMap()
            if (wireValue.canonicalTimer == null) operation["canonicalTimer"] = JsonNull
            if (wireValue.selectedTaskId == null) operation["selectedTaskId"] = JsonNull
            operation["history"] = JsonArray(value.history.map { item ->
                JsonObject(IrohJson.strict.encodeToJsonElement(item).jsonObject - "pending")
            })
            return IrohOperationRecord(IrohDomain.genesis, deviceId, JsonObject(operation))
        }
        fun timer(deviceId: String, value: TimerCommand) = of(IrohDomain.timer, deviceId, value)
        fun task(deviceId: String, value: TaskOperation) = of(IrohDomain.task, deviceId, value)
        fun duration(deviceId: String, value: DurationOperation) = of(IrohDomain.duration, deviceId, value)
        fun autoStart(deviceId: String, value: AutoStartOperation) = of(
            IrohDomain.autoStart,
            deviceId,
            IrohAutoStartOperation(
                value.id,
                value.enabled,
                value.occurredAt,
                value.hlcWallMs,
                value.hlcCounter,
            ),
        )
        fun selectedTask(deviceId: String, value: SelectedTaskOperation) =
            of(IrohDomain.selectedTask, deviceId, value)

        private inline fun <reified T> of(domain: IrohDomain, deviceId: String, operation: T) =
            IrohOperationRecord(domain, deviceId, IrohJson.strict.encodeToJsonElement(operation).jsonObject)

        fun fromJson(value: JsonObject): IrohOperationRecord {
            requireExactKeys(value, setOf("domain", "deviceId", "operation"))
            val domain = value["domain"]?.jsonString()?.let(IrohDomain::valueOf)
                ?: throw SerializationException("Record domain is invalid")
            val record = IrohOperationRecord(
                domain = domain,
                deviceId = value["deviceId"]?.jsonString()
                    ?: throw SerializationException("Record device ID is invalid"),
                operation = value["operation"] as? JsonObject
                    ?: throw SerializationException("Record operation is invalid"),
            )
            val keys = operationKeys(domain)
            requireExactKeys(record.operation, keys.first, keys.second)
            requireOmittedNulls(
                record.operation,
                keys.second - if (domain == IrohDomain.genesis) setOf("selectedTaskId") else emptySet(),
            )
            if (domain == IrohDomain.genesis) validateGenesisShape(record.operation)
            record.validate()
            return record
        }

        val canonicalComparator = Comparator<IrohOperationRecord> { left, right ->
            compareValues(left.hlcWallMs, right.hlcWallMs).takeIf { it != 0 }
                ?: compareValues(left.hlcCounter, right.hlcCounter).takeIf { it != 0 }
                ?: IrohProtocolV1.utf8Compare(left.deviceId, right.deviceId).takeIf { it != 0 }
                ?: IrohProtocolV1.utf8Compare(left.id, right.id)
        }

        private fun validateGenesis(value: IrohGenesis) {
            require(value.durationsMs.isValid()) { "Genesis durations are invalid" }
            require(SyncWireBounds.isClockTuple(value.hlcWallMs, value.hlcCounter, true)) {
                "Genesis clock is invalid"
            }
            require(value.tasks.map(FocusTask::id).toSet().size == value.tasks.size &&
                value.tasks.all { TaskReducer.taskFromTitle(it.title) == it }
            ) { "Genesis tasks are invalid" }
            require(value.selectedTaskId?.let(IrohProtocolV1::isIdentifier) ?: true) {
                "Genesis selected task is invalid"
            }
            value.canonicalTimer?.let(::validateCanonicalTimer)
            require(value.history.map(HistoryItem::id).toSet().size == value.history.size &&
                value.history.map(HistoryItem::timerId).toSet().size == value.history.size
            ) { "Genesis history contains duplicate identities" }
            value.history.forEach(::validateHistory)
            val commandIds = value.history.mapNotNull(HistoryItem::commandId)
            require(commandIds.toSet().size == commandIds.size) {
                "Genesis history contains duplicate command identities"
            }
        }

        private fun validateGenesisShape(operation: JsonObject) {
            val timer = operation["canonicalTimer"]
            if (timer != null && timer !is JsonNull) {
                val timerObject = timer.jsonObject
                requireExactKeys(
                    timerObject,
                    setOf(
                        "id", "phase", "status", "plannedDurationMs", "elapsedAtAnchorMs", "anchorAt",
                    ),
                    setOf("taskId", "startedByDeviceId", "lastIntent"),
                )
                requireOmittedNulls(timerObject, setOf("taskId", "startedByDeviceId", "lastIntent"))
                timerObject["lastIntent"]?.takeUnless { it is JsonNull }?.let { intent ->
                    requireExactKeys(
                        intent.jsonObject,
                        setOf("type", "commandId", "occurredAt"),
                    )
                }
            }
            (operation["history"] as? JsonArray)?.forEach { item ->
                requireExactKeys(
                    item.jsonObject,
                    setOf("id", "timerId", "phase", "status", "plannedDurationMs"),
                    setOf("commandId", "taskId", "completedAt", "endedAt"),
                )
                requireOmittedNulls(
                    item.jsonObject,
                    setOf("commandId", "taskId", "completedAt", "endedAt"),
                )
            } ?: throw IllegalArgumentException("Genesis history is invalid")
            (operation["tasks"] as? JsonArray)?.forEach { task ->
                requireExactKeys(task.jsonObject, setOf("id", "title"))
            } ?: throw IllegalArgumentException("Genesis tasks are invalid")
            requireExactKeys(
                operation["durationsMs"]?.jsonObject
                    ?: throw IllegalArgumentException("Genesis durations are invalid"),
                setOf("focus", "short_break", "long_break"),
            )
        }

        private fun validateCanonicalTimer(timer: CanonicalTimer) {
            require(IrohProtocolV1.isIdentifier(timer.id) && timer.phase in TimerPhase.all &&
                timer.status in timerStatuses &&
                timer.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs &&
                timer.elapsedAtAnchorMs in 0..timer.plannedDurationMs &&
                parseInstant(timer.anchorAt) &&
                (timer.taskId?.let(IrohProtocolV1::isIdentifier) ?: true) &&
                (timer.startedByDeviceId?.let(IrohProtocolV1::isIdentifier) ?: true)
            ) { "Genesis timer is invalid" }
            timer.lastIntent?.let { intent ->
                require(intent.type in commandTypes && IrohProtocolV1.isIdentifier(intent.commandId) &&
                    parseInstant(intent.occurredAt) && intent.deviceId == null
                ) { "Genesis timer intent is invalid" }
            }
        }

        private fun validateHistory(item: HistoryItem) {
            require(IrohProtocolV1.isIdentifier(item.id) &&
                IrohProtocolV1.isIdentifier(item.timerId) &&
                (item.commandId?.let(IrohProtocolV1::isIdentifier) ?: true) &&
                item.phase in TimerPhase.all && item.status in historyStatuses &&
                item.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs &&
                !item.pending && (item.taskId?.let(IrohProtocolV1::isIdentifier) ?: true)
            ) { "Genesis history is invalid" }
            require(item.completedAt?.let(::parseInstant) ?: (item.status != TimerStatus.Completed)) {
                "Completed genesis history requires a valid completion time: ${item.timerId}"
            }
            require(item.endedAt?.let(::parseInstant) ?: (item.status == TimerStatus.Completed)) {
                "Genesis history end time is invalid: ${item.timerId}"
            }
        }

        private fun validateTimer(value: TimerCommand) {
            require(IrohProtocolV1.isIdentifier(value.id) &&
                IrohProtocolV1.isIdentifier(value.timerId) &&
                value.deviceSequence in 1..SyncWireBounds.MaxSafeInteger &&
                value.type in commandTypes && value.phase in TimerPhase.all &&
                value.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs &&
                value.observedElapsedMs in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger &&
                (value.taskId?.let(IrohProtocolV1::isIdentifier) ?: true) &&
                (value.taskId == null || value.type == CommandType.Start && value.phase == TimerPhase.Focus)
            ) { "Timer operation is invalid" }
            SyncWireBounds.requireOperationClock(
                value.occurredAt,
                value.hlcWallMs,
                value.hlcCounter,
                allowLegacySentinel = false,
            )
        }

        private fun validateTask(value: TaskOperation) {
            require(IrohProtocolV1.isIdentifier(value.id) && IrohProtocolV1.isIdentifier(value.taskId) &&
                value.type in setOf(TaskOperationType.Upsert, TaskOperationType.Delete) &&
                when (value.type) {
                    TaskOperationType.Upsert -> value.title?.let(TaskReducer::taskFromTitle)?.id == value.taskId
                    else -> value.title == null
                }
            ) { "Task operation is invalid" }
            SyncWireBounds.requireOperationClock(value.occurredAt, value.hlcWallMs, value.hlcCounter, false)
        }

        private fun validateDuration(value: DurationOperation) {
            require(IrohProtocolV1.isIdentifier(value.id) && value.phase in TimerPhase.all &&
                DurationLimits.isValid(value.durationMs)
            ) { "Duration operation is invalid" }
            SyncWireBounds.requireOperationClock(value.occurredAt, value.hlcWallMs, value.hlcCounter, true)
        }

        private fun validateAutoStart(value: AutoStartOperation, originDeviceId: String) {
            require(IrohProtocolV1.isIdentifier(value.id) && value.deviceId == originDeviceId) {
                "Auto-start operation is invalid"
            }
            SyncWireBounds.requireOperationClock(value.occurredAt, value.hlcWallMs, value.hlcCounter, true)
        }

        private fun validateSelectedTask(value: SelectedTaskOperation) {
            require(IrohProtocolV1.isIdentifier(value.id) &&
                (value.taskId?.let(IrohProtocolV1::isIdentifier) ?: true)
            ) { "Selected-task operation is invalid" }
            SyncWireBounds.requireOperationClock(value.occurredAt, value.hlcWallMs, value.hlcCounter, false)
        }

        private fun operationKeys(domain: IrohDomain): Pair<Set<String>, Set<String>> = when (domain) {
            IrohDomain.genesis -> setOf(
                "canonicalTimer", "history", "tasks", "durationsMs", "autoStartBreaks",
                "hlcWallMs", "hlcCounter",
            ) to setOf("selectedTaskId")
            IrohDomain.timer -> setOf(
                "id", "deviceSequence", "timerId", "type", "phase", "plannedDurationMs",
                "occurredAt", "hlcWallMs", "hlcCounter", "observedElapsedMs",
            ) to setOf("taskId")
            IrohDomain.task -> setOf(
                "id", "taskId", "type", "occurredAt", "hlcWallMs", "hlcCounter",
            ) to setOf("title")
            IrohDomain.duration -> setOf(
                "id", "phase", "durationMs", "occurredAt", "hlcWallMs", "hlcCounter",
            ) to emptySet()
            IrohDomain.autoStart -> setOf(
                "id", "enabled", "occurredAt", "hlcWallMs", "hlcCounter",
            ) to emptySet()
            IrohDomain.selectedTask -> setOf(
                "id", "taskId", "occurredAt", "hlcWallMs", "hlcCounter",
            ) to emptySet()
        }

        private val commandTypes = setOf(
            CommandType.Start,
            CommandType.Pause,
            CommandType.Resume,
            CommandType.Finish,
            CommandType.Cancel,
            CommandType.Clear,
        )
        private val timerStatuses = setOf(
            TimerStatus.Running,
            TimerStatus.Paused,
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        private val historyStatuses = setOf(
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        private const val MaxTimerDurationMs = 14_400_000L
        private fun parseInstant(value: String): Boolean = runCatching { Instant.parse(value) }.isSuccess
    }
}

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

sealed interface IrohRpcMessage {
    val requestId: String

    data class Hello(val value: IrohHello) : IrohRpcMessage { override val requestId = value.requestId }
    data class Inventory(val value: IrohInventoryRequest) : IrohRpcMessage { override val requestId = value.requestId }
    data class InventoryResult(val value: IrohInventoryResult) : IrohRpcMessage { override val requestId = value.requestId }
    data class Operations(val value: IrohOperationsRequest) : IrohRpcMessage { override val requestId = value.requestId }
    data class OperationsResult(val value: IrohOperationsResult) : IrohRpcMessage { override val requestId = value.requestId }
    data class Error(val value: IrohErrorResponse) : IrohRpcMessage { override val requestId = value.requestId }
}

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
        require(body.size <= IrohProtocolV1.MaxFrameBodyBytes)
        val objectValue = IrohJson.strict.parseToJsonElement(strictJson(body)).jsonObject
        val version = objectValue["protocolVersion"]?.jsonInt()
        val roomId = objectValue["roomId"]?.jsonString()
        val requestId = objectValue["requestId"]?.jsonString()
        val kind = objectValue["kind"]?.jsonString()
        require(version == IrohProtocolV1.Version && roomId != null && IrohProtocolV1.isRoomId(roomId) &&
            requestId != null && IrohProtocolV1.isRequestId(requestId) && kind != null
        ) { "Iroh message envelope is invalid" }
        return when (kind) {
            "hello" -> {
                requireExactKeys(
                    objectValue,
                    setOf(
                        "protocolVersion", "roomId", "requestId", "kind", "deviceId",
                        "endpointTicket", "platform",
                    ),
                    setOf("displayName"),
                )
                requireOmittedNulls(objectValue, setOf("displayName"))
                val value = IrohJson.strict.decodeFromJsonElement<IrohHello>(objectValue)
                require(value.kind == kind && IrohProtocolV1.isIdentifier(value.deviceId) &&
                    value.endpointTicket.encodeToByteArray().size <= IrohProtocolV1.MaxEndpointTicketBytes &&
                    value.platform in setOf("ios", "macos", "android", "linux", "windows") &&
                    IrohProtocolV1.isDisplayName(value.displayName)
                ) { "Iroh hello is invalid" }
                IrohRpcMessage.Hello(value)
            }
            "inventory" -> {
                requireExactKeys(
                    objectValue,
                    setOf("protocolVersion", "roomId", "requestId", "kind", "after", "limit"),
                )
                val value = IrohJson.strict.decodeFromJsonElement<IrohInventoryRequest>(objectValue)
                require(value.limit in 1..IrohProtocolV1.MaxInventoryEntries &&
                    (value.after?.let(::validCursor) ?: true)
                ) { "Iroh inventory request is invalid" }
                IrohRpcMessage.Inventory(value)
            }
            "inventoryResult" -> {
                requireExactKeys(
                    objectValue,
                    setOf("protocolVersion", "roomId", "requestId", "kind", "entries", "next"),
                )
                val entries = objectValue["entries"] as? JsonArray
                    ?: throw SerializationException("Inventory entries are invalid")
                entries.forEach { entry ->
                    requireExactKeys(entry.jsonObject, setOf("domain", "id", "digest"))
                }
                val value = IrohJson.strict.decodeFromJsonElement<IrohInventoryResult>(objectValue)
                require(value.entries.size <= IrohProtocolV1.MaxInventoryEntries &&
                    (value.next?.let(::validCursor) ?: true) && entriesOrdered(value.entries) &&
                    value.entries.all(::validInventoryEntry)
                ) { "Iroh inventory result is invalid" }
                IrohRpcMessage.InventoryResult(value)
            }
            "operations" -> {
                requireExactKeys(
                    objectValue,
                    setOf("protocolVersion", "roomId", "requestId", "kind", "refs"),
                )
                val refs = objectValue["refs"] as? JsonArray
                    ?: throw SerializationException("Operation references are invalid")
                refs.forEach { requireExactKeys(it.jsonObject, setOf("domain", "id")) }
                val value = IrohJson.strict.decodeFromJsonElement<IrohOperationsRequest>(objectValue)
                require(value.refs.isNotEmpty() && value.refs.size <= IrohProtocolV1.MaxOperationReferences &&
                    value.refs.toSet().size == value.refs.size && value.refs.all(::validReference)
                ) { "Iroh operation references are invalid" }
                IrohRpcMessage.Operations(value)
            }
            "operationsResult" -> {
                requireExactKeys(
                    objectValue,
                    setOf("protocolVersion", "roomId", "requestId", "kind", "records"),
                )
                val raw = objectValue["records"] as? JsonArray
                    ?: throw SerializationException("Operation records are invalid")
                require(raw.size <= IrohProtocolV1.MaxOperationReferences)
                IrohRpcMessage.OperationsResult(
                    IrohOperationsResult(version, roomId, requestId, raw.map { record ->
                        IrohOperationRecord.fromJson(record.jsonObject)
                    }),
                )
            }
            "error" -> {
                requireExactKeys(
                    objectValue,
                    setOf(
                        "protocolVersion", "roomId", "requestId", "kind", "code",
                        "message", "retryable",
                    ),
                )
                val value = IrohJson.strict.decodeFromJsonElement<IrohErrorResponse>(objectValue)
                require(value.code in errorCodes && value.message.encodeToByteArray().size <= 1_024)
                IrohRpcMessage.Error(value)
            }
            else -> throw SerializationException("Unknown Iroh message kind")
        }
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

object JsonCanonicalizer {
    fun encode(value: JsonElement): ByteArray = canonical(value).encodeToByteArray()

    private fun canonical(value: JsonElement): String = when (value) {
        JsonNull -> "null"
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",", transform = ::canonical)
        is JsonObject -> value.keys.sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            IrohJson.strict.encodeToString(key) + ":" + canonical(requireNotNull(value[key]))
        }
        is JsonPrimitive -> when {
            value.isString -> IrohJson.strict.encodeToString(value.content)
            value.booleanOrNull != null -> value.booleanOrNull.toString()
            else -> value.longOrNull?.also {
                require(it in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger)
            }?.toString() ?: throw IllegalArgumentException("Canonical records require integer numbers")
        }
    }
}

@Serializable
data class IrohConflictEvidence(
    val domain: IrohDomain,
    val id: String,
    val localDigest: String,
    val receivedDigest: String,
    val detectedAtMs: Long,
)

internal object IrohJson {
    val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }
}

@Serializable
private data class IrohAutoStartOperation(
    val id: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)

private fun requireExactKeys(
    value: JsonObject,
    required: Set<String>,
    optional: Set<String> = emptySet(),
) {
    require(value.keys.containsAll(required) && value.keys.all { it in required || it in optional }) {
        "JSON object has missing or unknown fields"
    }
}

private fun requireOmittedNulls(value: JsonObject, optional: Set<String>) {
    require(optional.none { value[it] is JsonNull }) { "Optional JSON fields must be omitted instead of null" }
}

private fun JsonElement.jsonString(): String? = (this as? JsonPrimitive)?.contentOrNull
    ?.takeIf { (this as JsonPrimitive).isString }
private fun JsonElement.jsonLong(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement.jsonInt(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun strictJson(value: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(value))
    .toString()
    .also { StrictJsonScanner(it).validate() }

private class StrictJsonScanner(private val source: String) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        require(index == source.length) { "Body is not strict JSON" }
    }

    private fun parseValue() {
        when (current()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> consumeLiteral("true")
            'f' -> consumeLiteral("false")
            'n' -> consumeLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> invalid()
        }
    }

    private fun parseObject() {
        consume('{')
        skipWhitespace()
        if (consumeIfPresent('}')) return
        val keys = mutableSetOf<String>()
        while (true) {
            require(current() == '"') { "Body is not strict JSON" }
            val key = Normalizer.normalize(parseString(), Normalizer.Form.NFC)
            require(keys.add(key)) { "JSON contains a duplicate object key" }
            skipWhitespace()
            consume(':')
            skipWhitespace()
            parseValue()
            skipWhitespace()
            if (consumeIfPresent('}')) return
            consume(',')
            skipWhitespace()
        }
    }

    private fun parseArray() {
        consume('[')
        skipWhitespace()
        if (consumeIfPresent(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (consumeIfPresent(']')) return
            consume(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        consume('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character.code in 0x00..0x1f -> invalid()
                character == '\\' -> parseEscape(result)
                Character.isHighSurrogate(character) -> {
                    require(index < source.length && Character.isLowSurrogate(source[index])) {
                        "Body is not strict JSON"
                    }
                    result.append(character).append(source[index++])
                }
                Character.isLowSurrogate(character) -> invalid()
                else -> result.append(character)
            }
        }
        invalid()
    }

    private fun parseEscape(result: StringBuilder) {
        val escaped = current() ?: invalid()
        index += 1
        when (escaped) {
            '"', '/', '\\' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val first = parseHexQuad()
                when (first) {
                    in 0xd800..0xdbff -> {
                        consume('\\')
                        consume('u')
                        val second = parseHexQuad()
                        require(second in 0xdc00..0xdfff) { "Body is not strict JSON" }
                        result.appendCodePoint(
                            0x10000 + ((first - 0xd800) shl 10) + second - 0xdc00,
                        )
                    }
                    in 0xdc00..0xdfff -> invalid()
                    else -> result.append(first.toChar())
                }
            }
            else -> invalid()
        }
    }

    private fun parseHexQuad(): Int {
        require(index <= source.length - 4) { "Body is not strict JSON" }
        var value = 0
        repeat(4) {
            val digit = source[index++].digitToIntOrNull(16) ?: invalid()
            value = (value shl 4) or digit
        }
        return value
    }

    private fun parseNumber() {
        consumeIfPresent('-')
        when (current()) {
            '0' -> {
                index += 1
                require(current() !in '0'..'9') { "Body is not strict JSON" }
            }
            in '1'..'9' -> consumeDigits()
            else -> invalid()
        }
        if (consumeIfPresent('.')) {
            require(current() in '0'..'9') { "Body is not strict JSON" }
            consumeDigits()
        }
        if (current() == 'e' || current() == 'E') {
            index += 1
            if (current() == '+' || current() == '-') index += 1
            require(current() in '0'..'9') { "Body is not strict JSON" }
            consumeDigits()
        }
    }

    private fun consumeDigits() {
        while (current() in '0'..'9') index += 1
    }

    private fun consumeLiteral(value: String) = value.forEach(::consume)

    private fun consume(expected: Char) {
        require(current() == expected) { "Body is not strict JSON" }
        index += 1
    }

    private fun consumeIfPresent(expected: Char): Boolean {
        if (current() != expected) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (current() in setOf(' ', '\t', '\n', '\r')) index += 1
    }

    private fun current(): Char? = source.getOrNull(index)

    private fun invalid(): Nothing = throw IllegalArgumentException("Body is not strict JSON")
}

private fun JsonObject.withRequiredNull(key: String, value: Any?): JsonObject =
    if (value != null || key in this) this else JsonObject(this + (key to JsonNull))
