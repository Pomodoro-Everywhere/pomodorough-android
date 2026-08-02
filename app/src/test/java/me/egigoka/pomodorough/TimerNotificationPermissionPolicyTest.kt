package me.egigoka.pomodorough

import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerNotificationPermissionPolicyTest {
    @Test
    fun preNotificationPermissionPlatformsAlwaysToggle() {
        listOf(
            null,
            TimerStatus.Running,
            TimerStatus.Paused,
            TimerStatus.Completed,
            TimerStatus.Superseded,
        ).forEach { status ->
            assertDecision(
                TimerNotificationPermissionAction.ToggleTimer,
                sdkInt = 32,
                permissionGranted = false,
                timerStatus = status,
            )
        }
    }

    @Test
    fun grantedPermissionAlwaysToggles() {
        listOf(
            null,
            TimerStatus.Running,
            TimerStatus.Paused,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        ).forEach { status ->
            assertDecision(
                TimerNotificationPermissionAction.ToggleTimer,
                sdkInt = 33,
                permissionGranted = true,
                timerStatus = status,
            )
        }
    }

    @Test
    fun deniedPermissionRequestsBeforeStartingOrResuming() {
        listOf(
            null,
            TimerStatus.Paused,
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        ).forEach { status ->
            assertDecision(
                TimerNotificationPermissionAction.RequestPermission,
                sdkInt = 33,
                permissionGranted = false,
                timerStatus = status,
            )
        }
    }

    @Test
    fun deniedPermissionDoesNotBlockPausingRunningTimer() {
        assertDecision(
            TimerNotificationPermissionAction.ToggleTimer,
            sdkInt = 35,
            permissionGranted = false,
            timerStatus = TimerStatus.Running,
        )
    }

    private fun assertDecision(
        expected: TimerNotificationPermissionAction,
        sdkInt: Int,
        permissionGranted: Boolean,
        timerStatus: String?,
    ) {
        assertEquals(
            expected,
            TimerNotificationPermissionPolicy.decide(
                sdkInt = sdkInt,
                permissionGranted = permissionGranted,
                timerStatus = timerStatus,
            ),
        )
    }
}
