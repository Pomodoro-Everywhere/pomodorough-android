package me.egigoka.pomodorough.data

internal data class CentralizedAccountSyncSnapshot(
    val user: User?,
    val authStatus: AuthStatus,
    val syncing: Boolean,
    val retrying: Boolean,
    val terminalSyncError: String?,
    val historyResolution: HistoryResolutionState?,
    val accountSwitch: AccountSwitchState?,
)

internal sealed interface CentralizedAccountSyncTransition {
    data class SessionCleared(val authStatus: AuthStatus) : CentralizedAccountSyncTransition

    data class SyncActivityChanged(
        val syncing: Boolean,
        val retrying: Boolean,
    ) : CentralizedAccountSyncTransition

    data class AuthenticationChanged(
        val user: User?,
        val authStatus: AuthStatus,
    ) : CentralizedAccountSyncTransition
}

internal class CentralizedAccountSyncAggregate(
    eventSink: AccountWorkspaceEventSink,
) {
    val workspace = AccountWorkspaceController(eventSink)

    var user: User? = null
    var authStatus: AuthStatus = AuthStatus.Loading
    var syncing: Boolean = false
    var retrying: Boolean = false
    var terminalSyncError: String? = null
    var historyResolution: HistoryResolutionState? = null
    var accountSwitch: AccountSwitchState? = null

    fun snapshot() = CentralizedAccountSyncSnapshot(
        user = user,
        authStatus = authStatus,
        syncing = syncing,
        retrying = retrying,
        terminalSyncError = terminalSyncError,
        historyResolution = historyResolution,
        accountSwitch = accountSwitch,
    )

    fun runtimeSnapshot(
        centralized: Boolean,
        pendingQueuesEmpty: Boolean,
        localRevision: Long,
    ) = CentralizedSyncRuntimeSnapshot(
        signedIn = authStatus == AuthStatus.SignedIn,
        centralized = centralized,
        resolutionPending = historyResolution != null,
        accountSwitchPending = accountSwitch != null,
        terminalSyncError = terminalSyncError != null,
        pendingQueuesEmpty = pendingQueuesEmpty,
        localRevision = localRevision,
    )

    fun accept(transition: CentralizedAccountSyncTransition) {
        when (transition) {
            is CentralizedAccountSyncTransition.SessionCleared -> clearSessionState(transition.authStatus)
            is CentralizedAccountSyncTransition.SyncActivityChanged -> {
                syncing = transition.syncing
                retrying = transition.retrying
            }
            is CentralizedAccountSyncTransition.AuthenticationChanged -> {
                user = transition.user
                authStatus = transition.authStatus
            }
        }
    }

    fun clearSessionState(nextAuthStatus: AuthStatus) {
        user = null
        authStatus = nextAuthStatus
        syncing = false
        retrying = false
        terminalSyncError = null
        historyResolution = null
        accountSwitch = null
    }
}
