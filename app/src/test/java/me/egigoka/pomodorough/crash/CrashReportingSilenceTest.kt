package me.egigoka.pomodorough.crash

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingSilenceTest {
    private val auditedFiles = listOf(
        "me/egigoka/pomodorough/data/TimerRepository.kt",
        "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt",
        "me/egigoka/pomodorough/data/iroh/IrohEndpointLifecycle.kt",
        "me/egigoka/pomodorough/data/iroh/IrohIncomingRpcHandler.kt",
        "me/egigoka/pomodorough/data/TimerSyncValidation.kt",
        "me/egigoka/pomodorough/data/CentralizedSyncRuntime.kt",
        "me/egigoka/pomodorough/data/RevisionStreamLifecycle.kt",
        "me/egigoka/pomodorough/data/iroh/IrohPeerSynchronization.kt",
        "me/egigoka/pomodorough/data/auth/AuthRepository.kt",
        "me/egigoka/pomodorough/data/time/TrustedClock.kt",
        "me/egigoka/pomodorough/data/CoreProjectionDispatcher.kt",
        "me/egigoka/pomodorough/data/CoreSynchronizationDispatchers.kt",
        "me/egigoka/pomodorough/data/CoreTimerPolicyDispatchers.kt",
        "me/egigoka/pomodorough/data/api/PomodoroughApi.kt",
    )

    @Test
    fun expectedSilentBranchesNeverReport() {
        var markers = 0
        auditedFiles.forEach { relativePath ->
            val lines = productionFile(relativePath).readText().lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("expected-silent")) return@forEachIndexed
                markers += 1
                assertSilentCatchBody(relativePath, lines, index)
            }
        }
        assertTrue("expected-silent markers shrank to $markers, update this audit", markers >= 50)
    }

    @Test
    fun everyCatchSiteReportsOrDeclaresExpectedSilent() {
        auditedFiles.forEach { relativePath ->
            val lines = productionFile(relativePath).readText().lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("catch (")) return@forEachIndexed
                assertTrue(
                    "$relativePath:${index + 1} must report, rethrow, or declare expected-silent",
                    isAuditedCatch(lines, index),
                )
            }
        }
    }

    @Test
    fun runCatchingSitesAreValidatedHandledOrJustified() {
        auditedFiles.forEach { relativePath ->
            val lines = productionFile(relativePath).readText().lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("runCatching")) return@forEachIndexed
                assertTrue(
                    "$relativePath:${index + 1} runCatching must be validation, handled, or justified",
                    isAuditedRunCatching(lines, index),
                )
            }
        }
    }

    private fun isAuditedCatch(lines: List<String>, catchIndex: Int): Boolean {
        val body = catchBody(lines, catchIndex)
        if (body.any { it.contains("CrashReporter") }) return true
        if (body.drop(1).any { it.contains("throw ") }) return true
        return hasSilenceJustification(lines, catchIndex)
    }

    private fun hasSilenceJustification(lines: List<String>, catchIndex: Int): Boolean {
        val from = maxOf(0, catchIndex - 5)
        val to = minOf(lines.size, catchIndex + 4)
        return lines.subList(from, to).any { line ->
            val colon = line.indexOf("expected-silent:")
            colon >= 0 && line.drop(colon + 17).trim().length >= 10
        }
    }

    private fun catchBody(lines: List<String>, catchIndex: Int): List<String> {
        val body = mutableListOf(lines[catchIndex])
        var depth = 1
        for (next in catchIndex + 1 until minOf(lines.size, catchIndex + 40)) {
            val line = lines[next]
            body += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) break
        }
        return body
    }

    private fun isAuditedRunCatching(lines: List<String>, index: Int): Boolean {
        val window = lines.subList(index, minOf(lines.size, index + 3)).joinToString("\n")
        if (isPureValidation(window)) return true
        if (hasRunCatchingHandling(window)) return true
        if (isBestEffortCleanup(window)) return true
        return hasRunCatchingJustification(lines, index)
    }

    private fun isPureValidation(window: String): Boolean {
        if (!window.contains("runCatching")) return false
        return window.contains("parse") || window.contains("UUID.fromString") ||
            window.contains("URI(") || window.contains("valueOf") ||
            window.contains("Base64") || window.contains("decode") ||
            window.contains("subtractExact") || window.contains("addExact") ||
            window.contains("toRequestStrict") || window.contains("toLongOrNull") ||
            window.contains("jsonObject") || window.contains("jsonPrimitive")
    }

    private fun hasRunCatchingHandling(window: String): Boolean {
        return window.contains(".exceptionOrNull") || window.contains(".fold(") ||
            window.contains(".onFailure") || window.contains(".onSuccess") ||
            window.contains(".getOrNull") || window.contains(".getOrDefault") ||
            window.contains(".getOrElse") || window.contains(".isSuccess") ||
            window.contains(".isFailure")
    }

    private fun isBestEffortCleanup(window: String): Boolean {
        return window.contains("shutdown()") || window.contains("cancelAndJoin()") ||
            window.contains(".close()") || window.contains(".ignore()") ||
            window.contains("discard") || window.contains("auth::clear") ||
            window.contains(".fill(0)") || window.contains(".cancel()")
    }

    private fun hasRunCatchingJustification(lines: List<String>, index: Int): Boolean {
        val from = maxOf(0, index - 3)
        val to = minOf(lines.size, index + 3)
        return lines.subList(from, to).any {
            it.contains("expected-silent:") || it.contains("best-effort") || it.contains("A31:")
        }
    }

    private fun assertSilentCatchBody(relativePath: String, lines: List<String>, marker: Int) {
        var depth = 0
        for (next in marker + 1 until lines.size) {
            val line = lines[next]
            val trimmed = line.trim()
            if (depth == 0 && trimmed.contains("catch (")) return
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth < 0) return
            assertTrue(
                "$relativePath:${next + 1} reports from an expected-silent branch",
                !line.contains("CrashReporter"),
            )
        }
    }

    private fun productionFile(relativePath: String) = File(productionRoot(), relativePath)

    private fun productionRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return requireNotNull(root)
    }
}
