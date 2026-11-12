package me.egigoka.pomodorough.data

import me.egigoka.pomodorough.data.local.*

/** Independently specified storage observation; expected envelope lives in a static fixture. */
internal object AndroidLegacyDependencyWitness {
    const val At = "2026-08-31T12:00:00Z"
    const val Physical = "2026-08-31T11:59:59Z"
    const val Wall = 1788177600000L

    suspend fun seed(dao: TimerDao) {
        dao.insertState(LocalStateEntity(deviceId = "android-proof", ownerUserId = "account-proof",
            userJson = "{\"id\":\"account-proof\"}", settingsJson = "{}", ownedTimerId = "focus-proof",
            canonicalTimerJson = "{\"id\":\"focus-proof\",\"phase\":\"focus\",\"status\":\"running\",\"plannedDurationMs\":1500000,\"elapsedAtAnchorMs\":0,\"anchorAt\":\"2026-08-30T12:00:00Z\",\"startedByDeviceId\":\"android-proof\"}",
            tasksJson = "[{\"id\":\"aaf83054-24b2-8c0e-901f-a974147bfe82\",\"title\":\"Café\"}]",
            safeBaseDurationsJson = "{\"focus\":1500000,\"short_break\":300000,\"long_break\":900000}",
            safeCanonicalHeadWallMs = Wall, safeCanonicalHeadCounter = 2, canonicalAutoStartBreaks = true,
            // Migration 16 to 17 marks an upgraded queue that retained pre-R54 dependency rows.
            legacyDependencyResultJson = LegacyDependencyAdoptionPending))
        dao.insertCommands(listOf(
            command("finish-proof", 8, "focus-proof", "finish", "focus", 1500000, 3, null),
            command("break-proof", 9, "break-timer", "start", "short_break", 300000, 4, "finish-proof"),
            command("pause-proof", 10, "break-timer", "pause", "short_break", 300000, 5, "finish-proof"),
        ))
        dao.insertTaskOperation(PendingTaskOperationEntity("task-op", "aaf83054-24b2-8c0e-901f-a974147bfe82", "upsert", "Café", At, Wall, 6, true))
        dao.upsertDurationOperations(listOf(PendingDurationOperationEntity("short_break", "duration-op", 300000, At, Wall, 7, true)))
        dao.insertAutoStartOperation(PendingAutoStartOperationEntity("auto-op", "android-proof", true, At, Wall, 8, true))
        dao.insertSelectedTaskOperation(PendingSelectedTaskOperationEntity("select-op", "aaf83054-24b2-8c0e-901f-a974147bfe82", At, Wall, 9, true))
    }

    private fun command(id: String, sequence: Long, timer: String, type: String, phase: String,
        duration: Long, counter: Long, parent: String?) = PendingCommandEntity(
        id, sequence, timer, type, phase, duration, At, Wall, counter, 0,
        generatedByFinishCommandId = parent, physicalOccurredAt = Physical, neverSent = true,
    )
}
