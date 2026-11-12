package me.egigoka.pomodorough.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.ResolutionRecovery
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.ReplicationMode

@Composable
fun PomodoroughScreen(
    state: AppState, onSignIn: () -> Unit, onLogout: () -> Unit,
    onResetLocalAccount: () -> Unit, onRefresh: () -> Unit,
    onToggleTimer: () -> Unit, onFinishTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onSelectPhase: (String) -> Unit, onChangeDuration: (String, Int) -> Unit,
    onSetAutoStart: (Boolean) -> Unit, onSelectTask: (String?) -> Unit,
    onAddTask: (String, (Boolean) -> Unit) -> Unit, onDeleteTask: (String) -> Unit,
    onResolveHistory: (BootstrapStrategy) -> Unit, onRecoverHistoryResolution: () -> Unit,
    onConfirmAccountSwitch: () -> Unit, onCancelAccountSwitch: () -> Unit,
    onDismissConflict: () -> Unit, onDismissNotice: () -> Unit,
    onSetReplicationMode: (ReplicationMode) -> Unit, onCreateIrohRoom: (String) -> Unit,
    onJoinIrohRoom: (String) -> Unit, onLeaveIrohRoom: () -> Unit,
    onRefreshIrohInvite: () -> Unit, onSyncIrohNow: () -> Unit,
    onConfirmIrohIdentityRecovery: () -> Unit = {},
    onShareIrohInvite: (String) -> Unit,
    onRetryStartup: () -> Unit,
    onDeleteAccount: (String) -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onStopSound: () -> Unit = {},
    joinInviteDraft: String = "",
    onJoinInviteChange: (String) -> Unit = {},
    taskEditing: TaskEditingActions = TaskEditingActions(),
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!state.ready) {
            if (state.localStorageReadFailed) LocalStorageFailureScreen(onRetryStartup) else LoadingScreen()
        } else {
            TimerScreen(state, TimerScreenActions(
                onSignIn, onLogout, onResetLocalAccount, onRefresh, onToggleTimer,
                onFinishTimer, onCancelTimer, onStopSound, onSelectPhase,
                onChangeDuration, onSetAutoStart, onSelectTask, onAddTask, onDeleteTask,
                onResolveHistory, onRecoverHistoryResolution, onConfirmAccountSwitch,
                onCancelAccountSwitch, onDismissConflict, onDismissNotice,
                onSetReplicationMode, onCreateIrohRoom, onJoinIrohRoom, onLeaveIrohRoom,
                onRefreshIrohInvite, onSyncIrohNow, onConfirmIrohIdentityRecovery,
                onShareIrohInvite,
                onDeleteAccount, onOpenPrivacy,
                joinInviteDraft, onJoinInviteChange, taskEditing,
            ))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(88.dp),
                containerColor = Lavender,
                indicatorColor = Violet,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.syncing_your_clock),
                color = darkModeTextColor(Cloud),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

internal data class TimerScreenActions(
    val onSignIn: () -> Unit, val onLogout: () -> Unit, val onResetLocalAccount: () -> Unit,
    val onRefresh: () -> Unit, val onToggleTimer: () -> Unit, val onFinishTimer: () -> Unit,
    val onCancelTimer: () -> Unit, val onStopSound: () -> Unit,
    val onSelectPhase: (String) -> Unit, val onChangeDuration: (String, Int) -> Unit,
    val onSetAutoStart: (Boolean) -> Unit, val onSelectTask: (String?) -> Unit,
    val onAddTask: (String, (Boolean) -> Unit) -> Unit, val onDeleteTask: (String) -> Unit,
    val onResolveHistory: (BootstrapStrategy) -> Unit, val onRecoverHistoryResolution: () -> Unit,
    val onConfirmAccountSwitch: () -> Unit, val onCancelAccountSwitch: () -> Unit,
    val onDismissConflict: () -> Unit, val onDismissNotice: () -> Unit,
    val onSetReplicationMode: (ReplicationMode) -> Unit, val onCreateIrohRoom: (String) -> Unit,
    val onJoinIrohRoom: (String) -> Unit, val onLeaveIrohRoom: () -> Unit,
    val onRefreshIrohInvite: () -> Unit, val onSyncIrohNow: () -> Unit,
    val onConfirmIrohIdentityRecovery: () -> Unit,
    val onShareIrohInvite: (String) -> Unit,
    val onDeleteAccount: (String) -> Unit, val onOpenPrivacy: () -> Unit,
    val joinInviteDraft: String = "",
    val onJoinInviteChange: (String) -> Unit = {},
    val taskEditing: TaskEditingActions = TaskEditingActions(),
)
