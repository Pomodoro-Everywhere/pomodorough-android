package me.egigoka.pomodorough.timer

import me.egigoka.pomodorough.data.TimerStatus

object ExactAlarmDisclosurePolicy {
    fun usesInexactFallback(
        sdkInt: Int,
        canScheduleExactAlarms: Boolean,
        timerStatus: String? = null,
        fallbackAccepted: Boolean = false,
    ): Boolean = sdkInt >= 31 &&
        !canScheduleExactAlarms &&
        !fallbackAccepted &&
        timerStatus != TimerStatus.Running
}
