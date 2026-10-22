package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException

/**
 * R51-A05 committed-scrub retry classification. Local scrub failures
 * report one bounded RoomScrub category with a fixed message, no raw
 * cause, no payload. Cancellation always propagates.
 */
internal suspend fun retryCommittedAccountScrub(
    scrub: suspend () -> Unit,
    report: (AccountDeletionFailure) -> Unit = AccountDeletionReporter::report,
) {
    runCatching { scrub() }.onFailure { error ->
        if (error is CancellationException) throw error
        handleAccountDeletionExecutionFailure(
            AccountDeletionExecutionStage.RoomScrub,
            error,
            report,
        )
    }
}
