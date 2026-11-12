package me.egigoka.pomodorough.core

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.Parser

/**
 * R54-A15: a minimal module with the shared-core ABI exports whose `pomodorough_dispatch` runs
 * `unreachable`, so Chicory raises its own TrapException, not a SharedCoreException, from inside
 * the export. Allocation always returns one buffer at byte 1024 and every free succeeds, so a
 * dispatch reaches the trap with both inputs written.
 */
internal object TrappingCoreModule {
    fun instance(): Instance = build(listOf(UNREACHABLE))

    /**
     * The same exports with a `pomodorough_dispatch` that calls itself with its own arguments, so
     * Chicory runs out of call stack inside the export. Chicory 1.7.5 catches the
     * StackOverflowError and raises ChicoryException("call stack exhausted"), not a TrapException.
     */
    fun recursiveInstance(): Instance = build(
        listOf(LOCAL_GET, 0, LOCAL_GET, 1, LOCAL_GET, 2, LOCAL_GET, 3, CALL, DISPATCH_INDEX),
    )

    private fun build(dispatchBody: List<Int>): Instance =
        Instance.builder(Parser.parse(bytes(dispatchBody))).build()

    private fun bytes(dispatchBody: List<Int>): ByteArray {
        val types = vector(
            listOf(FUNC_TYPE, 1, I32, 1, I32), // alloc: (i32) -> i32
            listOf(FUNC_TYPE, 2, I32, I32, 1, I32), // free: (i32, i32) -> i32
            listOf(FUNC_TYPE, 4, I32, I32, I32, I32, 1, I64), // dispatch: (i32 x4) -> i64
        )
        val functions = vector(listOf(0), listOf(1), listOf(2))
        val memories = vector(listOf(0, 1)) // no maximum, one page
        val exports = vector(
            export("memory", MEMORY_EXPORT, 0),
            export("pomodorough_alloc", FUNCTION_EXPORT, 0),
            export("pomodorough_free_v2", FUNCTION_EXPORT, 1),
            export("pomodorough_dispatch", FUNCTION_EXPORT, DISPATCH_INDEX),
        )
        val code = vector(
            body(I32_CONST, 0x80, 0x08), // i32.const 1024
            body(I32_CONST, 1), // i32.const 1, the accepted free status
            body(*dispatchBody.toIntArray()),
        )
        val module = HEADER + section(1, types) + section(3, functions) + section(5, memories) +
            section(7, exports) + section(10, code)
        return ByteArray(module.size) { module[it].toByte() }
    }

    private fun vector(vararg entries: List<Int>): List<Int> = listOf(entries.size) + entries.flatMap { it }

    private fun export(name: String, kind: Int, index: Int): List<Int> =
        listOf(name.length) + name.map(Char::code) + listOf(kind, index)

    private fun body(vararg instructions: Int): List<Int> =
        sized(listOf(NO_LOCALS) + instructions.toList() + END)

    private fun section(id: Int, content: List<Int>): List<Int> = listOf(id) + sized(content)

    // Every size in this module fits one unsigned LEB128 byte.
    private fun sized(content: List<Int>): List<Int> {
        check(content.size < 0x80) { "content needs a multi-byte size" }
        return listOf(content.size) + content
    }

    private val HEADER = listOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00)
    private const val FUNC_TYPE = 0x60
    private const val I32 = 0x7f
    private const val I64 = 0x7e
    private const val FUNCTION_EXPORT = 0x00
    private const val MEMORY_EXPORT = 0x02
    private const val NO_LOCALS = 0x00
    private const val I32_CONST = 0x41
    private const val UNREACHABLE = 0x00
    private const val LOCAL_GET = 0x20
    private const val CALL = 0x10
    private const val DISPATCH_INDEX = 2
    private const val END = 0x0b
}
