package me.egigoka.pomodorough

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

// AND-07: "Open Settings" in "Timer alerts are off" opens this app's
// notification page, where the switch the user needs is, not App info.
internal fun appNotificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

internal fun appDetailsSettingsIntent(packageName: String): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:$packageName"),
)

/**
 * Opens the app's notification settings. A device without that settings page
 * gets App info instead, which still links to the notification switch.
 */
internal fun launchNotificationSettings(packageName: String, launch: (Intent) -> Unit) {
    try {
        launch(appNotificationSettingsIntent(packageName))
    } catch (error: ActivityNotFoundException) {
        // expected-silent: some devices have no notification page; App info is the documented fallback.
        Log.w(NotificationSettingsTag, "App notification settings are unavailable; opening App info", error)
        launch(appDetailsSettingsIntent(packageName))
    }
}

private const val NotificationSettingsTag = "PomodoroughSettings"
