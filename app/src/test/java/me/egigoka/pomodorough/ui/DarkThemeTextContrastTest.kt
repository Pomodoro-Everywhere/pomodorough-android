package me.egigoka.pomodorough.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.PendingTaskDeletion
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Text takes its colour from the surface it sits on, in both themes. The
 * dark typography once forced white onto every style, which beat the content
 * colour of light containers: white on the Butter primary button (1.5:1), on
 * the Lavender break card (2.5:1), and on the default snackbar (1.3:1). The
 * dark on-primary role was white too, on a light blue primary (2.0:1).
 *
 * Renders the production screen through Robolectric native graphics, reads
 * each label's resolved text colour and the rendered pixels behind it, and
 * requires WCAG AA body contrast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h914dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkThemeTextContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkPrimaryTimerButtonLabelReadsOnItsContainer() {
        render(darkTheme = true, phase = TimerPhase.Focus)
        assertReadable("Start focus")
    }

    @Test
    fun darkBreakCardLabelsReadOnLavender() {
        render(darkTheme = true, phase = TimerPhase.ShortBreak)
        breakCardLabels.forEach(::assertReadable)
    }

    @Test
    fun darkDeleteUndoSnackbarReads() {
        render(darkTheme = true, phase = TimerPhase.Focus, pendingDeletion = deletedTask)
        // The undo snackbar lives on the Tasks tab only.
        openTab("Tasks")
        snackbarLabels.forEach(::assertReadable)
    }

    @Test
    @Config(qualifiers = TallScreen)
    fun darkFilledButtonLabelReadsOnPrimary() {
        render(darkTheme = true, phase = TimerPhase.Focus, network = openRoom)
        openTab("Network")
        assertReadable("Share")
    }

    // Ink and Violet containers stay dark in dark theme, so their labels
    // must not follow the on-primary colour.
    @Test
    @Config(qualifiers = TallScreen)
    fun darkButtonsOnInkAndVioletKeepLightLabels() {
        render(darkTheme = true, phase = TimerPhase.Focus)
        openTab("Tasks")
        typeInto("Task", "Alpha")
        assertReadable("ADD TASK")
        openTab("Network")
        listOf("CREATE IROH ROOM", "JOIN ROOM").forEach(::assertReadable)
    }

    @Test
    fun lightThemeKeepsTheSameSurfacesReadable() {
        render(darkTheme = false, phase = TimerPhase.ShortBreak, pendingDeletion = deletedTask)
        breakCardLabels.forEach(::assertReadable)
        openTab("Tasks")
        snackbarLabels.forEach(::assertReadable)
    }

    // The header is Ink in both themes; the light error role is dark red.
    @Test
    fun lightHeaderResetLocalAccountReadsOnInk() {
        render(darkTheme = false, phase = TimerPhase.Focus, localAccountResetRequired = true)
        assertReadable("Reset local account")
    }

    @Test
    fun darkHeaderResetLocalAccountReadsOnInk() {
        render(darkTheme = true, phase = TimerPhase.Focus, localAccountResetRequired = true)
        assertReadable("Reset local account")
    }

    // Destructive labels keep a danger colour in the dark theme. They were
    // white there, so "Delete" read as an ordinary action.
    @Test
    @Config(qualifiers = TallScreen)
    fun darkTaskRowDeleteKeepsDangerColour() {
        render(darkTheme = true, phase = TimerPhase.Focus, tasks = listOf(alphaTask))
        openTab("Tasks")
        assertDangerLabel("Delete")
    }

    @Test
    fun darkHeaderResetLocalAccountKeepsDangerColour() {
        render(darkTheme = true, phase = TimerPhase.Focus, localAccountResetRequired = true)
        assertDangerLabel("Reset local account")
    }

    // Landscape has no header; the reset action sits on the theme surface.
    @Test
    @Config(qualifiers = Landscape)
    fun darkLandscapeResetLocalAccountKeepsDangerColour() {
        render(darkTheme = true, phase = TimerPhase.Focus, localAccountResetRequired = true)
        assertDangerLabel("Reset local account")
    }

    @Test
    @Config(qualifiers = TallScreen)
    fun darkLeaveRoomKeepsDangerColourOnInk() {
        render(darkTheme = true, phase = TimerPhase.Focus, network = openRoom)
        openTab("Network")
        assertDangerLabel("Leave room")
    }

    @Test
    @Config(qualifiers = TallScreen)
    fun darkLeaveRoomDialogConfirmKeepsDangerColour() {
        render(darkTheme = true, phase = TimerPhase.Focus, network = openRoom)
        openTab("Network")
        compose.onNodeWithText("Leave room").performClick()
        assertDangerLabel("Leave and restore")
    }

    @Test
    fun darkAccountDialogDeleteAccountKeepsDangerColour() {
        render(darkTheme = true, phase = TimerPhase.Focus, user = signedInUser)
        compose.onNodeWithText("Account").performClick()
        assertDangerLabel("Delete account")
    }

    @Test
    fun darkSignOutDialogConfirmKeepsDangerColour() {
        render(darkTheme = true, phase = TimerPhase.Focus, user = signedInUser)
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithText("Sign out").performClick()
        assertDangerLabel("Sign out")
    }

    @Test
    @Config(qualifiers = TallScreen)
    fun lightDestructiveLabelsKeepDangerColour() {
        render(darkTheme = false, phase = TimerPhase.Focus, network = openRoom, tasks = listOf(alphaTask))
        openTab("Tasks")
        assertDangerLabel("Delete")
        openTab("Network")
        assertDangerLabel("Leave room")
    }

    @Test
    fun lightAccountDialogDeleteAccountKeepsDangerColour() {
        render(darkTheme = false, phase = TimerPhase.Focus, user = signedInUser)
        compose.onNodeWithText("Account").performClick()
        assertDangerLabel("Delete account")
    }

    private fun openTab(label: String) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(hasText(label) and tab).performClick()
    }

    private fun typeInto(fieldLabel: String, text: String) {
        compose.onNode(hasSetTextAction() and hasText(fieldLabel)).performTextInput(text)
    }

    private fun assertReadable(label: String) {
        val text = resolvedTextColor(label)
        val container = renderedContainerColor(label)
        val ratio = contrastRatio(text.compositeOver(container), container)
        assertTrue(
            "\"$label\" text ${hex(text)} on ${hex(container)} is %.2f:1, needs $AaBodyContrast:1".format(ratio),
            ratio >= AaBodyContrast,
        )
    }

    // A danger label is readable and red. White or an accent colour fails.
    private fun assertDangerLabel(label: String) {
        assertReadable(label)
        val text = resolvedTextColor(label)
        val redLead = text.red - maxOf(text.green, text.blue)
        assertTrue(
            "\"$label\" text ${hex(text)} is not a danger colour: red leads by %.2f, needs $DangerRedLead".format(redLead),
            redLead >= DangerRedLead,
        )
    }

    private fun resolvedTextColor(label: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    // Glyphs cover the smaller share of a label's box, so the most frequent
    // rendered pixel is the surface the label sits on.
    private fun renderedContainerColor(label: String): Color {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(frame)) }
        val box = node.boundsInRoot
        val counts = HashMap<Int, Int>()
        for (y in box.top.toInt() until box.bottom.toInt()) {
            for (x in box.left.toInt() until box.right.toInt()) counts.merge(frame.getPixel(x, y), 1, Int::plus)
        }
        return Color(counts.maxBy { it.value }.key)
    }

    private fun hex(color: Color) = "#%08X".format(color.value.shr(32).toLong())

    private fun render(
        darkTheme: Boolean,
        phase: String,
        pendingDeletion: PendingTaskDeletion? = null,
        network: IrohNetworkState = IrohNetworkState(),
        localAccountResetRequired: Boolean = false,
        tasks: List<FocusTask> = emptyList(),
        user: User? = null,
    ) {
        val state = AppState(
            ready = true,
            authStatus = if (user == null) AuthStatus.SignedOut else AuthStatus.SignedIn,
            user = user,
            tasks = tasks,
            taskSummaries = tasks.map { TaskDailySummary(it, 0, 0L) },
            settings = TimerSettings(selectedPhase = phase),
            network = network,
            localAccountResetRequired = localAccountResetRequired,
        )
        compose.setContent {
            PomodoroughTheme(darkTheme = darkTheme) {
                PomodoroughScreen(
                    state = state, onSignIn = {}, onLogout = {}, onResetLocalAccount = {}, onRefresh = {},
                    onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {}, onSelectPhase = {},
                    onChangeDuration = { _, _ -> }, onSetAutoStart = {}, onSelectTask = {},
                    onAddTask = { _, _ -> }, onDeleteTask = {}, onResolveHistory = {},
                    onRecoverHistoryResolution = {}, onConfirmAccountSwitch = {}, onCancelAccountSwitch = {},
                    onDismissConflict = {}, onDismissNotice = {}, onSetReplicationMode = {},
                    onCreateIrohRoom = {}, onJoinIrohRoom = {}, onLeaveIrohRoom = {},
                    onRefreshIrohInvite = {}, onSyncIrohNow = {}, onShareIrohInvite = {},
                    onRetryStartup = {},
                    taskEditing = TaskEditingActions(pendingDeletion = pendingDeletion),
                    joinInviteDraft = "pomodorough1-invite",
                )
            }
        }
    }

    private companion object {
        const val AaBodyContrast = 4.5

        // Red channel lead over green and blue. White is 0.00 and Butter is
        // 0.14; the light and dark danger colours are 0.29 or more.
        const val DangerRedLead = 0.2

        // Tall enough to lay out the whole Network tab, so every label has
        // rendered pixels behind it.
        const val TallScreen = "en-w411dp-h2400dp"
        const val Landscape = "en-w914dp-h411dp-land"
        val alphaTask = FocusTask("task-alpha", "Alpha")
        val signedInUser = User("user-1", "rider@example.test", "Rider", "")
        val openRoom = IrohNetworkState(
            mode = ReplicationMode.IROH,
            status = IrohConnectionStatus.SYNCING,
            roomId = "room-1",
            roomName = "Platform nine",
            invite = "pomodorough-invite-abcdefghijklmnopqrstuvwxyz",
        )
        val deletedTask = PendingTaskDeletion("task-alpha", "Alpha")
        val breakCardLabels = listOf("Start short break", "FOCUS TASK", "No task", "Idle", "5 min")
        val snackbarLabels = listOf("Deleted “Alpha”", "Undo")
    }
}
