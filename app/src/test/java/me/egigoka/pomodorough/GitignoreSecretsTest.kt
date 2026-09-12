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

    @Test
    fun editorAndMergeArtifactsAreIgnored() {
        val lines = gitignoreLines()
        assertTrue("expected `.vscode/` rule", lines.contains(".vscode/"))
        assertTrue("expected `*.orig` rule", lines.contains("*.orig"))
        assertTrue("expected `*.swp` rule", lines.contains("*.swp"))
        val root = requireNotNull(gitignoreFile().parentFile) { "gitignore parent" }
        listOf(
            ".vscode/settings.json",
            "src/.vscode/settings.json",
            "merge.orig",
            "src/merge.orig",
            "notes.swp",
            "src/notes.swp",
        ).forEach { sample ->
            assertTrue("$sample must be ignored", isIgnoredByGit(root, sample))
        }
        val tracked = gitLsFiles(root).filter { path ->
            path.contains(".vscode/") || path.endsWith(".orig") || path.endsWith(".swp")
        }
        assertTrue("tracked editor artifacts would leak: $tracked", tracked.isEmpty())
    }

    @Test
    fun mobileSecretsAreIgnored() {
        val lines = gitignoreLines()
        assertTrue("expected `google-services.json` rule", lines.contains("google-services.json"))
        assertTrue("expected `sentry.properties` rule", lines.contains("sentry.properties"))
        assertTrue("expected `*.keystore` rule", lines.contains("*.keystore"))
        assertTrue("expected `*.jks` rule", lines.contains("*.jks"))
        assertTrue("expected `*.apk` rule", lines.contains("*.apk"))
        val root = requireNotNull(gitignoreFile().parentFile) { "gitignore parent" }
        listOf(
            "google-services.json",
            "app/google-services.json",
            "sentry.properties",
            "app/sentry.properties",
            "release.keystore",
            "app/release.keystore",
            "release.jks",
            "keystore.properties",
            "secrets.properties",
            "release.p12",
            "release.pem",
            "app-debug.apk",
            "app/build/outputs/app-debug.apk",
            "app-release.aab",
        ).forEach { sample ->
            assertTrue("$sample must be ignored", isIgnoredByGit(root, sample))
        }
        val tracked = gitLsFiles(root).filter { path ->
            val name = path.substringAfterLast('/')
            name == "google-services.json" ||
                name == "sentry.properties" ||
                name == "keystore.properties" ||
                name == "secrets.properties" ||
                path.endsWith(".keystore") ||
                path.endsWith(".jks") ||
                path.endsWith(".apk") ||
                path.endsWith(".aab") ||
                path.endsWith(".p12") ||
                path.endsWith(".pem")
        }
        assertTrue("tracked secrets would leak: $tracked", tracked.isEmpty())
    }

    private fun isIgnoredByGit(root: File, path: String): Boolean {
        // A31: proof via real gitignore semantics, not hand-rolled matching.
        val process = ProcessBuilder("git", "check-ignore", "-q", path).directory(root).start()
        return process.waitFor() == 0
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
