package me.egigoka.pomodorough.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import me.egigoka.pomodorough.core.SharedCoreException
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.BootstrapConflictException
import me.egigoka.pomodorough.data.auth.AuthenticationRequired

/**
 * R50-L02 account bootstrap classification.
 * Sign-in, restore, and corrupted-resolution recovery share one
 * bounded stage+cause taxonomy. Transport, auth expiry, conflict,
 * malformed, cancellation, and dismissal stay quiet. Credential,
 * Core, and commit infrastructure each get one fixed report.
 */
internal enum class AccountBootstrapStage(val key: String) {
    SignIn("sign_in"),
    Restore("restore"),
    ResolutionRecovery("resolution_recovery"),
}

internal enum class AccountBootstrapCause(val key: String) {
    CredentialWrite("credential_write"),
    CoreLoad("core_load"),
    CoreAbi("core_abi"),
    CoreInvalidOutput("core_invalid_output"),
    BootstrapCommit("bootstrap_commit"),
}

internal data class AccountBootstrapFailure(
    val stage: AccountBootstrapStage,
    val cause: AccountBootstrapCause,
) {
    val reportMessage: String get() = "Account bootstrap ${stage.key} failed: ${cause.key}"
}

internal class AccountBootstrapReport(failure: AccountBootstrapFailure) :
    Exception(failure.reportMessage)

internal object AccountBootstrapReporter {
    private val reported = mutableSetOf<AccountBootstrapFailure>()

    fun report(failure: AccountBootstrapFailure) {
        if (!claim(failure)) return
        CrashReporter.report(AccountBootstrapReport(failure))
    }

    private fun claim(failure: AccountBootstrapFailure): Boolean = synchronized(reported) {
        reported.add(failure)
    }

    internal fun resetForTest() = synchronized(reported) {
        reported.clear()
    }
}

internal fun classifyAccountBootstrapFailure(
    stage: AccountBootstrapStage,
    error: Throwable,
): AccountBootstrapFailure? {
    if (error is CancellationException) throw error
    if (isQuietAccountBootstrap(error)) return null
    if (error is CoreProjectionException.InvalidOutput) {
        return AccountBootstrapFailure(stage, AccountBootstrapCause.CoreInvalidOutput)
    }
    if (error is CoreProjectionException) return null
    if (error is SharedCoreException.Load) {
        return AccountBootstrapFailure(stage, AccountBootstrapCause.CoreLoad)
    }
    if (error is SharedCoreException.Abi) {
        return AccountBootstrapFailure(stage, AccountBootstrapCause.CoreAbi)
    }
    if (error is SharedCoreException) {
        return AccountBootstrapFailure(stage, AccountBootstrapCause.CoreInvalidOutput)
    }
    if (isCredentialWrite(error)) {
        return AccountBootstrapFailure(stage, AccountBootstrapCause.CredentialWrite)
    }
    return AccountBootstrapFailure(stage, AccountBootstrapCause.BootstrapCommit)
}

internal fun handleAccountBootstrapFailure(
    stage: AccountBootstrapStage,
    error: Exception,
    report: (AccountBootstrapFailure) -> Unit = AccountBootstrapReporter::report,
): AccountBootstrapFailure? {
    val category = classifyAccountBootstrapFailure(stage, error) ?: return null
    report(category)
    return category
}

private fun isQuietAccountBootstrap(error: Throwable): Boolean {
    if (error is AuthenticationRequired) return true
    if (error is BootstrapConflictException) return true
    if (error is ApiException) return true
    if (error is IOException) return true
    if (error is ProfileProtocolException) return true
    if (error is SyncProtocolException) return true
    if (error is SerializationException) return true
    if (isCredentialDismissal(error)) return true
    return false
}

private fun isCredentialDismissal(error: Throwable): Boolean {
    val name = error.javaClass.name
    if (name.contains("Credential") || name.contains("GetCredential")) return true
    return error.stackTrace.any {
        it.className.contains("CredentialManager") ||
            it.className.contains("GoogleCredentialProvider")
    }
}

private fun isCredentialWrite(error: Throwable): Boolean {
    if (error is IllegalStateException || error is SecurityException) {
        if (hasCredentialFrame(error)) return true
    }
    if (error.javaClass.name.contains("KeyStore")) return true
    return hasCredentialFrame(error)
}

private fun hasCredentialFrame(error: Throwable): Boolean {
    val frames = error.stackTrace + (error.cause?.stackTrace ?: emptyArray())
    return frames.any {
        it.className.contains("TokenVault") ||
            it.className.contains("TokenStore") ||
            it.className.contains("AuthRepository")
    }
}
