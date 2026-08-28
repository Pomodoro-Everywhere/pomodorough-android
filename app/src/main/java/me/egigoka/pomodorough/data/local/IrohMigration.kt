package me.egigoka.pomodorough.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object IrohMigration {
    val Migration10To11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrateReplicationSettings(db)
            createIrohRoomsSchema(db)
            createIrohPeersSchema(db)
            createIrohOperationsSchema(db)
            createIrohConflictsSchema(db)
        }

        private fun migrateReplicationSettings(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS replication_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        mode TEXT NOT NULL,
                        activeRoomId TEXT
                    )""".trimIndent(),
            )
            db.execSQL(
                "INSERT INTO replication_settings (id, mode, activeRoomId) " +
                    "VALUES (0, 'CENTRALIZED', NULL)",
            )
        }

        private fun createIrohRoomsSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS iroh_rooms (
                        roomId TEXT NOT NULL PRIMARY KEY,
                        roomName TEXT,
                        encryptedRoomSecret BLOB NOT NULL,
                        returnStateJson TEXT NOT NULL,
                        roomStateJson TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        activated INTEGER NOT NULL
                    )""".trimIndent(),
            )
        }

        private fun createIrohPeersSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS iroh_peers (
                        roomId TEXT NOT NULL,
                        endpointId TEXT NOT NULL,
                        endpointTicket TEXT NOT NULL,
                        deviceId TEXT,
                        displayName TEXT,
                        lastSeenAtMs INTEGER,
                        PRIMARY KEY(roomId, endpointId),
                        FOREIGN KEY(roomId) REFERENCES iroh_rooms(roomId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX index_iroh_peers_roomId ON iroh_peers(roomId)")
        }

        private fun createIrohOperationsSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS iroh_operations (
                        roomId TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        originDeviceId TEXT NOT NULL,
                        operationJson TEXT NOT NULL,
                        digest TEXT NOT NULL,
                        hlcWallMs INTEGER NOT NULL,
                        hlcCounter INTEGER NOT NULL,
                        deviceSequence INTEGER,
                        PRIMARY KEY(roomId, domain, operationId),
                        FOREIGN KEY(roomId) REFERENCES iroh_rooms(roomId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX index_iroh_operations_roomId ON iroh_operations(roomId)")
            db.execSQL(
                """CREATE UNIQUE INDEX index_iroh_operations_roomId_originDeviceId_deviceSequence
                        ON iroh_operations(roomId, originDeviceId, deviceSequence)""".trimIndent(),
            )
        }

        private fun createIrohConflictsSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS iroh_conflicts (
                        roomId TEXT NOT NULL PRIMARY KEY,
                        domain TEXT NOT NULL,
                        operationId TEXT NOT NULL,
                        localDigest TEXT NOT NULL,
                        receivedDigest TEXT NOT NULL,
                        detectedAtMs INTEGER NOT NULL,
                        FOREIGN KEY(roomId) REFERENCES iroh_rooms(roomId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
            )
        }
    }
}
