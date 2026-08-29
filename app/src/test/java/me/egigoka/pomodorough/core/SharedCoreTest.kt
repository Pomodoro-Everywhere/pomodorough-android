package me.egigoka.pomodorough.core

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser
import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SharedCoreTest {
    private val core by lazy {
        SharedCore.load(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm")) {
                "pomodorough_core.wasm must be available to JVM tests"
            },
        )
    }

    @Test
    fun coreVersionRunsThroughRealWasmAbi() {
        assertEquals(
            buildJsonObject {
                put("schemaVersion", JsonPrimitive(1))
                put("coreVersion", JsonPrimitive("0.6.0"))
            },
            core.dispatch("core.version", "{}"),
        )
    }

    @Test
    fun hlcHeadRunsThroughRealWasmAbi() {
        assertEquals(
            buildJsonObject {
                put("wallMs", JsonPrimitive(101))
                put("counter", JsonPrimitive(7))
            },
            core.dispatch(
                "hlc.head.v1",
                """{"physicalNowMs":100,"observed":[{"wallMs":101,"counter":2},{"wallMs":101,"counter":7},{"wallMs":99,"counter":99}]}""",
            ),
        )
    }

    @Test
    fun selectedTaskClassifyPreservesOmissionNullAndValue() {
        assertEquals(
            JsonPrimitive("omitted"),
            core.dispatch("selectedTask.classify", "{}"),
        )
        assertEquals(
            JsonPrimitive("deselected"),
            core.dispatch("selectedTask.classify", """{"selectedTaskId":null}"""),
        )
        assertEquals(
            JsonPrimitive("selected:33f9d32c-a7ee-8aa9-897a-13e19bc4e5d4"),
            core.dispatch(
                "selectedTask.classify",
                """{"selectedTaskId":"33f9d32c-a7ee-8aa9-897a-13e19bc4e5d4"}""",
            ),
        )
    }

    @Test
    fun rejectsEmptyOperationAndInputBeforeEnteringAbi() {
        for ((operation, input) in listOf("" to "{}", "core.version" to "")) {
            try {
                core.dispatch(operation, input)
                fail("empty ABI input must fail")
            } catch (_: IllegalArgumentException) {
                // Expected before any allocation or raw ABI call.
            }
        }
    }

    @Test
    fun rejectsNonCanonicalResultEnvelopes() {
        val invalid = listOf(
            """{"ok":true,"value":{},"error":"contradiction"}""",
            """{"ok":true,"value":{},"unknown":1}""",
            """{"ok":false,"error":"failure","value":{}}""",
            """{"ok":false,"error":""}""",
            """{"ok":false,"error":"failure","unknown":1}""",
        )

        invalid.forEach { envelope ->
            try {
                core.parseEnvelope("test.operation", envelope)
                fail("non-canonical envelope must fail: $envelope")
            } catch (_: SharedCoreException.Abi) {
                // Expected.
            }
        }
    }

    @Test
    fun invalidatedInstanceRejectsSubsequentDispatches() {
        val field = SharedCore::class.java.getDeclaredField("unusableCause").apply {
            isAccessible = true
        }
        field.set(core, IllegalStateException("cleanup failed"))

        try {
            core.dispatch("core.version", "{}")
            fail("invalidated shared core must reject reuse")
        } catch (error: SharedCoreException.Abi) {
            assertTrue(error.message.orEmpty().contains("unusable after cleanup failure"))
        }
    }

    @Test
    fun inconsistentResultOwnershipInvalidatesInstance() {
        val malformed = SharedCore(
            freshInstance(),
            dispatchResultOverride = { 8L shl 32 },
        )

        try {
            malformed.dispatch("core.version", "{}")
            fail("inconsistent result ownership must fail")
        } catch (error: SharedCoreException.Abi) {
            assertTrue(error.message.orEmpty().contains("inconsistent pointer/length"))
        }
        try {
            malformed.dispatch("core.version", "{}")
            fail("ownership uncertainty must invalidate the instance")
        } catch (error: SharedCoreException.Abi) {
            assertTrue(error.message.orEmpty().contains("unusable after cleanup failure"))
        }
    }

    @Test
    fun rejectedFreeStatusPreservesPrimaryAndInvalidatesInstance() {
        val rejected = SharedCore(freshInstance(), freeStatusOverride = { _, _ -> 0L })

        try {
            rejected.dispatch("missing.operation", "{}")
            fail("rejected free must fail the dispatch")
        } catch (error: SharedCoreException.Operation) {
            assertTrue(error.suppressed.any { it.message.orEmpty().contains("rejected buffer") })
        }
        try {
            rejected.dispatch("core.version", "{}")
            fail("cleanup uncertainty must invalidate the instance")
        } catch (error: SharedCoreException.Abi) {
            assertTrue(error.message.orEmpty().contains("unusable after cleanup failure"))
        }
    }

    @Test
    fun bundledWasmReportsInvalidAndDuplicateFrees() {
        val instance = freshInstance()
        val allocate = instance.export("pomodorough_alloc")
        val free = instance.export("pomodorough_free_v2")
        val pointer = allocate.apply(8).single()

        assertEquals(0L, free.apply(pointer, 7).single())
        assertEquals(1L, free.apply(pointer, 8).single())
        assertEquals(0L, free.apply(pointer, 8).single())
        assertEquals(0L, free.apply(0, 8).single())
    }

    @Test
    fun oneInstanceSafelyServesConcurrentDispatches() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 32).map { index ->
                executor.submit(Callable {
                    core.dispatch(
                        "selectedTask.classify",
                        """{"selectedTaskId":"task-$index"}""",
                    )
                })
            }

            results.forEachIndexed { index, result ->
                assertEquals(JsonPrimitive("selected:task-$index"), result.get(10, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun coreErrorsBecomeTypedHostErrors() {
        try {
            core.dispatch("missing.operation", "{}")
            fail("missing operation must fail")
        } catch (error: SharedCoreException.Operation) {
            assertEquals("missing.operation", error.operation)
            assertEquals(
                "shared-core operation missing.operation failed: " +
                    "unsupported shared-core operation: missing.operation",
                error.message,
            )
        }
    }

    @Test
    fun moduleHashIsVerifiedBeforeInstantiation() {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm"))
            .use { it.readBytes() }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        try {
            SharedCore.load(ByteArrayInputStream(bytes))
            fail("modified module must fail hash verification")
        } catch (error: SharedCoreException.Load) {
            assertTrue(error.message.orEmpty().startsWith("shared-core SHA-256 mismatch:"))
        }
    }

    @Test
    fun packagedPinMetadataMatchesAdapter() {
        val properties = Properties().apply {
            requireNotNull(
                this@SharedCoreTest.javaClass.classLoader
                    ?.getResourceAsStream("shared_core.properties"),
            )
                .use(::load)
        }

        assertEquals(SharedCore.CORE_COMMIT, properties.getProperty("CORE_COMMIT"))
        assertEquals(SharedCore.CORE_SHA256, properties.getProperty("CORE_SHA256"))
    }

    private fun freshInstance(): Instance {
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("pomodorough_core.wasm"))
            .use { it.readBytes() }
        return Instance.builder(Parser.parse(bytes)).build()
    }
}
