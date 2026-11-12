package me.egigoka.pomodorough.data.iroh.protocol

import me.egigoka.pomodorough.data.TaskOperation
import me.egigoka.pomodorough.domain.TaskReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Core 0.56.0: a room record may carry a `rename` task operation. It keeps the
 * title it renames to through the strict wire form, and a rename without a
 * title fails closed like any other malformed task record.
 */
class IrohTaskRenameRecordTest {
    @Test
    fun renameRecordWithItsTitleRoundTripsThroughStrictValidation() {
        val record = IrohOperationRecord.task(DeviceId, rename(title = beta.title))

        record.validate()

        assertEquals(record, IrohOperationRecord.fromJson(record.toJson()))
        assertEquals(beta.title, record.decodeOperation<TaskOperation>().title)
    }

    @Test
    fun renameRecordWithoutATitleFailsClosed() {
        val record = IrohOperationRecord.task(DeviceId, rename(title = null))

        assertThrows(IllegalArgumentException::class.java, record::validate)
    }

    private fun rename(title: String?) =
        TaskOperation("task-op-0001", alpha.id, "rename", title, At, WallMs, 1L)

    private companion object {
        const val DeviceId = "device-0001"
        const val At = "2026-01-01T00:00:00Z"
        const val WallMs = 1_767_225_600_000L
        val alpha = requireNotNull(TaskReducer.taskFromTitle("Alpha"))
        val beta = requireNotNull(TaskReducer.taskFromTitle("Beta"))
    }
}
