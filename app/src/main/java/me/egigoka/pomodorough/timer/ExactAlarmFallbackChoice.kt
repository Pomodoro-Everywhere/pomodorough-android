package me.egigoka.pomodorough.timer

import android.content.Context
import androidx.core.content.edit

/**
 * Remembers that the user chose the inexact alarm fallback, so the
 * "Timer alert may arrive late" dialog is asked once per install rather
 * than on every start and resume. Granting exact alarms later makes the
 * choice irrelevant because the disclosure policy checks access first.
 */
object ExactAlarmFallbackChoice {
    const val PREFS_NAME = "pomodorough_exact_alarm"
    const val KEY_ACCEPTED = "inexact_fallback_accepted"

    fun isAccepted(context: Context): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_ACCEPTED, true) }
    }
}
