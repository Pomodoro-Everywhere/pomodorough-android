package me.egigoka.pomodorough.timer

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExactAlarmFallbackChoiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun freshInstallHasNotAcceptedFallback() {
        assertFalse(ExactAlarmFallbackChoice.isAccepted(context))
    }

    @Test
    fun acceptingFallbackPersistsInSharedPreferences() {
        ExactAlarmFallbackChoice.accept(context)

        assertTrue(ExactAlarmFallbackChoice.isAccepted(context))
        val stored = context
            .getSharedPreferences(ExactAlarmFallbackChoice.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ExactAlarmFallbackChoice.KEY_ACCEPTED, false)
        assertTrue(stored)
    }
}
