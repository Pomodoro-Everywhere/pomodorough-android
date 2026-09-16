package me.egigoka.pomodorough.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainTabSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun saverRoundTripPreservesEveryTab() {
        for (tab in MainTab.entries) {
            val saved = with(MainTabSaver) { scope.save(tab) }
            assertEquals(tab.name, saved)
            assertEquals(tab, restoreMainTab(saved as? String))
        }
    }

    @Test
    fun unknownTabNameFailsClosedToNull() {
        assertNull(restoreMainTab("NoSuchTab"))
        assertNull(restoreMainTab(null))
        assertNull(restoreMainTab(""))
    }
}
