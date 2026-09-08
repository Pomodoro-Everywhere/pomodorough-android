package me.egigoka.pomodorough.crash

import android.util.Log
import io.sentry.Sentry
import kotlinx.coroutines.CancellationException

object CrashReporter {
    private const val Tag = "CrashReporter"

    @Volatile
    var delegate: (Throwable) -> Unit = { error ->
        Sentry.captureException(error)
    }

    @Volatile
    var onReporterFailure: ((original: Throwable, failure: Throwable) -> Unit)? = null

    fun shouldReport(error: Throwable): Boolean =
        error !is CancellationException

    fun report(error: Throwable) {
        if (!shouldReport(error)) return
        try {
            delegate(error)
        } catch (failure: Throwable) {
            runCatching { Log.e(Tag, "CrashReporter delegate failed", failure) }
            runCatching { onReporterFailure?.invoke(error, failure) }
        }
    }
}
