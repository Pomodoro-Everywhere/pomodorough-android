package me.egigoka.pomodorough.ui

import java.io.File
import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43-A12: fold-tracker exceptions must not all be swallowed.
 * Cancellation propagates, unsupported capability stays flat and
 * silent, unexpected tracker failure stays flat with one bounded
 * report carrying no window payload.
 */
class R43A12FoldTrackerFailureTest {
    @Test
    fun cancellationPropagatesWithoutReport() {
        val cancellation = CancellationException("tracker gone")
        val reported = mutableListOf<FoldTrackerFailure>()
        val failure = runCatching {
            handleFoldTrackerFailure(cancellation, reported::add)
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(reported.isEmpty())
        assertSame(cancellation, runCatching { classifyFoldTrackerFailure(cancellation) }.exceptionOrNull())
    }

    @Test
    fun unsupportedCapabilityStaysSilentWithoutReport() {
        val payload = "R43A12-UNSUPPORTED-SENTINEL-{\"left\":999}"
        val error = UnsupportedOperationException(payload)
        assertEquals(FoldTrackerFailure.Unsupported, classifyFoldTrackerFailure(error))
        val recorded = mutableListOf<FoldTrackerFailure>()
        val reported = captureReports {
            val category = handleFoldTrackerFailure(error, recorded::add)
            assertEquals(FoldTrackerFailure.Unsupported, category)
        }
        assertEquals(listOf(FoldTrackerFailure.Unsupported), recorded)
        assertTrue(reported.isEmpty())
        val direct = captureReports { FoldTrackerReporter.report(FoldTrackerFailure.Unsupported) }
        assertTrue(direct.isEmpty())
    }

    @Test
    fun unexpectedFailureReportsBoundedCategoryWithoutPayload() {
        val payload = "R43A12-UNEXPECTED-SENTINEL-{\"left\":999}"
        val error = IllegalStateException(payload)
        assertEquals(FoldTrackerFailure.Unexpected, classifyFoldTrackerFailure(error))
        val reported = captureReports { FoldTrackerReporter.report(FoldTrackerFailure.Unexpected) }
        assertEquals(1, reported.size)
        assertBoundedReport(reported.single(), payload)
    }

    @Test
    fun unexpectedCategoryReportsOnce() {
        val reported = captureReports {
            FoldTrackerReporter.report(FoldTrackerFailure.Unexpected)
            FoldTrackerReporter.report(FoldTrackerFailure.Unexpected)
        }
        assertEquals(1, reported.size)
    }

    @Test
    fun scaffoldRethrowsCancellation() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val foldWindow = foldWindowSection(scaffold)
        assertTrue(
            "fold tracker must rethrow coroutine cancellation",
            foldWindow.contains("CancellationException") && foldWindow.contains("throw error"),
        )
        assertTrue(
            "fold tracker must not swallow every exception with catch (_: Exception)",
            !foldWindow.contains("catch (_: Exception)"),
        )
    }

    @Test
    fun scaffoldDistinguishesUnsupportedCapability() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val foldWindow = foldWindowSection(scaffold)
        assertTrue(
            "fold tracker must classify unsupported capability separately",
            foldWindow.contains("UnsupportedOperationException"),
        )
        assertTrue(
            "unsupported tracker capability stays on the flat fallback",
            foldWindow.contains("foldState = FoldWindowState()"),
        )
    }

    @Test
    fun scaffoldReportsBoundedUnexpectedFailure() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        val foldWindow = foldWindowSection(scaffold)
        assertTrue(
            "unexpected tracker failure must report the bounded category",
            foldWindow.contains("FoldTrackerFailure.Unexpected") ||
                foldWindow.contains("handleFoldTrackerFailure"),
        )
        assertTrue(
            "unexpected tracker report must go through the bounded reporter",
            foldWindow.contains("CrashReporter") &&
                (foldWindow.contains("FoldTrackerReporter") || foldWindow.contains("handleFoldTrackerFailure")),
        )
        val failures = productionText("me/egigoka/pomodorough/ui/FoldTrackerFailures.kt")
        assertTrue(failures.contains("FoldTrackerReporter"))
        assertTrue(failures.contains("CrashReporter"))
        assertTrue(failures.contains("Unexpected"))
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        FoldTrackerReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            FoldTrackerReporter.resetForTest()
        }
        return reported
    }

    private fun assertBoundedReport(error: Throwable, payload: String) {
        assertTrue(error is FoldTrackerReport)
        assertEquals(FoldTrackerFailure.Unexpected.reportMessage, error.message)
        assertTrue(error.cause == null)
        assertTrue(error.message?.contains(payload) != true)
        assertTrue(!error.stackTraceToString().contains(payload))
    }

    private fun foldWindowSection(scaffold: String): String {
        val start = scaffold.indexOf("private fun rememberFoldWindowState()")
        assertTrue(start >= 0)
        return scaffold.substring(start, (start + 2_500).coerceAtMost(scaffold.length))
    }

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
