package me.egigoka.pomodorough.unit.negative

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.local.DaoBoundaryFixtures
import me.egigoka.pomodorough.data.local.RecordingCentralizedSyncDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DaoTransactionNegativeUnitTest {
    private val fixture = DaoBoundaryFixtures

    @Test
    fun emptyMutationQueuesDoNotIssueMeaninglessWrites() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.updateMutationState(
            fixture.local,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )

        assertEquals(listOf("updateState"), dao.calls.map { it.name })
    }

    @Test
    fun taskWithoutSelectionDoesNotInventSelectionMutation() = runTest {
        val dao = RecordingCentralizedSyncDao()

        dao.persistTaskOperation(fixture.task, fixture.local, null)

        assertEquals(listOf("insertTaskOperation", "updateState"), dao.calls.map { it.name })
    }

    @Test
    fun failedOperationWriteNeverAdvancesLocalState() {
        val dao = RecordingCentralizedSyncDao().apply { failOn = "upsertDurationOperation" }

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                dao.persistDurationOperation(fixture.duration, fixture.local)
            }
        }

        assertEquals(listOf("upsertDurationOperation"), dao.calls.map { it.name })
    }
}
