package me.egigoka.pomodorough.data

import java.io.File
import java.io.IOException
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R50-A06: close the three gaps left beside the L02/A04 hunks.
 * Bootstrap resolution routes through the bounded stage+cause
 * taxonomy, planning shares the commit storage-vs-Core split, and
 * corrupt safe-base JSON keeps its fallback with one bounded report.
 */
class R50A06BootstrapProjectionGapsTest {
    @Test
    fun resolutionWiringUsesBoundedTaxonomy() {
        val window = functionWindow("private suspend fun performBootstrapResolution(")
        assertTrue(window.contains("failBootstrapResolutionBounded(identity, error)"))
        assertTrue(window.contains("catch (error: CancellationException)"))
        assertTrue(window.contains("throw error"))
        assertTrue(window.contains("expected-silent:"))
        assertTrue(!window.contains("CrashReporter.report("))
        val helper = functionWindow("private suspend fun failBootstrapResolutionBounded(")
        assertTrue(helper.contains("AccountBootstrapStage.ResolutionRecovery"))
        assertTrue(helper.contains("reportAccountBootstrap("))
        assertTrue(helper.contains("failBootstrapResolution("))
        assertTrue(!helper.contains("CrashReporter.report("))
    }

    @Test
    fun planningCatchReusesCommitSplit() {
        val window = functionWindow("private fun <T> mutationProjection(")
        assertTrue(window.contains("publishMutationCommitFailure(error)"))
        assertTrue(window.contains("catch (error: CancellationException)"))
        assertTrue(window.contains("throw error"))
        assertTrue(!window.contains("CrashReporter.report("))
        assertTrue(!window.contains("projectionFailureMessage"))
    }

    @Test
    fun safeBaseFallbackReportsBoundedDecoding() {
        val window = functionWindow("private fun safeBaseDurationsOrNull(")
        assertTrue(window.contains("CorruptStateReporter.report(CorruptStateCategory.Decoding)"))
        assertTrue(window.contains(".getOrNull()"))
    }

    @Test
    fun resolutionRecoveryIOExceptionStaysQuiet() {
        val reported = captureReports {
            val result = handleAccountBootstrapFailure(
                AccountBootstrapStage.ResolutionRecovery,
                IOException("offline-canary"),
            )
            assertNull(result)
        }
        assertTrue(reported.isEmpty())
        assertNull(
            classifyAccountBootstrapFailure(
                AccountBootstrapStage.ResolutionRecovery,
                IOException("offline-canary"),
            ),
        )
    }

    @Test
    fun corruptDecodingReportsOnceWithoutPayload() {
        val first = captureCorruptReports {
            CorruptStateReporter.report(CorruptStateCategory.Decoding)
            CorruptStateReporter.report(CorruptStateCategory.Decoding)
        }
        assertEquals(1, first.size)
        assertEquals("Corrupt persisted state: local decoding failed", first.single().message)
        val second = captureCorruptReports {
            CorruptStateReporter.report(CorruptStateCategory.Decoding)
        }
        assertEquals(1, second.size)
    }

    @Test
    fun boundedResolutionReportOmitsPayload() {
        val canary = "R50A06-SENTINEL-token-abc123-device-xyz"
        val error = RuntimeException("Room failed: $canary")
        error.stackTrace = arrayOf(
            StackTraceElement(
                "me.egigoka.pomodorough.data.storage.TimerStore",
                "saveMutationState",
                "TimerStore.kt",
                120,
            ),
        )
        val reported = captureReports {
            handleAccountBootstrapFailure(AccountBootstrapStage.ResolutionRecovery, error)
        }
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is AccountBootstrapReport)
        assertEquals(
            "Account bootstrap resolution_recovery failed: bootstrap_commit",
            single.message,
        )
        assertTrue(single.cause == null)
        assertTrue(!single.message.orEmpty().contains(canary))
        assertTrue(!single.stackTraceToString().contains(canary))
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountBootstrapReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            AccountBootstrapReporter.resetForTest()
        }
        return reported
    }

    private fun captureCorruptReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        CorruptStateReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            CorruptStateReporter.resetForTest()
        }
        return reported
    }

    // functionWindow truncation contract: window ends at next
    // private/override/internal boundary or 3200-char fallback.
    // Pins depend on this; behavioral fault-injection above covers
    // quiet-transport and bounded-reporter paths independent of windowing.
    private fun functionWindow(marker: String): String {
        val text = repositoryText()
        val start = text.indexOf(marker)
        assertTrue("$marker missing", start >= 0)
        val tail = text.drop(start)
        val next = tail.indexOf("\n    private ", 10).takeIf { it > 0 }
            ?: tail.indexOf("\n    override ", 10).takeIf { it > 0 }
            ?: tail.indexOf("\n    internal ", 10).takeIf { it > 0 }
            ?: 3_200
        return tail.take(next)
    }

    private fun repositoryText(): String = productionText(
        "me/egigoka/pomodorough/data/TimerRepository.kt",
    )

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
