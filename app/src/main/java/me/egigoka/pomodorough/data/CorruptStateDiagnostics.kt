package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.crash.CrashReporter

internal enum class CorruptStateCategory(val reportMessage: String) {
    Decoding("Corrupt persisted state: local decoding failed"),
    QueueValidation("Corrupt persisted state: pending queue validation failed"),
    ClockRange("Corrupt persisted state: persisted clock range failed"),
    CredentialClear("Credential clear failed: stale tokens need recovery"),
    WorkspaceStateRead("Local storage read failed: workspace state"),
    PendingQueueRead("Local storage read failed: pending queues"),
    BootstrapResolutionRead("Local storage read failed: pending bootstrap resolution"),
    ReplicationRouteRead("Local storage read failed: replication route"),
    StartupWorkspaceRead("Local storage read failed: startup workspace"),
    ProjectionRuntimeStartup("Shared Core projection failed at startup"),
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
