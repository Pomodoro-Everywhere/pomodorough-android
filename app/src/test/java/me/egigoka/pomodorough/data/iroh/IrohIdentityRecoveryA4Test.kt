package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohIdentityRecoveryA4Test {
    @Test
    fun coldRestartQuarantinesPendingResetBeforeRoomCleanupOrEndpointStart() = runTest {
        val fixture = RecoveryFixture(
            recovery = IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING,
            pendingRecovery = IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING,
            activeRoomMissing = true,
        )

        fixture.orchestration.initialize()

        assertEquals(0, fixture.discardCount)
        assertEquals(0, fixture.startCount)
        assertEquals(0, fixture.actionCount)
        assertEquals(1, fixture.stopCount)
        assertEquals(IrohConnectionStatus.UNAVAILABLE, fixture.state.value.status)
        assertEquals(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING, fixture.state.value.identityRecovery)
        fixture.orchestration.close()
    }

    @Test
    fun endpointRepairValidatesEveryRoomSecretAndPreservesRoomData() = runTest {
        val fixture = RecoveryFixture(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)

        fixture.orchestration.confirmIdentityRecovery()

        assertEquals(listOf("stop", "validate", "replace"), fixture.events)
        assertEquals(0, fixture.resetCount)
        assertNull(fixture.state.value.identityRecovery)
        assertFalse(fixture.state.value.recoveryAttemptFailed)
        fixture.orchestration.close()
    }

    @Test
    fun endpointRepairConfirmedInForegroundRestartsTheEndpoint() = runTest {
        // R54-A05: repair runs while foreground, where a plain foreground replay starts nothing.
        val fixture = RecoveryFixture(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
        fixture.enterForegroundWithoutAction()

        fixture.orchestration.confirmIdentityRecovery()
        withContext(Dispatchers.IO) {
            withTimeout(5_000) { while (fixture.startCount == 0) delay(1) }
        }

        assertEquals(1, fixture.replaceCount)
        assertNull(fixture.state.value.identityRecovery)
        fixture.orchestration.close()
    }

    @Test
    fun endpointRepairInterruptedByBackgroundRestartsOnlyAtTheNextForeground() = runTest {
        // R54-A05 X2: a background that lands during the repair stays in force; the restart must
        // neither force the lifecycle foreground nor read the room secret behind the app.
        val fixture = RecoveryFixture(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
        fixture.enterForegroundWithoutAction()
        fixture.onReplace = { fixture.orchestration.onBackground() }

        fixture.orchestration.confirmIdentityRecovery()
        assertFalse(fixture.lifecycleForeground())
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(1_000) { while (fixture.secretReads == 0) delay(1) }
        }
        assertEquals(0, fixture.secretReads)
        assertEquals(0, fixture.startCount)

        fixture.orchestration.onForeground()
        withContext(Dispatchers.IO) {
            withTimeout(5_000) { while (fixture.startCount == 0) delay(1) }
        }
        assertEquals(1, fixture.replaceCount)
        assertNull(fixture.state.value.identityRecovery)
        fixture.orchestration.close()
    }

    @Test
    fun endpointRepairEscalatesToDestructiveScopeWhenRoomSecretsCannotDecrypt() = runTest {
        val fixture = RecoveryFixture(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED)
        fixture.validateFailure = recoveryError(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)

        fixture.orchestration.confirmIdentityRecovery()

        assertEquals(listOf("stop", "validate", "stop"), fixture.events)
        assertEquals(0, fixture.replaceCount)
        assertEquals(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING, fixture.state.value.identityRecovery)
        assertTrue(fixture.state.value.recoveryAttemptFailed)
        fixture.orchestration.close()
    }

    @Test
    fun confirmedKeyResetOrdersQuarantineDatabaseResetAndKeyDeletion() = runTest {
        val fixture = RecoveryFixture(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)

        fixture.orchestration.confirmIdentityRecovery()

        assertEquals(listOf("stop", "begin", "reset", "complete"), fixture.events)
        assertEquals(ReplicationMode.OFFLINE, fixture.state.value.mode)
        assertNull(fixture.state.value.identityRecovery)
        fixture.orchestration.close()
    }

    @Test
    fun failedResetRemainsQuarantinedAndFencesConcurrentIrohActions() = runTest {
        val fixture = RecoveryFixture(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
        val releaseReset = CompletableDeferred<Unit>()
        fixture.resetGate = releaseReset
        fixture.resetFailure = IllegalStateException("database reset failed")

        val recovery = async { fixture.orchestration.confirmIdentityRecovery() }
        fixture.resetStarted.await()
        fixture.orchestration.createRoom("blocked")
        fixture.orchestration.joinRoom("invalid")
        fixture.orchestration.syncNow()
        fixture.orchestration.setMode(ReplicationMode.OFFLINE)
        releaseReset.complete(Unit)
        recovery.await()

        assertEquals(0, fixture.actionCount)
        assertEquals(listOf("stop", "begin", "reset"), fixture.events)
        assertEquals(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING, fixture.state.value.identityRecovery)
        assertTrue(fixture.state.value.recoveryAttemptFailed)
        fixture.orchestration.close()
    }

    @Test
    fun cancelledResetReturnsToTheRecoveryPromptAndRetries() = runTest {
        // R54-A06: a cancelled confirmation must not leave the prompt disabled behind
        // `transitioning`. The cancellation still reaches the caller.
        val fixture = RecoveryFixture(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
        fixture.resetGate = CompletableDeferred()
        // The stored room snapshot carries no recovery prompt, so it must not replace the prompt.
        fixture.storedSnapshot = IrohNetworkState(mode = ReplicationMode.IROH, roomId = "room-a4-test")
        val outcome = CompletableDeferred<Throwable?>()

        val recovery = launch {
            try {
                fixture.orchestration.confirmIdentityRecovery()
                outcome.complete(null)
            } catch (error: Throwable) {
                outcome.complete(error)
                throw error
            }
        }
        fixture.resetStarted.await()
        assertTrue(fixture.state.value.transitioning)
        recovery.cancelAndJoin()

        assertTrue(outcome.await() is CancellationException)
        assertEquals(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING, fixture.state.value.identityRecovery)
        assertFalse(fixture.state.value.transitioning)
        assertFalse(fixture.state.value.recoveryAttemptFailed)
        fixture.resetGate = null
        fixture.orchestration.confirmIdentityRecovery()
        assertEquals(listOf("stop", "begin", "reset", "stop", "begin", "reset", "complete"), fixture.events)
        assertNull(fixture.state.value.identityRecovery)
        fixture.orchestration.close()
    }
}

private class RecoveryFixture(
    recovery: IrohIdentityRecoveryKind,
    private var pendingRecovery: IrohIdentityRecoveryKind? = null,
    private val activeRoomMissing: Boolean = false,
) {
    val state = MutableStateFlow(recoveryState(recovery))
    val events = mutableListOf<String>()
    val resetStarted = CompletableDeferred<Unit>()
    var resetGate: CompletableDeferred<Unit>? = null
    var validateFailure: Exception? = null
    var resetFailure: Exception? = null
    var onReplace: (() -> Unit)? = null
    var storedSnapshot: IrohNetworkState? = null
    var discardCount = 0
    var startCount = 0
    var stopCount = 0
    var replaceCount = 0
    var resetCount = 0
    var actionCount = 0
    var secretReads = 0
    private val local = LocalStateEntity(
        deviceId = "device-a4-test",
        settingsJson = IrohJson.strict.encodeToString(TimerSettings()),
    )
    private val workspace = LocalWorkspaceSnapshot(local)
    private val room = IrohRoomEntity(
        roomId = "room-a4-test",
        roomName = "A4 room",
        encryptedRoomSecret = byteArrayOf(1),
        returnStateJson = "return",
        roomStateJson = "room",
        createdAtMs = 1,
        activated = true,
    )
    val orchestration = IrohRoomOrchestration(servicePort(), persistencePort())

    // The recovery screen is foreground before the user confirms; no foreground action runs.
    fun enterForegroundWithoutAction() {
        lifecycle().enterForeground()
    }

    fun lifecycleForeground() = lifecycle().snapshot().foreground

    private fun lifecycle() = IrohRoomOrchestration::class.java.getDeclaredField("lifecycle")
        .apply { isAccessible = true }
        .get(orchestration) as IrohLifecycleState

    private fun servicePort() = IrohRoomServicePort(
        state = state,
        publishState = { state.value = it },
        transformState = { transform -> state.update(transform) },
        start = { _, _ -> startCount += 1; "ticket-a4" },
        stop = { stopCount += 1; events += "stop" },
        close = {},
        stopIf = {},
        join = { actionCount += 1; projection() },
        endpointIdForTicket = { "endpoint-a4" },
        syncNow = { actionCount += 1 },
        pendingIdentityRecovery = { pendingRecovery },
        replaceEndpointIdentity = { replaceCount += 1; events += "replace"; onReplace?.invoke() },
        beginIdentityReset = { events += "begin"; pendingRecovery = recoveryKind() },
        completeIdentityReset = { events += "complete"; pendingRecovery = null },
    )

    private fun persistencePort() = IrohRoomPersistencePort(
        localState = { local },
        room = { if (activeRoomMissing) null else room },
        replicationSettings = {
            ReplicationSettingsEntity(mode = ReplicationMode.IROH.name, activeRoomId = room.roomId)
        },
        discardIncompleteRooms = { discardCount += 1 },
        setMode = { actionCount += 1 },
        snapshot = { storedSnapshot ?: state.value },
        captureLocalOperations = { projection() },
        createRoom = { actionCount += 1; room to projection() },
        activeRoom = { room },
        activeRoomSecret = { secretReads += 1; ByteArray(32) },
        prepareJoinedRoom = { _, _ -> actionCount += 1; room to false },
        discardIncompleteInactiveRoom = {},
        leaveActiveRoom = {},
        clearAccountData = {},
        validateRoomSecrets = { validateSecrets() },
        resetIdentityData = { resetIdentity() },
    )

    private fun validateSecrets() {
        events += "validate"
        validateFailure?.let { throw it }
    }

    private suspend fun resetIdentity() {
        resetCount += 1
        events += "reset"
        resetStarted.complete(Unit)
        resetGate?.await()
        resetFailure?.let { throw it }
    }

    private fun projection() = IrohRoomProjection(workspace, 1)

    private fun recoveryKind() = IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING
}

private fun recoveryState(kind: IrohIdentityRecoveryKind) = IrohNetworkState(
    mode = ReplicationMode.IROH,
    status = IrohConnectionStatus.UNAVAILABLE,
    roomId = "room-a4-test",
    identityRecovery = kind,
)

private fun recoveryError(kind: IrohIdentityRecoveryKind) = IrohSecretVaultException(kind)
