package me.egigoka.pomodorough.data

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCoreTaskIdentityCrashReportingTest {
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
