package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.crash.CrashReporter

internal suspend fun retryCommittedAccountScrub(
    scrub: suspend () -> Unit,
    report: (Throwable) -> Unit = CrashReporter::report,
) {
    runCatching { scrub() }.onFailure(report)
}
