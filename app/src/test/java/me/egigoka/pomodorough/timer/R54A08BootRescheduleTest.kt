package me.egigoka.pomodorough.timer

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.StartupWithoutWorkspace
import me.egigoka.pomodorough.data.StartupWithoutWorkspaceHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R54-A08: the boot broadcast reschedules the timer alarm on a background job. A startup that
 * left no local workspace has nothing to schedule, so the reschedule returns quietly, and a
 * recovered startup schedules its running timer again. Any other failure in the job is logged
 * and reported instead of crashing the process, cancellation keeps its own path, and the
 * broadcast always finishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A08BootRescheduleTest {
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
    fun failedBootRescheduleIsReportedAndTheBroadcastFinishes() = runBlocking {
        // The test application is not PomodoroughApplication, so the job fails on its first
        // step, as any failure in that job would.
        val application = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        val receiver = shadowOf(application).getReceiversForIntent(intent).filterIsInstance<BootReceiver>().single()

        application.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()

        val pendingResult = requireNotNull(shadowOf(receiver).originalPendingResult) { "boot receiver must go async" }
        shadowOf(pendingResult).future.get(10, TimeUnit.SECONDS)
        awaitSettled { harness.reported.isNotEmpty() || uncaught.isNotEmpty() }
        assertEquals(emptyList<Throwable>(), uncaught.toList())
        assertEquals(listOf(ClassCastException::class.java), harness.reported.map { it.javaClass })
    }

    @Test
    fun rescheduleAfterContainedReadFailureReturnsQuietlyAndRecovers() = runBlocking {
        val seeded = harness.seedTask("r54 a08 task")
        val startup = harness.start(StartupWithoutWorkspace.LocalStateRead)
        val reportsAfterStartup = harness.reported.toList()

        startup.repository.rescheduleAlarmFromLocal()

        assertFalse(startup.repository.state.value.ready)
        assertEquals(reportsAfterStartup, harness.reported.toList())
        assertEquals(seeded, harness.durable())
        startup.recoverStorage()
        startup.repository.initialize()
        assertTrue("retry must complete startup", startup.repository.state.value.ready)
        startup.repository.rescheduleAlarmFromLocal()
        assertEquals(reportsAfterStartup, harness.reported.toList())
    }

    @Test
    fun rescheduleAfterRecoveredReadFailureSchedulesTheRunningTimer() = runBlocking {
        harness.seedRunningTimer()
        val startup = harness.start(StartupWithoutWorkspace.LocalStateRead)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val alarms = shadowOf(application.getSystemService(AlarmManager::class.java))
        startup.recoverStorage()
        startup.repository.initialize()
        assertTrue("retry must complete startup", startup.repository.state.value.ready)
        TimerAlarmScheduler(application).cancel()
        assertNull("the alarm must start cancelled", alarms.peekNextScheduledAlarm())

        startup.repository.rescheduleAlarmFromLocal()

        assertNotNull("a recovered workspace reschedules its running timer", alarms.peekNextScheduledAlarm())
    }

    @Test
    fun rescheduleAfterProjectionRuntimeFailureReturnsQuietly() = runBlocking {
        val seeded = harness.seedTask("r54 a08 task")
        val startup = harness.start(StartupWithoutWorkspace.ProjectionRuntime)
        val reportsAfterStartup = harness.reported.toList()

        startup.repository.rescheduleAlarmFromLocal()

        harness.assertStartupScreen(StartupWithoutWorkspace.ProjectionRuntime, startup.repository.state.value)
        assertEquals(reportsAfterStartup, harness.reported.toList())
        assertEquals(seeded, harness.durable())
    }

    @Test
    fun bootRescheduleRethrowsCancellationBeforeReportingAndAlwaysFinishes() {
        // Structural pin: the boot job has its own scope, so a test cannot cancel it. Cancellation
        // is not a reschedule failure, and finish() must run on every exit.
        val window = onReceiveSource().take(1_600)
        val order = listOf(
            "rescheduleAlarmFromLocal()",
            "catch (error: CancellationException)",
            "throw error",
            "catch (error: Exception)",
            "CrashReporter.report(error)",
            "finally {",
            "pendingResult.finish()",
        ).map(window::indexOf)
        assertTrue("catch order changed: $order", order.all { it >= 0 } && order == order.sorted())
    }

    private suspend fun awaitSettled(condition: () -> Boolean) {
        val settled = withTimeoutOrNull(10_000) {
            while (!condition()) delay(5)
            true
        }
        assertTrue("the boot job must settle", settled == true)
        // A late second failure would land after the first; give it time to show.
        delay(200)
    }

    private fun onReceiveSource(): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java")).firstOrNull(File::isDirectory)
        val source = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/timer/BootReceiver.kt",
        ).readText()
        val start = source.indexOf("fun onReceive(")
        assertTrue("BootReceiver.onReceive must exist", start >= 0)
        return source.drop(start)
    }
}
