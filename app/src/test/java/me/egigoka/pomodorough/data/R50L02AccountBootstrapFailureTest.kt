package me.egigoka.pomodorough.data

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import me.egigoka.pomodorough.core.SharedCoreException
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.BootstrapConflictException
import me.egigoka.pomodorough.data.api.BootstrapConflictKind
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.CredentialPersistenceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R50-L02: sign-in/restore/recovery must not silence bootstrap infra as
 * network. Finite stage+cause categories get one capped fixed report;
 * offline/cancel/401/409/stale/malformed stay quiet with no payload.
 */
class R50L02AccountBootstrapFailureTest {
    @Test
    fun infraCategoriesAreDistinctFixedMessages() {
        val signIn = AccountBootstrapFailure(
            AccountBootstrapStage.SignIn,
            AccountBootstrapCause.CoreLoad,
        )
        val restore = AccountBootstrapFailure(
            AccountBootstrapStage.Restore,
            AccountBootstrapCause.CoreLoad,
        )
        val commit = AccountBootstrapFailure(
            AccountBootstrapStage.SignIn,
            AccountBootstrapCause.BootstrapCommit,
        )
        assertTrue(signIn.reportMessage != restore.reportMessage)
        assertTrue(signIn.reportMessage != commit.reportMessage)
        assertTrue(signIn.reportMessage.contains("sign_in"))
        assertTrue(signIn.reportMessage.contains("core_load"))
        assertTrue(commit.reportMessage.contains("bootstrap_commit"))
    }

    @Test
    fun coreLoadAbiInvalidOutputClassifySeparately() {
        val load = classifyAccountBootstrapFailure(
            AccountBootstrapStage.SignIn,
            SharedCoreException.Load("load-canary"),
        )
        val abi = classifyAccountBootstrapFailure(
            AccountBootstrapStage.SignIn,
            SharedCoreException.Abi("abi-canary"),
        )
        val invalid = classifyAccountBootstrapFailure(
            AccountBootstrapStage.SignIn,
            CoreProjectionException.InvalidOutput("output-canary"),
        )
        assertEquals(AccountBootstrapCause.CoreLoad, load?.cause)
        assertEquals(AccountBootstrapCause.CoreAbi, abi?.cause)
        assertEquals(AccountBootstrapCause.CoreInvalidOutput, invalid?.cause)
    }

    // R52-L01 credential rule: only the typed owning-boundary
    // failure (AuthRepository wrapping a refused TokenStore write)
    // reads as CredentialWrite. Untyped persistence errors fall to
    // BootstrapCommit; provider infrastructure reads as
    // CredentialProvider. No stack-frame sniffing.
    @Test
    fun credentialWriteNeedsOwningBoundaryType() {
        val credential = CredentialPersistenceException(
            "Could not persist authentication tokens",
            IOException("persist-canary"),
        )
        val classified = classifyAccountBootstrapFailure(
            AccountBootstrapStage.SignIn,
            credential,
        )
        assertEquals(AccountBootstrapCause.CredentialWrite, classified?.cause)
        // Untyped Room failure falls to BootstrapCommit even with a
        // storage frame: R52-L01 removed stack-frame sniffing.
        val room = RuntimeException("room-canary")
        val commit = classifyAccountBootstrapFailure(
            AccountBootstrapStage.Restore,
            room,
        )
        assertEquals(AccountBootstrapCause.BootstrapCommit, commit?.cause)
    }

    @Test
    fun signInCredentialWriteReportsBoundedCredentialWrite() {
        val canary = "R50L02-SENTINEL-credential-canary"
        val error = CredentialPersistenceException(
            "Could not persist authentication tokens",
            IllegalStateException("vault persist failed: $canary"),
        )
        assertEquals(
            AccountBootstrapCause.CredentialWrite,
            classifyAccountBootstrapFailure(AccountBootstrapStage.SignIn, error)?.cause,
        )
        val reported = captureReports {
            val result = handleAccountBootstrapFailure(AccountBootstrapStage.SignIn, error)
            assertEquals(AccountBootstrapStage.SignIn, result?.stage)
            assertEquals(AccountBootstrapCause.CredentialWrite, result?.cause)
        }
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is AccountBootstrapReport)
        assertEquals("Account bootstrap sign_in failed: credential_write", single.message)
        assertTrue(single.cause == null)
        assertTrue(!single.message.orEmpty().contains(canary))
        assertTrue(!single.stackTraceToString().contains(canary))
    }

    @Test
    fun expectedTransportAndMalformedStayQuiet() {
        val quiet = listOf(
            IOException("offline-canary"),
            AuthenticationRequired("expired"),
            BootstrapConflictException(BootstrapConflictKind.Revision, "conflict"),
            ApiException(401, "unauthorized"),
            ApiException(500, "server-error"),
            ProfileProtocolException("malformed"),
            SyncProtocolException("malformed"),
            SerializationException("malformed"),
            CoreProjectionException.InvalidInput("bad input"),
            GetCredentialCancellationException("dismissed"),
            GetCredentialInterruptedException("interrupted"),
        )
        quiet.forEach { error ->
            val stage = AccountBootstrapStage.SignIn
            assertNull("$error should stay quiet", classifyAccountBootstrapFailure(stage, error))
        }
    }

    @Test
    fun providerInfrastructureReportsCredentialProvider() {
        val provider = listOf(
            GetCredentialProviderConfigurationException("no provider"),
            GetCredentialUnknownException("unknown provider failure"),
        )
        provider.forEach { error ->
            val classified = classifyAccountBootstrapFailure(
                AccountBootstrapStage.SignIn,
                error,
            )
            assertEquals(AccountBootstrapCause.CredentialProvider, classified?.cause)
        }
        val reported = captureReports {
            val result = handleAccountBootstrapFailure(
                AccountBootstrapStage.SignIn,
                GetCredentialProviderConfigurationException("no provider"),
            )
            assertEquals(AccountBootstrapCause.CredentialProvider, result?.cause)
        }
        assertEquals(1, reported.size)
        assertEquals(
            "Account bootstrap sign_in failed: credential_provider",
            reported.single().message,
        )
    }

    @Test
    fun cancellationPropagatesWithoutReport() {
        val cancellation = CancellationException("gone")
        val recorded = mutableListOf<AccountBootstrapFailure>()
        val failure = runCatching {
            handleAccountBootstrapFailure(
                AccountBootstrapStage.SignIn,
                cancellation,
                recorded::add,
            )
        }.exceptionOrNull()
        assertSame(cancellation, failure)
        assertTrue(recorded.isEmpty())
        assertSame(
            cancellation,
            runCatching {
                classifyAccountBootstrapFailure(AccountBootstrapStage.SignIn, cancellation)
            }.exceptionOrNull(),
        )
    }

    @Test
    fun boundedReportOmitsRawMessageAndCause() {
        val canary = "R50L02-SENTINEL-token-abc123-device-xyz"
        val error = RuntimeException("Room failed: $canary")
        error.stackTrace = arrayOf(
            StackTraceElement(
                "me.egigoka.pomodorough.data.storage.TimerStore",
                "saveMutationState",
                "TimerStore.kt",
                120,
            ),
        )
        val reported = captureReports {
            handleAccountBootstrapFailure(AccountBootstrapStage.SignIn, error)
        }
        assertEquals(1, reported.size)
        val single = reported.single()
        assertTrue(single is AccountBootstrapReport)
        assertEquals(
            "Account bootstrap sign_in failed: bootstrap_commit",
            single.message,
        )
        assertTrue(single.cause == null)
        assertTrue(!single.message.orEmpty().contains(canary))
        assertTrue(!single.stackTraceToString().contains(canary))
    }

    @Test
    fun duplicateReportsOnceAndRecurAfterReset() {
        val failure = AccountBootstrapFailure(
            AccountBootstrapStage.Restore,
            AccountBootstrapCause.CoreAbi,
        )
        val first = captureReports {
            AccountBootstrapReporter.report(failure)
            AccountBootstrapReporter.report(failure)
        }
        assertEquals(1, first.size)
        val second = captureReports {
            AccountBootstrapReporter.report(failure)
        }
        assertEquals(1, second.size)
    }

    @Test
    fun consentOffSuppressesInfraReport() {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = false
        AccountBootstrapReporter.resetForTest()
        try {
            AccountBootstrapReporter.report(
                AccountBootstrapFailure(
                    AccountBootstrapStage.SignIn,
                    AccountBootstrapCause.CoreLoad,
                ),
            )
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            AccountBootstrapReporter.resetForTest()
        }
        assertTrue(reported.isEmpty())
    }

    @Test
    fun signInWiringReportsBoundedInfraWithStaleGuard() {
        val window = functionWindow("private suspend fun signInInternal(")
        assertTrue(window.contains("AccountBootstrapStage.SignIn"))
        assertTrue(window.contains("reportAccountBootstrap("))
        assertTrue(window.contains("failSignIn("))
        assertTrue(window.contains("catch (error: CancellationException)"))
        assertTrue(window.contains("throw error"))
        assertTrue(window.contains("expected-silent:"))
        assertTrue(!window.contains("CrashReporter.report("))
    }

    @Test
    fun restoreWiringReportsBoundedInfraWithStaleGuard() {
        val window = functionWindow("private suspend fun restoreProfile(")
        assertTrue(window.contains("AccountBootstrapStage.Restore"))
        assertTrue(window.contains("reportAccountBootstrap("))
        assertTrue(window.contains("failProfileRestore("))
        assertTrue(window.contains("catch (error: IOException)"))
        assertTrue(window.contains("expected-silent:"))
        assertTrue(!window.contains("CrashReporter.report("))
    }

    @Test
    fun recoveryWiringReportsBoundedInfra() {
        val recovery = functionWindow("override suspend fun recoverCorruptedResolution(")
        assertTrue(recovery.contains("AccountBootstrapStage.ResolutionRecovery"))
        assertTrue(recovery.contains("reportAccountBootstrap("))
        assertTrue(recovery.contains("failResolutionRecovery("))
        val refresh = functionWindow("private suspend fun refreshResolutionBootstrap(")
        assertTrue(refresh.contains("AccountBootstrapStage.ResolutionRecovery"))
        assertTrue(refresh.contains("handleAccountBootstrapFailure("))
        assertTrue(refresh.contains("isCurrent(identity)"))
    }

    @Test
    fun reportHelperSkipsStaleAttempts() {
        val window = functionWindow("private fun reportAccountBootstrap(")
        assertTrue(window.contains("isCurrent(identity)"))
        assertTrue(window.contains("handleAccountBootstrapFailure("))
        assertTrue(window.contains("return"))
    }

    @Test
    fun noDuplicateCorruptionOrPhaseReporters() {
        val failures = failuresText()
        assertTrue(!failures.contains("CorruptStateReporter"))
        assertTrue(!failures.contains("PhaseSelectionReporter"))
        assertTrue(!failures.contains("MutationPersistenceReporter"))
        assertTrue(!failures.contains("CrashReporter.report(error"))
        assertTrue(!failures.contains("AccountBootstrapReport(error"))
        assertTrue(failures.contains("AccountBootstrapStage"))
        assertTrue(failures.contains("AccountBootstrapCause"))
        assertTrue(failures.contains("CrashReporter.report("))
        val signIn = functionWindow("private suspend fun signInInternal(")
        assertTrue(!signIn.contains("CorruptStateReporter"))
        assertTrue(!signIn.contains("PhaseSelectionReporter"))
        val restore = functionWindow("private suspend fun restoreProfile(")
        assertTrue(!restore.contains("MutationPersistenceReporter"))
    }

    @Test
    fun messagesContainNoIdentifiersOrTokens() {
        val text = failuresText()
        assertTrue(!text.contains("accessToken"))
        assertTrue(!text.contains("refreshToken"))
        assertTrue(!text.contains("idToken"))
        assertTrue(!text.contains("accountId"))
        assertTrue(!text.contains("requestId"))
        assertTrue(!text.contains("bootstrap.response"))
        assertTrue(!text.contains("PendingSyncQueues"))
        assertTrue(text.contains("credential_write"))
        assertTrue(text.contains("credential_provider"))
        assertTrue(text.contains("core_load"))
        assertTrue(text.contains("core_abi"))
        assertTrue(text.contains("core_invalid_output"))
        assertTrue(text.contains("bootstrap_commit"))
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountBootstrapReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            AccountBootstrapReporter.resetForTest()
        }
        return reported
    }

    // functionWindow truncation contract: window ends at next
    // private/override/internal boundary or 3200-char fallback.
    // Wiring pins depend on this; classifier fault-injection above
    // covers stage+cause paths independent of windowing.
    private fun functionWindow(marker: String): String {
        val text = repositoryText()
        val start = text.indexOf(marker)
        assertTrue("$marker missing", start >= 0)
        val tail = text.drop(start)
        val next = tail.indexOf("\n    private ", 10).takeIf { it > 0 }
            ?: tail.indexOf("\n    override ", 10).takeIf { it > 0 }
            ?: tail.indexOf("\n    internal ", 10).takeIf { it > 0 }
            ?: 3_200
        return tail.take(next)
    }

    private fun repositoryText(): String = productionText(
        "me/egigoka/pomodorough/data/TimerRepository.kt",
    )

    private fun failuresText(): String = productionText(
        "me/egigoka/pomodorough/data/AccountBootstrapFailures.kt",
    )

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }
}
