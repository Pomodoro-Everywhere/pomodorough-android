package me.egigoka.pomodorough.crash

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {
    @Test
    fun cancellationIsNeverReported() {
        assertFalse(CrashReporter.shouldReport(CancellationException("gone")))
    }

    @Test
    fun unexpectedFailuresAreReportable() {
        assertTrue(CrashReporter.shouldReport(RuntimeException("boom")))
        assertTrue(CrashReporter.shouldReport(IOException("offline")))
        assertTrue(CrashReporter.shouldReport(IllegalStateException("bad state")))
    }

    @Test
    fun reportForwardsUnexpectedFailuresToDelegate() {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            val failure = RuntimeException("boom")
            CrashReporter.report(failure)
            assertEquals(listOf(failure), reported)
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun reportSkipsExpectedSilents() {
        val reported = mutableListOf<Throwable>()
        val previous = CrashReporter.delegate
        CrashReporter.delegate = reported::add
        try {
            CrashReporter.report(CancellationException("gone"))
            assertTrue(reported.isEmpty())
        } finally {
            CrashReporter.delegate = previous
        }
    }

    @Test
    fun delegateFailureDoesNotThrow() {
        val previous = CrashReporter.delegate
        CrashReporter.delegate = { throw RuntimeException("sentry down") }
        try {
            CrashReporter.report(RuntimeException("boom"))
        } finally {
            CrashReporter.delegate = previous
        }
    }
}
