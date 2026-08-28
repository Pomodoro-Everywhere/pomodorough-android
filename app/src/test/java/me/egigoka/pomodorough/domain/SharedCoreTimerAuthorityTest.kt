package me.egigoka.pomodorough.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class SharedCoreTimerAuthorityTest {
    @Test
    fun shippingSourceContainsNoNativeTimerReplayPolicy() {
        val sourceRoot = productionSourceRoot()
        val sources = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        assertFalse(sources.any { it.name == "TimerReducer.kt" })
        assertFalse(sources.any { sourceDefinesNativeTimerReplay(it.readText()) })
    }

    private fun productionSourceRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return requireNotNull(root)
    }

    private fun sourceDefinesNativeTimerReplay(source: String): Boolean =
        "object TimerReducer" in source ||
            "object LegacyTimerReducer" in source ||
            ("TimerProjection" in source && replayMarkers.any(source::contains))

    private companion object {
        val replayMarkers = listOf("fun replay(", "fun replayOrdered(", "fun projectAt(")
    }
}
