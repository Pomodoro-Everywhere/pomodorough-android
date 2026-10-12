package me.egigoka.pomodorough.crash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// R48-A05: re-enable must share launch eligibility (DSN + opt-in + test/CI).
// Pure-predicate JVM coverage; real Context gating lives in instrumented tests.
// Synthetic DSN only; no production events.
class R48A05RuntimeSuppressionTest {
    private val testDsn = "http://127.0.0.1:9/1"

    @Test
    fun instrumentedContextStaysSuppressed() {
        assertFalse(CrashReportingRuntime.isEligible(testDsn, true, true, false))
    }

    @Test
    fun ciOnEmulatorStaysSuppressed() {
        assertFalse(CrashReportingRuntime.isEligible(testDsn, true, false, true))
    }

    @Test
    fun blankDsnStaysSuppressed() {
        assertFalse(CrashReportingRuntime.isEligible("", true))
        assertFalse(CrashReportingRuntime.isEligible("   ", true))
        assertFalse(CrashReportingRuntime.isEligible("", true, false, false))
    }

    @Test
    fun optOutStaysSuppressed() {
        assertFalse(CrashReportingRuntime.isEligible(testDsn, false))
        assertFalse(CrashReportingRuntime.isEligible(testDsn, false, false, false))
    }

    @Test
    fun eligibleRealDeviceControl() {
        assertTrue(CrashReportingRuntime.isEligible(testDsn, true, false, false))
    }

    @Test
    fun uiOffOnSharesLaunchPredicate() {
        assertFalse(CrashReportingRuntime.isEligible(testDsn, true, true, false))
        assertFalse(CrashReportingRuntime.isEligible(testDsn, true, false, true))
        assertTrue(CrashReportingRuntime.isEligible(testDsn, true, false, false))
    }

    @Test
    fun disabledRuntimeDropsManualReports() {
        val prevEnabled = CrashReportingRuntime.reportingEnabled
        val prevDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        try {
            CrashReportingRuntime.applyConsent(enabled = false, onEnable = {}, onDisable = {})
            CrashReporter.report(RuntimeException("suppressed"))
            assertTrue(reported.isEmpty())
        } finally {
            CrashReportingRuntime.reportingEnabled = prevEnabled
            CrashReporter.delegate = prevDelegate
        }
    }

    @Test
    fun eligibleReenablePreservesDelivery() {
        val prevEnabled = CrashReportingRuntime.reportingEnabled
        val prevDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        try {
            assertTrue(CrashReportingRuntime.isEligible(testDsn, true, false, false))
            CrashReportingRuntime.applyConsent(enabled = true, onEnable = {}, onDisable = {})
            val failure = RuntimeException("eligible")
            CrashReporter.report(failure)
            assertTrue(reported.size == 1 && reported[0] === failure)
        } finally {
            CrashReportingRuntime.reportingEnabled = prevEnabled
            CrashReporter.delegate = prevDelegate
        }
    }
}
