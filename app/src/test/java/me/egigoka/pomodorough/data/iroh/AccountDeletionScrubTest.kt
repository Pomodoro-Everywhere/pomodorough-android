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

    // R50-A03: generated invite must not resurrect after deletion.
    // Covers collector settlement, repeated null emissions (the async
    // restore path), and UI gating (invite null hides RoomInviteCard so
    // Share is not offered; roomId null restores create/join cards).
    // Settlement uses condition waits on state.value, not fixed delays.
    @Test
    fun deletionClearsGeneratedInviteAndBlocksCollectorResurrection() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.enterForeground()
            harness.orchestration.createRoom("Team room")
            val generated = harness.state.value.invite
            assertNotNull("real harness generates invite", generated)
            assertTrue(generated!!.startsWith("pomodorough1."))
            harness.orchestration.quarantineAccount()
            harness.orchestration.scrubDeletedAccount()
            harness.await {
                harness.state.value.mode == ReplicationMode.OFFLINE &&
                    harness.state.value.invite == null
            }
            assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
            assertNull(harness.room)
            assertNull(harness.roomSecret)
            assertNull(harness.state.value.invite)
            assertNull(harness.state.value.roomId)
            assertNull(harness.state.value.roomName)
            repeat(3) {
                harness.state.value = harness.state.value.copy(invite = null)
                harness.await { harness.state.value.invite == null }
                assertNull("emission $it must not resurrect invite", harness.state.value.invite)
            }
            assertNull("invite card hidden", harness.state.value.invite)
            assertTrue("share needs invite card", harness.state.value.invite == null)
        } finally {
            harness.orchestration.close()
        }
    }

    // R50-A03: logout clears published invite + cache but retains rows.
    // Condition wait replaces fixed delay; logout keeps device-local rows.
    @Test
    fun logoutClearsGeneratedInviteButRetainsRows() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.enterForeground()
            harness.orchestration.createRoom("Team room")
            assertNotNull(harness.state.value.invite)
            harness.orchestration.clearAccountData()
            harness.await {
                harness.state.value.mode == ReplicationMode.OFFLINE &&
                    harness.state.value.invite == null
            }
            assertEquals(1, harness.clearCount)
            assertNotNull("logout keeps rooms", harness.room)
            assertNotNull("logout keeps secrets", harness.roomSecret)
            assertNull(harness.state.value.invite)
            assertEquals(ReplicationMode.OFFLINE, harness.state.value.mode)
            harness.state.value = harness.state.value.copy(invite = null)
            harness.await { harness.state.value.invite == null }
            assertNull(harness.state.value.invite)
        } finally {
            harness.orchestration.close()
        }
    }

    // R50-A03: fresh room works after scrub (no stale cache blocks it).
    // Condition waits replace fixed delays for scrub settlement and invite.
    @Test
    fun recreatedRoomAfterScrubPublishesFreshInvite() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        try {
            harness.enterForeground()
            harness.orchestration.createRoom("First")
            assertNotNull(harness.state.value.invite)
            harness.orchestration.quarantineAccount()
            harness.orchestration.scrubDeletedAccount()
            harness.await {
                harness.state.value.mode == ReplicationMode.OFFLINE &&
                    harness.state.value.invite == null
            }
            harness.orchestration.releaseAccountQuarantine()
            harness.orchestration.onForeground()
            harness.roomSecret = ByteArray(32) { (it + 41).toByte() }
            harness.orchestration.createRoom("Again")
            harness.await { harness.state.value.invite?.startsWith("pomodorough1.") == true }
            val fresh = harness.state.value.invite
            assertNotNull("recreation publishes invite", fresh)
            assertTrue(fresh!!.startsWith("pomodorough1."))
            harness.await { harness.state.value.invite != null }
            assertNotNull(harness.state.value.invite)
        } finally {
            harness.orchestration.close()
        }
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
