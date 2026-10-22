package me.egigoka.pomodorough.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.auth.AuthenticationRequired

/**
 * R51-A03 deletion-preparation persistence classification.
 * First marker write retains committed Room and memory, publishes a
 * recoverable notice, and reports one bounded storage event.
 * Cancellation always propagates. Storage never maps to Core output.
 */
internal enum class AccountDeletionFailure(val reportMessage: String) {
    PreparationStorage("Account deletion preparation failed: committed state retained"),
    RemoteDelete("Account deletion failed: remote outcome unresolved"),
    DeletionMarker("Account deletion marker failed: remote outcome unresolved"),
    CredentialClear("Account deletion credential clear failed: remote outcome unresolved"),
    VaultClear("Account deletion vault clear failed: remote outcome unresolved"),
    RoomScrub("Account deletion scrub failed: remote outcome unresolved"),
}

/**
 * R51-A05 deletion-execution stage. RemoteDelete covers quarantine plus
 * the authenticated remote call. Later stages each own one bounded
 * category. Vault versus credential splits on vault frames only.
 */
internal enum class AccountDeletionExecutionStage {
    RemoteDelete,
    Marker,
    CredentialClear,
    RoomScrub,
}

internal class AccountDeletionReport(category: AccountDeletionFailure) : Exception(category.reportMessage)

internal object AccountDeletionReporter {
    private val reported = mutableSetOf<AccountDeletionFailure>()

    fun report(category: AccountDeletionFailure) {
        if (!claim(category)) return
        CrashReporter.report(AccountDeletionReport(category))
    }

    private fun claim(category: AccountDeletionFailure): Boolean = synchronized(reported) {
        reported.add(category)
    }

    internal fun resetForTest() = synchronized(reported) {
        reported.clear()
    }
}

internal fun classifyAccountDeletionFailure(error: Throwable): AccountDeletionFailure {
    if (error is CancellationException) throw error
    return AccountDeletionFailure.PreparationStorage
}

internal fun handleAccountDeletionFailure(
    error: Exception,
    report: (AccountDeletionFailure) -> Unit = AccountDeletionReporter::report,
): AccountDeletionFailure {
    val category = classifyAccountDeletionFailure(error)
    report(category)
    return category
}

/**
 * R51-A05: expected offline transport during remote delete stays quiet
 * (IOException covers ApiException; auth expiry is equally expected).
 * Every other stage is local infrastructure and always reports with a
 * fixed stage-and-cause message, no raw cause, no payload.
 * Crash-reporter budget: one report per category per process, bounded
 * by the category count above. Cancellation always propagates.
 */
internal fun isQuietAccountDeletionTransport(error: Throwable): Boolean =
    error is IOException || error is AuthenticationRequired

internal fun classifyAccountDeletionExecutionFailure(
    stage: AccountDeletionExecutionStage,
    error: Throwable,
): AccountDeletionFailure? {
    if (error is CancellationException) throw error
    if (stage == AccountDeletionExecutionStage.RemoteDelete && isQuietAccountDeletionTransport(error)) {
        return null
    }
    return when (stage) {
        AccountDeletionExecutionStage.RemoteDelete -> AccountDeletionFailure.RemoteDelete
        AccountDeletionExecutionStage.Marker -> AccountDeletionFailure.DeletionMarker
        AccountDeletionExecutionStage.CredentialClear ->
            if (hasAccountDeletionVaultFrame(error)) AccountDeletionFailure.VaultClear
            else AccountDeletionFailure.CredentialClear
        AccountDeletionExecutionStage.RoomScrub -> AccountDeletionFailure.RoomScrub
    }
}

internal fun handleAccountDeletionExecutionFailure(
    stage: AccountDeletionExecutionStage,
    error: Throwable,
    report: (AccountDeletionFailure) -> Unit = AccountDeletionReporter::report,
): AccountDeletionFailure? {
    val category = classifyAccountDeletionExecutionFailure(stage, error) ?: return null
    report(category)
    return category
}

private fun hasAccountDeletionVaultFrame(error: Throwable): Boolean {
    val frames = error.stackTrace + (error.cause?.stackTrace ?: emptyArray())
    if (error.javaClass.name.contains("KeyStore")) return true
    return frames.any {
        it.className.contains("TokenVault") ||
            it.className.contains("TokenStore") ||
            it.className.contains("AuthRepository")
    }
}
