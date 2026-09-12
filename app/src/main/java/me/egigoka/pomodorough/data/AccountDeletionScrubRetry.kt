package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter

internal suspend fun retryCommittedAccountScrub(
    scrub: suspend () -> Unit,
    report: (Throwable) -> Unit = CrashReporter::report,
) {
    runCatching { scrub() }.onFailure { error ->
        if (error is CancellationException) throw error
        report(error)
    }
}
