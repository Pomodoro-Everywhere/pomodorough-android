package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot

@Serializable
internal data class LegacyDependencyPlan(
    val schemaVersion: Int,
    val outcome: String,
    val workspace: JsonObject,
    val ownership: JsonObject,
    val outgoing: JsonElement,
    val outgoingAction: String,
    val wireAction: String,
    val metadataWrites: List<JsonObject>,
    val timerDependencies: List<CoreTimerDependency>?,
    val validatedDependencies: List<CoreTimerDependency>,
    val classifications: List<JsonObject>,
    val recovery: JsonObject,
    val effectsAfterCommit: List<JsonElement>,
)

internal data class LegacyDependencyDecision(val input: JsonObject, val result: JsonObject, val plan: LegacyDependencyPlan)

/** Adapts observed Room rows. Missing historical claims/acknowledgements stay absent, never inferred. */
internal class LegacyDependencyAdoption(private val dispatch: (String, String) -> JsonElement) {
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    fun plan(snapshot: LocalWorkspaceSnapshot, now: Instant, zone: ZoneId): LegacyDependencyDecision {
        val input = LegacyDependencyObservations(snapshot, json).request(now, zone)
        return plan(input)
    }

    fun plan(input: JsonObject): LegacyDependencyDecision {
        val result = dispatch(Operation, input.toString()).jsonObject
        return validate(input, result)
    }

    fun validate(input: JsonObject, result: JsonObject): LegacyDependencyDecision {
        require(input.getValue("profile").jsonPrimitive.content in setOf("androidCentralized", "pwaStorage"))
        require(input.getValue("deviceId").jsonPrimitive.content.isNotBlank())
        val workspace = input.getValue("workspace").jsonObject
        for (field in listOf("base", "local", "neverSent", "canonicalHead", "timerDependencies", "displayContext", "now")) {
            require(workspace.containsKey(field))
        }
        require(input.containsKey("outgoing") && input.getValue("sourceAcknowledgements") is JsonArray)
        require(input.getValue("calendarIntervals") is JsonArray)
        val ownership = input.getValue("ownership").jsonObject
        require(ownership.containsKey("ownerId") && ownership["ownerId"] == ownership["expectedOwnerId"])
        require(ownership.containsKey("timerOwner"))
        val display = workspace.getValue("displayContext").jsonObject
        require(display["profile"] == JsonPrimitive("pwaStorage") && display.containsKey("projectionPending"))
        for (domain in CoreBatchPlanDispatcher.Domains) require(workspace.getValue("local").jsonObject[domain] is JsonArray)
        for (field in listOf("canonicalTimer", "history", "tasks", "durationsMs", "autoStartBreaks", "selectedTaskId")) {
            require(workspace.getValue("base").jsonObject.containsKey(field))
        }
        val plan = json.decodeFromJsonElement<LegacyDependencyPlan>(result)
        require(plan.schemaVersion == 1 && plan.outcome in setOf("planned", "noop", "blocked"))
        require(plan.outgoingAction == "preserve" && plan.wireAction == "preserve")
        require(plan.outgoing == input["outgoing"] && plan.ownership == input["ownership"])
        require(plan.effectsAfterCommit.isEmpty())
        require(plan.recovery.getValue("status").jsonPrimitive.content in setOf("ready", "blocked"))
        require(plan.recovery.getValue("automaticRepair") == JsonPrimitive(false))
        require(plan.recovery.getValue("unresolved") is JsonArray)
        val original = input.getValue("workspace").jsonObject
        require(plan.workspace.filterKeys { it != "timerDependencies" } == original.filterKeys { it != "timerDependencies" })
        if (plan.outcome == "blocked") {
            require(plan.recovery["status"] == JsonPrimitive("blocked"))
            require(plan.workspace == original && plan.metadataWrites.isEmpty() && plan.timerDependencies == null)
            require(plan.recovery["blocksSync"] == JsonPrimitive(true) && plan.recovery["blocksMutations"] == JsonPrimitive(true))
        } else {
            require(plan.recovery["status"] == JsonPrimitive("ready") && plan.recovery["unresolved"] == JsonArray(emptyList()))
            require(plan.timerDependencies != null)
            require(json.decodeFromJsonElement<List<CoreTimerDependency>>(plan.workspace.getValue("timerDependencies")) == plan.timerDependencies)
            require(plan.recovery["blocksSync"] == JsonPrimitive(false) && plan.recovery["blocksMutations"] == JsonPrimitive(false))
            require(plan.metadataWrites.all {
                it["kind"] == JsonPrimitive("recordTimerDependencies") && it["value"] == plan.workspace["timerDependencies"]
            })
            require(plan.validatedDependencies == plan.timerDependencies)
            require(plan.metadataWrites.size == if (plan.outcome == "planned") 1 else 0)
        }
        return LegacyDependencyDecision(input, result, plan)
    }

    companion object { const val Operation = "workspace.legacyDependencyPlan.v1" }
}

internal class LegacyDependencyObservations(private val snapshot: LocalWorkspaceSnapshot, private val json: Json) {
    private val local = snapshot.local

    fun request(now: Instant, zone: ZoneId): JsonObject = buildJsonObject {
        put("profile", "androidCentralized")
        put("deviceId", local.deviceId)
        put("workspace", workspace(now))
        put("ownership", buildJsonObject {
            put("ownerId", local.ownerUserId?.let(::JsonPrimitive) ?: JsonNull)
            val userOwner = local.userJson?.let(json::parseToJsonElement)?.jsonObject?.get("id") ?: JsonNull
            put("expectedOwnerId", userOwner)
        put("timerOwner", local.ownedTimerId?.let { timerId -> buildJsonObject {
            put("timerId", timerId); put("deviceId", local.deviceId)
        } } ?: JsonNull)
        })
        // Android never persisted normal outgoing bodies or source ACKs. Retain bootstrap
        // queue observations below without inventing its missing original HTTP body.
        put("outgoing", snapshot.bootstrapResolution?.let { saved -> buildJsonObject {
            put("ownerId", saved.ownerUserId)
            // A bootstrap stores separate immutable JSON queues, not a captured HTTP body.
            // Preserve those observations, but do not synthesize the missing original body.
            put("payload", buildJsonObject {
                put("commands", json.parseToJsonElement(saved.commandsJson))
                put("taskOperations", json.parseToJsonElement(saved.taskOperationsJson))
                put("durationOperations", json.parseToJsonElement(saved.durationOperationsJson))
                saved.autoStartOperationsJson?.let { put("autoStartOperations", json.parseToJsonElement(it)) }
                saved.selectedTaskOperationsJson?.let { put("selectedTaskOperations", json.parseToJsonElement(it)) }
            })
        } } ?: JsonNull)
        put("sourceAcknowledgements", JsonArray(emptyList()))
        put("calendarIntervals", calendar(now, zone))
    }

    private fun workspace(now: Instant): JsonObject = buildJsonObject {
        put("base", base())
        put("local", queues())
        put("canonicalHead", if (local.safeCanonicalHeadWallMs == null || local.safeCanonicalHeadCounter == null) JsonNull
            else buildJsonObject { put("wallMs", local.safeCanonicalHeadWallMs); put("counter", local.safeCanonicalHeadCounter) })
        put("neverSent", buildJsonObject {
            put("commands", json.encodeToJsonElement(snapshot.commands.filter { it.neverSent }.map { it.id }))
            put("taskOperations", json.encodeToJsonElement(snapshot.taskOperations.filter { it.neverSent }.map { it.id }))
            put("durationOperations", json.encodeToJsonElement(snapshot.durationOperations.filter { it.neverSent }.map { it.id }))
            put("autoStartOperations", json.encodeToJsonElement(snapshot.autoStartOperations.filter { it.neverSent }.map { it.id }))
            put("selectedTaskOperations", json.encodeToJsonElement(snapshot.selectedTaskOperations.filter { it.neverSent }.map { it.id }))
        })
        put("timerDependencies", JsonNull)
        put("displayContext", buildJsonObject { put("profile", "pwaStorage"); put("projectionPending", JsonNull) })
        put("now", now.toString())
    }

    private fun base(): JsonObject = buildJsonObject {
        val settings = json.decodeFromString<TimerSettings>(local.settingsJson)
        put("canonicalTimer", local.canonicalTimerJson?.let(json::parseToJsonElement) ?: JsonNull)
        put("history", json.parseToJsonElement(local.historyJson))
        put("tasks", json.parseToJsonElement(local.tasksJson))
        put("durationsMs", local.safeBaseDurationsJson?.let(json::parseToJsonElement)
            ?: json.encodeToJsonElement(settings.effectiveDurationsMs()))
        put("autoStartBreaks", local.canonicalAutoStartBreaks)
        put("selectedTaskId", (if (local.safeCanonicalHeadWallMs == null) local.selectedTaskId else local.safeBaseSelectedTaskId)
            ?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun queues(): JsonObject = buildJsonObject {
        put("commands", JsonArray(snapshot.commands.map { row ->
            JsonObject(json.encodeToJsonElement(row.toModel()).jsonObject + buildJsonObject {
                row.physicalOccurredAt?.let { put("physicalOccurredAt", it) }
                put("generatedByFinishCommandId", row.generatedByFinishCommandId?.let(::JsonPrimitive) ?: JsonNull)
                put("neverSent", row.neverSent)
            })
        }))
        put("taskOperations", json.encodeToJsonElement(snapshot.taskOperations.map { it.toModel() }))
        put("durationOperations", json.encodeToJsonElement(snapshot.durationOperations.map { it.toModel() }))
        put("autoStartOperations", json.encodeToJsonElement(snapshot.autoStartOperations.map { it.toModel() }))
        put("selectedTaskOperations", json.encodeToJsonElement(snapshot.selectedTaskOperations.map { it.toModel() }))
    }

    private fun calendar(now: Instant, zone: ZoneId): JsonArray {
        val times = snapshot.commands.map { Instant.parse(it.physicalOccurredAt ?: it.occurredAt) } + now
        return JsonArray(times.map { it.atZone(zone).toLocalDate() }.distinct().sorted().map { day ->
            buildJsonObject {
                put("start", day.atStartOfDay(zone).toInstant().toString())
                put("end", day.plusDays(1).atStartOfDay(zone).toInstant().toString())
            }
        })
    }
}
