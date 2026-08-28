package me.egigoka.pomodorough.integration.negative

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.local.DaoBoundaryFixtures
import me.egigoka.pomodorough.data.local.RecordingCentralizedSyncDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CentralizedDaoNegativeIntegrationTest {
    private val fixture = DaoBoundaryFixtures

    @Test
    fun emptySyncAcknowledgementOnlyPublishesReplacementState() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.applySync(emptyList(), fixture.local)
        dao.applyFullSync(emptyList(), emptyList(), emptyList(), fixture.local)

        assertEquals(listOf("updateState", "updateState"), dao.calls.map { it.name })
    }

    @Test
    fun clearingBootstrapResolutionIgnoresRetainedRowsFromRejectedMergeRoute() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.applyBootstrapResolution(
            state = fixture.local,
            clearAutoStartOperations = true,
            retainedCommands = listOf(fixture.command),
            retainedAutoStartOperations = listOf(fixture.autoStart),
            clearSelectedTaskOperations = true,
            retainedSelectedTaskOperations = listOf(fixture.selected),
        )

        assertEquals(
            listOf(
                "deleteAllCommands", "insertCommands", "deleteAllTaskOperations",
                "deleteAllDurationOperations", "deleteAllAutoStartOperations",
                "deleteAllSelectedTaskOperations", "deleteBootstrapResolution", "updateState",
            ),
            dao.calls.map { it.name },
        )
    }

    @Test
    fun retainedBootstrapRouteWithEmptyRowsDoesNotIssueEmptyUpdates() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.applyBootstrapResolution(
            state = fixture.local,
            clearAutoStartOperations = false,
            clearSelectedTaskOperations = false,
        )

        assertEquals(
            listOf(
                "deleteAllCommands", "deleteAllTaskOperations", "deleteAllDurationOperations",
                "deleteBootstrapResolution", "updateState",
            ),
            dao.calls.map { it.name },
        )
    }

    @Test
    fun failedAcknowledgementDeletionNeverPublishesPartiallyAppliedState() {
        val dao = RecordingCentralizedSyncDao().apply { failOn = "deleteTaskOperations" }

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.applyFullSync(
                    listOf(fixture.command),
                    listOf(fixture.task),
                    emptyList(),
                    fixture.local,
                )
            }
        }

        assertEquals(listOf("deleteCommands", "deleteTaskOperations"), dao.calls.map { it.name })
    }
}
