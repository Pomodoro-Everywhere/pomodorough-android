package me.egigoka.pomodorough.timer

import me.egigoka.pomodorough.data.TimerStatus
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
            timerStatus = TimerStatus.Running,
        ),
    )

    @Test fun resumingPausedTimerDisclosesUntilFallbackAccepted() = assertTrue(
        ExactAlarmDisclosurePolicy.usesInexactFallback(
            sdkInt = 36,
            canScheduleExactAlarms = false,
            timerStatus = TimerStatus.Paused,
            fallbackAccepted = false,
        ),
    )

    @Test fun acceptedFallbackSilencesDisclosureOnResume() = assertFalse(
        ExactAlarmDisclosurePolicy.usesInexactFallback(
            sdkInt = 36,
            canScheduleExactAlarms = false,
            timerStatus = TimerStatus.Paused,
            fallbackAccepted = true,
        ),
    )

    @Test fun acceptedFallbackSilencesDisclosureOnFreshStart() = assertFalse(
        ExactAlarmDisclosurePolicy.usesInexactFallback(
            sdkInt = 36,
            canScheduleExactAlarms = false,
            timerStatus = null,
            fallbackAccepted = true,
        ),
    )
}
