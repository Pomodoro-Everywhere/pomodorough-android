package me.egigoka.pomodorough

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteShareOnlyA1Test {
    @Test
    fun shareContentPreservesExactInviteAndPlainTextContract() {
        val invite = "pomodorough1.exact-bearer-payload"
        val content = irohInviteShareContent(invite, "Pomodorough Iroh room")

        assertEquals("text/plain", content.mimeType)
        assertEquals("Pomodorough Iroh room", content.subject)
        assertEquals(invite, content.text)
    }

    @Test
    fun productionContainsNoInviteClipboardPathOrCleanupState() {
        val sources = productionSources()
        val production = sources.joinToString("\n") { it.readText() }

        forbiddenClipboardMarkers.forEach { marker ->
            assertFalse("production contains $marker", marker in production)
        }
        assertFalse(sources.any { it.invariantSeparatorsPath.contains("/clipboard/") })
        assertFalse("onCopyIrohInvite" in production)
        assertFalse("onCopyInvite" in production)
        assertFalse(Regex("""R\.string\.copy\b""").containsMatchIn(production))
    }

    @Test
    fun activityUsesAndroidShareSheetWithoutRetainedCleanupState() {
        val activity = productionFile("me/egigoka/pomodorough/MainActivity.kt").readText()
        val application = productionFile("me/egigoka/pomodorough/PomodoroughApplication.kt").readText()

        assertTrue("Intent(Intent.ACTION_SEND)" in activity)
        assertTrue("type = shareContent.mimeType" in activity)
        assertTrue("putExtra(Intent.EXTRA_TEXT, shareContent.text)" in activity)
        assertTrue("Intent.createChooser(" in activity)
        assertFalse("inviteClipboard" in activity + application)
        assertFalse("processScope" in application)
    }

    private fun productionSources(): List<File> = productionRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun productionFile(relativePath: String) = File(productionRoot(), relativePath)

    private fun productionRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return requireNotNull(root)
    }

    private companion object {
        val forbiddenClipboardMarkers = listOf(
            "ClipboardManager",
            "ClipData",
            "setPrimaryClip",
            "clearPrimaryClip",
            "InviteClipboardCleanup",
        )
    }
}
