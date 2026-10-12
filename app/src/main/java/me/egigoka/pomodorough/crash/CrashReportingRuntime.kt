package me.egigoka.pomodorough.crash

import android.content.Context
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

object CrashReportingRuntime {
    @Volatile
    var reportingEnabled: Boolean = true

    @Volatile
    var closeSentry: () -> Unit = { Sentry.close() }

    @Volatile
    var starter: (Context, String, String) -> Unit =
        { ctx, dsnArg, releaseArg -> initSentry(ctx, dsnArg, releaseArg) }

    fun isEligible(dsn: String, enabled: Boolean): Boolean =
        CrashReportingConsent.shouldStart(dsn, enabled)

    fun isEligible(dsn: String, enabled: Boolean, isInstrumentedTest: Boolean, isCiOnEmulator: Boolean): Boolean =
        CrashReportingConsent.shouldStart(dsn, enabled, isInstrumentedTest, isCiOnEmulator)

    fun applyConsent(enabled: Boolean, onEnable: () -> Unit, onDisable: () -> Unit) {
        reportingEnabled = enabled
        if (enabled) onEnable() else onDisable()
    }

    fun start(context: Context, dsn: String, release: String): Boolean =
        start(context, dsn, release, true)

    fun start(context: Context, dsn: String, release: String, enabled: Boolean): Boolean {
        // R48-A05: single entry owns DSN + opt-in + test/CI suppression.
        if (!isEligible(dsn, enabled)) {
            reportingEnabled = false
            closeSentry()
            return false
        }
        reportingEnabled = true
        starter(context, dsn, release)
        return true
    }

    fun stop() {
        reportingEnabled = false
        closeSentry()
    }

    private fun initSentry(context: Context, dsn: String, release: String) {
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
}
