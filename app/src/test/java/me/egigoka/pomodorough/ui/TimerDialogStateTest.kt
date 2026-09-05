package me.egigoka.pomodorough.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TimerDialogStateTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Test
    fun saverRoundTripPreservesEveryDialogFlag() {
        val dialogs = TimerDialogState().apply {
            showLogout = true
            showAccount = true
            showLocalReset = true
            showDelete = true
            deleteConfirmation = "DELETE"
        }

        val restored = restore(save(dialogs))

        assertEquals(true, restored.showLogout)
        assertEquals(true, restored.showAccount)
        assertEquals(true, restored.showLocalReset)
        assertEquals(true, restored.showDelete)
        assertEquals("DELETE", restored.deleteConfirmation)
    }

    @Test
    fun saverRoundTripPreservesPartialDeleteConfirmation() {
        val dialogs = TimerDialogState().apply {
            showDelete = true
            deleteConfirmation = "DEL"
        }

        val restored = restore(save(dialogs))

        assertFalse(restored.showLogout)
        assertEquals(true, restored.showDelete)
        assertEquals("DEL", restored.deleteConfirmation)
    }

    @Test
    fun freshStateStartsFailClosed() {
        val dialogs = TimerDialogState()

        assertFalse(dialogs.showLogout)
        assertFalse(dialogs.showAccount)
        assertFalse(dialogs.showLocalReset)
        assertFalse(dialogs.showDelete)
        assertEquals("", dialogs.deleteConfirmation)
    }

    @Test
    fun truncatedSavedStateFailsClosedToNull() {
        assertNull(restoreTimerDialogState(listOf(true, false)))
        assertNull(restoreTimerDialogState(emptyList()))
    }

    @Test
    fun mistypedSavedStateFailsClosedToNull() {
        assertNull(restoreTimerDialogState(listOf("yes", false, false, false, "")))
        assertNull(restoreTimerDialogState(listOf(false, false, false, false, 42)))
    }

    private fun save(dialogs: TimerDialogState): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        return with(TimerDialogSaver) { scope.save(dialogs) } as List<Any?>
    }

    private fun restore(saved: List<Any?>): TimerDialogState =
        checkNotNull(restoreTimerDialogState(saved)) { "Saver must restore its own output" }
}
