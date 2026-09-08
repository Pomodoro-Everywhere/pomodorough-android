package me.egigoka.pomodorough

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitignoreSecretsTest {
    @Test
    fun envVariantsAreIgnored() {
        val lines = gitignoreLines()
        assertTrue("expected exact `.env` rule", lines.contains(".env"))
        assertTrue("expected exact `.env.*` rule", lines.contains(".env.*"))
        listOf(".env.local", ".env.production", ".env.test", "config/.env.staging").forEach { sample ->
            assertTrue("$sample must match an ignore rule", isIgnored(lines, sample))
        }
        assertTrue(".env must match an ignore rule", isIgnored(lines, ".env"))
        assertEquals(emptySet<String>(), setOf("src/main.kt", "README.md").filter { isIgnored(lines, it) }.toSet())
    }

    @Test
    fun noEnvFilesAreTracked() {
        val root = requireNotNull(gitignoreFile().parentFile) { "gitignore parent" }
        val tracked = gitLsFiles(root).filter { it.substringAfterLast('/').startsWith(".env") }
        assertTrue("tracked env files would leak secrets: $tracked", tracked.isEmpty())
    }

    private fun isIgnored(lines: List<String>, path: String): Boolean {
        val name = path.substringAfterLast('/')
        return lines.any { rule ->
            when (rule) {
                ".env" -> name == ".env"
                ".env.*" -> name.startsWith(".env.")
                else -> false
            }
        }
    }

    private fun gitignoreLines(): List<String> = gitignoreFile().readLines().map(String::trim)

    private fun gitignoreFile(): File {
        val file = sequenceOf(File("../.gitignore"), File(".gitignore")).firstOrNull(File::isFile)
        assertNotNull("repo .gitignore", file)
        return requireNotNull(file)
    }

    private fun gitLsFiles(root: File): List<String> {
        val process = ProcessBuilder("git", "ls-files").directory(root).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output.lines().filter(String::isNotBlank)
    }
}
