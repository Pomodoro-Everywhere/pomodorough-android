package me.egigoka.pomodorough.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class AccountWorkspaceReason {
    AuthenticationCompleted,
    AuthenticationExpired,
    AccountSwitchDetected,
    AccountSwitchConfirmed,
    AccountSwitchCancelled,
    BootstrapInvalidated,
    BootstrapRefreshed,
    BootstrapResolved,
    DeletionStarted,
    IrohLogoutStarted,
    LocalAccountResetStarted,
    LogoutStarted,
    RecoveryStarted,
    RecoveryExpired,
    ReplicationModeChanged,
    SyncAuthenticationExpired,
}

internal data class AccountAttemptIdentity(
    val accountGeneration: Long,
    val requestId: String?,
)

internal data class AccountAdmissionSnapshot(
    val generation: Long,
    val deletionQuarantined: Boolean,
)

internal data class AccountBootstrapCapture(
    val response: SyncResponse,
    val clockSample: ServerClockSample?,
)

internal data class AccountSwitchCandidate(
    val profile: User,
    val bootstrap: SyncResponse,
    val clockSample: ServerClockSample,
)

internal sealed interface AccountWorkspaceTransition {
    data class SignInClaim(val acquired: Boolean) : AccountWorkspaceTransition

    data class GenerationAdvanced(
        val previousGeneration: Long,
        val generation: Long,
        val reason: AccountWorkspaceReason,
    ) : AccountWorkspaceTransition

    data class BootstrapCaptured(
        val capture: AccountBootstrapCapture,
        val generation: Long,
        val reason: AccountWorkspaceReason,
    ) : AccountWorkspaceTransition

    data class BootstrapCleared(
        val generation: Long,
        val reason: AccountWorkspaceReason,
    ) : AccountWorkspaceTransition

    data class AccountSwitchCaptured(
        val candidate: AccountSwitchCandidate,
        val generation: Long,
    ) : AccountWorkspaceTransition

    data class AccountSwitchCleared(
        val reason: AccountWorkspaceReason,
    ) : AccountWorkspaceTransition
}

internal fun interface AccountWorkspaceEventSink {
    fun emit(transition: AccountWorkspaceTransition)
}

internal class AccountWorkspaceController(
    private val eventSink: AccountWorkspaceEventSink,
) {
    private val actionMutex = Mutex()
    private val signInInFlight = AtomicBoolean(false)
    @Volatile private var currentAdmission = AccountAdmissionSnapshot(
        generation = 0L,
        deletionQuarantined = false,
    )
    @Volatile private var currentBootstrap: AccountBootstrapCapture? = null
    @Volatile private var currentAccountSwitch: AccountSwitchCandidate? = null

    val generation: Long get() = currentAdmission.generation
    val admissionSnapshot: AccountAdmissionSnapshot get() = currentAdmission
    val bootstrap: AccountBootstrapCapture? get() = currentBootstrap
    val accountSwitchCandidate: AccountSwitchCandidate? get() = currentAccountSwitch

    suspend fun <T> serialize(action: suspend () -> T): T = actionMutex.withLock { action() }

    fun claimSignIn(): AccountWorkspaceTransition.SignInClaim {
        return AccountWorkspaceTransition.SignInClaim(
            signInInFlight.compareAndSet(false, true),
        ).also(eventSink::emit)
    }

    fun releaseSignIn() {
        signInInFlight.set(false)
        eventSink.emit(AccountWorkspaceTransition.SignInClaim(acquired = false))
    }

    fun attemptIdentity(requestId: String?): AccountAttemptIdentity =
        AccountAttemptIdentity(generation, requestId)

    fun owns(identity: AccountAttemptIdentity, requestId: String?): Boolean =
        identity.accountGeneration == generation && identity.requestId == requestId

    fun beginDeletionAdmission(): AccountWorkspaceTransition.GenerationAdvanced {
        currentAdmission = currentAdmission.copy(deletionQuarantined = true)
        return advanceGeneration(AccountWorkspaceReason.DeletionStarted)
    }

    fun setDeletionAdmissionQuarantined(quarantined: Boolean) {
        currentAdmission = currentAdmission.copy(deletionQuarantined = quarantined)
    }

    fun advanceGeneration(
        reason: AccountWorkspaceReason,
    ): AccountWorkspaceTransition.GenerationAdvanced {
        val previous = currentAdmission.generation
        currentAdmission = currentAdmission.copy(generation = previous + 1)
        return AccountWorkspaceTransition.GenerationAdvanced(
            previousGeneration = previous,
            generation = currentAdmission.generation,
            reason = reason,
        ).also(eventSink::emit)
    }

    fun completeAuthentication(
        response: SyncResponse,
        clockSample: ServerClockSample,
    ): AccountWorkspaceTransition.GenerationAdvanced {
        captureBootstrap(response, clockSample, AccountWorkspaceReason.AuthenticationCompleted)
        return advanceGeneration(AccountWorkspaceReason.AuthenticationCompleted)
    }

    fun beginRecovery(): AccountWorkspaceTransition.GenerationAdvanced {
        clearBootstrap(AccountWorkspaceReason.RecoveryStarted)
        return advanceGeneration(AccountWorkspaceReason.RecoveryStarted)
    }

    fun expireRecovery(): AccountWorkspaceTransition.GenerationAdvanced {
        val transition = advanceGeneration(AccountWorkspaceReason.RecoveryExpired)
        clearBootstrap(AccountWorkspaceReason.RecoveryExpired)
        return transition
    }

    fun expireAuthentication(
        reason: AccountWorkspaceReason,
        discardBootstrap: Boolean,
    ): AccountWorkspaceTransition.GenerationAdvanced {
        val transition = advanceGeneration(reason)
        if (discardBootstrap) clearBootstrap(reason)
        return transition
    }

    fun captureBootstrap(
        response: SyncResponse,
        clockSample: ServerClockSample?,
        reason: AccountWorkspaceReason,
    ): AccountWorkspaceTransition.BootstrapCaptured {
        val capture = AccountBootstrapCapture(response, clockSample)
        currentBootstrap = capture
        return AccountWorkspaceTransition.BootstrapCaptured(
            capture = capture,
            generation = generation,
            reason = reason,
        ).also(eventSink::emit)
    }

    fun replaceBootstrapResponse(
        response: SyncResponse,
        reason: AccountWorkspaceReason,
    ): AccountWorkspaceTransition.BootstrapCaptured = captureBootstrap(
        response = response,
        clockSample = currentBootstrap?.clockSample,
        reason = reason,
    )

    fun captureAccountSwitch(
        profile: User,
        bootstrap: SyncResponse,
        clockSample: ServerClockSample,
    ): AccountSwitchCandidate {
        advanceGeneration(AccountWorkspaceReason.AccountSwitchDetected)
        val candidate = AccountSwitchCandidate(profile, bootstrap, clockSample)
        currentAccountSwitch = candidate
        eventSink.emit(AccountWorkspaceTransition.AccountSwitchCaptured(candidate, generation))
        return candidate
    }

    fun clearAccountSwitch(reason: AccountWorkspaceReason) {
        currentAccountSwitch = null
        eventSink.emit(AccountWorkspaceTransition.AccountSwitchCleared(reason))
    }

    fun clearBootstrap(
        reason: AccountWorkspaceReason,
    ): AccountWorkspaceTransition.BootstrapCleared {
        currentBootstrap = null
        return AccountWorkspaceTransition.BootstrapCleared(
            generation = generation,
            reason = reason,
        ).also(eventSink::emit)
    }
}
