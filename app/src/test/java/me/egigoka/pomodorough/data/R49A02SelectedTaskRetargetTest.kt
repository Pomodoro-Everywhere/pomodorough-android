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
 * R49-A02: selection plus retarget must commit atomically.
 * Behavioral coverage lives in androidTest with real Room;
 * these JVM pins fail if two-commit or two-projection ordering returns.
 */
class R49A02SelectedTaskRetargetTest {
    @Test
    fun infrastructureReportsBoundedCategoryWithoutPayload() {
        val payload = "R49A02-SENTINEL-{\"taskId\":\"secret\"}"
        val reported = captureReports {
            SelectedTaskRetargetReporter.report(SelectedTaskRetargetFailure.Infrastructure)
        }
        assertEquals(1, reported.size)
        val error = reported.single()
        assertTrue(error is SelectedTaskRetargetReport)
        assertEquals(SelectedTaskRetargetFailure.Infrastructure.reportMessage, error.message)
        assertTrue(error.cause == null)
        assertTrue(error.message?.contains(payload) != true)
    }

    @Test
    fun duplicateInfrastructureReportsOnce() {
        val reported = captureReports {
            SelectedTaskRetargetReporter.report(SelectedTaskRetargetFailure.Infrastructure)
            SelectedTaskRetargetReporter.report(SelectedTaskRetargetFailure.Infrastructure)
        }
        assertEquals(1, reported.size)
    }

    @Test
    fun cancellationPropagatesWithoutReport() {
        val cancellation = CancellationException("retarget gone")
        val recorded = mutableListOf<SelectedTaskRetargetFailure>()
        val failure = runCatching {
            handleSelectedTaskRetargetFailure(cancellation, recorded::add)
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun selectTaskReservesTwoWithDeviceSequencesForAtomicPath() {
        val window = selectTaskWindow(repositoryText())
        assertTrue(window.any { it.contains("commitSelectedTaskWithRetarget") })
        assertTrue(window.any { it.contains("selectionNeeded") })
        assertTrue(window.any { it.contains("retargetNeeded") })
        val coordinator = coordinatorText()
        assertTrue(coordinator.contains("fun selectedTaskWithRetarget("))
        assertTrue(coordinator.contains("reservation.stamps.size != 2"))
        assertTrue(repositoryText().contains("fun reserveSelectionRetarget("))
    }

    @Test
    fun atomicPlannerReusesExistingCoreDecisions() {
        val text = coordinatorText()
        val start = text.indexOf("fun selectedTaskWithRetarget(")
        assertTrue(start >= 0)
        val window = text.drop(start).take(2_400)
        assertTrue(window.contains("selectedTask(SelectedTaskMutationInput"))
        assertTrue(window.contains("retarget(TimerRetargetMutationInput"))
        assertTrue(!window.contains("TimerRetargetPolicy.plan("))
    }

    @Test
    fun atomicCommitPersistsOnceAndInstallsOnce() {
        val text = repositoryText()
        val start = text.indexOf("fun commitSelectedTaskWithRetarget(")
        assertTrue(start >= 0)
        val window = text.drop(start).take(2_800)
        assertTrue(window.contains("reserveSelectionRetarget()"))
        assertTrue(text.contains("local.deviceSequence + 1"))
        assertTrue(window.contains("RepositorySelectedTaskRetargetTransition"))
        assertTrue(window.contains("installSelectedTaskRetargetMutation"))
        assertTrue(!window.contains("commitSelectedTask("))
        assertTrue(!window.contains("commitRetarget("))
        val install = installWindow(text)
        assertEquals(1, install.count { it.contains("installCoreProjection") })
    }

    @Test
    fun atomicInstallKeepsBothIdentitiesProofAndDependencies() {
        val text = repositoryText()
        val start = text.indexOf("fun installSelectedTaskRetargetMutation(")
        assertTrue(start >= 0)
        val window = text.drop(start).take(1_600)
        assertTrue(window.contains("pendingSelectedTaskOperations ="))
        assertTrue(window.contains("pending = pending +"))
        assertTrue(window.contains("commandDependencies = commandDependencies +"))
        assertTrue(window.contains("selectedTaskOperations ="))
        assertTrue(window.contains("commands ="))
        assertTrue(window.contains("scheduleAlarm()"))
    }

    @Test
    fun storageFailureDistinguishedFromCoreOutput() {
        val text = repositoryText()
        val start = text.indexOf("fun commitSelectedTaskWithRetarget(")
        assertTrue(start >= 0)
        val window = text.drop(start).take(2_800)
        assertTrue(window.contains("CancellationException"))
        assertTrue(window.contains("SharedCoreException"))
        assertTrue(window.contains("handleSelectedTaskRetargetFailure"))
        assertTrue(window.contains("sync_stopped_after_a_local_failure"))
        assertTrue(window.contains("projectionFailureMessage"))
        assertTrue(window.contains("CrashReporter.report"))
    }

    @Test
    fun storageBranchReportsOnlyBoundedEvent() {
        val text = repositoryText()
        val atomic = functionWindow(text, "fun commitSelectedTaskWithRetarget(")
        assertTrue(atomic.contains("handleSelectedTaskRetargetFailure"))
        assertEquals(1, countOccurrences(atomic, "CrashReporter.report"))
        val retarget = functionWindow(text, "fun commitRetarget(")
        assertTrue(retarget.contains("handleSelectedTaskRetargetFailure"))
        assertEquals(1, countOccurrences(retarget, "CrashReporter.report"))
    }

    @Test
    fun daoPersistsBothInOneTransaction() {
        val text = daoText()
        val start = text.indexOf("fun persistSelectedTaskRetarget(")
        assertTrue(start >= 0)
        val window = text.drop(start).take(800)
        assertTrue(window.contains("@Transaction") || text.contains("@Transaction"))
        assertTrue(window.contains("insertSelectedTaskOperation"))
        assertTrue(window.contains("insertCommand"))
        assertTrue(window.contains("updateState"))
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        SelectedTaskRetargetReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            SelectedTaskRetargetReporter.resetForTest()
        }
        return reported
    }

    private fun selectTaskWindow(text: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.contains("override suspend fun selectTask(") }
        assertTrue("selectTask missing", start >= 0)
        return lines.drop(start).take(30)
    }

    private fun installWindow(text: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.contains("fun installSelectedTaskRetargetMutation(") }
        assertTrue("install missing", start >= 0)
        val end = (start + 1 until minOf(lines.size, start + 40)).firstOrNull { index ->
            lines[index].trimStart().startsWith("override ") ||
                lines[index].trimStart().startsWith("private ") ||
                lines[index].trimStart().startsWith("internal ") ||
                lines[index].trimStart().startsWith("fun ")
        } ?: (start + 40)
        return lines.subList(start, end)
    }

    private fun repositoryText(): String = productionText("me/egigoka/pomodorough/data/TimerRepository.kt")

    private fun functionWindow(text: String, marker: String): String {
        val start = text.indexOf(marker)
        assertTrue(start >= 0)
        val tail = text.drop(start)
        val next = tail.indexOf("\n    private ", 10)
        val bounded = if (next > 0) tail.take(next) else tail.take(2_800)
        return bounded
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun coordinatorText(): String = productionText("me/egigoka/pomodorough/data/TimerMutationCoordinator.kt")

    private fun daoText(): String = productionText("me/egigoka/pomodorough/data/local/TimerWorkspaceDao.kt")

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
