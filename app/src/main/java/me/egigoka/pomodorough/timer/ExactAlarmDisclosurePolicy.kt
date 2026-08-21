package me.egigoka.pomodorough.timer

import me.egigoka.pomodorough.data.TimerStatus

object ExactAlarmDisclosurePolicy {
    fun usesInexactFallback(
        sdkInt: Int,
        canScheduleExactAlarms: Boolean,
        timerStatus: String? = null,
    ): Boolean = sdkInt >= 31 && !canScheduleExactAlarms && timerStatus != TimerStatus.Running
}
