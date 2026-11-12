package me.egigoka.pomodorough.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.egigoka.pomodorough.data.local.LegacyDependencyAdoptionPending
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.TimerDao

/** Whether a workspace's retained legacy queue may sync. */
internal enum class LegacyDependencyGate {
    Open,

    /** The marker still asks for adoption, so the next startup or reload retries it. */
    RetryAdoption,

    /** Core blocked the queue, or its recorded decision is invalid. Only a local reset clears it. */
    Blocked,
}

/**
 * Records Core's legacy dependency decision without touching retained rows.
 * `generatedByFinishCommandId` keeps its stored sibling edge (LEGACY_DEPENDENCIES.md), so
 * release gating stays keyed on it. Core's edges live only in the persisted result JSON.
 */
internal class LegacyDependencyStartup(
    private val dao: TimerDao,
    private val dispatch: (String, String) -> JsonElement,
) {
    suspend fun adopt(now: Instant, zone: ZoneId): LocalStateEntity {
        val snapshot = dao.legacyDependencySnapshot()
        val local = snapshot.local
        if (!pending(local)) return local
        if (snapshot.commands.none { it.generatedByFinishCommandId != null }) {
            // No retained edge is left to reconstruct, so later edges are modern rows.
            val modern = local.copy(legacyDependencyResultJson = null)
            dao.installLegacyDependencies(snapshot, modern)
            return modern
        }
        var raw: JsonObject? = null
        val decision = try {
            raw = LegacyDependencyObservations(snapshot, Json { encodeDefaults = true; explicitNulls = true }).request(now, zone)
            LegacyDependencyAdoption(dispatch).plan(raw)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // No Core decision authorizes a metadata write after invalid or incomplete evidence.
            CorruptStateReporter.report(CorruptStateCategory.Decoding)
            val blocked = local.copy(legacyDependencyInputJson = raw?.toString(),
                legacyDependencyResultJson = "{\"outcome\":\"invalid_evidence\"}")
            dao.installLegacyDependencies(snapshot, blocked)
            return blocked
        }
        if (decision.plan.outcome != "blocked") {
            val retainedIds = snapshot.commands.map { it.id }.toSet()
            require(checkNotNull(decision.plan.timerDependencies).all {
                it.operationId in retainedIds && it.dependsOnOperationId in retainedIds
            })
        }
        val next = local.copy(
            legacyDependencyInputJson = decision.input.toString(),
            legacyDependencyResultJson = decision.result.toString(),
        )
        dao.installLegacyDependencies(snapshot, next)
        return next
    }

    companion object {
        /** Migration 16 to 17 marks only workspaces that retained pre-R54 dependency rows. */
        fun pending(local: LocalStateEntity): Boolean =
            local.legacyDependencyInputJson == null && local.legacyDependencyResultJson == LegacyDependencyAdoptionPending

        fun blocked(local: LocalStateEntity): Boolean = !pending(local) && local.legacyDependencyResultJson?.let { raw -> runCatching {
            require(local.legacyDependencyInputJson != null)
            val input = Json.parseToJsonElement(checkNotNull(local.legacyDependencyInputJson)).jsonObject
            val result = Json.parseToJsonElement(raw).jsonObject
            val decision = LegacyDependencyAdoption { _, _ -> error("persisted validation never calls Core") }.validate(input, result)
            decision.plan.outcome == "blocked"
        }.getOrDefault(true) } ?: (local.legacyDependencyInputJson != null)
    }
}
