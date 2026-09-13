package me.egigoka.pomodorough.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A67/A68/A69 structural pins (A60 pattern).
 *
 * Pure serialize/decode sites are non-suspend today, so cancellation cannot
 * arise; each pin fails if a guard-first rethrow is dropped, keeping the
 * convention if either fact changes.
 */
class CoreDispatcherCancellationTest {
    @Test
    fun bootstrapDecodePlanRethrowsCancellationFirst() {
        // A67: pure decodePlan guard-first.
        val window = functionWindow(
            "me/egigoka/pomodorough/data/CoreSynchronizationDispatchers.kt",
            "private fun decodePlan(",
        )
        assertGuardFirst(window)
    }

    @Test
    fun reconciliationDecodeRethrowsCancellationFirst() {
        // A67: pure rebase decode guard-first.
        val window = functionWindow(
            "me/egigoka/pomodorough/data/CoreSynchronizationDispatchers.kt",
            "fun rebase(",
        )
        assertGuardFirst(window)
    }

    @Test
    fun projectionApplyRethrowsCancellationFirst() {
        // A69: pure serialize + decode guard-first in apply().
        val window = functionWindow(
            "me/egigoka/pomodorough/data/CoreProjectionDispatcher.kt",
            "fun apply(",
            60,
        )
        val guards = window.indexesOf { it.contains("CancellationException") && it.contains("catch (") }
        assertTrue("apply() needs two cancel guards, found ${guards.size}", guards.size >= 2)
        guards.forEach { guard ->
            assertTrue(window[guard + 1].contains("A69") || window[guard].contains("A69") ||
                window.subList(guard, minOf(window.size, guard + 4)).any { it.contains("throw error") })
        }
        val firstGeneric = window.indexOfFirst { it.contains("catch (") && it.contains(": Exception)") }
        assertTrue(firstGeneric > guards.first())
    }

    @Test
    fun taskIdentityExtractRethrowsCancellation() {
        // A68: pure JSON field extract onFailure rethrow.
        val window = functionWindow(
            "me/egigoka/pomodorough/data/TimerRepository.kt",
            "private fun taskFromSharedCore(",
        )
        val catching = window.indexOfFirst { it.contains("runCatching {") }
        assertTrue(catching >= 0)
        val guard = window.indexOfFirst { it.contains("is CancellationException") }
        assertTrue(guard > catching)
        assertTrue(window[guard].contains("throw"))
    }

    private fun assertGuardFirst(window: List<String>) {
        val cancel = window.indexOfFirst { it.contains("catch (") && it.contains("CancellationException") }
        val generic = window.indexOfFirst { it.contains("catch (") && it.contains(": Exception)") }
        assertTrue(cancel >= 0 && generic > cancel)
        assertTrue(window.subList(cancel, minOf(window.size, cancel + 4)).any { it.contains("throw error") })
    }

    private fun List<String>.indexesOf(predicate: (String) -> Boolean): List<Int> =
        mapIndexedNotNull { index, line -> if (predicate(line)) index else null }

    private fun functionWindow(relativePath: String, funSig: String, size: Int = 30): List<String> {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        val lines = File(checkNotNull(root), relativePath).readText().lines()
        val start = lines.indexOfFirst { it.contains(funSig) }
        assertTrue("$funSig missing", start >= 0)
        return lines.subList(start, minOf(lines.size, start + size))
    }
}
