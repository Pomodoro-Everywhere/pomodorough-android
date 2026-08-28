package me.egigoka.pomodorough.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

internal object TimerMigrations {
    val Migration1To2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE local_state ADD COLUMN ownerUserId TEXT")
        }
    }

    val Migration2To3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE local_state ADD COLUMN tasksJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE local_state ADD COLUMN knownTasksJson TEXT NOT NULL DEFAULT '[]'")
            db.execSQL("ALTER TABLE local_state ADD COLUMN selectedTaskId TEXT")
            db.execSQL("ALTER TABLE pending_commands ADD COLUMN taskId TEXT")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS pending_task_operations (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        title TEXT,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
            )
        }
    }

    val Migration3To4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createDurationOperationsSchema(db)
            backfillCustomDurations(db)
        }

        private fun createDurationOperationsSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS pending_duration_operations (
                        phase TEXT NOT NULL PRIMARY KEY,
                        id TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS index_pending_duration_operations_id
                        ON pending_duration_operations (id)""".trimIndent(),
            )
        }

        private fun backfillCustomDurations(db: SupportSQLiteDatabase) {
            db.query("SELECT settingsJson FROM local_state WHERE id = 0").use { cursor ->
                if (!cursor.moveToFirst()) return
                val settings = runCatching { JSONObject(cursor.getString(0)) }.getOrNull() ?: return
                val customDurations = listOf(
                    Triple("focus", settings.optInt("focusMinutes", 25), 25),
                    Triple("short_break", settings.optInt("shortBreakMinutes", 5), 5),
                    Triple("long_break", settings.optInt("longBreakMinutes", 15), 15),
                ).mapNotNull { (phase, minutes, defaultMinutes) ->
                    val bounded = minutes.coerceIn(1, 180)
                    if (bounded == defaultMinutes) null else phase to bounded * 60_000L
                }
                if (customDurations.isEmpty()) return

                val occurredAt = Instant.EPOCH.toString()
                customDurations.forEach { (phase, durationMs) ->
                    db.execSQL(
                        """INSERT INTO pending_duration_operations (
                                phase, id, durationMs, occurredAt, hlcWallMs, hlcCounter
                            ) VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
                        arrayOf<Any>(
                            phase,
                            "duration-operation-${UUID.randomUUID()}",
                            durationMs,
                            occurredAt,
                            0L,
                            0L,
                        ),
                    )
                }
            }
        }
    }

    val Migration4To5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS pending_bootstrap_resolution (
                        id INTEGER NOT NULL PRIMARY KEY,
                        requestId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        expectedRevision INTEGER NOT NULL,
                        strategy TEXT NOT NULL,
                        commandsJson TEXT NOT NULL,
                        taskOperationsJson TEXT NOT NULL,
                        durationOperationsJson TEXT NOT NULL,
                        ownerUserId TEXT NOT NULL,
                        userJson TEXT NOT NULL
                    )""".trimIndent(),
            )
        }
    }

    val Migration5To6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            addAutoStartColumns(db)
            createAutoStartOperationsSchema(db)
            backfillAutoStartOperation(db)
        }

        private fun addAutoStartColumns(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE local_state ADD COLUMN canonicalAutoStartBreaks INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE pending_bootstrap_resolution ADD COLUMN autoStartOperationsJson TEXT",
            )
        }

        private fun createAutoStartOperationsSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS pending_auto_start_operations (
                        id TEXT NOT NULL PRIMARY KEY,
                        deviceId TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
            )
        }

        private fun backfillAutoStartOperation(db: SupportSQLiteDatabase) {
            db.query("SELECT deviceId, settingsJson FROM local_state WHERE id = 0").use { cursor ->
                if (!cursor.moveToFirst()) return
                val settings = runCatching { JSONObject(cursor.getString(1)) }.getOrNull() ?: return
                if (!settings.optBoolean("autoStartBreaks", false)) return

                db.execSQL(
                    """INSERT INTO pending_auto_start_operations (
                            id, deviceId, enabled, occurredAt, hlcWallMs, hlcCounter
                        ) VALUES (?, ?, 1, ?, ?, ?)""".trimIndent(),
                    arrayOf<Any>(
                        UUID.randomUUID().toString(),
                        cursor.getString(0),
                        Instant.EPOCH.toString(),
                        0L,
                        0L,
                    ),
                )
                db.execSQL("UPDATE local_state SET canonicalAutoStartBreaks = 1 WHERE id = 0")
            }
        }
    }

    val Migration6To7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE local_state ADD COLUMN ownedTimerId TEXT")
            db.execSQL(
                "ALTER TABLE pending_commands ADD COLUMN generatedByFinishCommandId TEXT",
            )
            // Version 6 lacks trigger-time auto-start state, so legacy commands stay independent.
            db.execSQL(
                """UPDATE local_state
                        SET ownedTimerId = (
                            SELECT timerId FROM pending_commands
                            WHERE type = 'start'
                            ORDER BY deviceSequence DESC LIMIT 1
                        )
                        WHERE id = 0""".trimIndent(),
            )
        }
    }

    val Migration7To8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockOffsetMs INTEGER")
            db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockUncertaintyMs INTEGER")
            db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockSamplePhysicalMs INTEGER")
            db.execSQL(
                "ALTER TABLE local_state ADD COLUMN serverClockSampleElapsedRealtimeMs INTEGER",
            )
            db.execSQL("ALTER TABLE local_state ADD COLUMN serverClockBootId TEXT")
            db.execSQL("ALTER TABLE pending_commands ADD COLUMN physicalOccurredAt TEXT")
            db.execSQL("UPDATE pending_commands SET physicalOccurredAt = occurredAt")
        }
    }

    val Migration8To9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE local_state ADD COLUMN lastUuidV7 TEXT")
        }
    }

    val Migration9To10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """UPDATE pending_commands
                        SET deviceSequence = -9223372036854775807 + rowid
                        WHERE typeof(deviceSequence) != 'integer'
                            OR deviceSequence <= 0 OR deviceSequence IN (
                            SELECT deviceSequence
                            FROM pending_commands
                            GROUP BY deviceSequence
                            HAVING COUNT(*) > 1
                        )""".trimIndent(),
            )
            db.execSQL(
                """CREATE UNIQUE INDEX IF NOT EXISTS
                        index_pending_commands_deviceSequence
                        ON pending_commands(deviceSequence)""".trimIndent(),
            )
        }
    }

    val Migration11To12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE pending_bootstrap_resolution " +
                    "ADD COLUMN selectedTaskOperationsJson TEXT",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS pending_selected_task_operations (
                        id TEXT NOT NULL PRIMARY KEY,
                        taskId TEXT,
                        occurredAt TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL
                    )""".trimIndent(),
            )
        }
    }

    val Migration12To13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE local_state ADD COLUMN accountDeletionState TEXT")
        }
    }
}
