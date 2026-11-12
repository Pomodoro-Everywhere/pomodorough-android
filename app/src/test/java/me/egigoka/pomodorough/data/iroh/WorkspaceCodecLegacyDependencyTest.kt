package me.egigoka.pomodorough.data.iroh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.local.LegacyDependencyAdoptionPending
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Iroh return state is the only copy of a centralized workspace while a room is active.
 * Leaving the room restores it, so it must keep the legacy dependency decision markers.
 */
class WorkspaceCodecLegacyDependencyTest {
    @Test
    fun pendingAdoptionMarkerSurvivesTheReturnStateRoundTrip() {
        val stashed = workspace(input = null, result = LegacyDependencyAdoptionPending, edge = "command-finish")

        val restored = WorkspaceCodec.decode(WorkspaceCodec.encodeReturnState(stashed)).local

        assertNull(restored.legacyDependencyInputJson)
        assertEquals(LegacyDependencyAdoptionPending, restored.legacyDependencyResultJson)
    }

    @Test
    fun recordedCoreDecisionSurvivesTheReturnStateRoundTrip() {
        val input = "{\"queue\":[\"command-child\"]}"
        val result = "{\"outcome\":\"blocked\"}"
        val stashed = workspace(input = input, result = result, edge = "command-finish")

        val restored = WorkspaceCodec.decode(WorkspaceCodec.encodeReturnState(stashed)).local

        assertEquals(input, restored.legacyDependencyInputJson)
        assertEquals(result, restored.legacyDependencyResultJson)
    }

    @Test
    fun modernQueueWithEdgesStaysUnmarkedAfterTheRoundTrip() {
        val stashed = workspace(input = null, result = null, edge = "command-finish")

        val restored = WorkspaceCodec.decode(WorkspaceCodec.encodeReturnState(stashed)).local

        assertNull(restored.legacyDependencyInputJson)
        assertNull(restored.legacyDependencyResultJson)
    }

    @Test
    fun stateStashedBeforeMarkersWithRetainedEdgesIsMarkedForAdoption() {
        val stashed = workspace(input = null, result = null, edge = "command-finish")

        val restored = WorkspaceCodec.decode(withoutMarkerField(WorkspaceCodec.encodeReturnState(stashed))).local

        assertNull(restored.legacyDependencyInputJson)
        assertEquals(LegacyDependencyAdoptionPending, restored.legacyDependencyResultJson)
    }

    @Test
    fun stateStashedBeforeMarkersWithoutEdgesStaysUnmarked() {
        val stashed = workspace(input = null, result = null, edge = null)

        val restored = WorkspaceCodec.decode(withoutMarkerField(WorkspaceCodec.encodeReturnState(stashed))).local

        assertNull(restored.legacyDependencyInputJson)
        assertNull(restored.legacyDependencyResultJson)
    }

    @Test
    fun stateStashedBeforeMarkersIsMarkedWhenOnlyOneOfTwoCommandsHasAnEdge() {
        // One retained edge is enough for Core to own the queue, so "any", not "all", decides.
        val stashed = workspace(input = null, result = null, edges = listOf("command-finish", null))

        val restored = WorkspaceCodec.decode(withoutMarkerField(WorkspaceCodec.encodeReturnState(stashed))).local

        assertNull(restored.legacyDependencyInputJson)
        assertEquals(LegacyDependencyAdoptionPending, restored.legacyDependencyResultJson)
    }

    @Test
    fun encodedMarkerObjectKeepsInputAndResultInTheirOwnKeys() {
        val input = "{\"queue\":[\"command-child\"]}"
        val result = "{\"outcome\":\"planned\"}"
        val stashed = workspace(input = input, result = result, edge = "command-finish")

        val local = IrohJson.strict.parseToJsonElement(WorkspaceCodec.encodeReturnState(stashed))
            .jsonObject.getValue("local").jsonObject
        val markers = local.getValue("legacyDependency").jsonObject

        assertEquals(input, markers.getValue("inputJson").jsonPrimitive.content)
        assertEquals(result, markers.getValue("resultJson").jsonPrimitive.content)
    }

    private fun withoutMarkerField(encoded: String): String {
        val root = IrohJson.strict.parseToJsonElement(encoded).jsonObject
        val local = root.getValue("local").jsonObject
        check("legacyDependency" in local) { "The current encoder must write the marker object" }
        return JsonObject(root + ("local" to JsonObject(local - "legacyDependency"))).toString()
    }

    private fun workspace(input: String?, result: String?, edge: String?) =
        workspace(input, result, edges = listOf(edge))

    private fun workspace(input: String?, result: String?, edges: List<String?>) = LocalWorkspaceSnapshot(
        local = LocalStateEntity(
            deviceId = "device-1",
            deviceSequence = 1,
            settingsJson = "{}",
            legacyDependencyInputJson = input,
            legacyDependencyResultJson = result,
        ),
        commands = edges.mapIndexed { index, edge -> command("command-child-$index", index + 1L, edge) },
    )

    private fun command(id: String, sequence: Long, edge: String?) = PendingCommandEntity(
        id = id,
        deviceSequence = sequence,
        timerId = "timer-1",
        type = "start",
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_000,
        hlcCounter = sequence,
        observedElapsedMs = 0,
        generatedByFinishCommandId = edge,
    )
}
