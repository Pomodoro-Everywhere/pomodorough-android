package me.egigoka.pomodorough.data

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
 * R49-A01: failed phase persistence must retain committed memory.
 * Full behavioral coverage lives in androidTest with real Room;
 * these JVM pins fail if commit-then-install ordering is dropped.
 */
class R49A01PhaseSelectionTest {
    @Test
    fun infrastructureReportsBoundedCategoryWithoutPayload() {
        val payload = "R49A01-SENTINEL-{\"selectedPhase\":\"secret\"}"
        val reported = captureReports {
            PhaseSelectionReporter.report(PhaseSelectionFailure.Infrastructure)
        }
        assertEquals(1, reported.size)
        val error = reported.single()
        assertTrue(error is PhaseSelectionReport)
        assertEquals(PhaseSelectionFailure.Infrastructure.reportMessage, error.message)
        assertTrue(error.cause == null)
        assertTrue(error.message?.contains(payload) != true)
        assertTrue(!error.stackTraceToString().contains(payload))
    }

    @Test
    fun duplicateInfrastructureReportsOnce() {
        val reported = captureReports {
            PhaseSelectionReporter.report(PhaseSelectionFailure.Infrastructure)
            PhaseSelectionReporter.report(PhaseSelectionFailure.Infrastructure)
        }
        assertEquals(1, reported.size)
    }

    @Test
    fun consentDisabledSkipsReport() {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = false
        try {
            PhaseSelectionReporter.report(PhaseSelectionFailure.Infrastructure)
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            PhaseSelectionReporter.resetForTest()
        }
        assertTrue(reported.isEmpty())
    }

    @Test
    fun cancellationPropagatesWithoutReport() {
        val cancellation = CancellationException("phase gone")
        val recorded = mutableListOf<PhaseSelectionFailure>()
        val failure = runCatching {
            handlePhaseSelectionFailure(cancellation, recorded::add)
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(recorded.isEmpty())
        assertSame(
            cancellation,
            runCatching { classifyPhaseSelectionFailure(cancellation) }.exceptionOrNull(),
        )
    }

    @Test
    fun repositoryConstructsProposedStateBeforeCommit() {
        val text = productionText()
        val window = selectPhaseWindow(text)
        val proposed = window.indexOfFirst { it.contains("proposedSettings") }
        val proposedLocal = window.indexOfFirst { it.contains("proposedLocal") }
        val save = window.indexOfFirst { it.contains("timerStore.saveState(proposedLocal)") }
        assertTrue(proposed >= 0 && proposedLocal > proposed && save > proposedLocal)
    }

    @Test
    fun repositoryInstallsCommittedStateOnlyAfterCommit() {
        val text = productionText()
        val window = selectPhaseWindow(text)
        val save = window.indexOfFirst { it.contains("timerStore.saveState(proposedLocal)") }
        val installSettings = window.indexOfFirst { it.contains("settings = proposedSettings") }
        val installLocal = window.indexOfFirst { it.contains("local = proposedLocal") }
        val generation = window.indexOfFirst { it.contains("selectedPhaseGeneration +=") }
        assertTrue(save >= 0 && installSettings > save && installLocal > save && generation > save)
    }

    @Test
    fun repositoryReportsBoundedFailureAndRetainsCommittedState() {
        val text = productionText()
        val window = selectPhaseWindow(text)
        assertTrue(window.any { it.contains("handlePhaseSelectionFailure") })
        assertTrue(window.any { it.contains("PhaseSelectionFailure") })
        assertTrue(window.any { it.contains("sync_stopped_after_a_local_failure") })
        assertTrue(window.any { it.contains("publish()") })
        val failurePublish = window.indexOfFirst {
            it.contains("sync_stopped_after_a_local_failure")
        }
        assertTrue(failurePublish >= 0)
    }

    @Test
    fun repositoryRethrowsCancellationBeforeNotice() {
        val window = selectPhaseWindow(productionText())
        val save = window.indexOfFirst { it.contains("timerStore.saveState(proposedLocal)") }
        val catchCancel = window.indexOfFirst {
            it.contains("catch (error: CancellationException)")
        }
        val catchGeneric = window.indexOfFirst { it.contains("catch (error: Exception)") }
        val rethrow = window.indexOfFirst { it.contains("throw error") }
        assertTrue(save >= 0 && catchCancel > save)
        assertTrue(catchGeneric > catchCancel && rethrow > catchCancel && rethrow < catchGeneric)
    }

    @Test
    fun repositoryKeepsCorePhaseAuthoritative() {
        val text = productionText()
        val window = selectPhaseWindow(text)
        assertTrue(window.any { it.contains("phase !in TimerPhase.all") })
        assertTrue(text.contains("TimerPhase.all mirrors Shared Core"))
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        PhaseSelectionReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            PhaseSelectionReporter.resetForTest()
        }
        return reported
    }

    private fun selectPhaseWindow(text: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.contains("override suspend fun selectPhase(") }
        assertTrue("selectPhase missing", start >= 0)
        val end = minOf(lines.size, start + 60)
        val nextFun = (start + 1 until end).firstOrNull { index ->
            lines[index].trimStart().startsWith("override suspend fun ") ||
                lines[index].trimStart().startsWith("private suspend fun ") ||
                lines[index].trimStart().startsWith("private fun ")
        } ?: end
        return lines.subList(start, nextFun)
    }

    private fun productionText(): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(
            requireNotNull(root),
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
    }
}
