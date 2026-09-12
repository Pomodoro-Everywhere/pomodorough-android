package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionScrubRetryTest {
    @Test
    fun scrubRetryFailureIsReported() = runTest {
        val reported = mutableListOf<Throwable>()
        val failure = RuntimeException("scrub failed")
        retryCommittedAccountScrub({ throw failure }, reported::add)
        assertEquals(listOf(failure), reported)
    }

    @Test
    fun successfulScrubRetryStaysSilent() = runTest {
        val reported = mutableListOf<Throwable>()
        var scrubs = 0
        retryCommittedAccountScrub({ scrubs += 1 }, reported::add)
        assertEquals(1, scrubs)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun scrubRetryCancellationPropagatesWithoutReport() = runTest {
        // A52: committed-scrub retry must not swallow coroutine cancellation.
        val reported = mutableListOf<Throwable>()
        val cancellation = CancellationException("gone")
        val failure = runCatching {
            retryCommittedAccountScrub({ throw cancellation }, reported::add)
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(reported.isEmpty())
    }
}
