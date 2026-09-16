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
    fun initSkippedWhenInstrumentedTestEvenWithDsnAndOptIn() {
        assertFalse(
            CrashReportingConsent.shouldStart(
                "https://key@sentry.io/1",
                true,
                isInstrumentedTest = true,
                isCiOnEmulator = false,
            ),
        )
    }

    @Test
    fun initSkippedWhenCiOnEmulatorEvenWithDsnAndOptIn() {
        assertFalse(
            CrashReportingConsent.shouldStart(
                "https://key@sentry.io/1",
                true,
                isInstrumentedTest = false,
                isCiOnEmulator = true,
            ),
        )
    }

    @Test
    fun initStartsOnRealDeviceEvenWithCiSignal() {
        assertTrue(
            CrashReportingConsent.shouldStart(
                "https://key@sentry.io/1",
                true,
                isInstrumentedTest = false,
                isCiOnEmulator = false,
            ),
        )
    }

    @Test
    fun ciDetectedOnlyForTrueFlag() {
        assertTrue(CrashReportingConsent.isCiBuild("true"))
        assertTrue(CrashReportingConsent.isCiBuild("TRUE"))
        assertFalse(CrashReportingConsent.isCiBuild(null))
        assertFalse(CrashReportingConsent.isCiBuild(""))
        assertFalse(CrashReportingConsent.isCiBuild("false"))
        assertFalse(CrashReportingConsent.isCiBuild("1"))
    }

    @Test
    fun emulatorDetectedForKnownSignaturesOnly() {
        assertTrue(
            CrashReportingConsent.isEmulatorBuild(
                "generic/sdk_gphone64_arm64/emulator64_arm64:14/UE1A/1234567:userdebug/test-keys",
                "ranchu",
                "sdk_gphone64_arm64",
                "Google",
                "generic",
                "emulator64_arm64",
                "sdk_gphone64_arm64",
            ),
        )
        assertFalse(
            CrashReportingConsent.isEmulatorBuild(
                "google/cheetah/cheetah:14/UP1A.231105.003/11010456:user/release-keys",
                "cheetah",
                "Pixel 7 Pro",
                "Google",
                "google",
                "cheetah",
                "cheetah",
            ),
        )
    }

    @Test
    fun reportingIsOnByDefault() {
        assertTrue(CrashReportingConsent.DEFAULT_ENABLED)
    }
}
