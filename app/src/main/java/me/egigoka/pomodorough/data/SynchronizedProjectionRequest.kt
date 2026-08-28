package me.egigoka.pomodorough.data

import java.time.Instant

internal data class SynchronizedProjectionRequest(
    val base: CoreProjectionBase,
    val pending: CoreProjectionPending,
    val horizon: Instant,
)

internal object SynchronizedProjectionRequestFactory {
    fun create(
        base: CoreProjectionBase,
        queues: PendingSyncQueues,
        localDeviceId: String,
    ): SynchronizedProjectionRequest = create(
        base = base,
        pending = CoreProjectionPending(
            commands = queues.commands.map { DeviceOperation(localDeviceId, it) },
            taskOperations = queues.taskOperations.map { DeviceOperation(localDeviceId, it) },
            durationOperations = queues.durationOperations.map { DeviceOperation(localDeviceId, it) },
            autoStartOperations = queues.autoStartOperations.map { DeviceOperation(it.deviceId, it) },
            selectedTaskOperations = queues.selectedTaskOperations.map {
                DeviceOperation(localDeviceId, it)
            },
        ),
    )

    internal fun create(
        base: CoreProjectionBase,
        pending: CoreProjectionPending,
    ): SynchronizedProjectionRequest = SynchronizedProjectionRequest(
        base = base,
        pending = pending,
        horizon = deterministicHorizon(base, pending.commands),
    )

    private fun deterministicHorizon(
        base: CoreProjectionBase,
        commands: List<DeviceOperation<TimerCommand>>,
    ): Instant = commands.maxWithOrNull(commandOrder)
        ?.value
        ?.occurredAt
        ?.let(::parseInstant)
        ?: base.canonicalTimer?.anchorAt?.let(::parseInstant)
        ?: Instant.EPOCH

    private val commandOrder = Comparator<DeviceOperation<TimerCommand>> { left, right ->
        compareValues(left.value.hlcWallMs, right.value.hlcWallMs)
            .takeUnless { it == 0 }
            ?: compareValues(left.value.hlcCounter, right.value.hlcCounter)
                .takeUnless { it == 0 }
            ?: SyncWireBounds.compareUtf8(left.deviceId, right.deviceId).takeUnless { it == 0 }
            ?: SyncWireBounds.compareUtf8(left.value.id, right.value.id)
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
}
