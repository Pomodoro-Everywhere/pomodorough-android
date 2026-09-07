package me.egigoka.pomodorough.crash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingConsentTest {
    @Test
    fun initSkippedWhenUserOptsOutEvenWithDsn() {
        assertFalse(CrashReportingConsent.shouldStart("https://key@sentry.io/1", false))
    }

    @Test
    fun initSkippedWhenDsnBlankEvenWhenOptedIn() {
        assertFalse(CrashReportingConsent.shouldStart("", true))
        assertFalse(CrashReportingConsent.shouldStart("   ", true))
    }

    @Test
    fun initStartsOnlyWithDsnAndOptIn() {
        assertTrue(CrashReportingConsent.shouldStart("https://key@sentry.io/1", true))
    }

    @Test
    fun reportingIsOnByDefault() {
        assertTrue(CrashReportingConsent.DEFAULT_ENABLED)
    }
}
