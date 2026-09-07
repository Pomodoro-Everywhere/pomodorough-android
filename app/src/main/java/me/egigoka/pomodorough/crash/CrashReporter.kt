package me.egigoka.pomodorough.crash

import io.sentry.Sentry
import kotlinx.coroutines.CancellationException

object CrashReporter {
    @Volatile
    var delegate: (Throwable) -> Unit = { error ->
        Sentry.captureException(error)
    }

    fun shouldReport(error: Throwable): Boolean =
        error !is CancellationException

    fun report(error: Throwable) {
        if (!shouldReport(error)) return
        runCatching { delegate(error) }
    }
}
