package me.egigoka.pomodorough

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AND-07: "Open Settings" opens the app's notification page, with App info only as a fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationSettingsIntentTest {
    @Test
    fun opensTheAppNotificationPage() {
        val launched = mutableListOf<Intent>()

        launchNotificationSettings("me.egigoka.pomodorough") { launched += it }

        assertEquals(listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS), launched.map { it.action })
        assertEquals("me.egigoka.pomodorough", launched.single().getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun fallsBackToAppInfoWhenTheNotificationPageIsMissing() {
        val launched = mutableListOf<Intent>()

        launchNotificationSettings("me.egigoka.pomodorough") { intent ->
            launched += intent
            if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) throw ActivityNotFoundException()
        }

        assertEquals(
            listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            launched.map { it.action },
        )
        assertEquals("package:me.egigoka.pomodorough", launched.last().dataString)
    }
}
