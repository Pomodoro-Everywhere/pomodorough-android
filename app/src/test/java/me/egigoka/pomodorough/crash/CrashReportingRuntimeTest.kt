package me.egigoka.pomodorough.crash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingRuntimeTest {
    @Test
    fun disableStopsDeliveryWithoutRestart() {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        try {
            CrashReportingRuntime.applyConsent(
                enabled = false,
                onEnable = { error("must not re-init on disable") },
                onDisable = { },
            )
            assertFalse(CrashReportingRuntime.reportingEnabled)
            CrashReporter.report(RuntimeException("boom"))
            assertTrue(reported.isEmpty())
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
        }
    }

    @Test
    fun enableResumesDelivery() {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        try {
            CrashReportingRuntime.applyConsent(enabled = false, onEnable = {}, onDisable = {})
            CrashReportingRuntime.applyConsent(enabled = true, onEnable = {}, onDisable = {})
            assertTrue(CrashReportingRuntime.reportingEnabled)
            val failure = RuntimeException("boom")
            CrashReporter.report(failure)
            assertEquals(listOf(failure), reported)
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
        }
    }

    @Test
    fun stopClosesSentryPipeline() {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousClose = CrashReportingRuntime.closeSentry
        var closed = 0
        CrashReportingRuntime.closeSentry = { closed += 1 }
        try {
            CrashReportingRuntime.stop()
            assertFalse(CrashReportingRuntime.reportingEnabled)
            assertEquals(1, closed)
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReportingRuntime.closeSentry = previousClose
        }
    }

    @Test
    fun cardWiresCloseOnDisableAndReinitOnEnable() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        val base = requireNotNull(root)
        val card = File(base, "me/egigoka/pomodorough/ui/CrashReportingCard.kt").readText()
        assertTrue(card.contains("CrashReportingRuntime.applyConsent"))
        assertTrue(card.contains("CrashReportingRuntime.stop()"))
        assertTrue(card.contains("CrashReportingRuntime.start("))
        val runtime = File(base, "me/egigoka/pomodorough/crash/CrashReportingRuntime.kt").readText()
        assertTrue(runtime.contains("Sentry.close()"))
        assertTrue(runtime.contains("SentryAndroid.init"))
        val reporter = File(base, "me/egigoka/pomodorough/crash/CrashReporter.kt").readText()
        assertTrue(reporter.contains("CrashReportingRuntime.reportingEnabled"))
    }
}
