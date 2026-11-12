package me.egigoka.pomodorough

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UX round 2, B6 (AND-06): declining timer notifications is remembered for the install. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationPromptChoiceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aFreshInstallHasNotDeclined() {
        assertFalse(NotificationPromptChoice.isDeclined(context))
    }

    @Test
    fun aDeclineIsRemembered() {
        NotificationPromptChoice.decline(context)
        assertTrue(NotificationPromptChoice.isDeclined(context))
    }
}
