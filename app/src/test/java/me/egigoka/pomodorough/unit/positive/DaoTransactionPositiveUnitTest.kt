package me.egigoka.pomodorough.unit.positive

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.local.DaoBoundaryFixtures
import me.egigoka.pomodorough.data.local.RecordingCentralizedSyncDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DaoTransactionPositiveUnitTest {
    private val fixture = DaoBoundaryFixtures

    @Test
    fun eachSingleMutationPersistsOperationBeforeUpdatedState() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.persistCommand(fixture.command, fixture.local)
        dao.persistTaskOperation(fixture.task, fixture.local, fixture.selected)
        dao.persistDurationOperation(fixture.duration, fixture.local)
        dao.persistAutoStartOperation(fixture.autoStart, fixture.local)
        dao.persistSelectedTaskOperation(fixture.selected, fixture.local)

        assertEquals(
            listOf(
                "insertCommand", "updateState",
                "insertTaskOperation", "insertSelectedTaskOperation", "updateState",
                "upsertDurationOperation", "updateState",
                "insertAutoStartOperation", "updateState",
                "insertSelectedTaskOperation", "updateState",
            ),
            dao.calls.map { it.name },
        )
        assertSame(fixture.selected, dao.calls[3].arguments.single())
    }

    @Test
    fun mutationStateUpdatesEveryNonemptyQueueBeforeState() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.updateMutationState(
            fixture.local,
            listOf(fixture.command),
            listOf(fixture.task),
            listOf(fixture.duration),
            listOf(fixture.autoStart),
            listOf(fixture.selected),
        )

        assertEquals(
            listOf(
                "updateCommands", "updateTaskOperations", "updateDurationOperations",
                "updateAutoStartOperations", "updateSelectedTaskOperations", "updateState",
            ),
            dao.calls.map { it.name },
        )
        assertSame(fixture.local, dao.calls.last().arguments.single())
    }
}
