package me.egigoka.pomodorough.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingConsent
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// R48-A05: runtime re-enable must retain startup suppression.
// Synthetic DSN only (127.0.0.1:9); starter/close/delegate stubbed; no production events.
@RunWith(AndroidJUnit4::class)
class R48A05SuppressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val testDsn = "http://127.0.0.1:9/1"
    private val release = "0.47.0+58"

    @Test
    fun instrumentedReenableStaysSuppressed() {
        val ctx = target().applicationContext
        val prevEnabled = CrashReportingRuntime.reportingEnabled
        val prevStarter = CrashReportingRuntime.starter
        val prevClose = CrashReportingRuntime.closeSentry
        var starts = 0
        var closes = 0
        CrashReportingRuntime.starter = { _, _, _ -> starts += 1 }
        CrashReportingRuntime.closeSentry = { closes += 1 }
        try {
            assertFalse("instrumented process must be ineligible", CrashReportingRuntime.isEligible(testDsn, true))
            CrashReportingRuntime.stop()
            val started = CrashReportingRuntime.start(ctx, testDsn, release)
            assertFalse(started)
            assertFalse(CrashReportingRuntime.reportingEnabled)
            assertEquals(0, starts)
            assertTrue(closes >= 1)
        } finally {
            CrashReportingRuntime.reportingEnabled = prevEnabled
            CrashReportingRuntime.starter = prevStarter
            CrashReportingRuntime.closeSentry = prevClose
        }
    }

    @Test
    fun toggleOffOnKeepsSdkDisabledAndDropsReports() {
        val ctx = target().applicationContext
        val prevEnabled = CrashReportingRuntime.reportingEnabled
        val prevStarter = CrashReportingRuntime.starter
        val prevClose = CrashReportingRuntime.closeSentry
        val prevDelegate = CrashReporter.delegate
        var starts = 0
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.starter = { _, _, _ -> starts += 1 }
        CrashReportingRuntime.closeSentry = {}
        try {
            CrashReportingRuntime.stop()
            assertFalse(CrashReportingRuntime.start(ctx, testDsn, release))
            CrashReporter.report(RuntimeException("suppressed"))
            assertTrue(reported.isEmpty())
            assertEquals(0, starts)
            assertFalse(CrashReportingRuntime.reportingEnabled)
        } finally {
            CrashReportingRuntime.reportingEnabled = prevEnabled
            CrashReportingRuntime.starter = prevStarter
            CrashReportingRuntime.closeSentry = prevClose
            CrashReporter.delegate = prevDelegate
        }
    }

    @Test
    fun ciEmulatorPredicateControl() {
        assertFalse(CrashReportingRuntime.isEligible(testDsn, true, false, true))
    }

    @Test
    fun blankDsnControl() {
        val ctx = target().applicationContext
        val prevEnabled = CrashReportingRuntime.reportingEnabled
        val prevStarter = CrashReportingRuntime.starter
        val prevClose = CrashReportingRuntime.closeSentry
        var starts = 0
        CrashReportingRuntime.starter = { _, _, _ -> starts += 1 }
        CrashReportingRuntime.closeSentry = {}
        try {
            assertFalse(CrashReportingRuntime.start(ctx, "", release))
            assertFalse(CrashReportingRuntime.start(ctx, "   ", release))
            assertEquals(0, starts)
            assertFalse(CrashReportingRuntime.reportingEnabled)
        } finally {
            CrashReportingRuntime.reportingEnabled = prevEnabled
            CrashReportingRuntime.starter = prevStarter
            CrashReportingRuntime.closeSentry = prevClose
        }
    }

    @Test
    fun optOutControl() {
        val ctx = target().applicationContext
        val prevEnabled = CrashReportingRuntime.reportingEnabled
        val prevStarter = CrashReportingRuntime.starter
        val prevClose = CrashReportingRuntime.closeSentry
        var starts = 0
        CrashReportingRuntime.starter = { _, _, _ -> starts += 1 }
        CrashReportingRuntime.closeSentry = {}
        try {
            assertFalse(CrashReportingRuntime.start(ctx, testDsn, release, false))
            assertEquals(0, starts)
            assertFalse(CrashReportingRuntime.reportingEnabled)
        } finally {
            CrashReportingRuntime.reportingEnabled = prevEnabled
            CrashReportingRuntime.starter = prevStarter
            CrashReportingRuntime.closeSentry = prevClose
        }
    }

    @Test
    fun eligibleReenableControl() {
        assertTrue(CrashReportingRuntime.isEligible(testDsn, true, false, false))
    }

    @Test
    fun uiToggleOnKeepsRuntimeStopped() {
        val ctx = target()
        val prevEnabled = CrashReportingRuntime.reportingEnabled
        val prevStarter = CrashReportingRuntime.starter
        val prevClose = CrashReportingRuntime.closeSentry
        val prevConsent = CrashReportingConsent.isEnabled(ctx)
        var starts = 0
        CrashReportingRuntime.starter = { _, _, _ -> starts += 1 }
        CrashReportingRuntime.closeSentry = {}
        try {
            CrashReportingConsent.setEnabled(ctx, false)
            CrashReportingRuntime.stop()
            composeRule.setContent { Frame { CrashReportingCard() } }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription(ctx.getString(R.string.crash_reporting_title)).performClick()
            composeRule.waitForIdle()
            assertTrue("UI must persist opt-in", CrashReportingConsent.isEnabled(ctx))
            assertFalse("suppressed re-enable must stay stopped", CrashReportingRuntime.reportingEnabled)
            assertEquals(0, starts)
        } finally {
            CrashReportingConsent.setEnabled(ctx, prevConsent)
            CrashReportingRuntime.reportingEnabled = prevEnabled
            CrashReportingRuntime.starter = prevStarter
            CrashReportingRuntime.closeSentry = prevClose
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        val c = Configuration(LocalConfiguration.current)
        val density = Density(LocalDensity.current.density, 1f)
        CompositionLocalProvider(LocalConfiguration provides c, LocalDensity provides density) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                PomodoroughTheme { Box(Modifier.size(400.dp, 900.dp)) { content() } }
            }
        }
    }

    private fun target() = InstrumentationRegistry.getInstrumentation().targetContext
}
