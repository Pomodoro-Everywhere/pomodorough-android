package me.egigoka.pomodorough.data.iroh.protocol

import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.DurationLimits
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.SelectedTaskOperation
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.data.TaskOperationType
import me.egigoka.pomodorough.data.TimerCommand
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus

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

        fun duration(deviceId: String, value: DurationOperation) =
            of(IrohDomain.duration, deviceId, value)

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
                value.tasks.all { IrohProtocolV1.isIdentifier(it.id) }
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
                value.observedElapsedMs in 0..value.plannedDurationMs &&
                (value.type != CommandType.Start || value.observedElapsedMs == 0L) &&
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
                    TaskOperationType.Upsert -> value.title != null
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
private data class IrohAutoStartOperation(
    val id: String,
    val enabled: Boolean,
    val occurredAt: String,
    val hlcWallMs: Long,
    val hlcCounter: Long,
)
