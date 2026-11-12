package me.egigoka.pomodorough.data

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.BootstrapConflictException
import me.egigoka.pomodorough.data.api.BootstrapConflictKind
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.CredentialPersistenceException
import me.egigoka.pomodorough.data.time.FreshServerTimeRequiredException
import me.egigoka.pomodorough.data.time.TrustedClockRejectedException
import me.egigoka.pomodorough.data.time.TrustedClockRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AND-17: account failures classify by exception type into one localized
 * sentence and a retry. Every message below is the same text, so a pass
 * proves the sentence never depends on it.
 */
class AccountFailureNoticesTest {
    @Test
    fun cancelledGooglePromptShowsNothing() {
        assertNull(signInFailureNotice(GetCredentialCancellationException(Raw)))
    }

    @Test
    fun signInFailuresMapToOneSentenceWithSignInRetry() {
        signInCases.forEach { (error, sentence) ->
            assertEquals(
                error::class.simpleName,
                AccountFailureNotice(sentence, NoticeAction.RetrySignIn),
                signInFailureNotice(error),
            )
        }
    }

    @Test
    fun restoreFailuresMapToOneSentenceWithTheRetryThatExists() {
        restoreCases.forEach { (error, expected) ->
            assertEquals(error::class.simpleName, expected, accountRestoreFailureNotice(error))
        }
    }

    // UX round 2, B3: a refused time sample names its cause. Only the
    // device-clock reasons send the user to the device date and time.
    @Test
    fun everyTrustedClockRejectionReasonHasItsSentence() {
        val sentences = TrustedClockRejectionReason.entries.associateWith(::trustedClockRejectionSentence)
        assertEquals(
            mapOf(
                TrustedClockRejectionReason.ClockSampleUncertaintyTooHigh to R.string.device_clock_changed_during_request,
                TrustedClockRejectionReason.LocalReceiptTimingOutOfRange to R.string.device_clock_unusable,
                TrustedClockRejectionReason.BootstrapClockSampleOutOfRange to R.string.device_clock_unusable,
                TrustedClockRejectionReason.ServerClockOffsetOutOfRange to R.string.device_clock_unusable,
                TrustedClockRejectionReason.InvalidServerTimestamp to R.string.server_time_unusable,
                TrustedClockRejectionReason.ServerTimestampOutOfRange to R.string.server_time_unusable,
                TrustedClockRejectionReason.ServerHlcDisagreesWithTimestamp to R.string.server_time_unusable,
            ),
            sentences,
        )
    }

    @Test
    fun otherFailuresShowTheSentenceOfThePlaceTheyHappenedIn() {
        val fallback = R.string.sign_out_failed_local_timer_data_was_kept
        assertEquals(fallback, failureSentence(IllegalStateException(Raw), fallback))
        assertEquals(fallback, failureSentence(SyncProtocolException(Raw), fallback))
        assertEquals(R.string.fresh_server_time_required, failureSentence(FreshServerTimeRequiredException(), fallback))
        assertEquals(
            R.string.device_clock_unusable,
            failureSentence(rejected(TrustedClockRejectionReason.LocalReceiptTimingOutOfRange), fallback),
        )
    }

    private companion object {
        const val Raw = "same raw text for every failure"

        val signInCases = listOf(
            GetCredentialInterruptedException(Raw) to R.string.sign_in_failed_interrupted,
            NoCredentialException(Raw) to R.string.sign_in_failed_no_google_account,
            GetCredentialProviderConfigurationException(Raw) to R.string.sign_in_failed_google_unavailable,
            GetCredentialUnknownException(Raw) to R.string.sign_in_failed_google,
            ApiException(503, Raw) to R.string.sign_in_failed_server,
            BootstrapConflictException(BootstrapConflictKind.Unknown, Raw) to R.string.sign_in_failed_server,
            UnknownHostException(Raw) to R.string.sign_in_failed_offline,
            IOException(Raw) to R.string.sign_in_failed_offline,
            ProfileProtocolException(Raw) to R.string.sign_in_failed_unreadable_reply,
            SyncProtocolException(Raw) to R.string.sign_in_failed,
            rejected(TrustedClockRejectionReason.ClockSampleUncertaintyTooHigh) to
                R.string.device_clock_changed_during_request,
            rejected(TrustedClockRejectionReason.ServerHlcDisagreesWithTimestamp) to R.string.server_time_unusable,
            AuthenticationRequired(Raw) to R.string.sign_in_failed,
            SerializationException(Raw) to R.string.sign_in_failed_unreadable_reply,
            CredentialPersistenceException(Raw, IOException(Raw)) to R.string.sign_in_failed_not_saved,
            IllegalStateException(Raw) to R.string.sign_in_failed,
        )

        val restoreCases = listOf(
            ProfileProtocolException(Raw) to AccountFailureNotice(
                R.string.account_restore_failed_profile_rejected,
                NoticeAction.RetrySignIn,
            ),
            ApiException(503, Raw) to restore(R.string.account_restore_failed_server),
            UnknownHostException(Raw) to restore(R.string.account_restore_failed_offline),
            SyncProtocolException(Raw) to restore(R.string.account_restore_failed),
            rejected(TrustedClockRejectionReason.LocalReceiptTimingOutOfRange) to
                restore(R.string.device_clock_unusable),
            SerializationException(Raw) to restore(R.string.account_restore_failed_unreadable_reply),
            IllegalStateException(Raw) to restore(R.string.account_restore_failed),
        )

        fun rejected(reason: TrustedClockRejectionReason) = TrustedClockRejectedException(reason)

        fun restore(sentence: Int) = AccountFailureNotice(sentence, NoticeAction.RetryAccountRestore)
    }
}
