package me.egigoka.pomodorough.crash

import android.content.Context

object CrashReportingConsent {
    const val PREFS_NAME = "pomodorough_crash_reporting"
    const val KEY_ENABLED = "crash_reporting_enabled"
    const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun shouldStart(dsn: String, enabled: Boolean): Boolean =
        dsn.isNotBlank() && enabled
}
