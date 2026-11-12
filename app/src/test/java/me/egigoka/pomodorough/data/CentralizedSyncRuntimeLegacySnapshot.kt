package me.egigoka.pomodorough.data

/** A signed-in centralized snapshot whose retained queue may still wait for its Core decision. */
internal fun legacyHoldSnapshot(pendingQueuesEmpty: Boolean, legacyAdoptionPending: Boolean) =
    CentralizedSyncRuntimeSnapshot(
        signedIn = true,
        centralized = true,
        resolutionPending = false,
        accountSwitchPending = false,
        terminalSyncError = false,
        pendingQueuesEmpty = pendingQueuesEmpty,
        localRevision = 0,
        legacyAdoptionPending = legacyAdoptionPending,
    )
