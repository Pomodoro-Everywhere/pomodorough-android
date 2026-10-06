package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.crash.CrashReporter

internal enum class CorruptStateCategory(val reportMessage: String) {
    Decoding("Corrupt persisted state: local decoding failed"),
    QueueValidation("Corrupt persisted state: pending queue validation failed"),
    ClockRange("Corrupt persisted state: persisted clock range failed"),
    CredentialClear("Credential clear failed: stale tokens need recovery"),
}

internal class CorruptStateReport(category: CorruptStateCategory) : Exception(category.reportMessage)

internal object CorruptStateReporter {
    private val reported = mutableSetOf<CorruptStateCategory>()

    fun report(category: CorruptStateCategory) {
        if (!claim(category)) return
        CrashReporter.report(CorruptStateReport(category))
    }

    private fun claim(category: CorruptStateCategory): Boolean = synchronized(reported) {
        reported.add(category)
    }

    internal fun resetForTest() = synchronized(reported) {
        reported.clear()
    }
}
