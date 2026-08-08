package me.egigoka.pomodorough.domain

import java.time.Instant
import me.egigoka.pomodorough.data.AutoStartOperation
import me.egigoka.pomodorough.data.DurationLimits
import me.egigoka.pomodorough.data.DurationOperation
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings

object SettingsReducer {
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

    fun applyDuration(base: TimerSettings, operation: DurationOperation): TimerSettings =
        if (isValid(operation)) base.withDuration(operation.phase, operation.durationMs) else base

    fun applyAutoStart(base: Boolean, operation: AutoStartOperation): Boolean =
        if (isValid(operation)) operation.enabled else base

    fun isValid(operation: DurationOperation): Boolean =
        operation.phase in TimerPhase.all &&
            DurationLimits.isValid(operation.durationMs) &&
            SyncWireBounds.isClockTuple(
                operation.hlcWallMs,
                operation.hlcCounter,
                allowLegacySentinel = true,
            )

    fun isValid(operation: AutoStartOperation): Boolean =
        SyncWireBounds.isIdentifier(operation.id) &&
            SyncWireBounds.isIdentifier(operation.deviceId) &&
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
