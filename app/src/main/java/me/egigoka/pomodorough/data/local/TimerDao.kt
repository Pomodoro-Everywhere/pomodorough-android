package me.egigoka.pomodorough.data.local

import androidx.room.Dao

@Dao
interface TimerDao : CentralizedSyncDao, IrohPersistenceDao
