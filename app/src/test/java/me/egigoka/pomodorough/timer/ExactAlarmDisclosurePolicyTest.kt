package me.egigoka.pomodorough.timer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmDisclosurePolicyTest {
    @Test fun preAndroid12DoesNotNeedDisclosure() = assertFalse(
        ExactAlarmDisclosurePolicy.usesInexactFallback(30, false),
    )

    @Test fun android12WithoutAccessDisclosesFallback() = assertTrue(
        ExactAlarmDisclosurePolicy.usesInexactFallback(31, false),
    )

    @Test fun exactAccessDoesNotNeedDisclosure() = assertFalse(
        ExactAlarmDisclosurePolicy.usesInexactFallback(36, true),
    )

    @Test fun pausingRunningTimerDoesNotNeedDisclosure() = assertFalse(
        ExactAlarmDisclosurePolicy.usesInexactFallback(
            sdkInt = 36,
            canScheduleExactAlarms = false,
            timerStatus = me.egigoka.pomodorough.data.TimerStatus.Running,
        ),
    )
}
