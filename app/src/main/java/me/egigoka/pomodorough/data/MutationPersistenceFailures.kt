package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.crash.CrashReporter

/**
 * R50-A04 mutation persistence classification.
 * Ordinary Room writes retain committed state with one bounded
 * infrastructure report. Cancellation always propagates. Storage is
 * never misclassified as invalid Core output.
 */
internal enum class MutationPersistenceFailure(val reportMessage: String) {
    Infrastructure("Local mutation persistence failed: committed state retained"),
}

internal class MutationPersistenceReport(category: MutationPersistenceFailure) : Exception(category.reportMessage)

internal object MutationPersistenceReporter {
    private val reported = mutableSetOf<MutationPersistenceFailure>()

    fun report(category: MutationPersistenceFailure) {
        if (!claim(category)) return
        CrashReporter.report(MutationPersistenceReport(category))
    }

    private fun claim(category: MutationPersistenceFailure): Boolean = synchronized(reported) {
        reported.add(category)
    }

    internal fun resetForTest() = synchronized(reported) {
        reported.clear()
    }
}

internal fun classifyMutationPersistenceFailure(error: Throwable): MutationPersistenceFailure {
    if (error is CancellationException) throw error
    return MutationPersistenceFailure.Infrastructure
}

internal fun handleMutationPersistenceFailure(
    error: Exception,
    report: (MutationPersistenceFailure) -> Unit = MutationPersistenceReporter::report,
): MutationPersistenceFailure {
    val category = classifyMutationPersistenceFailure(error)
    report(category)
    return category
}
