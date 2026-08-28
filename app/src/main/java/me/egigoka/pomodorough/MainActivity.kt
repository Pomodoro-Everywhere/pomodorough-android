package me.egigoka.pomodorough

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.data.auth.SystemGoogleCredentialProvider
import me.egigoka.pomodorough.ui.PomodoroughScreen
import me.egigoka.pomodorough.ui.PomodoroughTheme
import me.egigoka.pomodorough.ui.PomodoroughViewModel

class MainActivity : ComponentActivity() {
    private var showNotificationIntro by mutableStateOf(false)
    private var showNotificationRecovery by mutableStateOf(false)
    private var showExactAlarmFallback by mutableStateOf(false)
    private val viewModel by viewModels<PomodoroughViewModel> {
        PomodoroughViewModel.Factory(
            (application as PomodoroughApplication).timerRepository,
        )
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) toggleWithAlarmDisclosure() else showNotificationRecovery = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeAppLifecycle()
        setContent { PomodoroughContent() }
    }

    private fun observeAppLifecycle() {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        })
    }

    @Composable
    private fun PomodoroughContent() {
        val state by viewModel.state.collectAsStateWithLifecycle()
        PomodoroughTheme {
            PomodoroughScreen(
                state = state,
                onSignIn = ::signIn,
                onLogout = viewModel::logout,
                onResetLocalAccount = viewModel::resetLocalAccount,
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
                onDeleteAccount = viewModel::deleteAccount,
                onOpenPrivacy = ::openPrivacyPolicy,
                onStopSound = ::stopSound,
            )
            PermissionDialogs()
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
            TimerNotificationPermissionAction.RequestPermission -> showNotificationIntro = true
            TimerNotificationPermissionAction.ToggleTimer -> toggleWithAlarmDisclosure()
        }
    }

    private fun requestNotificationPermission() {
        showNotificationIntro = false
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toggleWithAlarmDisclosure() {
        val exact = Build.VERSION.SDK_INT < 31 ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        if (me.egigoka.pomodorough.timer.ExactAlarmDisclosurePolicy.usesInexactFallback(
                sdkInt = Build.VERSION.SDK_INT,
                canScheduleExactAlarms = exact,
                timerStatus = viewModel.state.value.timer?.status,
            )
        ) {
            showExactAlarmFallback = true
        } else {
            viewModel.toggleTimer()
        }
    }

    private fun openNotificationSettings() {
        showNotificationRecovery = false
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openExactAlarmSettings() {
        showExactAlarmFallback = false
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    @Composable
    private fun PermissionDialogs() {
        if (showNotificationIntro) NotificationIntroDialog()
        if (showNotificationRecovery) NotificationRecoveryDialog()
        if (showExactAlarmFallback) ExactAlarmFallbackDialog()
    }

    @Composable
    private fun NotificationIntroDialog() {
        AlertDialog(
            onDismissRequest = { showNotificationIntro = false },
            title = { Text(stringResource(R.string.notification_intro_title)) },
            text = { Text(stringResource(R.string.notification_intro_body)) },
            confirmButton = {
                TextButton(onClick = ::requestNotificationPermission) {
                    Text(stringResource(R.string.continue_label))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationIntro = false
                    showNotificationRecovery = true
                }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }

    @Composable
    private fun NotificationRecoveryDialog() {
        AlertDialog(
            onDismissRequest = { showNotificationRecovery = false },
            title = { Text(stringResource(R.string.notification_denied_title)) },
            text = { Text(stringResource(R.string.notification_denied_body)) },
            confirmButton = {
                TextButton(onClick = ::openNotificationSettings) {
                    Text(stringResource(R.string.open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationRecovery = false
                    toggleWithAlarmDisclosure()
                }) { Text(stringResource(R.string.continue_without_notifications)) }
            },
        )
    }

    @Composable
    private fun ExactAlarmFallbackDialog() {
        AlertDialog(
            onDismissRequest = { showExactAlarmFallback = false },
            title = { Text(stringResource(R.string.exact_alarm_title)) },
            text = { Text(stringResource(R.string.exact_alarm_body)) },
            confirmButton = {
                TextButton(onClick = ::openExactAlarmSettings) {
                    Text(stringResource(R.string.open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExactAlarmFallback = false
                    viewModel.toggleTimer()
                }) { Text(stringResource(R.string.use_inexact_alarm)) }
            },
        )
    }

    private fun stopSound() {
        (application as PomodoroughApplication).timerRepository.stopCompletionAlert(
            viewModel.state.value.completionAlertTimerId,
        )
    }

    private fun copyIrohInvite(invite: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(getString(R.string.pomodorough_iroh_room_invite), invite)
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
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.pomodorough_iroh_room))
                    putExtra(Intent.EXTRA_TEXT, invite)
                },
                getString(R.string.share_room_invite),
            ),
        )
    }

    private fun openPrivacyPolicy() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pomodorough.egigoka.me/privacy")))
    }
}
