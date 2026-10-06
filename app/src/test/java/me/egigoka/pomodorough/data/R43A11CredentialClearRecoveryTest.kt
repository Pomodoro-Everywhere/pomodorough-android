package me.egigoka.pomodorough.data

import java.io.File
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43-A11: stale-credential vault-clear failure must preserve recovery
 * state and report a bounded category instead of a clean sign-out.
 * Each test injects a vault-clear failure, then asserts recovery state
 * plus a bounded report carrying no vault payload.
 */
class R43A11CredentialClearRecoveryTest {
    @Test
    fun vaultClearFailurePreservesRecoveryAndReportsBoundedCategory() {
        val payload = "R43A11-VAULT-SENTINEL-{\"token\":\"secret\"}"
        val failure = IllegalStateException(payload)
        val reported = captureReports {
            val outcome = StaleCredentialClearRecovery.resolve(failure)
            assertTrue(outcome.recoveryRequired)
            assertEquals(CorruptStateCategory.CredentialClear, outcome.category)
        }
        assertEquals(1, reported.size)
        assertBoundedReport(reported.single(), payload)
    }

    @Test
    fun vaultClearSuccessStaysCleanWithoutReport() {
        val reported = captureReports {
            val outcome = StaleCredentialClearRecovery.resolve(null)
            assertFalse(outcome.recoveryRequired)
            assertEquals(null, outcome.category)
        }
        assertTrue(reported.isEmpty())
    }

    @Test
    fun repositoryWiringPreservesRecoveryOnVaultClearFailure() {
        val text = repositoryText()
        val start = text.indexOf("fun initializeCentralizedAccount(")
        assertTrue(start >= 0)
        val window = text.substring(start, (start + 2_500).coerceAtMost(text.length))
        assertTrue(window.contains("credentialRecoveryRequired = true"))
        assertTrue(window.contains("StaleCredentialClearRecovery.resolve"))
        assertTrue(window.contains("unreadable_credential"))
        assertFalse(hasBareDiscard(window))
        assertTrue(helperReportsBoundedCategory())
    }

    private fun helperReportsBoundedCategory(): Boolean {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val helper = File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/StaleCredentialClearRecovery.kt",
        ).readText()
        return helper.contains("CorruptStateCategory.CredentialClear")
    }

    private fun hasBareDiscard(window: String): Boolean {
        val clear = window.indexOf("runCatching(auth::clear)")
        if (clear < 0) return false
        val tail = window.substring(clear)
        return !tail.contains("exceptionOrNull") && !tail.contains("onFailure")
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
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

    private fun assertBoundedReport(error: Throwable, payload: String) {
        assertTrue(error is CorruptStateReport)
        assertEquals(CorruptStateCategory.CredentialClear.reportMessage, error.message)
        assertTrue(error.cause == null)
        assertTrue(error.message?.contains(payload) != true)
        assertTrue(!error.stackTraceToString().contains(payload))
    }

    private fun repositoryText(): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        return File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
    }
}
