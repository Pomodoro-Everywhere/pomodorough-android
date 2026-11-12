package me.egigoka.pomodorough.tile

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

/** UX round 2, M1: the tile cannot start a task that the user deleted and can still undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TileToggleTest {
    private val workspace = PendingDeletionWorkspace()

    @After
    fun tearDown() = workspace.close()

    @Test
    fun tileStartDuringTheUndoWindowDoesNotStartTheDeletedTask() = runBlocking {
        val repository = workspace.startProcess()
        val task = repository.seedSelectedTask("Write report")
        repository.requestTaskDeletion(task.id)

        toggleTimerFromTile(repository)

        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        assertNull("the timer must not focus the deleted task", repository.state.value.timer?.taskId)
        assertEquals(emptyList<FocusTask>(), repository.state.value.tasks)
        assertNull(repository.pendingTaskDeletion.value)
    }
}
