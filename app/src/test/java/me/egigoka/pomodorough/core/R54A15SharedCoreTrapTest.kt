package me.egigoka.pomodorough.core

import com.dylibso.chicory.runtime.ChicoryInterruptedException
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.TrapException
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R54-A15: a failure that Chicory raises inside an export reaches callers as a typed
 * SharedCoreException.Abi with the runtime failure as its cause, and the instance takes no
 * further calls because the module's state is unknown. Cancellation, typed core failures and
 * JVM Errors keep their own paths and leave the instance usable.
 */
class R54A15SharedCoreTrapTest {
    @Test
    fun dispatchTrapIsTypedAndRetiresTheInstance() {
        val core = SharedCore(TrappingCoreModule.instance())

        val trap = dispatchFailure(core)
        assertTrue("a trap must reach callers typed: $trap", trap is SharedCoreException.Abi)
        assertTrue("the Chicory trap stays the cause: ${trap.cause}", trap.cause is TrapException)

        assertRetired(core, trap)
    }

    // Chicory raises interruption and call-stack exhaustion as ChicoryException subtypes that are
    // not TrapExceptions, so each one pins that the runtime catch covers more than traps.
    @Test
    fun threadInterruptionIsTypedAndRetiresTheInstance() {
        val core = SharedCore(bundledInstance())
        Thread.currentThread().interrupt()
        val failure = try {
            dispatchFailure(core)
        } finally {
            Thread.interrupted()
        }
        assertTrue("an interrupted dispatch must reach callers typed: $failure", failure is SharedCoreException.Abi)
        assertTrue("the interruption stays the cause: ${failure.cause}", failure.cause is ChicoryInterruptedException)
        assertRetired(core, failure)
    }

    @Test
    fun callStackExhaustionIsTypedAndRetiresTheInstance() {
        var failure: Throwable? = null
        var retired: Throwable? = null
        var threadError: Throwable? = null
        // A small thread stack makes the recursion run out quickly and keeps the test JVM's own
        // stack out of it.
        val worker = Thread(null, {
            runCatching {
                val core = SharedCore(TrappingCoreModule.recursiveInstance())
                failure = dispatchFailure(core)
                retired = dispatchFailure(core)
            }.onFailure { threadError = it }
        }, "r54-a15-call-stack", 512L * 1024)
        worker.start()
        worker.join(60_000)
        assertFalse("the recursion thread must finish", worker.isAlive)
        assertNull("the recursion thread must not fail", threadError)
        val typed = checkNotNull(failure)
        assertTrue("an exhausted call stack must reach callers typed: $typed", typed is SharedCoreException.Abi)
        assertTrue("the Chicory failure stays the cause: ${typed.cause}", typed.cause is ChicoryException)
        assertFalse("the cause is not a trap: ${typed.cause}", typed.cause is TrapException)
        assertEquals("call stack exhausted", typed.cause?.message)
        assertRefusal(checkNotNull(retired), typed)
    }

    @Test
    fun plainRuntimeFailureIsTypedAndRetiresTheInstance() {
        val runtime = IllegalStateException("r54 a15 runtime")
        val core = SharedCore(bundledInstance(), dispatchResultOverride = { throw runtime })

        val failure = dispatchFailure(core)
        assertTrue("a runtime failure must reach callers typed: $failure", failure is SharedCoreException.Abi)
        assertSame(runtime, failure.cause)
        assertRetired(core, failure)
    }

    @Test
    fun coreOperationErrorKeepsTheInstance() {
        val core = SharedCore(bundledInstance())

        val failure = runCatching { core.dispatch("missing.operation", "{}") }.exceptionOrNull()
        assertTrue("a core error stays an operation failure: $failure", failure is SharedCoreException.Operation)
        core.dispatch("core.version", "{}")
    }

    // The release that follows a successful dispatch has no primary failure to carry the trap,
    // so it is the dispatch's own failure.
    @Test
    fun cleanupTrapAfterSuccessfulDispatchIsTyped() {
        val cleanupTraps = mutableListOf<TrapException>()
        val core = SharedCore(
            bundledInstance(),
            freeStatusOverride = { _, _ -> throw TrapException("r54 a15 free trap").also(cleanupTraps::add) },
        )

        val failure = dispatchFailure(core)
        assertTrue("a cleanup trap must reach callers typed: $failure", failure is SharedCoreException.Abi)
        assertSame("the first cleanup trap is the cause", cleanupTraps.first(), failure.cause)
        val retired = dispatchFailure(core)
        assertTrue(
            "a failed cleanup must retire the instance: ${retired.message}",
            retired.message.orEmpty().contains("unusable after an earlier failure"),
        )
    }

    @Test
    fun cancellationIsNotRetypedAndKeepsTheInstance() {
        val cancellation = CancellationException("r54 a15 cancelled dispatch")
        var calls = 0
        val core = SharedCore(
            TrappingCoreModule.instance(),
            dispatchResultOverride = {
                calls += 1
                throw cancellation
            },
        )

        assertSame(cancellation, dispatchFailure(core))
        assertSame(cancellation, dispatchFailure(core))
        assertEquals("a cancelled dispatch must not retire the instance", 2, calls)
    }

    // JVM Errors (for example OutOfMemoryError) are not retyped: they keep Kotlin's convention of
    // reaching the caller unchanged, and the instance is not retired for them.
    @Test
    fun jvmErrorIsNotRetypedAndKeepsTheInstance() {
        val error = AssertionError("r54 a15 jvm error")
        var calls = 0
        val core = SharedCore(
            TrappingCoreModule.instance(),
            dispatchResultOverride = {
                calls += 1
                throw error
            },
        )

        assertSame(error, dispatchFailure(core))
        assertSame(error, dispatchFailure(core))
        assertEquals("a JVM Error must not retire the instance", 2, calls)
    }

    private fun assertRetired(core: SharedCore, failure: Throwable) {
        assertRefusal(dispatchFailure(core), failure)
    }

    private fun assertRefusal(retired: Throwable, failure: Throwable) {
        assertTrue("a failed instance must refuse reuse: $retired", retired is SharedCoreException.Abi)
        assertTrue(
            "the refusal must say the instance is unusable: ${retired.message}",
            retired.message.orEmpty().contains("unusable after an earlier failure"),
        )
        assertSame("the refusal names the runtime failure", failure.cause, retired.cause)
    }

    private fun dispatchFailure(core: SharedCore): Throwable {
        val outcome = runCatching { core.dispatch("task.identity.v1", """{"title":"r54 a15"}""") }
        return requireNotNull(outcome.exceptionOrNull()) { "dispatch must fail, got ${outcome.getOrNull()}" }
    }

    private fun bundledInstance(): Instance {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm"))
            .use { it.readBytes() }
        return Instance.builder(Parser.parse(bytes)).build()
    }
}
