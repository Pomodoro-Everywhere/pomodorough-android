package me.egigoka.pomodorough.data

import android.app.Application
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R54-A09: a startup that leaves no local workspace must block every mutation instead of
 * throwing. Each public entry point returns without an exception, an uncaught background
 * failure, a new report or a durable row change. A contained read failure still recovers on
 * the next initialize(). A cancelled projection runtime check reports nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A09UninitializedLocalTest {
    private val harness = StartupWithoutWorkspaceHarness()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        harness.open()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        harness.close()
    }

    @Test
    fun readFailureBlocksEveryEntryPoint() = runBlocking {
        assertEveryEntryPointBlocked(StartupWithoutWorkspace.LocalStateRead)
    }

    @Test
    fun projectionRuntimeFailureBlocksEveryEntryPoint() = runBlocking {
        assertEveryEntryPointBlocked(StartupWithoutWorkspace.ProjectionRuntime)
    }

    @Test
    fun readFailureStaysRetryableAfterBlockedEntryPoints() = runBlocking {
        val seeded = harness.seedTask("r54 a09 task")
        val startup = harness.start(StartupWithoutWorkspace.LocalStateRead)
        val repo = startup.repository
        entryPoints(repo).forEach { (_, call) -> escape(call) }

        startup.recoverStorage()
        repo.initialize()

        assertTrue("retry must complete startup", repo.state.value.ready)
        assertEquals(listOf("r54 a09 task"), repo.state.value.tasks.map { it.title })
        assertEquals(seeded, harness.durable())
        assertTrue("a recovered workspace accepts edits", repo.addTask("r54 a09 after retry"))
    }

    @Test
    fun projectionRuntimeFailureStaysBlockedAfterRetry() = runBlocking {
        val seeded = harness.seedTask("r54 a09 task")
        val repo = harness.start(StartupWithoutWorkspace.ProjectionRuntime).repository
        entryPoints(repo).forEach { (_, call) -> escape(call) }

        repo.initialize()

        harness.assertStartupScreen(StartupWithoutWorkspace.ProjectionRuntime, repo.state.value)
        assertFalse("a failed startup must not accept edits", repo.addTask("r54 a09 after retry"))
        assertEquals(seeded, harness.durable())
    }

    // CorruptStateReporter sends one report per category per process. A cancelled runtime check
    // must send none, or the failure that follows would find its one report already used.
    @Test
    fun cancelledProjectionRuntimeCheckLeavesItsReportForTheFailure() = runBlocking {
        val cancellation = CancellationException("r54 a09 startup cancelled")
        var failure: Exception = cancellation
        val repo = harness.repositoryWithProjectionFailure { failure }

        assertSame(cancellation, runCatching { repo.initialize() }.exceptionOrNull())
        assertEquals("a cancelled startup must not report", emptyList<String?>(), harness.reported.map { it.message })

        failure = IllegalStateException("r54 core runtime")
        repo.initialize()

        harness.assertStartupScreen(StartupWithoutWorkspace.ProjectionRuntime, repo.state.value)
        harness.assertStartupReport(StartupWithoutWorkspace.ProjectionRuntime)
    }

    private suspend fun assertEveryEntryPointBlocked(failure: StartupWithoutWorkspace) {
        val seeded = harness.seedTask("r54 a09 task")
        val repo = harness.start(failure).repository
        val reportsAfterStartup = harness.reported.toList()
        harness.assertStartupScreen(failure, repo.state.value)

        val reached = mutableListOf<String>()
        val escapes = entryPoints(repo).mapNotNull { (name, call) ->
            escape { call(); reached.add(name) }?.let { "$name: $it" }
        }

        assertEquals(emptyList<String>(), escapes)
        assertEquals("every entry point returns", EntryPointCount, reached.distinct().size)
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(reportsAfterStartup, harness.reported.toList())
        harness.assertStartupReport(failure)
        assertEquals(seeded, harness.durable())
        harness.assertStartupScreen(failure, repo.state.value)
    }

    // Every public action a view, receiver or service can call before startup completes.
    private fun entryPoints(repo: TimerRepository): List<Pair<String, suspend () -> Unit>> = listOf(
        entry("toggleTimer") { repo.toggleTimer() },
        entry("finishTimer") { repo.finishTimer() },
        entry("cancelAndClearTimer") { repo.cancelAndClearTimer() },
        entry("clearTimer") { repo.clearTimer() },
        entry("selectPhase") { repo.selectPhase("short_break") },
        entry("changeDuration") { repo.changeDuration("focus", 5) },
        entry("setAutoStart") { repo.setAutoStart(true) },
        entry("selectTask") { repo.selectTask(null) },
        entry("addTask") { repo.addTask("r54 a09 blocked task") },
        entry("deleteTask") { repo.deleteTask("missing-task") },
        entry("finishExpiredTimer") { repo.finishExpiredTimer() },
        entry("showCompletionAlert") { repo.showCompletionAlert("missing-timer") { true } },
        entry("consumeCompletionAlert") { repo.consumeCompletionAlert("missing-timer") { true } },
        entry("rescheduleAlarmFromLocal") { repo.rescheduleAlarmFromLocal() },
        entry("refresh") { repo.refresh(); settle() },
        entry("onForeground") { repo.onForeground(); settle() },
        entry("onBackground") { repo.onBackground(); settle() },
        entry("dismissConflict") { repo.dismissConflict() },
        entry("dismissNotice") { repo.dismissNotice() },
        entry("setReplicationMode") { repo.setReplicationMode(ReplicationMode.OFFLINE) },
        entry("createIrohRoom") { repo.createIrohRoom("r54 a09 room") },
        entry("joinIrohRoom") { repo.joinIrohRoom("not-an-invite") },
        entry("leaveIrohRoom") { repo.leaveIrohRoom() },
        entry("refreshIrohInvite") { repo.refreshIrohInvite() },
        entry("syncIrohNow") { repo.syncIrohNow() },
        entry("resolveHistory") { repo.resolveHistory(BootstrapStrategy.KeepRemote) },
        entry("recoverCorruptedResolution") { repo.recoverCorruptedResolution() },
        entry("confirmAccountSwitch") { repo.confirmAccountSwitch() },
        entry("cancelAccountSwitch") { repo.cancelAccountSwitch() },
    )

    private fun entry(name: String, call: suspend () -> Unit): Pair<String, suspend () -> Unit> = name to call

    /** Null when [call] returns in time; otherwise what escaped it. */
    private suspend fun escape(call: suspend () -> Unit): String? = try {
        val done = withTimeoutOrNull(5_000) {
            call()
            true
        }
        if (done == true) null else "timed out"
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        "${error.javaClass.simpleName}: ${error.message?.take(80)}"
    }

    // Background work started by refresh and lifecycle calls fails, if at all, within this window.
    private suspend fun settle() = delay(300)

    private companion object {
        const val EntryPointCount = 29
    }
}
