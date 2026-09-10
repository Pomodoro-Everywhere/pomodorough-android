package me.egigoka.pomodorough.data

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCoreTaskIdentityCrashReportingTest {
    @Test
    fun shapeMismatchReportsWhileOperationValidationStaysSilent() {
        val lines = productionFile("me/egigoka/pomodorough/data/TimerRepository.kt")
            .readText().lines()
        val start = lines.indexOfFirst { it.contains("private fun taskFromSharedCore") }
        assertTrue("taskFromSharedCore not found", start >= 0)
        val end = lines.subList(start + 1, lines.size)
            .indexOfFirst { it.contains("private fun ") }
            .let { if (it < 0) lines.size else start + 1 + it }
        val mismatch = (start until end).firstOrNull {
            lines[it].contains("shared_core_invalid_output")
        }
        assertTrue("shape-mismatch notice not found", mismatch != null)
        val window = lines.subList(maxOf(start, mismatch!! - 6), mismatch + 3).joinToString("\n")
        assertTrue("shape mismatch must report via CrashReporter", window.contains("CrashReporter.report("))
        assertTrue(
            "shape mismatch must keep invalid-output notice",
            window.contains("shared_core_invalid_output"),
        )
    }

    @Test
    fun identityMismatchReportsWhileOperationValidationStaysSilent() {
        val lines = productionFile("me/egigoka/pomodorough/data/TimerRepository.kt")
            .readText().lines()
        val start = lines.indexOfFirst { it.contains("private fun authoritativeTask") }
        assertTrue("authoritativeTask not found", start >= 0)
        val end = lines.subList(start + 1, lines.size)
            .indexOfFirst { it.contains("private fun ") }
            .let { if (it < 0) lines.size else start + 1 + it }
        val body = lines.subList(start, end).joinToString("\n")
        assertTrue("id mismatch must report via CrashReporter", body.contains("CrashReporter.report("))
        assertTrue(
            "id mismatch must wrap as InvalidOutput",
            body.contains("InvalidOutput"),
        )
        assertTrue(
            "id mismatch must keep invalid-output notice",
            body.contains("shared_core_invalid_output"),
        )
    }

    @Test
    fun abiLoadReportsWhileOperationValidationStaysSilent() {
        val lines = productionFile("me/egigoka/pomodorough/data/TimerRepository.kt")
            .readText().lines()
        val start = lines.indexOfFirst { it.contains("private fun taskFromSharedCore") }
        assertTrue("taskFromSharedCore not found", start >= 0)
        val operation = findCatch(lines, start, "catch (error: SharedCoreException.Operation)")
        val generic = findCatch(lines, start, "catch (error: SharedCoreException)")
        assertTrue("Operation catch not found", operation >= 0)
        assertTrue("generic SharedCore catch not found", generic >= 0)
        assertTrue("Operation must precede generic catch", operation < generic)
        assertSilentValidationBody(lines, operation)
        assertReportedInfrastructureBody(lines, generic)
    }

    private fun findCatch(lines: List<String>, from: Int, marker: String): Int {
        for (index in from until lines.size) {
            if (lines[index].contains(marker)) return index
        }
        return -1
    }

    private fun assertSilentValidationBody(lines: List<String>, catchLine: Int) {
        val body = lines.subList(catchLine, (catchLine + 8).coerceAtMost(lines.size))
        assertTrue(
            "Operation validation must stay expected-silent",
            body.any { it.contains("expected-silent") },
        )
        assertTrue(
            "Operation validation must not report",
            body.none { it.contains("CrashReporter") },
        )
    }

    private fun assertReportedInfrastructureBody(lines: List<String>, catchLine: Int) {
        val body = lines.subList(catchLine, (catchLine + 6).coerceAtMost(lines.size))
        assertTrue(
            "ABI/Load failure must report via CrashReporter",
            body.any { it.contains("CrashReporter.report(error)") },
        )
    }

    private fun productionFile(relativePath: String) = File(productionRoot(), relativePath)

    private fun productionRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return requireNotNull(root)
    }
}
