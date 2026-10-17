package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter

/**
 * R49-A02 selected-task plus retarget persistence classification.
 * Storage failure retains committed selection and timer allocation
 * with one bounded infrastructure report. Core invalid output keeps
 * the existing retarget report path. Cancellation always propagates.
 */
internal enum class SelectedTaskRetargetFailure(val reportMessage: String) {
    Infrastructure("Selected task retarget persistence failed: committed state retained"),
}

internal class SelectedTaskRetargetReport(category: SelectedTaskRetargetFailure) :
    Exception(category.reportMessage)

internal object SelectedTaskRetargetReporter {
    private val reported = mutableSetOf<SelectedTaskRetargetFailure>()

    fun report(category: SelectedTaskRetargetFailure) {
        if (!claim(category)) return
        CrashReporter.report(SelectedTaskRetargetReport(category))
    }

    private fun claim(category: SelectedTaskRetargetFailure): Boolean = synchronized(reported) {
        reported.add(category)
    }

    internal fun resetForTest() = synchronized(reported) {
        reported.clear()
    }
}

internal fun classifySelectedTaskRetargetFailure(error: Throwable): SelectedTaskRetargetFailure {
    if (error is CancellationException) throw error
    return SelectedTaskRetargetFailure.Infrastructure
}

internal fun handleSelectedTaskRetargetFailure(
    error: Exception,
    report: (SelectedTaskRetargetFailure) -> Unit = SelectedTaskRetargetReporter::report,
): SelectedTaskRetargetFailure {
    val category = classifySelectedTaskRetargetFailure(error)
    report(category)
    return category
}
