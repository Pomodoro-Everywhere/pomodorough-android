package me.egigoka.pomodorough.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalStateEntity::class,
        PendingCommandEntity::class,
        PendingTaskOperationEntity::class,
        PendingDurationOperationEntity::class,
        PendingBootstrapResolutionEntity::class,
        PendingAutoStartOperationEntity::class,
        PendingSelectedTaskOperationEntity::class,
        ReplicationSettingsEntity::class,
        IrohRoomEntity::class,
        IrohPeerEntity::class,
        IrohOperationEntity::class,
        IrohConflictEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class PomodoroughDatabase : RoomDatabase() {
    // Room generates one implementation; callers consume its focused interface views.
    abstract fun timerDao(): TimerDao

    companion object {
        fun create(context: Context): PomodoroughDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PomodoroughDatabase::class.java,
                "pomodorough.db",
            ).addMigrations(
                Migration1To2,
                Migration2To3,
                Migration3To4,
                Migration4To5,
                Migration5To6,
                Migration6To7,
                Migration7To8,
                Migration8To9,
                Migration9To10,
                Migration10To11,
                Migration11To12,
                Migration12To13,
            ).build()

        val Migration1To2 = TimerMigrations.Migration1To2
        val Migration2To3 = TimerMigrations.Migration2To3
        val Migration3To4 = TimerMigrations.Migration3To4
        val Migration4To5 = TimerMigrations.Migration4To5
        val Migration5To6 = TimerMigrations.Migration5To6
        val Migration6To7 = TimerMigrations.Migration6To7
        val Migration7To8 = TimerMigrations.Migration7To8
        val Migration8To9 = TimerMigrations.Migration8To9
        val Migration9To10 = TimerMigrations.Migration9To10
        val Migration10To11 = IrohMigration.Migration10To11
        val Migration11To12 = TimerMigrations.Migration11To12
        val Migration12To13 = TimerMigrations.Migration12To13
    }
}
