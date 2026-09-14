package me.egigoka.pomodorough.crash

import android.content.Context
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

object CrashReportingRuntime {
    @Volatile
    var reportingEnabled: Boolean = true

    @Volatile
    var closeSentry: () -> Unit = { Sentry.close() }

    fun applyConsent(enabled: Boolean, onEnable: () -> Unit, onDisable: () -> Unit) {
        reportingEnabled = enabled
        if (enabled) onEnable() else onDisable()
    }

    fun start(context: Context, dsn: String, release: String) {
        reportingEnabled = true
        SentryAndroid.init(context) { options ->
            options.release = release
            options.environment = "production"
            CrashReportingInit.configure(options, dsn)
            options.sessionReplay.sessionSampleRate = 0.1
            options.sessionReplay.onErrorSampleRate = 1.0
            options.sessionReplay.setMaskAllText(true)
            options.sessionReplay.setMaskAllImages(true)
        }
    }

    fun stop() {
        reportingEnabled = false
        closeSentry()
    }
}
