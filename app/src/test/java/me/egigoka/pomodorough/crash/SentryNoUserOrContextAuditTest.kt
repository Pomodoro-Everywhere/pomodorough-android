package me.egigoka.pomodorough.crash

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryNoUserOrContextAuditTest {
    @Test
    fun appNeverSetsUserOrTypedContexts() {
        val root = productionRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "me/egigoka/pomodorough/crash/" !in it.path }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (isUserOrTypedContextWrite(line)) {
                        offenders += "${file.relativeTo(root)}:${index + 1}: $line"
                    }
                }
            }
        assertTrue(
            "app must not set Sentry User or typed Device/App/Os contexts " +
                "(SDK owns them; scrubber covers residuals): $offenders",
            offenders.isEmpty(),
        )
    }

    private fun isUserOrTypedContextWrite(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("//")) return false
        return line.contains("Sentry.setUser(") ||
            line.contains(".setUser(") ||
            line.contains("event.user =") ||
            line.contains("event.setUser(") ||
            line.contains("contexts.setDevice(") ||
            line.contains("contexts.setApp(") ||
            line.contains("contexts.setOperatingSystem(") ||
            line.contains("contexts.device =") ||
            line.contains("contexts.app =")
    }

    @Test
    fun appNeverWritesTagsOrScopesOutsideScrubber() {
        val root = productionRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "me/egigoka/pomodorough/crash/" !in it.path }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (isTagOrScopeWrite(line)) {
                        offenders += "${file.relativeTo(root)}:${index + 1}: $line"
                    }
                }
            }
        assertTrue(
            "app must not write Sentry tags or scopes outside crash/ " +
                "(only SentryScrubber.setTag is audited): $offenders",
            offenders.isEmpty(),
        )
    }

    private fun isTagOrScopeWrite(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("//")) return false
        // A31: setTag/configureScope audit. SentryScrubber.setTag is the
        // single allowed writer (inside crash/, filtered above) and its
        // values pass through scrubDataValue. No app path configures scope.
        return line.contains("Sentry.setTag(") ||
            line.contains(".setTag(") ||
            line.contains("Sentry.configureScope") ||
            line.contains("configureScope(")
    }

    private fun productionRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return requireNotNull(root)
    }
}
