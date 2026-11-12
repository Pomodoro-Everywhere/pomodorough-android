package me.egigoka.pomodorough.timer

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.PendingDeletionWorkspace
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.seedSelectedTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UX round 2, M1: "Start next" on the notification cannot start a task that waits for Undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompletionNotificationPendingDeletionTest {
    private val workspace = PendingDeletionWorkspace()

    @After
    fun tearDown() = workspace.close()

    @Test
    fun startNextDuringTheUndoWindowDoesNotStartTheDeletedTask() = runBlocking {
        val repository = workspace.startProcess()
        val task = repository.seedSelectedTask("Write report")
        repository.requestTaskDeletion(task.id)
        val handler = CompletionNotificationActionHandler(repository, stopAlert = {}, cancelNotification = {})

        val outcome = handler.handle(CompletionNotificationAction.StartNext, timerId = null)

        assertEquals(CompletionNotificationOutcome.Started, outcome)
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        assertNull("the timer must not focus the deleted task", repository.state.value.timer?.taskId)
        assertEquals(emptyList<FocusTask>(), repository.state.value.tasks)
    }
}
