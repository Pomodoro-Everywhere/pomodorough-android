package me.egigoka.pomodorough.data

import androidx.annotation.StringRes
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import java.io.IOException
import kotlinx.serialization.SerializationException
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.auth.CredentialPersistenceException
import me.egigoka.pomodorough.data.time.FreshServerTimeRequiredException
import me.egigoka.pomodorough.data.time.TrustedClockRejectedException
import me.egigoka.pomodorough.data.time.TrustedClockRejectionReason

/** The retry a notice offers. The notice card renders it as a button. */
enum class NoticeAction { RetrySignIn, RetryAccountRestore }

/**
 * One localized sentence for an account failure and the retry it offers.
 * The user never sees the exception message: failures classify by type, the
 * same way the R50-L02 report taxonomy does, and never by message text.
 */
internal data class AccountFailureNotice(
    @param:StringRes val sentence: Int,
    val action: NoticeAction,
)

/** Null means the user cancelled the Google prompt: nothing to explain. */
internal fun signInFailureNotice(error: Exception): AccountFailureNotice? {
    if (error is GetCredentialCancellationException) return null
    return AccountFailureNotice(signInFailureSentence(error), NoticeAction.RetrySignIn)
}

@StringRes
private fun signInFailureSentence(error: Exception): Int = when (error) {
    is GetCredentialInterruptedException -> R.string.sign_in_failed_interrupted
    is NoCredentialException -> R.string.sign_in_failed_no_google_account
    is GetCredentialProviderConfigurationException -> R.string.sign_in_failed_google_unavailable
    is GetCredentialException -> R.string.sign_in_failed_google
    // ApiException is an IOException: the server answered, so it goes first.
    is ApiException -> R.string.sign_in_failed_server
    is IOException -> R.string.sign_in_failed_offline
    is ProfileProtocolException,
    is SerializationException,
    -> R.string.sign_in_failed_unreadable_reply
    is CredentialPersistenceException -> R.string.sign_in_failed_not_saved
    is TrustedClockRejectedException -> trustedClockRejectionSentence(error.reason)
    else -> R.string.sign_in_failed
}

/**
 * A stored session that could not be confirmed keeps its credentials, so the
 * retry is another restore. A rejected profile retires them: the retry is a
 * new sign-in.
 */
internal fun accountRestoreFailureNotice(error: Exception): AccountFailureNotice = when (error) {
    is ProfileProtocolException -> AccountFailureNotice(
        R.string.account_restore_failed_profile_rejected,
        NoticeAction.RetrySignIn,
    )
    is ApiException -> restoreRetry(R.string.account_restore_failed_server)
    is IOException -> restoreRetry(R.string.account_restore_failed_offline)
    is SerializationException -> restoreRetry(R.string.account_restore_failed_unreadable_reply)
    is TrustedClockRejectedException -> restoreRetry(trustedClockRejectionSentence(error.reason))
    else -> restoreRetry(R.string.account_restore_failed)
}

private fun restoreRetry(@StringRes message: Int) =
    AccountFailureNotice(message, NoticeAction.RetryAccountRestore)

/** The stored session ended, so only a new sign-in can bring the account back. */
internal val SessionExpiredNotice = AccountFailureNotice(R.string.session_expired_sign_in_again, NoticeAction.RetrySignIn)

/**
 * UX round 2, B3: the sentence for a failure outside sign-in and restore.
 * A refused time sample names its own cause; every other failure shows the
 * generic sentence of the place it happened in. The exception message is
 * never the sentence.
 */
@StringRes
internal fun failureSentence(error: Throwable, @StringRes fallback: Int): Int = when (error) {
    is TrustedClockRejectedException -> trustedClockRejectionSentence(error.reason)
    is FreshServerTimeRequiredException -> R.string.fresh_server_time_required
    else -> fallback
}

// A device-clock rejection sends the user to the device date and time. A
// server-side one does not: the device settings cannot fix it.
@StringRes
internal fun trustedClockRejectionSentence(reason: TrustedClockRejectionReason): Int = when (reason) {
    TrustedClockRejectionReason.ClockSampleUncertaintyTooHigh -> R.string.device_clock_changed_during_request
    TrustedClockRejectionReason.LocalReceiptTimingOutOfRange,
    TrustedClockRejectionReason.BootstrapClockSampleOutOfRange,
    TrustedClockRejectionReason.ServerClockOffsetOutOfRange,
    -> R.string.device_clock_unusable
    TrustedClockRejectionReason.InvalidServerTimestamp,
    TrustedClockRejectionReason.ServerTimestampOutOfRange,
    TrustedClockRejectionReason.ServerHlcDisagreesWithTimestamp,
    -> R.string.server_time_unusable
}
