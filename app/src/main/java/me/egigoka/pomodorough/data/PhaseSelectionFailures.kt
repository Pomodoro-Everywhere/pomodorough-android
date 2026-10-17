package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter

/**
 * R49-A01 phase-selection persistence classification.
 * The selection is best-effort local settings: DAO failure retains
 * committed settings with one bounded infrastructure report.
 * Cancellation always propagates to the caller.
 */
internal enum class PhaseSelectionFailure(val reportMessage: String) {
    Infrastructure("Phase selection persistence failed: committed settings retained"),
}

internal class PhaseSelectionReport(category: PhaseSelectionFailure) : Exception(category.reportMessage)

internal object PhaseSelectionReporter {
    private val reported = mutableSetOf<PhaseSelectionFailure>()

    fun report(category: PhaseSelectionFailure) {
        if (!claim(category)) return
        CrashReporter.report(PhaseSelectionReport(category))
    }

    private fun claim(category: PhaseSelectionFailure): Boolean = synchronized(reported) {
        reported.add(category)
    }

    internal fun resetForTest() = synchronized(reported) {
        reported.clear()
    }
}

internal fun classifyPhaseSelectionFailure(error: Throwable): PhaseSelectionFailure {
    if (error is CancellationException) throw error
    return PhaseSelectionFailure.Infrastructure
}

internal fun handlePhaseSelectionFailure(
    error: Exception,
    report: (PhaseSelectionFailure) -> Unit = PhaseSelectionReporter::report,
): PhaseSelectionFailure {
    val category = classifyPhaseSelectionFailure(error)
    report(category)
    return category
}
