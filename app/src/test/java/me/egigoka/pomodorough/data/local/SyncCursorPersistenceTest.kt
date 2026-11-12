package me.egigoka.pomodorough.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TimerLocalInitializer
import me.egigoka.pomodorough.data.LocalDecodingException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncCursorPersistenceTest {
    @Test
    fun invalidStoredCursorBlocksInitialization() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        try {
            database.timerDao().insertState(
                LocalStateEntity(deviceId = "device-1", settingsJson = "{}", syncNextDomain = "unknown"),
            )
            val initializer = TimerLocalInitializer(database.timerDao(), database.timerDao(), Json, Json) {}
            val error = runCatching { initializer.load() }.exceptionOrNull()
            assertTrue(error is LocalDecodingException)
            assertEquals("Sync batch cursor is invalid", error?.cause?.message)
        } finally {
            database.close()
        }
    }

    @Test
    fun version15MigrationPreservesRowsAndDefaultsCursor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        try {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE legacy_local_state (id INTEGER PRIMARY KEY NOT NULL, deviceId TEXT NOT NULL)")
            sql.execSQL("INSERT INTO legacy_local_state VALUES (0, 'retained-device')")
            sql.execSQL("DROP TABLE local_state")
            sql.execSQL("ALTER TABLE legacy_local_state RENAME TO local_state")

            PomodoroughDatabase.Migration15To16.migrate(sql)

            sql.query("SELECT deviceId, syncNextDomain FROM local_state").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("retained-device", cursor.getString(0))
                assertEquals("commands", cursor.getString(1))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun cursorSurvivesRealDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sync-cursor-reopen.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        val state = LocalStateEntity(deviceId = "device-1", settingsJson = "{}")
        database.timerDao().insertState(state)
        database.timerDao().retireNeverSentWithCursor(
            state.copy(syncNextDomain = "taskOperations"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        database.close()
        val reopened = Room.databaseBuilder(context, PomodoroughDatabase::class.java, name).build()
        try {
            assertEquals("taskOperations", reopened.timerDao().localState()?.syncNextDomain)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun cursorWriteFailureRollsBackNeverSentRetirement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
        val state = LocalStateEntity(deviceId = "device-1", settingsJson = "{}")
        val command = PendingCommandEntity(
            "command-1", 1, "timer-1", "start", "focus", 60_000,
            "2026-11-06T00:00:00Z", 1, 0, 0, neverSent = true,
        )
        try {
            database.timerDao().insertState(state)
            database.timerDao().insertCommand(command)
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER refuse_cursor BEFORE UPDATE ON local_state BEGIN SELECT RAISE(ABORT, 'refused'); END",
            )
            val result = runCatching {
                database.timerDao().retireNeverSentWithCursor(
                    state.copy(syncNextDomain = "taskOperations"), listOf(command.id),
                    emptyList(), emptyList(), emptyList(), emptyList(),
                )
            }
            assertTrue(result.isFailure)
            assertTrue(database.timerDao().pendingCommands().single().neverSent)
            assertEquals("commands", database.timerDao().localState()?.syncNextDomain)
        } finally {
            database.close()
        }
    }
}
