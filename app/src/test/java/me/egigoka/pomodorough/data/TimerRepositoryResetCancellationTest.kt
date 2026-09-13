package me.egigoka.pomodorough.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A61/A62/A63 structural pins for TimerRepository reset/resolution paths.
 *
 * TimerRepository needs Android Context/Dao, so full behavioral coverage
 * lives in androidTest; these JVM pins fail if a cancel guard is dropped.
 * Each pin asserts cancel rethrows before any notice branch, while ordinary
 * failures still flow to the existing notice/commit handling.
 */
class TimerRepositoryResetCancellationTest {
    @Test
    fun resetLogoutCancellationPropagatesBeforeCommit() {
        // A61: suspend auth.logout() cancel must throw instead of reaching
        // commitLocalAccountReset as a spurious "remote logout failed" notice.
        val window = functionWindow("fun resetLocalAccountInternal(")
        val catching = window.indexOfFirst { it.contains("runCatching { auth.logout() }") }
        assertTrue(catching >= 0)
        val guard = window.indexOfFirst { it.contains("is CancellationException") }
        assertTrue(guard > catching)
        assertTrue(window[guard].contains("throw"))
        assertTrue(window.indexOfFirst { it.contains("commitLocalAccountReset(") } > guard)
    }

    @Test
    fun switchLogoutCancellationPropagatesBeforeNotice() {
        // A61: cancel must throw before auth.clear() and the logoutError
        // notice; ordinary logout errors still surface via logoutError?.let.
        val window = functionWindow("fun cancelAccountSwitchInternal(")
        val catching = window.indexOfFirst { it.contains("runCatching { auth.logout() }") }
        assertTrue(catching >= 0)
        val guard = window.indexOfFirst { it.contains("is CancellationException") }
        assertTrue(guard > catching)
        assertTrue(window[guard].contains("throw"))
        assertTrue(window.indexOfFirst { it.contains("auth.clear()") } > guard)
        assertTrue(window.any { it.contains("logoutError?.let") })
    }

    @Test
    fun quarantineCancellationPropagatesBeforeNotice() {
        // A62: suspend quarantineAccount() cancel must throw before the
        // reset-failed notice; ordinary failures still publish it.
        val window = functionWindow("fun quarantineReplicationForReset(")
        val catching = window.indexOfFirst { it.contains("quarantineAccount()") }
        assertTrue(catching >= 0)
        val guard = window.indexOfFirst { it.contains("is CancellationException") }
        assertTrue(guard > catching)
        assertTrue(window[guard].contains("throw"))
        assertTrue(window.indexOfFirst { it.contains("publishNotice(") } > guard)
    }

    @Test
    fun clearReplicationCancellationPropagatesBeforeNotice() {
        // A62: suspend clearAccountData() cancel must throw before the
        // failure notice; ordinary failures still publish it.
        val window = functionWindow("fun clearReplicationForReset(")
        val catching = window.indexOfFirst { it.contains("clearAccountData()") }
        assertTrue(catching >= 0)
        val guard = window.indexOfFirst { it.contains("is CancellationException") }
        assertTrue(guard > catching)
        assertTrue(window[guard].contains("throw"))
        assertTrue(window.indexOfFirst { it.contains("notice =") } > guard)
    }

    @Test
    fun credentialClearStaysNonSuspendWithoutGuard() {
        // A62-excluded: auth.clear() is non-suspend, so no cancel can arise;
        // pin the exclusion so a future suspend change is a conscious edit.
        val window = functionWindow("fun clearCredentialsForReset(")
        assertTrue(window.any { it.contains("runCatching(auth::clear)") })
        assertFalse(window.any { it.contains("CancellationException") })
    }

    @Test
    fun storedResolutionCancelGuardStaysFirst() {
        // A63: pure toRequestStrict guard-first (A60 pattern) in the
        // non-suspend restoreStoredResolution caller.
        val window = functionWindow("fun restoreStoredResolution(")
        val cancel = window.indexOfFirst {
            it.contains("catch (") && it.contains("CancellationException")
        }
        val generic = window.indexOfFirst {
            it.contains("catch (") && it.contains(": Exception)")
        }
        assertTrue(cancel >= 0 && generic > cancel)
    }

    @Test
    fun signedOutResolutionCancelIsRethrown() {
        // A63: pure toRequestStrict onFailure rethrow in the non-suspend
        // restorePendingResolutionForSignedOut caller.
        val window = functionWindow("fun restorePendingResolutionForSignedOut(")
        assertTrue(window.any { it.contains("runCatching { stored.toRequestStrict() }") })
        val guard = window.indexOfFirst { it.contains("is CancellationException") }
        assertTrue(guard >= 0)
        assertTrue(window[guard].contains("throw"))
    }

    private fun functionWindow(funSig: String, size: Int = 60): List<String> {
        val lines = productionText().lines()
        val start = lines.indexOfFirst { it.contains(funSig) }
        assertTrue("$funSig missing", start >= 0)
        val end = minOf(lines.size, start + size)
        val nextFun = (start + 1 until end).firstOrNull { index ->
            lines[index].startsWith("    ") && lines[index].trimStart().startsWith("fun ") ||
                lines[index].trimStart().startsWith("private ") && lines[index].contains(" fun ")
        } ?: end
        return lines.subList(start, nextFun)
    }

    private fun productionText(): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        return File(
            checkNotNull(root) { "production source root" },
            "me/egigoka/pomodorough/data/TimerRepository.kt",
        ).readText()
    }
}
