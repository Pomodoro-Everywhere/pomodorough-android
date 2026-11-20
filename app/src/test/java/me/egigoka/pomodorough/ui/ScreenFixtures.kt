package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import me.egigoka.pomodorough.data.AppState

// UX round 3 layout tests: the production screen with no-op callbacks, and a
// font scale applied the way the system applies it.
@Composable
internal fun StaticPomodoroughScreen(state: AppState, taskEditing: TaskEditingActions = TaskEditingActions()) {
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
        taskEditing = taskEditing,
    )
}

@Composable
internal fun AtFontScale(fontScale: Float, content: @Composable () -> Unit) {
    val config = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
    CompositionLocalProvider(
        LocalDensity provides Density(LocalDensity.current.density, fontScale),
        LocalConfiguration provides config,
        content = content,
    )
}
