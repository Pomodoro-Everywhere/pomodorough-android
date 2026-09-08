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
        val previousHook = CrashReporter.onReporterFailure
        CrashReporter.delegate = { throw RuntimeException("sentry down") }
        CrashReporter.onReporterFailure = null
        try {
            CrashReporter.report(RuntimeException("boom"))
        } finally {
            CrashReporter.delegate = previous
            CrashReporter.onReporterFailure = previousHook
        }
    }

    @Test
    fun delegateFailureReachesFallbackHook() {
        val previous = CrashReporter.delegate
        val previousHook = CrashReporter.onReporterFailure
        val failures = mutableListOf<Pair<Throwable, Throwable>>()
        CrashReporter.delegate = { throw RuntimeException("sentry down") }
        CrashReporter.onReporterFailure = { original, failure ->
            failures += original to failure
        }
        try {
            val original = RuntimeException("boom")
            CrashReporter.report(original)
            assertEquals(1, failures.size)
            assertTrue(failures.single().first === original)
            assertEquals("sentry down", failures.single().second.message)
        } finally {
            CrashReporter.delegate = previous
            CrashReporter.onReporterFailure = previousHook
        }
    }

    @Test
    fun fallbackHookFailureDoesNotThrow() {
        val previous = CrashReporter.delegate
        val previousHook = CrashReporter.onReporterFailure
        CrashReporter.delegate = { throw RuntimeException("sentry down") }
        CrashReporter.onReporterFailure = { _, _ -> throw RuntimeException("hook down") }
        try {
            CrashReporter.report(RuntimeException("boom"))
        } finally {
            CrashReporter.delegate = previous
            CrashReporter.onReporterFailure = previousHook
        }
    }
}
