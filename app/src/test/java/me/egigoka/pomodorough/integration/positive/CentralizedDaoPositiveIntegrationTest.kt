package me.egigoka.pomodorough.integration.positive

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.local.DaoBoundaryFixtures
import me.egigoka.pomodorough.data.local.RecordingCentralizedSyncDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CentralizedDaoPositiveIntegrationTest {
    private val fixture = DaoBoundaryFixtures

    @Test
    fun bootstrapPreparationPersistsAllQueuesBeforeResolutionMarker() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.persistBootstrapPreparation(
            fixture.local,
            listOf(fixture.command),
            listOf(fixture.task),
            listOf(fixture.duration),
            listOf(fixture.autoStart),
            fixture.resolution,
            listOf(fixture.selected),
        )

        assertEquals(
            listOf(
                "updateCommands", "updateTaskOperations", "updateDurationOperations",
                "updateAutoStartOperations", "updateSelectedTaskOperations", "updateState",
                "upsertBootstrapResolution",
            ),
            dao.calls.map { it.name },
        )
        assertSame(fixture.resolution, dao.calls.last().arguments.single())
    }

    @Test
    fun fullSyncAppliesEveryPartitionInDeterministicOrder() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.applyFullSync(
            acknowledgedCommands = listOf(fixture.command),
            acknowledgedTaskOperations = listOf(fixture.task),
            acknowledgedDurationOperationIds = listOf(fixture.duration.id),
            state = fixture.local,
            acknowledgedAutoStartOperations = listOf(fixture.autoStart),
            updatedCommands = listOf(fixture.command),
            updatedTaskOperations = listOf(fixture.task),
            updatedDurationOperations = listOf(fixture.duration),
            updatedAutoStartOperations = listOf(fixture.autoStart),
            discardedCommands = listOf(fixture.command),
            acknowledgedSelectedTaskOperations = listOf(fixture.selected),
            updatedSelectedTaskOperations = listOf(fixture.selected),
        )

        assertEquals(
            listOf(
                "deleteCommands", "deleteTaskOperations", "deleteDurationOperationsById",
                "deleteAutoStartOperations", "deleteSelectedTaskOperations", "deleteCommands",
                "updateCommands", "updateTaskOperations", "updateDurationOperations",
                "updateAutoStartOperations", "updateSelectedTaskOperations", "updateState",
            ),
            dao.calls.map { it.name },
        )
    }

    @Test
    fun accountClearRemovesEveryPendingDomainBeforeReplacingState() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.clearAccount(fixture.local)

        assertEquals(
            listOf(
                "deleteAllCommands", "deleteAllTaskOperations", "deleteAllDurationOperations",
                "deleteAllAutoStartOperations", "deleteAllSelectedTaskOperations",
                "deleteBootstrapResolution", "updateState",
            ),
            dao.calls.map { it.name },
        )
    }
}
