package me.egigoka.pomodorough.crash

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingSilenceTest {
    private val auditedFiles = listOf(
        "me/egigoka/pomodorough/data/TimerRepository.kt",
        "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt",
        "me/egigoka/pomodorough/data/iroh/IrohEndpointLifecycle.kt",
        "me/egigoka/pomodorough/data/iroh/IrohIncomingRpcHandler.kt",
        "me/egigoka/pomodorough/data/TimerSyncValidation.kt",
        "me/egigoka/pomodorough/data/CentralizedSyncRuntime.kt",
        "me/egigoka/pomodorough/data/RevisionStreamLifecycle.kt",
        "me/egigoka/pomodorough/data/iroh/IrohPeerSynchronization.kt",
        "me/egigoka/pomodorough/data/auth/AuthRepository.kt",
        "me/egigoka/pomodorough/data/time/TrustedClock.kt",
        "me/egigoka/pomodorough/data/CoreProjectionDispatcher.kt",
        "me/egigoka/pomodorough/data/CoreSynchronizationDispatchers.kt",
        "me/egigoka/pomodorough/data/CoreTimerPolicyDispatchers.kt",
        "me/egigoka/pomodorough/data/api/PomodoroughApi.kt",
        "me/egigoka/pomodorough/core/SharedCore.kt",
        "me/egigoka/pomodorough/crash/CrashReporter.kt",
        "me/egigoka/pomodorough/data/AccountDeletionScrubRetry.kt",
        "me/egigoka/pomodorough/data/CentralizedSyncCoordinator.kt",
        "me/egigoka/pomodorough/data/SyncWireBounds.kt",
        "me/egigoka/pomodorough/data/SynchronizedProjectionRequest.kt",
        "me/egigoka/pomodorough/data/TimerLocalInitialization.kt",
        "me/egigoka/pomodorough/data/UuidV7.kt",
        "me/egigoka/pomodorough/data/auth/LogoutRevocationRetryController.kt",
        "me/egigoka/pomodorough/data/auth/TokenVault.kt",
        "me/egigoka/pomodorough/data/iroh/IrohCanonicalRecordPersistence.kt",
        "me/egigoka/pomodorough/data/iroh/IrohEndpointTransport.kt",
        "me/egigoka/pomodorough/data/iroh/IrohReplicationService.kt",
        "me/egigoka/pomodorough/data/iroh/IrohRoomMetadataPersistence.kt",
        "me/egigoka/pomodorough/data/iroh/IrohSecretVault.kt",
        "me/egigoka/pomodorough/data/iroh/protocol/CanonicalRecordCodec.kt",
        "me/egigoka/pomodorough/data/iroh/protocol/EndpointIdentity.kt",
        "me/egigoka/pomodorough/data/iroh/protocol/InviteCodec.kt",
        "me/egigoka/pomodorough/data/iroh/protocol/RpcMessageCodec.kt",
        "me/egigoka/pomodorough/data/local/TimerMigrations.kt",
        "me/egigoka/pomodorough/domain/SettingsReducer.kt",
        "me/egigoka/pomodorough/domain/TaskReducer.kt",
        "me/egigoka/pomodorough/domain/TimerPresentation.kt",
        "me/egigoka/pomodorough/timer/TimerAlarmReceiver.kt",
        "me/egigoka/pomodorough/timer/TimerAlarmScheduler.kt",
        "me/egigoka/pomodorough/ui/UiComponents.kt",
    )

    private val cancellationGuardSites = listOf(
        "me/egigoka/pomodorough/data/iroh/IrohIncomingRpcHandler.kt" to "fun response(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun afterLocalMutation(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun finishExpiredIrohTimer(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun deleteAccountInternal(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun confirmAccountSwitchInternal(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun commitLocalAccountReset(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun logoutInternal(",
        "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt" to "fun setMode(",
        "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt" to "fun createRoom(",
        "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt" to "fun joinRoom(",
        "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt" to "fun leaveRoom(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun prepareRemoteLogout(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun refreshResolutionBootstrap(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun recoverCorruptedResolution(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun performBootstrapResolution(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun restoreProfile(",
        "me/egigoka/pomodorough/data/iroh/IrohIncomingRpcHandler.kt" to "fun readAuthenticatedRequest(",
        "me/egigoka/pomodorough/data/iroh/IrohEndpointLifecycle.kt" to "fun bindEndpoint(",
        "me/egigoka/pomodorough/data/iroh/IrohEndpointLifecycle.kt" to "fun createTicketOrClose(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun validateLoadedMutationState(",
        "me/egigoka/pomodorough/data/iroh/IrohRoomOrchestration.kt" to "fun recoverLocalOperations(",
        "me/egigoka/pomodorough/data/TimerRepository.kt" to "fun retargetRunningTimer(",
    )

    @Test
    fun everyFileWithCatchOrRunCatchingIsAudited() {
        val root = productionRoot()
        val expected = auditedFiles.toSet()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { relative ->
                val text = File(root, relative).readText()
                text.contains("catch (") || text.contains("runCatching")
            }
            .filter { it !in expected }
            .toList()
        assertTrue("add silent-catch audit coverage for: $offenders", offenders.isEmpty())
    }

    @Test
    fun auditedFilesAllExist() {
        val missing = auditedFiles.filter { !File(productionRoot(), it).isFile }
        assertTrue("audited files are missing: $missing", missing.isEmpty())
    }

    @Test
    fun logoutRetrySilenceUsesStandardMarker() {
        val text = File(
            productionRoot(),
            "me/egigoka/pomodorough/data/auth/LogoutRevocationRetryController.kt",
        ).readText()
        assertTrue(text.contains("expected-silent:"))
        assertFalse(text.contains("A31:"))
    }

    @Test
    fun expectedSilentBranchesNeverReport() {
        var markers = 0
        auditedFiles.forEach { relativePath ->
            val lines = productionFile(relativePath).readText().lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("expected-silent")) return@forEachIndexed
                markers += 1
                assertSilentCatchBody(relativePath, lines, index)
            }
        }
        assertTrue("expected-silent markers shrank to $markers, update this audit", markers >= 58)
    }

    @Test
    fun everyCatchSiteReportsOrDeclaresExpectedSilent() {
        auditedFiles.forEach { relativePath ->
            val lines = productionFile(relativePath).readText().lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("catch (")) return@forEachIndexed
                assertTrue(
                    "$relativePath:${index + 1} must report, rethrow, or declare expected-silent",
                    isAuditedCatch(lines, index),
                )
            }
        }
    }

    @Test
    fun runCatchingSitesAreValidatedHandledOrJustified() {
        auditedFiles.forEach { relativePath ->
            val lines = productionFile(relativePath).readText().lines()
            lines.forEachIndexed { index, line ->
                if (!line.contains("runCatching")) return@forEachIndexed
                assertTrue(
                    "$relativePath:${index + 1} runCatching must be validation, handled, or justified",
                    isAuditedRunCatching(lines, index),
                )
            }
        }
    }

    @Test
    fun suspendGenericCatchesRethrowCancellation() {
        // A48+A49+A51+A53: suspend generic catches must rethrow
        // CancellationException first. A53 runCatching sites rethrow via
        // `if (error is CancellationException) throw` instead of a
        // dedicated catch, pinned by the same list through the fallback
        // below.
        // TimerAlarmReceiver.deliver stays silent intentionally,
        // pinned by TimerAlarmDeliveryPolicyTest.cancellationStaysSilent.
        // IrohPeerSynchronization per-peer TimeoutCancellationException swallow stays,
        // pinned by IrohPeerSynchronizationTimeoutTest + A49 comment in syncPeers.
        cancellationGuardSites.forEach { (relativePath, funSig) ->
            assertHasCancellationGuard(relativePath, funSig)
        }
    }

    private fun assertHasCancellationGuard(relativePath: String, funSig: String) {
        val lines = productionFile(relativePath).readText().lines()
        val start = lines.indexOfFirst { it.contains(funSig) }
        assertTrue("$relativePath $funSig missing", start >= 0)
        val window = lines.subList(start, minOf(lines.size, start + 60))
        val cancelIndex = window.indexOfFirst {
            it.contains("catch (") && it.contains("CancellationException")
        }
        val genericIndex = window.indexOfFirst {
            it.contains("catch (") && it.contains(": Exception)")
        }
        if (cancelIndex >= 0 && genericIndex >= 0) {
            assertTrue(
                "$relativePath $funSig guard must precede generic catch",
                cancelIndex < genericIndex,
            )
            val rethrows = window.subList(cancelIndex, minOf(window.size, cancelIndex + 3))
                .any { it.contains("throw ") }
            assertTrue("$relativePath $funSig guard must rethrow", rethrows)
            return
        }
        assertHasRunCatchingCancellationRethrow(relativePath, funSig, window)
    }

    private fun assertHasRunCatchingCancellationRethrow(
        relativePath: String,
        funSig: String,
        window: List<String>,
    ) {
        assertTrue(
            "$relativePath $funSig must use runCatching when no catch guard exists",
            window.any { it.contains("runCatching") },
        )
        val rethrowIndex = window.indexOfFirst {
            it.contains("is CancellationException") && it.contains("throw")
        }
        assertTrue(
            "$relativePath $funSig must rethrow CancellationException",
            rethrowIndex >= 0,
        )
    }

    private fun isAuditedCatch(lines: List<String>, catchIndex: Int): Boolean {
        val body = catchBody(lines, catchIndex)
        if (body.any { it.contains("CrashReporter") }) return true
        if (body.drop(1).any { it.contains("throw ") }) return true
        return hasSilenceJustification(lines, catchIndex)
    }

    private fun hasSilenceJustification(lines: List<String>, catchIndex: Int): Boolean {
        val from = maxOf(0, catchIndex - 5)
        val to = minOf(lines.size, catchIndex + 4)
        return lines.subList(from, to).any { line ->
            val colon = line.indexOf("expected-silent:")
            colon >= 0 && line.drop(colon + 17).trim().length >= 10
        }
    }

    private fun catchBody(lines: List<String>, catchIndex: Int): List<String> {
        val body = mutableListOf(lines[catchIndex])
        var depth = 1
        for (next in catchIndex + 1 until minOf(lines.size, catchIndex + 40)) {
            val line = lines[next]
            body += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) break
        }
        return body
    }

    private fun isAuditedRunCatching(lines: List<String>, index: Int): Boolean {
        val window = lines.subList(index, minOf(lines.size, index + 3)).joinToString("\n")
        if (isPureValidation(window)) return true
        if (hasRunCatchingHandling(window)) return true
        if (isBestEffortCleanup(window)) return true
        return hasRunCatchingJustification(lines, index)
    }

    private fun isPureValidation(window: String): Boolean {
        if (!window.contains("runCatching")) return false
        return window.contains("parse") || window.contains("UUID.fromString") ||
            window.contains("URI(") || window.contains("valueOf") ||
            window.contains("Base64") || window.contains("decode") ||
            window.contains("subtractExact") || window.contains("addExact") ||
            window.contains("toRequestStrict") || window.contains("toLongOrNull") ||
            window.contains("jsonObject") || window.contains("jsonPrimitive")
    }

    private fun hasRunCatchingHandling(window: String): Boolean {
        return window.contains(".exceptionOrNull") || window.contains(".fold(") ||
            window.contains(".onFailure") || window.contains(".onSuccess") ||
            window.contains(".getOrNull") || window.contains(".getOrDefault") ||
            window.contains(".getOrElse") || window.contains(".isSuccess") ||
            window.contains(".isFailure")
    }

    private fun isBestEffortCleanup(window: String): Boolean {
        return window.contains("shutdown()") || window.contains("cancelAndJoin()") ||
            window.contains(".close()") || window.contains(".ignore()") ||
            window.contains("discard") || window.contains("auth::clear") ||
            window.contains(".fill(0)") || window.contains(".cancel()")
    }

    private fun hasRunCatchingJustification(lines: List<String>, index: Int): Boolean {
        val from = maxOf(0, index - 3)
        val to = minOf(lines.size, index + 3)
        return lines.subList(from, to).any {
            it.contains("expected-silent:") || it.contains("best-effort") || it.contains("A31:")
        }
    }

    private fun assertSilentCatchBody(relativePath: String, lines: List<String>, marker: Int) {
        var depth = 0
        for (next in marker + 1 until lines.size) {
            val line = lines[next]
            val trimmed = line.trim()
            if (depth == 0 && trimmed.contains("catch (")) return
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth < 0) return
            assertTrue(
                "$relativePath:${next + 1} reports from an expected-silent branch",
                !line.contains("CrashReporter"),
            )
        }
    }

    private fun productionFile(relativePath: String) = File(productionRoot(), relativePath)

    private fun productionRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return requireNotNull(root)
    }
}
