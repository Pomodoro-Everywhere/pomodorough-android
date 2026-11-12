package me.egigoka.pomodorough.data.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingSelectedTaskOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A return state is the centralized workspace stashed while a room is active, so it keeps the
 * safe base and the never-sent claims. A room state owns its own canonical settings and drops them.
 */
class WorkspaceCodecReturnStateTest {
    @Test
    fun returnStateKeepsTheSafeBase() {
        val restored = WorkspaceCodec.decode(WorkspaceCodec.encodeReturnState(workspace())).local

        assertEquals(SafeBaseDurations, restored.safeBaseDurationsJson)
        assertEquals("task-safe", restored.safeBaseSelectedTaskId)
        assertEquals(1_767_225_600_123, restored.safeCanonicalHeadWallMs)
        assertEquals(4L, restored.safeCanonicalHeadCounter)
    }

    @Test
    fun roomStateDropsTheSafeBase() {
        val restored = WorkspaceCodec.decode(WorkspaceCodec.encodeRoomState(workspace())).local

        assertNull(restored.safeBaseDurationsJson)
        assertNull(restored.safeBaseSelectedTaskId)
        assertNull(restored.safeCanonicalHeadWallMs)
        assertNull(restored.safeCanonicalHeadCounter)
    }

    @Test
    fun neverSentClaimSurvivesTheRoundTripOnEveryQueue() {
        val restored = WorkspaceCodec.decode(WorkspaceCodec.encodeReturnState(workspace(neverSent = true)))

        assertTrue(restored.commands.single().neverSent)
        assertTrue(restored.taskOperations.single().neverSent)
        assertTrue(restored.durationOperations.single().neverSent)
        assertTrue(restored.autoStartOperations.single().neverSent)
        assertTrue(restored.selectedTaskOperations.single().neverSent)
    }

    @Test
    fun stateWrittenBeforeTheNeverSentFieldDecodesAsSent() {
        val encoded = WorkspaceCodec.encodeReturnState(workspace(neverSent = true))
        val root = IrohJson.strict.parseToJsonElement(encoded).jsonObject
        check(root.getValue("commands").jsonArray.single().jsonObject.containsKey("neverSent")) {
            "The current encoder must write the never-sent field"
        }

        val restored = WorkspaceCodec.decode(withoutNeverSent(root).toString())

        assertFalse(restored.commands.single().neverSent)
        assertFalse(restored.taskOperations.single().neverSent)
        assertFalse(restored.durationOperations.single().neverSent)
        assertFalse(restored.autoStartOperations.single().neverSent)
        assertFalse(restored.selectedTaskOperations.single().neverSent)
    }

    @Test
    fun workspaceInAccountDeletionIsNeverStashed() {
        val deleting = workspace().let { it.copy(local = it.local.copy(accountDeletionState = "{\"phase\":\"pending\"}")) }

        assertThrows(IllegalArgumentException::class.java) { WorkspaceCodec.encodeReturnState(deleting) }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCodec.encodeRoomState(deleting) }
    }

    private fun withoutNeverSent(root: JsonObject): JsonObject = JsonObject(
        root.mapValues { (key, value) ->
            if (key in QueueKeys) JsonArray(value.jsonArray.map(::withoutNeverSentRow)) else value
        },
    )

    private fun withoutNeverSentRow(row: JsonElement): JsonElement = JsonObject(row.jsonObject - "neverSent")

    private fun workspace(neverSent: Boolean = false) = LocalWorkspaceSnapshot(
        local = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 1,
            settingsJson = "{}",
            safeBaseDurationsJson = SafeBaseDurations,
            safeBaseSelectedTaskId = "task-safe",
            safeCanonicalHeadWallMs = 1_767_225_600_123,
            safeCanonicalHeadCounter = 4,
        ),
        commands = listOf(
            PendingCommandEntity(
                id = "command-1", deviceSequence = 1, timerId = "timer-1", type = "start",
                phase = TimerPhase.Focus, plannedDurationMs = 1_500_000, occurredAt = At,
                hlcWallMs = Wall, hlcCounter = 1, observedElapsedMs = 0, neverSent = neverSent,
            ),
        ),
        taskOperations = listOf(PendingTaskOperationEntity("task-op", "task-1", "upsert", "Task", At, Wall, 2, neverSent)),
        durationOperations = listOf(PendingDurationOperationEntity("focus", "duration-op", 1_500_000, At, Wall, 3, neverSent)),
        autoStartOperations = listOf(PendingAutoStartOperationEntity("auto-op", "device-1", true, At, Wall, 4, neverSent)),
        selectedTaskOperations = listOf(PendingSelectedTaskOperationEntity("select-op", "task-1", At, Wall, 5, neverSent)),
    )

    private companion object {
        const val At = "2026-01-01T00:00:00Z"
        const val Wall = 1_767_225_600_000
        const val SafeBaseDurations = "{\"focus\":1500000,\"short_break\":300000,\"long_break\":900000}"
        val QueueKeys = setOf(
            "commands", "taskOperations", "durationOperations", "autoStartOperations", "selectedTaskOperations",
        )
    }
}
