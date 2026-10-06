package me.egigoka.pomodorough.ui

import androidx.compose.runtime.saveable.SaverScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R43-A07: drafts must survive tab switches and activity recreation.
// Task draft + room name are non-sensitive and survive process recreation
// via SavedState. Join/invite code is sensitive (full read/write grant,
// reveals peer IPs) and survives tab switches plus config recreation in
// ViewModel memory only, never in SavedState.
class R43A07DraftLifetimeTest {
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun taskDraftSurvivesTabSwitchesAndRecreation() {
        val views = productionText("me/egigoka/pomodorough/ui/TaskViews.kt")
        val header = views.substringAfter("fun TaskBoardHeader")
            .substringBefore("fun TaskDraftCard")
        assertTrue(
            "task draft must not use plain remember (loses state when tab leaves composition)",
            !header.contains("by remember {"),
        )
        assertTrue(
            "task draft must be hoisted (holder param) so tab switches keep it",
            header.contains("TaskDraftUiState"),
        )
    }

    @Test
    fun networkDraftsSurviveTabSwitchesAndRecreation() {
        val section = productionText("me/egigoka/pomodorough/ui/NetworkSection.kt")
        val header = section.substringAfter("fun NetworkSection")
            .substringBefore("fun NetworkActions")
        assertTrue(
            "roomName must not use plain remember",
            !header.contains("roomName by remember"),
        )
        assertTrue(
            "joinCode must not use plain remember",
            !header.contains("joinCode by remember"),
        )
        assertTrue(
            "network drafts must be hoisted params so tab switches keep them",
            header.contains("roomNameDraft") && header.contains("joinCode"),
        )
    }

    @Test
    fun sensitiveInviteLifetimeDecidedExplicitly() {
        val viewModel = productionText("me/egigoka/pomodorough/ui/PomodoroughViewModel.kt")
        val drafts = productionText("me/egigoka/pomodorough/ui/DraftUiState.kt")
        val combined = viewModel + drafts
        assertTrue(
            "sensitive invite draft lifetime must be decided explicitly in code",
            combined.contains("memory-only", ignoreCase = true) ||
                combined.contains("must not survive process", ignoreCase = true) ||
                combined.contains("never in SavedState", ignoreCase = true),
        )
        assertTrue(
            "join invite must live in ViewModel memory, not SavedState",
            viewModel.contains("joinInviteDraft by mutableStateOf"),
        )
        assertTrue(
            "join invite must have no Saver (never saved to process bundle)",
            !drafts.contains("JoinInviteDraftSaver") && !drafts.contains("JoinInviteSaver"),
        )
    }

    @Test
    fun draftsHoistedAboveTabKey() {
        val scaffold = productionText("me/egigoka/pomodorough/ui/TimerScreenScaffold.kt")
        assertTrue(
            "drafts must be hoisted above key(activeTab) so tab switches keep them",
            scaffold.contains("rememberSaveable") && scaffold.contains("taskDraft"),
        )
        assertTrue(
            "room name draft must be hoisted above key(activeTab)",
            scaffold.contains("roomNameDraft"),
        )
    }

    @Test
    fun typingDraftSwitchingTabsAndReturningPreservesIt() {
        val task = TaskDraftUiState()
        task.onDraftChange("Write release notes")
        assertEquals("Write release notes", task.draft)
        val room = RoomNameDraftState()
        room.onChange("Design desk")
        val invite = JoinInviteDraftState()
        invite.code = "pomodorough1-invite"
        assertEquals("Write release notes", task.draft)
        assertEquals("Design desk", room.name)
        assertEquals("pomodorough1-invite", invite.code)
    }

    @Test
    fun rotationOrFoldRecreationPreservesNonSensitiveDrafts() {
        val task = TaskDraftUiState().apply {
            onDraftChange("Rotate me")
            onSubmissionResult(false, "Try again")
        }
        val restoredTask = restoreTaskDraft(saveTask(task))
        assertEquals("Rotate me", restoredTask?.draft)
        assertEquals("Try again", restoredTask?.submissionError)
        val room = RoomNameDraftState().apply { onChange("Foldable room") }
        val restoredRoom = restoreRoomNameDraft(saveRoom(room))
        assertEquals("Foldable room", restoredRoom?.name)
    }

    @Test
    fun rejectedSubmissionPreservesDraft() {
        val task = TaskDraftUiState().apply { onDraftChange("Duplicate title") }
        task.onSubmissionResult(false, "Could not be saved")
        assertEquals("Duplicate title", task.draft)
        assertEquals("Could not be saved", task.submissionError)
        assertEquals(false, shouldClearNetworkDrafts(null, null))
        val room = RoomNameDraftState().apply { onChange("My room") }
        assertEquals("My room", room.name)
    }

    @Test
    fun clearsOnlyAfterConfirmedSuccess() {
        val task = TaskDraftUiState().apply { onDraftChange("Ship it") }
        task.onSubmissionResult(true, "Try again")
        assertEquals("", task.draft)
        assertNull(task.submissionError)
        assertEquals(true, shouldClearNetworkDrafts(null, "room-1"))
        assertEquals(false, shouldClearNetworkDrafts("room-1", "room-1"))
        assertEquals(false, shouldClearNetworkDrafts(null, null))
        val room = RoomNameDraftState().apply { onChange("My room") }
        room.clear()
        assertEquals("", room.name)
    }

    @Test
    fun corruptSavedDraftFailsClosed() {
        assertNull(restoreTaskDraft(listOf(true, false)))
        assertNull(restoreTaskDraft(emptyList()))
        assertNull(restoreRoomNameDraft(emptyList()))
        assertNull(restoreRoomNameDraft(listOf(42)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun saveTask(state: TaskDraftUiState): List<Any?> =
        with(TaskDraftSaver) { saverScope.save(state) } as List<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun saveRoom(state: RoomNameDraftState): List<Any?> =
        with(RoomNameDraftSaver) { saverScope.save(state) } as List<Any?>

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
