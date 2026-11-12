package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.NoticeAction
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX round 2, A3: on the production Timer screen the primary control (Start,
 * Pause, Resume) and the countdown are both fully on screen before any
 * scrolling, on a small phone, in landscape, and at the largest text size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = SmallPortrait)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimerPrimaryControlVisibleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun smallPortraitShowsStartWithCountdown() {
        render(fontScale = 1f)
        assertFullyOnScreen("Start focus")
    }

    @Test
    fun smallPortraitLargestTextShowsStartWithCountdown() {
        render(fontScale = 2f)
        assertFullyOnScreen("Start focus")
    }

    @Test
    fun smallPortraitLargestTextShowsResumeWithCountdown() {
        render(fontScale = 2f, timer = timer(TimerStatus.Paused))
        assertFullyOnScreen("Resume")
    }

    @Test
    fun smallPortraitLargestTextShowsPauseWithCountdown() {
        render(fontScale = 2f, timer = timer(TimerStatus.Running))
        assertFullyOnScreen("Pause")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeShowsStartWithCountdown() {
        render(fontScale = 1f)
        assertFullyOnScreen("Start focus")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeLargestTextShowsStartWithCountdown() {
        render(fontScale = 2f)
        assertFullyOnScreen("Start focus")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeLargestTextShowsResumeWithCountdown() {
        render(fontScale = 2f, timer = timer(TimerStatus.Paused))
        assertFullyOnScreen("Resume")
    }

    // UX round 2, B1: Robolectric draws no system bars, so the A3 landscape
    // tests had 76 dp more height than a device. These cases take that height away.
    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeLargestTextUnderSystemBarsShowsStartWithCountdown() {
        render(fontScale = 2f, systemBars = DeviceSystemBars)
        assertFullyOnScreen("Start focus")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeLargestTextUnderSystemBarsShowsPauseWithCountdown() {
        render(fontScale = 2f, timer = timer(TimerStatus.Running), systemBars = DeviceSystemBars)
        assertFullyOnScreen("Pause")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeLargestTextUnderSystemBarsShowsResumeWithCountdown() {
        render(fontScale = 2f, timer = timer(TimerStatus.Paused), systemBars = DeviceSystemBars)
        assertFullyOnScreen("Resume")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeUnderSystemBarsShowsStartWithCountdown() {
        render(fontScale = 1f, systemBars = DeviceSystemBars)
        assertFullyOnScreen("Start focus")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeLargestTextWithLongFocusTaskShowsStartWithCountdown() {
        render(fontScale = 2f, systemBars = DeviceSystemBars, focusTaskTitle = LongFocusTaskTitle)
        assertFullyOnScreen("Start focus")
    }

    @Test
    fun smallPortraitLargestTextWithLongFocusTaskShowsStartWithCountdown() {
        render(fontScale = 2f, systemBars = DeviceSystemBars, focusTaskTitle = LongFocusTaskTitle)
        assertFullyOnScreen("Start focus")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeWithNoticeShowsStartWithCountdown() {
        render(fontScale = 1f, systemBars = DeviceSystemBars, notice = "Could not restore your account.")
        assertFullyOnScreen("Start focus")
    }

    @Test
    @Config(qualifiers = SmallLandscape)
    fun smallLandscapeLargestTextWithNoticeShowsStartWithCountdown() {
        render(fontScale = 2f, systemBars = DeviceSystemBars, notice = "Could not restore your account.")
        assertFullyOnScreen("Start focus")
    }

    // UX round 2, M2: a notice with Retry is taller than the 64 dp the dial
    // used to give up for messages, so Start went below the fold.
    @Test
    fun smallPortraitWithRetryNoticeShowsStartWithCountdown() {
        render(fontScale = 1f, systemBars = DeviceSystemBars, notice = RestoreFailedNotice, retry = true)
        compose.onNodeWithText("Retry", useUnmergedTree = true).assertExists()
        assertFullyOnScreen("Start focus")
    }

    @Test
    fun smallPortraitLargeTextWithRetryNoticeShowsStartWithCountdown() {
        render(fontScale = 1.3f, systemBars = DeviceSystemBars, notice = RestoreFailedNotice, retry = true)
        compose.onNodeWithText("Retry", useUnmergedTree = true).assertExists()
        assertFullyOnScreen("Start focus")
    }

    @Test
    fun smallPortraitLargestTextWithRetryNoticeShowsStartWithCountdown() {
        render(fontScale = 2f, systemBars = DeviceSystemBars, notice = RestoreFailedNotice, retry = true)
        compose.onNodeWithText("Retry", useUnmergedTree = true).assertExists()
        assertFullyOnScreen("Start focus")
    }

    @Test
    @Config(qualifiers = RegularPortrait)
    fun regularPortraitLargestTextWithRetryNoticeShowsStartWithCountdown() {
        render(fontScale = 2f, systemBars = DeviceSystemBars, notice = RestoreFailedNotice, retry = true)
        compose.onNodeWithText("Retry", useUnmergedTree = true).assertExists()
        assertFullyOnScreen("Start focus")
    }

    private fun assertFullyOnScreen(primaryLabel: String) {
        assertUnclipped(primaryLabel, compose.onNodeWithText(primaryLabel))
        assertUnclipped("countdown", compose.onNode(hasContentDescription("remaining", substring = true)))
    }

    // B2 (AND-25): a 132 dp ring cannot hold a countdown and a phase title at
    // twice the text size, so the dial stacks them instead of overflowing.
    @Test
    fun smallPortraitLargestTextStacksTheDialInsteadOfOverflowingTheRing() {
        render(fontScale = 2f, systemBars = DeviceSystemBars)
        val dial = dialSize()
        assertTrue("dial is ${dial.width} x ${dial.height} px, still a ring", dial.width > dial.height)
    }

    @Test
    fun smallPortraitKeepsTheRingAtDefaultTextSize() {
        render(fontScale = 1f, systemBars = DeviceSystemBars)
        val dial = dialSize()
        assertTrue("dial is ${dial.width} x ${dial.height} px, not a ring", dial.width == dial.height)
    }

    @Test
    @Config(qualifiers = RegularPortrait)
    fun regularPortraitLargestTextKeepsTheRing() {
        render(fontScale = 2f, systemBars = DeviceSystemBars)
        val dial = dialSize()
        assertTrue("dial is ${dial.width} x ${dial.height} px, not a ring", dial.width == dial.height)
    }

    private fun dialSize() =
        compose.onNode(hasContentDescription("remaining", substring = true)).fetchSemanticsNode().size

    // boundsInRoot is cut by every scrolling or clipping parent. A node that
    // keeps its full size there is on screen without scrolling.
    private fun assertUnclipped(name: String, interaction: SemanticsNodeInteraction) {
        val node = interaction.fetchSemanticsNode()
        val shown = node.boundsInRoot
        assertTrue(
            "$name shows ${shown.width} x ${shown.height} px of ${node.size.width} x ${node.size.height} px " +
                "at ${node.positionInRoot} before any scrolling",
            shown.height >= node.size.height - 1f && shown.width >= node.size.width - 1f,
        )
    }

    private fun timer(status: String) = CanonicalTimer(
        id = "timer-a3",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = 25 * 60_000L,
        elapsedAtAnchorMs = 0L,
        anchorAt = "2026-11-11T00:00:00Z",
    )

    private fun render(
        fontScale: Float,
        timer: CanonicalTimer? = null,
        systemBars: SystemBars = SystemBars(),
        focusTaskTitle: String? = null,
        notice: String? = null,
        retry: Boolean = false,
    ) {
        val tasks = listOfNotNull(focusTaskTitle?.let { FocusTask(id = "task-b1", title = it) })
        val state = AppState(
            ready = true, authStatus = AuthStatus.SignedOut, timer = timer,
            tasks = tasks, selectedTaskId = tasks.firstOrNull()?.id, notice = notice,
            noticeAction = NoticeAction.RetryAccountRestore.takeIf { retry },
        )
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalConfiguration provides config,
            ) {
                PomodoroughTheme(darkTheme = false) {
                    Box(Modifier.padding(top = systemBars.top, bottom = systemBars.bottom)) { TimerScreen(state) }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun TimerScreen(state: AppState) {
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
        )
    }
}

private data class SystemBars(val top: Dp = 0.dp, val bottom: Dp = 0.dp)

// Measured on the audit emulator (API 36) at 569 x 320 dp: the hero card ran
// from 60 dp to 287.5 dp with 8 dp of screen padding on each side.
private val DeviceSystemBars = SystemBars(top = 52.dp, bottom = 24.dp)

// The longest account-restore sentence that offers Retry (`account_restore_failed_offline`).
private const val RestoreFailedNotice =
    "Could not reach the server to confirm your account. Check your connection, then try again."

private const val LongFocusTaskTitle =
    "Prepare the quarterly planning review with every team lead before the release freeze"

// The smallest phone the UX audit used: 320 x 569 dp, and the same turned.
private const val SmallPortrait = "en-w320dp-h569dp-port"
private const val SmallLandscape = "en-w569dp-h320dp-land"
private const val RegularPortrait = "en-w411dp-h914dp-port"
