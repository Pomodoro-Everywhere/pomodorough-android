package me.egigoka.pomodorough.crash

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingSilenceTest {
    @Test
    fun expectedSilentBranchesNeverReport() {
        val files = listOf(
            "me/egigoka/pomodorough/data/TimerRepository.kt",
            "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt",
            "me/egigoka/pomodorough/data/iroh/IrohEndpointLifecycle.kt",
        )
        var markers = 0
        files.forEach { relativePath ->
            val lines = productionFile(relativePath).readText().lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("expected-silent")) return@forEachIndexed
                markers += 1
                assertSilentCatchBody(relativePath, lines, index)
            }
        }
        assertTrue("expected-silent markers shrank to $markers, update this audit", markers >= 14)
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
