package me.egigoka.pomodorough

import me.egigoka.pomodorough.data.TimerStatus

enum class TimerNotificationPermissionAction {
    RequestPermission,
    ToggleTimer,
}

object TimerNotificationPermissionPolicy {
    fun decide(
        sdkInt: Int,
        permissionGranted: Boolean,
        timerStatus: String?,
    ): TimerNotificationPermissionAction =
        if (sdkInt >= 33 && !permissionGranted && timerStatus != TimerStatus.Running) {
            TimerNotificationPermissionAction.RequestPermission
        } else {
            TimerNotificationPermissionAction.ToggleTimer
        }
}
