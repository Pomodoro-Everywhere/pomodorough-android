package me.egigoka.pomodorough.core

import android.content.res.AssetManager
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.ValType
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thread-safe transport adapter for pinned shared-core WebAssembly ABI. */
class SharedCore internal constructor(
    private val instance: Instance,
    private val freeStatusOverride: ((Int, Long) -> Long)? = null,
    private val dispatchResultOverride: (() -> Long)? = null,
) {
    private val lock = Any()
    private var unusableCause: Throwable? = null
    private val memory = instance.memory()
    private val allocate = requireExport(
        name = "pomodorough_alloc",
        parameters = arrayOf(ValType.I32),
        returns = arrayOf(ValType.I32),
    )
    private val free = requireExport(
        name = "pomodorough_free_v2",
        parameters = arrayOf(ValType.I32, ValType.I32),
        returns = arrayOf(ValType.I32),
    )
    private val dispatch = requireExport(
        name = "pomodorough_dispatch",
        parameters = arrayOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
        returns = arrayOf(ValType.I64),
    )

    /** Dispatches UTF-8 JSON and returns successful envelope value. */
    fun dispatch(operation: String, inputJson: String): JsonElement = synchronized(lock) {
        unusableCause?.let { cause ->
            throw SharedCoreException.Abi("shared-core instance is unusable after cleanup failure", cause)
        }
        val operationBytes = operation.toByteArray(UTF_8)
        val inputBytes = inputJson.toByteArray(UTF_8)
        require(operationBytes.isNotEmpty()) { "shared-core operation must not be empty" }
        require(inputBytes.isNotEmpty()) { "shared-core input JSON must not be empty" }
        require(operationBytes.size <= MAX_OPERATION_BYTES) { "shared-core operation is too large" }
        require(inputBytes.size <= MAX_TRANSFER_BYTES) { "shared-core input is too large" }
        var operationPointer = 0
        var inputPointer = 0
        var resultPointer = 0
        var resultLength = 0L
        var failure: Throwable? = null

        try {
            operationPointer = allocate(operationBytes)
            inputPointer = allocate(inputBytes)
            val packedResult = dispatchResultOverride?.invoke() ?: callSingle(
                dispatch,
                "pomodorough_dispatch",
                unsigned(operationPointer),
                operationBytes.size.toLong(),
                unsigned(inputPointer),
                inputBytes.size.toLong(),
            )
            resultPointer = (packedResult and UINT32_MASK).toInt()
            resultLength = packedResult ushr 32
            if ((resultPointer == 0) != (resultLength == 0L)) {
                val ownershipError = SharedCoreException.Abi(
                    "dispatch returned inconsistent pointer/length ownership: " +
                        "pointer=${unsigned(resultPointer)} length=$resultLength",
                )
                unusableCause = ownershipError
                throw ownershipError
            }
            parseEnvelope(operation, readUtf8(resultPointer, checkedLength(resultLength)))
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        } finally {
            releaseAll(
                failure,
                resultPointer to resultLength,
                inputPointer to inputBytes.size.toLong(),
                operationPointer to operationBytes.size.toLong(),
            )
        }
    }

    private fun allocate(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val pointer = callSingle(allocate, "pomodorough_alloc", bytes.size.toLong()).toInt()
        return try {
            requireRange(pointer, bytes.size, "allocated input")
            memory.write(pointer, bytes)
            pointer
        } catch (cause: Throwable) {
            try {
                release(pointer, bytes.size.toLong())
            } catch (cleanup: Throwable) {
                unusableCause = cleanup
                cause.addSuppressed(cleanup)
            }
            throw cause
        }
    }

    private fun release(pointer: Int, length: Long) {
        if (pointer == 0 || length == 0L) return
        val status = freeStatusOverride?.invoke(pointer, length)
            ?: callSingle(free, "pomodorough_free_v2", unsigned(pointer), length)
        if (status != 1L) {
            throw SharedCoreException.Abi("pomodorough_free_v2 rejected buffer with status $status")
        }
    }

    private fun releaseAll(
        primaryFailure: Throwable?,
        vararg buffers: Pair<Int, Long>,
    ) {
        var failure = primaryFailure
        buffers.forEach { (pointer, length) ->
            try {
                release(pointer, length)
            } catch (cleanup: Throwable) {
                unusableCause = cleanup
                if (failure == null) {
                    failure = cleanup
                } else {
                    failure.addSuppressed(cleanup)
                }
            }
        }
        if (primaryFailure == null && failure != null) throw failure
    }

    private fun readUtf8(pointer: Int, length: Int): String {
        requireRange(pointer, length, "dispatch result")
        val bytes = memory.readBytes(pointer, length)
        return try {
            UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (cause: Exception) {
            throw SharedCoreException.Abi("dispatch result is not UTF-8", cause)
        }
    }

    private fun requireRange(pointer: Int, length: Int, label: String) {
        val pointerLong = unsigned(pointer)
        val memoryBytes = memory.pages().toLong() * Memory.PAGE_SIZE
        if ((pointerLong == 0L && length > 0) || length < 0 ||
            length > MAX_TRANSFER_BYTES || pointerLong > Int.MAX_VALUE ||
            pointerLong + length.toLong() > memoryBytes
        ) {
            throw SharedCoreException.Abi(
                "$label range is outside linear memory: pointer=$pointerLong length=$length",
            )
        }
    }

    private fun requireExport(
        name: String,
        parameters: Array<ValType>,
        returns: Array<ValType>,
    ): ExportFunction {
        val expected = FunctionType.of(parameters, returns)
        val actual = try {
            instance.exportType(name)
        } catch (cause: RuntimeException) {
            throw SharedCoreException.Abi("missing shared-core export: $name", cause)
        }
        if (actual != expected) {
            throw SharedCoreException.Abi(
                "shared-core export $name has type $actual; expected $expected",
            )
        }
        return try {
            instance.export(name)
        } catch (cause: RuntimeException) {
            throw SharedCoreException.Abi("missing shared-core export: $name", cause)
        }
    }

    private fun callSingle(function: ExportFunction, name: String, vararg arguments: Long): Long {
        val results = function.apply(*arguments)
        if (results.size != 1) {
            throw SharedCoreException.Abi("shared-core export $name returned ${results.size} values")
        }
        return results[0]
    }

    private fun checkedLength(length: Long): Int {
        if (length > MAX_TRANSFER_BYTES || length > Int.MAX_VALUE) {
            throw SharedCoreException.Abi("dispatch result is too large: $length bytes")
        }
        return length.toInt()
    }

    internal fun parseEnvelope(operation: String, envelopeJson: String): JsonElement {
        val envelope = try {
            Json.parseToJsonElement(envelopeJson).jsonObject
        } catch (cause: Exception) {
            throw SharedCoreException.Abi("dispatch returned an invalid JSON envelope", cause)
        }
        return try {
            when (envelope["ok"]?.jsonPrimitive?.booleanOrNull) {
                true -> {
                    if (envelope.keys != setOf("ok", "value")) {
                        throw SharedCoreException.Abi("successful dispatch envelope is not canonical")
                    }
                    envelope.getValue("value")
                }
                false -> {
                    if (envelope.keys != setOf("ok", "error")) {
                        throw SharedCoreException.Abi("failed dispatch envelope is not canonical")
                    }
                    val error = envelope["error"] as? JsonPrimitive
                    if (error == null || !error.isString || error.content.isBlank()) {
                        throw SharedCoreException.Abi("failed dispatch envelope has no non-empty string error")
                    }
                    throw SharedCoreException.Operation(operation, error.content)
                }
                null -> throw SharedCoreException.Abi("dispatch envelope has no boolean ok field")
            }
        } catch (cause: SharedCoreException) {
            throw cause
        } catch (cause: Exception) {
            throw SharedCoreException.Abi("dispatch returned an invalid JSON envelope", cause)
        }
    }

    companion object {
        const val ASSET_NAME = "pomodorough_core.wasm"
        const val CORE_COMMIT = "44ff36e125cf653c1761dfb5951f6e77e41a2c82"
        const val CORE_SHA256 = "f34fe57b5e080dd69afa5c7f28b60bc77851c7f874db99744eab72b4e1858877"

        private const val MAX_OPERATION_BYTES = 256
        private const val MAX_TRANSFER_BYTES = 16 * 1024 * 1024
        private const val UINT32_MASK = 0xffff_ffffL
        private const val HEX = "0123456789abcdef"
        private val assetCoreLock = Any()
        @Volatile private var assetCore: SharedCore? = null

        /** Opens pinned module from Android assets. */
        fun fromAssets(assets: AssetManager): SharedCore =
            assetCore ?: synchronized(assetCoreLock) {
                assetCore ?: load(assets.open(ASSET_NAME)).also { assetCore = it }
            }

        /** Consumes, closes, verifies, parses, and instantiates pinned module stream. */
        fun load(wasm: InputStream): SharedCore {
            val bytes = wasm.use(InputStream::readBytes)
            val actualHash = sha256(bytes)
            if (actualHash != CORE_SHA256) {
                throw SharedCoreException.Load(
                    "shared-core SHA-256 mismatch: expected $CORE_SHA256, got $actualHash",
                )
            }
            return try {
                SharedCore(Instance.builder(Parser.parse(bytes)).build())
            } catch (cause: SharedCoreException) {
                throw cause
            } catch (cause: RuntimeException) {
                throw SharedCoreException.Load("failed to instantiate shared core", cause)
            }
        }

        private fun sha256(bytes: ByteArray): String = buildString(64) {
            MessageDigest.getInstance("SHA-256").digest(bytes).forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }

        private fun unsigned(value: Int): Long = value.toLong() and UINT32_MASK
    }
}

sealed class SharedCoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class Load(message: String, cause: Throwable? = null) : SharedCoreException(message, cause)
    class Abi(message: String, cause: Throwable? = null) : SharedCoreException(message, cause)
    class Operation(
        val operation: String,
        message: String,
    ) : SharedCoreException("shared-core operation $operation failed: $message")
}
