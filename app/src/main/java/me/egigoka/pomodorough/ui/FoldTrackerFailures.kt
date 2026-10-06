package me.egigoka.pomodorough.ui

import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter

/**
 * R43-A12 fold-tracker failure classification.
 * The tracker is best-effort UI: unsupported window extensions stay
 * flat and silent, unexpected tracker failures stay flat with one
 * bounded report. Cancellation always propagates to the collector.
 */
internal enum class FoldTrackerFailure(val reportMessage: String) {
    Unsupported("Fold tracker unsupported: posture stays flat"),
    Unexpected("Fold tracker failed: posture stays flat"),
}

internal class FoldTrackerReport(category: FoldTrackerFailure) : Exception(category.reportMessage)

internal object FoldTrackerReporter {
    private val reported = mutableSetOf<FoldTrackerFailure>()

    fun report(category: FoldTrackerFailure) {
        if (category != FoldTrackerFailure.Unexpected) return
        if (!claim(category)) return
        CrashReporter.report(FoldTrackerReport(category))
    }

    private fun claim(category: FoldTrackerFailure): Boolean = synchronized(reported) {
        reported.add(category)
    }

    internal fun resetForTest() = synchronized(reported) {
        reported.clear()
    }
}

internal fun classifyFoldTrackerFailure(error: Throwable): FoldTrackerFailure {
    if (error is CancellationException) throw error
    return if (error is UnsupportedOperationException) FoldTrackerFailure.Unsupported
    else FoldTrackerFailure.Unexpected
}

internal fun handleFoldTrackerFailure(
    error: Exception,
    report: (FoldTrackerFailure) -> Unit = FoldTrackerReporter::report,
): FoldTrackerFailure {
    val category = classifyFoldTrackerFailure(error)
    report(category)
    return category
}
