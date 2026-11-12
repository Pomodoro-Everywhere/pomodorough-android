package me.egigoka.pomodorough.data.iroh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class R54A10InviteCollectorTest {
    @Test
    fun heldInviteDeliveryKeepsTheNewerNoticeWhenTheInviteReturns() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        try {
            val invite = harness.createRoomWithQuietCollector()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            harness.deliveryGate = { value ->
                if (value.message == ExceptionText) {
                    held.complete(Unit)
                    release.await()
                }
            }

            // The lifecycle Status event rebuilds state from the store, which has no invite.
            val bindFailure = harness.state.value.copy(
                status = IrohConnectionStatus.UNAVAILABLE,
                invite = null,
                message = ExceptionText,
            )
            harness.state.value = bindFailure
            harness.await { held.isCompleted }
            // The orchestration's notice lands while the collector still holds the older delivery.
            harness.state.value = bindFailure.copy(message = Notice)
            release.complete(Unit)
            harness.await { harness.state.value.invite != null }

            assertEquals(bindFailure.copy(message = Notice, invite = invite), harness.state.value)
        } finally {
            harness.orchestration.close()
        }
    }

    @Test
    fun inviteReturnsAfterAServiceWriteDropsIt() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        try {
            val invite = harness.createRoomWithQuietCollector()
            harness.deliveryGate = null

            harness.state.value = harness.state.value.copy(
                status = IrohConnectionStatus.LISTENING,
                invite = null,
                peerCount = 1,
            )
            harness.await { harness.state.value.invite != null }

            assertEquals(invite, harness.state.value.invite)
            assertEquals(IrohConnectionStatus.LISTENING, harness.state.value.status)
            assertEquals(1, harness.state.value.peerCount)
        } finally {
            harness.orchestration.close()
        }
    }

    @Test
    fun serviceWriteThatLandsWhileTheInviteReturnsSurvives() = runTest {
        val harness = IrohRoomOrchestrationHarness()
        try {
            val invite = harness.createRoomWithQuietCollector()
            harness.deliveryGate = null
            var landed = false
            harness.writeGate = { current ->
                // The orchestration's notice lands while the collector writes the invite back.
                if (!landed) {
                    landed = true
                    harness.state.value = current.copy(message = Notice)
                }
            }

            val dropped = harness.state.value.copy(status = IrohConnectionStatus.LISTENING, invite = null)
            harness.state.value = dropped
            harness.await { harness.state.value.invite != null }

            assertEquals(dropped.copy(message = Notice, invite = invite), harness.state.value)
        } finally {
            harness.orchestration.close()
        }
    }

    // Returns once the collector has received the final created-room state, so no older delivery
    // is still in flight when the test writes its own states.
    private suspend fun IrohRoomOrchestrationHarness.createRoomWithQuietCollector(): String {
        var delivered: IrohNetworkState? = null
        deliveryGate = { value -> delivered = value }
        enterForeground()
        orchestration.createRoom("Invite room")
        val invite = state.value.invite
        assertNotNull(invite)
        await { delivered == state.value }
        return checkNotNull(invite)
    }

    private companion object {
        const val ExceptionText = "native bind failed at /data/user/0/secret-path"
        const val Notice = "Iroh sync could not be completed. Try again."
    }
}
