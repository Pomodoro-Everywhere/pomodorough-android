package me.egigoka.pomodorough.ui

import me.egigoka.pomodorough.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun versionLabelRendersRunningVersion() {
        val running = BuildConfig.VERSION_NAME

        assertEquals(running.trim(), appVersionLabel(running))
        assertTrue(appVersionLabel(running).contains(running.trim()))
    }

    @Test fun versionLabelTrimsSurroundingWhitespace() {
        val running = BuildConfig.VERSION_NAME

        assertEquals(running.trim(), appVersionLabel("  $running  "))
    }
}
