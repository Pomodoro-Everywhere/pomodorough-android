package me.egigoka.pomodorough

import android.Manifest
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import me.egigoka.pomodorough.ui.PomodoroughScreen
import me.egigoka.pomodorough.ui.PomodoroughTheme
import me.egigoka.pomodorough.ui.PomodoroughViewModel
import me.egigoka.pomodorough.data.auth.SystemGoogleCredentialProvider

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<PomodoroughViewModel> {
        PomodoroughViewModel.Factory(
            (application as PomodoroughApplication).timerRepository,
        )
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.toggleTimer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        })
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            PomodoroughTheme {
                PomodoroughScreen(
                    state = state,
                    onSignIn = ::signIn,
                    onLogout = viewModel::logout,
                    onRefresh = viewModel::refresh,
                    onToggleTimer = ::startOrToggleTimer,
                    onFinishTimer = viewModel::finishTimer,
                    onCancelTimer = viewModel::cancelTimer,
                    onClearTimer = viewModel::clearTimer,
                    onSelectPhase = viewModel::selectPhase,
                    onChangeDuration = viewModel::changeDuration,
                    onSetAutoStart = viewModel::setAutoStart,
                    onSelectTask = viewModel::selectTask,
                    onAddTask = viewModel::addTask,
                    onDeleteTask = viewModel::deleteTask,
                    onResolveHistory = viewModel::resolveHistory,
                    onRecoverHistoryResolution = viewModel::recoverHistoryResolution,
                    onConfirmAccountSwitch = viewModel::confirmAccountSwitch,
                    onCancelAccountSwitch = viewModel::cancelAccountSwitch,
                    onDismissConflict = viewModel::dismissConflict,
                    onDismissNotice = viewModel::dismissNotice,
                    onSetReplicationMode = viewModel::setReplicationMode,
                    onCreateIrohRoom = viewModel::createIrohRoom,
                    onJoinIrohRoom = viewModel::joinIrohRoom,
                    onLeaveIrohRoom = viewModel::leaveIrohRoom,
                    onRefreshIrohInvite = viewModel::refreshIrohInvite,
                    onSyncIrohNow = viewModel::syncIrohNow,
                    onCopyIrohInvite = ::copyIrohInvite,
                    onShareIrohInvite = ::shareIrohInvite,
                )
            }
        }
    }

    private fun signIn() {
        lifecycleScope.launch {
            viewModel.signIn(SystemGoogleCredentialProvider(this@MainActivity))
        }
    }

    private fun startOrToggleTimer() {
        val decision = TimerNotificationPermissionPolicy.decide(
            sdkInt = Build.VERSION.SDK_INT,
            permissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            timerStatus = viewModel.state.value.timer?.status,
        )
        when (decision) {
            TimerNotificationPermissionAction.RequestPermission ->
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            TimerNotificationPermissionAction.ToggleTimer -> viewModel.toggleTimer()
        }
    }

    private fun copyIrohInvite(invite: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("Pomodorough Iroh room invite", invite)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        lifecycleScope.launch {
            delay(60_000L)
            val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            if (current == invite) {
                if (Build.VERSION.SDK_INT >= 28) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    private fun shareIrohInvite(invite: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Pomodorough Iroh room")
                    putExtra(Intent.EXTRA_TEXT, invite)
                },
                "Share room invite",
            ),
        )
    }
}
