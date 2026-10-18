package me.egigoka.pomodorough.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R50-A04: ordinary Room write failures must not escape to the
 * ViewModel uncaught handler. Full Room + ViewModel behavior lives in
 * androidTest with real persistence; these JVM pins fail if any local
 * mutation commit loses its recoverable boundary.
 */
class R50A04MutationPersistenceTest {
    @Test
    fun infrastructureReportsBoundedCategoryWithoutPayload() {
        val payload = "R50A04-SENTINEL-{\"op\":\"secret\"}"
        val reported = captureReports {
            MutationPersistenceReporter.report(MutationPersistenceFailure.Infrastructure)
        }
        assertEquals(1, reported.size)
        val error = reported.single()
        assertTrue(error is MutationPersistenceReport)
        assertEquals(MutationPersistenceFailure.Infrastructure.reportMessage, error.message)
        assertTrue(error.cause == null)
        assertTrue(error.message?.contains(payload) != true)
        assertTrue(!error.stackTraceToString().contains(payload))
    }

    @Test
    fun duplicateInfrastructureReportsOnce() {
        val reported = captureReports {
            MutationPersistenceReporter.report(MutationPersistenceFailure.Infrastructure)
            MutationPersistenceReporter.report(MutationPersistenceFailure.Infrastructure)
        }
        assertEquals(1, reported.size)
    }

    @Test
    fun cancellationPropagatesWithoutReport() {
        val cancellation = CancellationException("mutation gone")
        val recorded = mutableListOf<MutationPersistenceFailure>()
        val failure = runCatching {
            handleMutationPersistenceFailure(cancellation, recorded::add)
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(recorded.isEmpty())
        assertSame(
            cancellation,
            runCatching { classifyMutationPersistenceFailure(cancellation) }.exceptionOrNull(),
        )
    }

    @Test
    fun ordinaryIOExceptionMapsToBoundedInfrastructure() {
        val canary = "R50A04-SENTINEL-io-canary-xyz"
        val error = IOException("Room write failed: $canary")
        assertEquals(
            MutationPersistenceFailure.Infrastructure,
            classifyMutationPersistenceFailure(error),
        )
        val reported = captureReports {
            handleMutationPersistenceFailure(error)
        }
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is MutationPersistenceReport)
        assertEquals(MutationPersistenceFailure.Infrastructure.reportMessage, single.message)
        assertTrue(single.cause == null)
        assertTrue(!single.message.orEmpty().contains(canary))
        assertTrue(!single.stackTraceToString().contains(canary))
    }

    @Test
    fun storageClassificationNeverMentionsCoreOutput() {
        val text = failuresText()
        assertTrue(text.contains("CancellationException"))
        assertTrue(text.contains("Infrastructure"))
        assertTrue(!text.contains("projectionFailureMessage"))
        assertTrue(!text.contains("shared_core"))
        assertTrue(!text.contains("SharedCoreException"))
    }

    @Test
    fun boundaryHelperKeepsCorePathSeparateFromStorage() {
        val window = functionWindow("fun publishMutationCommitFailure(")
        assertTrue(window.contains("SharedCoreException"))
        assertTrue(window.contains("projectionFailureMessage"))
        assertTrue(window.contains("handleMutationPersistenceFailure"))
        assertTrue(window.contains("sync_stopped_after_a_local_failure"))
        assertTrue(window.contains("CrashReporter.report"))
        assertTrue(window.contains("publish()"))
    }

    @Test
    fun durationCommitRetainsCommittedStateOnStorageFailure() {
        val window = functionWindow("override suspend fun changeDuration(")
        assertTrue(window.contains("RepositoryDurationTransition"))
        assertTrue(window.contains("installDurationMutation"))
        assertTrue(window.contains("publishMutationCommitFailure"))
        assertTrue(window.contains("catch (error: CancellationException)"))
        assertTrue(window.contains("throw error"))
        assertTrue(window.contains("return@withLock"))
        assertTrue(commitBeforeInstall(window, "commit", "installDurationMutation"))
    }

    @Test
    fun autoStartCommitRetainsCommittedStateOnStorageFailure() {
        val window = functionWindow("override suspend fun setAutoStart(")
        assertTrue(window.contains("RepositoryAutoStartTransition"))
        assertTrue(window.contains("publishMutationCommitFailure"))
        assertTrue(window.contains("return@withLock"))
        assertTrue(commitBeforeInstall(window, "commit", "installCoreProjection"))
    }

    @Test
    fun selectionOnlyCommitReturnsRefusalOnStorageFailure() {
        val window = functionWindow("fun commitSelectedTask(")
        assertTrue(window.contains("RepositorySelectedTaskTransition"))
        assertTrue(window.contains("publishMutationCommitFailure"))
        assertTrue(window.contains("return false"))
        assertTrue(commitBeforeInstall(window, "commit", "installCoreProjection"))
    }

    @Test
    fun timerCommandCommitReturnsRefusalOnStorageFailure() {
        val window = functionWindow("fun commitTimerCommand(")
        assertTrue(window.contains("RepositoryTimerCommandTransition"))
        assertTrue(window.contains("publishMutationCommitFailure"))
        assertTrue(window.contains("return false"))
        assertTrue(commitBeforeInstall(window, "commit", "installTimerMutation"))
    }

    @Test
    fun taskCommitRetainsCommittedStateOnStorageFailure() {
        val window = functionWindow("fun issueTaskOperation(")
        assertTrue(window.contains("RepositoryTaskTransition"))
        assertTrue(window.contains("installTaskMutation"))
        assertTrue(window.contains("publishMutationCommitFailure"))
        assertTrue(window.contains("return@withLock"))
        assertTrue(commitBeforeInstall(window, "commit", "installTaskMutation"))
    }

    @Test
    fun finishAndCancelBatchCommitsStayRecoverable() {
        val finish = functionWindow("private suspend fun finishLocalTimer(")
        assertTrue(finish.contains("RepositoryTimerCommandBatchTransition"))
        assertTrue(finish.contains("publishMutationCommitFailure"))
        assertTrue(finish.contains("return@withLock"))
        val cancel = functionWindow("override suspend fun cancelAndClearTimer()")
        assertTrue(cancel.contains("RepositoryTimerCommandBatchTransition"))
        assertTrue(cancel.contains("publishMutationCommitFailure"))
        assertTrue(cancel.contains("return@withLock"))
    }

    @Test
    fun retryStaysPossibleAfterStorageFailure() {
        val text = repositoryText()
        assertTrue(text.contains("fun publishMutationCommitFailure("))
        assertTrue(text.contains("fun clearMutationStorageNotice()"))
        val timer = functionWindow("fun commitTimerCommand(")
        val timerFailure = timer.indexOf("publishMutationCommitFailure")
        assertTrue(timerFailure >= 0)
        assertTrue(timer.lastIndexOf("return false") > timerFailure)
        val task = functionWindow("fun issueTaskOperation(")
        val taskFailure = task.indexOf("publishMutationCommitFailure")
        assertTrue(taskFailure >= 0)
        assertTrue(task.lastIndexOf("return@withLock") > taskFailure)
        assertTrue(text.contains("if (saved) afterLocalMutation()"))
    }

    @Test
    fun successfulCommitClearsStorageNoticeBeforeInstall() {
        for (marker in listOf(
            "override suspend fun changeDuration(",
            "override suspend fun setAutoStart(",
            "fun commitSelectedTask(",
            "fun commitTimerCommand(",
            "fun issueTaskOperation(",
            "private suspend fun finishLocalTimer(",
            "override suspend fun cancelAndClearTimer()",
        )) {
            val window = functionWindow(marker)
            val clearAt = window.indexOf("clearMutationStorageNotice()")
            assertTrue("$marker must clear storage notice", clearAt >= 0)
            assertTrue(commitBeforeInstall(window, "commit", "clearMutationStorageNotice()"))
        }
    }

    private fun commitBeforeInstall(window: String, commit: String, install: String): Boolean {
        val commitAt = window.indexOf(commit)
        val installAt = window.indexOf(install)
        return commitAt >= 0 && installAt > commitAt
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        MutationPersistenceReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            MutationPersistenceReporter.resetForTest()
        }
        return reported
    }

    // functionWindow truncation contract: window ends at next
    // private/override/internal boundary or 2800-char fallback.
    // Pins depend on this; nearby refactors can shrink window and
    // flip pins for unrelated reasons. Behavioral fault-injection
    // above covers storage-vs-Core split independent of windowing.
    private fun functionWindow(marker: String): String {
        val text = repositoryText()
        val start = text.indexOf(marker)
        assertTrue("$marker missing", start >= 0)
        val tail = text.drop(start)
        val next = tail.indexOf("\n    private ", 10).takeIf { it > 0 }
            ?: tail.indexOf("\n    override ", 10).takeIf { it > 0 }
            ?: tail.indexOf("\n    internal ", 10).takeIf { it > 0 }
            ?: 2_800
        return tail.take(next)
    }

    private fun repositoryText(): String = productionText(
        "me/egigoka/pomodorough/data/TimerRepository.kt",
    )

    private fun failuresText(): String = productionText(
        "me/egigoka/pomodorough/data/MutationPersistenceFailures.kt",
    )

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
