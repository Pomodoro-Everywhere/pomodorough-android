package me.egigoka.pomodorough

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.data.auth.SystemGoogleCredentialProvider
import me.egigoka.pomodorough.ui.PomodoroughScreen
import me.egigoka.pomodorough.ui.PomodoroughTheme
import me.egigoka.pomodorough.ui.PomodoroughViewModel

class MainActivity : ComponentActivity() {
    private lateinit var pendingTimerAction: PendingTimerActionCoordinator
    private val pendingLaunchers = mutableMapOf<String, ActivityResultLauncher<*>>()
    private val viewModel by viewModels<PomodoroughViewModel> {
        PomodoroughViewModel.Factory(
            (application as PomodoroughApplication).timerRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePendingTimerAction()
        rebindPendingTimerAction()
        enableEdgeToEdge()
        observeAppLifecycle()
        setContent { PomodoroughContent() }
    }

    override fun onDestroy() {
        if (::pendingTimerAction.isInitialized) pendingTimerAction.detach()
        detachPendingLaunchers()
        super.onDestroy()
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
                onConfirmIrohIdentityRecovery = viewModel::confirmIrohIdentityRecovery,
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
            permissionGranted = notificationPermissionGranted(),
            timerStatus = viewModel.state.value.timer?.status,
        )
        handleTimerAction(
            pendingTimerAction.begin(decision == TimerNotificationPermissionAction.RequestPermission),
        )
    }

    private fun requestNotificationPermission(actionId: String) {
        handleTimerAction(pendingTimerAction.confirmNotificationIntro(actionId))
    }

    private fun evaluateExactAlarm(callback: PendingTimerActionCallbackIdentity) {
        val exact = Build.VERSION.SDK_INT < 31 ||
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val needsFallback =
            me.egigoka.pomodorough.timer.ExactAlarmDisclosurePolicy.usesInexactFallback(
                sdkInt = Build.VERSION.SDK_INT,
                canScheduleExactAlarms = exact,
                timerStatus = viewModel.state.value.timer?.status,
            )
        handleTimerAction(pendingTimerAction.exactAlarmResult(callback, needsFallback))
    }

    private fun openNotificationSettings(actionId: String) {
        handleTimerAction(pendingTimerAction.openNotificationSettings(actionId))
    }

    private fun openExactAlarmSettings(actionId: String) {
        handleTimerAction(pendingTimerAction.openExactAlarmSettings(actionId))
    }

    @Composable
    private fun PermissionDialogs() {
        val state = pendingTimerAction.state
        val actionId = state.actionId ?: return
        when (state.step) {
            PendingTimerActionStep.NotificationIntro -> NotificationIntroDialog(actionId)
            PendingTimerActionStep.NotificationRecovery -> NotificationRecoveryDialog(actionId)
            PendingTimerActionStep.ExactAlarmFallback -> ExactAlarmFallbackDialog(actionId)
            else -> Unit
        }
    }

    @Composable
    private fun NotificationIntroDialog(actionId: String) {
        AlertDialog(
            onDismissRequest = { pendingTimerAction.dismissDialog(actionId) },
            title = { Text(stringResource(R.string.notification_intro_title)) },
            text = { Text(stringResource(R.string.notification_intro_body)) },
            confirmButton = {
                TextButton(onClick = { requestNotificationPermission(actionId) }) {
                    Text(stringResource(R.string.continue_label))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    handleTimerAction(pendingTimerAction.declineNotificationIntro(actionId))
                }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }

    @Composable
    private fun NotificationRecoveryDialog(actionId: String) {
        AlertDialog(
            onDismissRequest = { pendingTimerAction.dismissDialog(actionId) },
            title = { Text(stringResource(R.string.notification_denied_title)) },
            text = { Text(stringResource(R.string.notification_denied_body)) },
            confirmButton = {
                TextButton(onClick = { openNotificationSettings(actionId) }) {
                    Text(stringResource(R.string.open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    handleTimerAction(pendingTimerAction.continueWithoutNotifications(actionId))
                }) { Text(stringResource(R.string.continue_without_notifications)) }
            },
        )
    }

    @Composable
    private fun ExactAlarmFallbackDialog(actionId: String) {
        AlertDialog(
            onDismissRequest = { pendingTimerAction.dismissDialog(actionId) },
            title = { Text(stringResource(R.string.exact_alarm_title)) },
            text = { Text(stringResource(R.string.exact_alarm_body)) },
            confirmButton = {
                TextButton(onClick = { openExactAlarmSettings(actionId) }) {
                    Text(stringResource(R.string.open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    handleTimerAction(pendingTimerAction.useInexactAlarm(actionId))
                }) { Text(stringResource(R.string.use_inexact_alarm)) }
            },
        )
    }

    private fun restorePendingTimerAction() {
        val restored = savedStateRegistry.consumeRestoredStateForKey(PendingTimerActionStateKey)
        pendingTimerAction = PendingTimerActionCoordinator.restore(restored?.pendingTimerActionState())
        savedStateRegistry.registerSavedStateProvider(PendingTimerActionStateKey) {
            pendingTimerAction.savedState().toBundle()
        }
    }

    private fun rebindPendingTimerAction() {
        val callback = pendingTimerAction.pendingCallback() ?: return
        when (pendingTimerAction.step) {
            PendingTimerActionStep.NotificationPermission -> bindNotificationPermission(callback)
            PendingTimerActionStep.NotificationSettings -> bindNotificationSettings(callback)
            PendingTimerActionStep.ExactAlarmSettings -> bindExactAlarmSettings(callback)
            PendingTimerActionStep.ExactAlarmCheck ->
                handleTimerAction(pendingTimerAction.resumeAfterRestore())
            else -> Unit
        }
    }

    private fun handleTimerAction(effect: PendingTimerActionEffect) {
        when (effect) {
            PendingTimerActionEffect.None -> Unit
            is PendingTimerActionEffect.CheckExactAlarm -> evaluateExactAlarm(effect.callback)
            is PendingTimerActionEffect.ToggleTimer -> viewModel.toggleTimer()
            is PendingTimerActionEffect.RequestNotificationPermission ->
                bindNotificationPermission(effect.callback).launch(Manifest.permission.POST_NOTIFICATIONS)
            is PendingTimerActionEffect.OpenNotificationSettings ->
                bindNotificationSettings(effect.callback).launch(notificationSettingsIntent())
            is PendingTimerActionEffect.OpenExactAlarmSettings ->
                bindExactAlarmSettings(effect.callback).launch(exactAlarmSettingsIntent())
        }
    }

    private fun bindNotificationPermission(
        callback: PendingTimerActionCallbackIdentity,
    ): ActivityResultLauncher<String> = registerPendingResult(
        key = callback.launcherKey(NotificationPermissionLauncherKey),
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        handleTimerAction(pendingTimerAction.notificationPermissionResult(callback, granted))
    }

    private fun bindNotificationSettings(
        callback: PendingTimerActionCallbackIdentity,
    ): ActivityResultLauncher<Intent> = registerPendingResult(
        key = callback.launcherKey(NotificationSettingsLauncherKey),
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = notificationPermissionGranted()
        handleTimerAction(pendingTimerAction.notificationSettingsResult(callback, granted))
    }

    private fun bindExactAlarmSettings(
        callback: PendingTimerActionCallbackIdentity,
    ): ActivityResultLauncher<Intent> = registerPendingResult(
        key = callback.launcherKey(ExactAlarmSettingsLauncherKey),
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        handleTimerAction(pendingTimerAction.exactAlarmSettingsResult(callback))
    }

    private fun <Input, Output> registerPendingResult(
        key: String,
        contract: ActivityResultContract<Input, Output>,
        onResult: (Output) -> Unit,
    ): ActivityResultLauncher<Input> {
        var deliveredDuringRegistration = false
        val launcher = activityResultRegistry.register(key, contract) { result ->
            val registered = pendingLaunchers.remove(key)
            if (registered == null) deliveredDuringRegistration = true else registered.unregister()
            onResult(result)
        }
        if (deliveredDuringRegistration) launcher.unregister() else pendingLaunchers[key] = launcher
        return launcher
    }

    private fun detachPendingLaunchers() {
        val launchers = pendingLaunchers.values.toList()
        pendingLaunchers.clear()
        launchers.forEach { it.unregister() }
    }

    private fun notificationPermissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun notificationSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    )

    private fun exactAlarmSettingsIntent(): Intent = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:$packageName"),
    )

    private fun stopSound() {
        (application as PomodoroughApplication).timerRepository.stopCompletionAlert(
            viewModel.state.value.completionAlertTimerId,
        )
    }

    private fun shareIrohInvite(invite: String) {
        val shareContent = irohInviteShareContent(invite, getString(R.string.pomodorough_iroh_room))
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = shareContent.mimeType
                    putExtra(Intent.EXTRA_SUBJECT, shareContent.subject)
                    putExtra(Intent.EXTRA_TEXT, shareContent.text)
                },
                getString(R.string.share_room_invite),
            ),
        )
    }

    private fun openPrivacyPolicy() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pomodorough.egigoka.me/privacy")))
    }
}

private const val PendingTimerActionStateKey = "pending-timer-action"
private const val PendingTimerActionStepKey = "step"
private const val PendingTimerActionIdKey = "action-id"
private const val PendingTimerActionLaunchIdKey = "launch-id"
private const val NotificationPermissionLauncherKey = "notification-permission"
private const val NotificationSettingsLauncherKey = "notification-settings"
private const val ExactAlarmSettingsLauncherKey = "exact-alarm-settings"

private fun PendingTimerActionCallbackIdentity.launcherKey(kind: String): String =
    "$PendingTimerActionStateKey:$kind:$actionId:$launchId"

private fun Bundle.pendingTimerActionState() = PendingTimerActionSavedState(
    stepName = getString(PendingTimerActionStepKey).orEmpty(),
    actionId = getString(PendingTimerActionIdKey),
    launchId = getString(PendingTimerActionLaunchIdKey),
)

private fun PendingTimerActionSavedState.toBundle() = Bundle().apply {
    putString(PendingTimerActionStepKey, stepName)
    putString(PendingTimerActionIdKey, actionId)
    putString(PendingTimerActionLaunchIdKey, launchId)
}

internal data class IrohInviteShareContent(
    val mimeType: String,
    val subject: String,
    val text: String,
)

internal fun irohInviteShareContent(invite: String, subject: String) = IrohInviteShareContent(
    mimeType = "text/plain",
    subject = subject,
    text = invite,
)
