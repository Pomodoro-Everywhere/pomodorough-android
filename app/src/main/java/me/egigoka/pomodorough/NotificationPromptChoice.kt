package me.egigoka.pomodorough

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers that the user declined timer notifications, so "Never miss an
 * arrival" is asked once per install and not on every start and resume.
 * Granting the permission later in system settings makes the choice
 * irrelevant because the permission policy checks the grant first.
 */
object NotificationPromptChoice {
    const val PREFS_NAME = "pomodorough_notification_prompt"
    const val KEY_DECLINED = "declined"

    fun isDeclined(context: Context): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_DECLINED, false)

    fun decline(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DECLINED, true) }
    }
}
