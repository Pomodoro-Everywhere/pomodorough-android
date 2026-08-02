package me.egigoka.pomodorough.domain

import java.time.Instant
import java.util.UUID
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.DurationLimits
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings

internal object SettingsReducer {
    fun replayDurations(
        base: TimerSettings,
        operations: List<DurationOperation>,
    ): TimerSettings = operations
        .asSequence()
        .filter(::isValid)
        .sortedWith(durationComparator)
        .fold(base) { current, operation ->
            current.withDuration(operation.phase, operation.durationMs)
        }

    fun replayAutoStart(
        base: Boolean,
        operations: List<AutoStartOperation>,
    ): Boolean = operations
        .asSequence()
        .filter(::isValid)
        .maxWithOrNull(autoStartComparator)
        ?.enabled
        ?: base

    fun isValid(operation: DurationOperation): Boolean =
        operation.phase in TimerPhase.all &&
            DurationLimits.isValid(operation.durationMs) &&
            SyncWireBounds.isClockTuple(
                operation.hlcWallMs,
                operation.hlcCounter,
                allowLegacySentinel = true,
            )

    fun isValid(operation: AutoStartOperation): Boolean =
        runCatching { UUID.fromString(operation.id) }.isSuccess &&
            operation.deviceId.isNotBlank() &&
            SyncWireBounds.isClockTuple(
                operation.hlcWallMs,
                operation.hlcCounter,
                allowLegacySentinel = true,
            ) &&
            runCatching { Instant.parse(operation.occurredAt) }.getOrNull()?.let { occurrence ->
                operation.hlcWallMs > 0L || occurrence == Instant.EPOCH
            } == true

    val durationComparator = compareBy<DurationOperation>(
        DurationOperation::hlcWallMs,
        DurationOperation::hlcCounter,
        DurationOperation::id,
    )

    val autoStartComparator = compareBy<AutoStartOperation>(
        AutoStartOperation::hlcWallMs,
        AutoStartOperation::hlcCounter,
        AutoStartOperation::deviceId,
        AutoStartOperation::id,
    )
}
