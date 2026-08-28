package me.egigoka.pomodorough.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountWorkspaceControllerTest {
    @Test
    fun signInClaimHasSingleOwnerUntilReleased() {
        val transitions = mutableListOf<AccountWorkspaceTransition>()
        val controller = controller(transitions)

        assertTrue(controller.claimSignIn().acquired)
        assertFalse(controller.claimSignIn().acquired)
        controller.releaseSignIn()
        assertTrue(controller.claimSignIn().acquired)

        assertEquals(
            listOf(true, false, false, true),
            transitions.filterIsInstance<AccountWorkspaceTransition.SignInClaim>()
                .map(AccountWorkspaceTransition.SignInClaim::acquired),
        )
    }

    @Test
    fun generationAndRequestIdBothOwnRetryAttempt() {
        val controller = controller()
        val attempt = controller.attemptIdentity("resolution-1")

        assertTrue(controller.owns(attempt, "resolution-1"))
        assertFalse(controller.owns(attempt, "resolution-2"))

        val transition = controller.advanceGeneration(AccountWorkspaceReason.LogoutStarted)

        assertEquals(0L, transition.previousGeneration)
        assertEquals(1L, transition.generation)
        assertEquals(AccountWorkspaceReason.LogoutStarted, transition.reason)
        assertFalse(controller.owns(attempt, "resolution-1"))
    }

    @Test
    fun bootstrapCaptureAndClearRetainTypedLifecycleReason() {
        val transitions = mutableListOf<AccountWorkspaceTransition>()
        val controller = controller(transitions)
        val response = bootstrap(revision = 7)
        val sample = ServerClockSample(1, 2, 3, 4, 5)

        controller.captureBootstrap(
            response,
            sample,
            AccountWorkspaceReason.AuthenticationCompleted,
        )

        assertSame(response, controller.bootstrap?.response)
        assertSame(sample, controller.bootstrap?.clockSample)
        val captured = transitions.last() as AccountWorkspaceTransition.BootstrapCaptured
        assertEquals(AccountWorkspaceReason.AuthenticationCompleted, captured.reason)

        controller.clearBootstrap(AccountWorkspaceReason.DeletionStarted)

        assertNull(controller.bootstrap)
        val cleared = transitions.last() as AccountWorkspaceTransition.BootstrapCleared
        assertEquals(AccountWorkspaceReason.DeletionStarted, cleared.reason)
    }

    @Test
    fun accountSwitchCaptureOwnsCandidateAndInvalidatesPriorAttempt() {
        val controller = controller()
        val attempt = controller.attemptIdentity(null)
        val profile = User("new", "new@example.com", "New", "")
        val response = bootstrap(revision = 8)
        val sample = ServerClockSample(1, 2, 3, 4, 5)

        val candidate = controller.captureAccountSwitch(profile, response, sample)

        assertSame(candidate, controller.accountSwitchCandidate)
        assertEquals(1L, controller.generation)
        assertFalse(controller.owns(attempt, null))

        controller.clearAccountSwitch(AccountWorkspaceReason.AccountSwitchCancelled)
        assertNull(controller.accountSwitchCandidate)
    }

    @Test
    fun authenticationAndRecoveryKeepGenerationCaptureOrder() {
        val transitions = mutableListOf<AccountWorkspaceTransition>()
        val controller = controller(transitions)
        val response = bootstrap(revision = 9)
        val sample = ServerClockSample(1, 2, 3, 4, 5)

        controller.completeAuthentication(response, sample)
        controller.beginRecovery()
        controller.expireRecovery()

        assertEquals(
            listOf(
                AccountWorkspaceTransition.BootstrapCaptured::class,
                AccountWorkspaceTransition.GenerationAdvanced::class,
                AccountWorkspaceTransition.BootstrapCleared::class,
                AccountWorkspaceTransition.GenerationAdvanced::class,
                AccountWorkspaceTransition.GenerationAdvanced::class,
                AccountWorkspaceTransition.BootstrapCleared::class,
            ),
            transitions.map { it::class },
        )
        assertEquals(3L, controller.generation)
        assertNull(controller.bootstrap)
    }

    @Test
    fun accountSwitchConfirmationReplacesBootstrapWithoutInventingClockSample() {
        val controller = controller()
        val original = bootstrap(revision = 3)
        val replacement = bootstrap(revision = 4)
        val sample = ServerClockSample(1, 2, 3, 4, 5)
        controller.captureBootstrap(
            original,
            sample,
            AccountWorkspaceReason.AuthenticationCompleted,
        )

        controller.replaceBootstrapResponse(
            replacement,
            AccountWorkspaceReason.AccountSwitchConfirmed,
        )

        assertSame(replacement, controller.bootstrap?.response)
        assertSame(sample, controller.bootstrap?.clockSample)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun accountActionsRemainSerializedInArrivalOrder() = runTest {
        val controller = controller()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            controller.serialize {
                order += "first.start"
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "first.end"
            }
        }
        firstStarted.await()
        val second = async { controller.serialize { order += "second" } }

        runCurrent()
        assertEquals(listOf("first.start"), order)
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        assertEquals(listOf("first.start", "first.end", "second"), order)
    }

    private fun controller(
        transitions: MutableList<AccountWorkspaceTransition> = mutableListOf(),
    ) = AccountWorkspaceController(AccountWorkspaceEventSink { transitions += it })

    private fun bootstrap(revision: Long) = SyncResponse(
        acknowledgements = emptyList(),
        revision = revision,
        canonicalTimer = null,
        history = emptyList(),
        serverTime = "2026-01-01T00:00:00Z",
        serverHlcWallMs = 0,
        serverHlcCounter = 0,
        durationAcknowledgements = emptyList(),
        durationsMs = DurationsMs(),
        taskAcknowledgements = emptyList(),
        tasks = emptyList(),
    )
}
