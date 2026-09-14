package me.egigoka.pomodorough.data.iroh

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionScrubTest {
    @Test
    fun deletionScrubEmptiesRoomsPeersOpsAndVault() = runTest {
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-scrubbed",
        )
        assertNotNull(harness.room)
        assertNotNull(harness.roomSecret)

        harness.orchestration.scrubDeletedAccount()

        assertEquals(1, harness.scrubCount)
        assertNull(harness.room)
        assertNull(harness.roomSecret)
        assertEquals(ReplicationMode.OFFLINE.name, harness.settings.mode)
        assertNull(harness.settings.activeRoomId)
        assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
        assertEquals(0, harness.state.value.operationCount)
    }

    @Test
    fun logoutScrubRetainsDeviceLocalRows() = runTest {
        val harness = IrohRoomOrchestrationHarness(
            initialMode = ReplicationMode.IROH,
            activeRoomId = "room-kept",
        )

        harness.orchestration.clearAccountData()

        assertEquals(1, harness.clearCount)
        assertEquals(0, harness.scrubCount)
        assertNotNull("logout keeps device-local rooms", harness.room)
        assertNotNull("logout keeps device-local secrets", harness.roomSecret)
    }

    @Test
    fun deletionWipeIsWiredThroughRepository() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        val base = requireNotNull(root)
        fun source(path: String) = File(base, path).readText()
        val repository = source("me/egigoka/pomodorough/data/TimerRepository.kt")
        assertTrue(repository.contains("replication?.scrubDeletedAccount()"))
        assertTrue(repository.contains("A78 product decision"))
        val metadata = source("me/egigoka/pomodorough/data/iroh/IrohRoomMetadataPersistence.kt")
        assertTrue(metadata.contains("fun scrubDeletedAccount()"))
        assertTrue(metadata.contains("vault.clearAccountSecrets()"))
        assertTrue(metadata.contains("scrubDeletedIrohWorkspace"))
        assertTrue(metadata.contains("A78 product decision"))
        val transactions = source("me/egigoka/pomodorough/data/local/IrohTransactionDaos.kt")
        assertTrue(transactions.contains("fun scrubDeletedIrohWorkspace"))
        assertTrue(transactions.contains("deleteAllIrohOperations()"))
        assertTrue(transactions.contains("deleteAllIrohPeers()"))
        assertTrue(transactions.contains("deleteAllIrohConflicts()"))
        assertTrue(transactions.contains("deleteAllIrohRooms()"))
        val vault = source("me/egigoka/pomodorough/data/iroh/IrohSecretVault.kt")
        assertTrue(vault.contains("fun clearAccountSecrets()"))
    }
}
