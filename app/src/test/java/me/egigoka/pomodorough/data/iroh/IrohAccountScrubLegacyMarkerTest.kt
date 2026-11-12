package me.egigoka.pomodorough.data.iroh

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.egigoka.pomodorough.data.local.IrohConflictsDao
import me.egigoka.pomodorough.data.local.IrohPersistenceDao
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sign-out scrubs the account's queue from the workspace and from every room's return state.
 * The legacy markers and the safe base describe that queue and domain, so they leave with it.
 * A signed-out workspace keeps its domain, so it keeps both.
 */
class IrohAccountScrubLegacyMarkerTest {
    @Test
    fun signedInScrubClearsMarkersAndSafeBaseFromTheWorkspace() = runBlocking {
        val cleared = scrub(workspace(ownerUserId = "account-1")).snapshot.local

        assertNull(cleared.legacyDependencyInputJson)
        assertNull(cleared.legacyDependencyResultJson)
        assertNull(cleared.safeBaseDurationsJson)
        assertNull(cleared.safeBaseSelectedTaskId)
        assertNull(cleared.safeCanonicalHeadWallMs)
        assertNull(cleared.safeCanonicalHeadCounter)
    }

    @Test
    fun signedOutScrubKeepsMarkersAndSafeBaseInTheWorkspace() = runBlocking {
        val kept = scrub(workspace(ownerUserId = null)).snapshot.local

        assertKeepsDomainEvidence(kept)
    }

    @Test
    fun signedInScrubClearsMarkersAndSafeBaseFromRoomReturnStates() = runBlocking {
        val room = room(returnState = workspace(ownerUserId = "account-1"))

        val returned = WorkspaceCodec.decode(scrub(workspace(ownerUserId = null), room).rooms.single().returnStateJson).local

        assertNull(returned.legacyDependencyInputJson)
        assertNull(returned.legacyDependencyResultJson)
        assertNull(returned.safeBaseDurationsJson)
        assertNull(returned.safeCanonicalHeadWallMs)
    }

    @Test
    fun signedOutScrubKeepsMarkersAndSafeBaseInRoomReturnStates() = runBlocking {
        val room = room(returnState = workspace(ownerUserId = null))

        val returned = WorkspaceCodec.decode(scrub(workspace(ownerUserId = null), room).rooms.single().returnStateJson).local

        assertKeepsDomainEvidence(returned)
    }

    private fun assertKeepsDomainEvidence(local: LocalStateEntity) {
        assertEquals(DecisionInput, local.legacyDependencyInputJson)
        assertEquals(DecisionResult, local.legacyDependencyResultJson)
        assertEquals(SafeBaseDurations, local.safeBaseDurationsJson)
        assertEquals("task-safe", local.safeBaseSelectedTaskId)
        assertEquals(1_767_225_600_123, local.safeCanonicalHeadWallMs)
        assertEquals(4L, local.safeCanonicalHeadCounter)
    }

    private class ClearedAccount(val rooms: List<IrohRoomEntity>, val snapshot: LocalWorkspaceSnapshot)

    /** Runs the real scrub against a DAO that serves [stored] and captures the one clearing write. */
    private suspend fun scrub(stored: LocalWorkspaceSnapshot, vararg rooms: IrohRoomEntity): ClearedAccount {
        var cleared: ClearedAccount? = null
        val answers = mapOf(
            "localWorkspaceSnapshot" to stored,
            "loadRoomsBounded" to rooms.toList(),
            "irohRoomsCapped" to rooms.toList(),
            "replicationSettings" to ReplicationSettingsEntity(mode = "CENTRALIZED"),
        )
        val dao = Proxy.newProxyInstance(
            IrohPersistenceDao::class.java.classLoader,
            arrayOf(IrohPersistenceDao::class.java),
        ) { _, method, args ->
            if (method.name == "clearIrohAccountData") {
                @Suppress("UNCHECKED_CAST")
                cleared = ClearedAccount(args[0] as List<IrohRoomEntity>, args[2] as LocalWorkspaceSnapshot)
                Unit
            } else {
                answers[method.name]
            }
        } as IrohPersistenceDao
        metadata(dao).clearAccountData()
        return checkNotNull(cleared) { "The scrub must write the cleared workspace" }
    }

    private fun metadata(dao: IrohPersistenceDao) = IrohRoomMetadataPersistence(
        dao = dao,
        conflicts = Proxy.newProxyInstance(
            IrohConflictsDao::class.java.classLoader,
            arrayOf(IrohConflictsDao::class.java),
        ) { _, _, _ -> null } as IrohConflictsDao,
        vault = blankInstance(),
        peerRegistry = blankInstance(),
        projection = blankInstance(),
        workspaceCoordinator = LocalWorkspaceCoordinator(),
        random = java.security.SecureRandom(),
        currentTimeMillis = { 1L },
    )

    private fun room(returnState: LocalWorkspaceSnapshot) = IrohRoomEntity(
        roomId = "room-scrub",
        roomName = "Scrub",
        encryptedRoomSecret = byteArrayOf(1),
        returnStateJson = WorkspaceCodec.encodeReturnState(returnState),
        roomStateJson = WorkspaceCodec.encodeRoomState(workspace(ownerUserId = null)),
        createdAtMs = 1L,
    )

    private fun workspace(ownerUserId: String?) = LocalWorkspaceSnapshot(
        local = LocalStateEntity(
            deviceId = "device-1",
            settingsJson = "{}",
            userJson = ownerUserId?.let { "{\"id\":\"$it\"}" },
            ownerUserId = ownerUserId,
            safeBaseDurationsJson = SafeBaseDurations,
            safeBaseSelectedTaskId = "task-safe",
            safeCanonicalHeadWallMs = 1_767_225_600_123,
            safeCanonicalHeadCounter = 4,
            legacyDependencyInputJson = DecisionInput,
            legacyDependencyResultJson = DecisionResult,
        ),
    )

    private inline fun <reified T> blankInstance(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), T::class.java) as T
    }

    private companion object {
        const val DecisionInput = "{\"queue\":[\"command-child\"]}"
        const val DecisionResult = "{\"outcome\":\"planned\"}"
        const val SafeBaseDurations = "{\"focus\":1500000,\"short_break\":300000,\"long_break\":900000}"
    }
}
