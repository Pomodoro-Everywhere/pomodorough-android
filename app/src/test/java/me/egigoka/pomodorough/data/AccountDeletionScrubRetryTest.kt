package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionScrubRetryTest {
    @Test
    fun scrubRetryFailureReportsBoundedRoomScrub() = runTest {
        val reported = mutableListOf<AccountDeletionFailure>()
        val failure = RuntimeException("scrub failed R51A05-SENTINEL")
        retryCommittedAccountScrub({ throw failure }, reported::add)
        assertEquals(listOf(AccountDeletionFailure.RoomScrub), reported)
    }

    @Test
    fun scrubRetryReportCarriesFixedMessageWithoutRaw() = runTest {
        val reported = mutableListOf<AccountDeletionFailure>()
        retryCommittedAccountScrub({ throw RuntimeException("raw payload") }, reported::add)
        val single = reported.single()
        assertEquals(AccountDeletionFailure.RoomScrub.reportMessage, single.reportMessage)
        val envelope = AccountDeletionReport(single)
        assertEquals(single.reportMessage, envelope.message)
        assertTrue(envelope.cause == null)
    }

    @Test
    fun successfulScrubRetryStaysSilent() = runTest {
        val reported = mutableListOf<AccountDeletionFailure>()
        var scrubs = 0
        retryCommittedAccountScrub({ scrubs += 1 }, reported::add)
        assertEquals(1, scrubs)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun scrubRetryCancellationPropagatesWithoutReport() = runTest {
        // A52: committed-scrub retry must not swallow coroutine cancellation.
        val reported = mutableListOf<AccountDeletionFailure>()
        val cancellation = CancellationException("gone")
        val failure = runCatching {
            retryCommittedAccountScrub({ throw cancellation }, reported::add)
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(reported.isEmpty())
    }
}
