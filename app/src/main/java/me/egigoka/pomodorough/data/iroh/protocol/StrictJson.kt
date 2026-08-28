package me.egigoka.pomodorough.data.iroh.protocol

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal object IrohJson {
    val strict = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }
}

internal fun requireExactKeys(
    value: JsonObject,
    required: Set<String>,
    optional: Set<String> = emptySet(),
) {
    require(value.keys.containsAll(required) && value.keys.all { it in required || it in optional }) {
        "JSON object has missing or unknown fields"
    }
}

internal fun requireOmittedNulls(value: JsonObject, optional: Set<String>) {
    require(optional.none { value[it] is JsonNull }) { "Optional JSON fields must be omitted instead of null" }
}

internal fun JsonElement.jsonString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.takeIf { primitive.isString }
}

internal fun JsonElement.jsonLong(): Long? {
    return (this as? JsonPrimitive)?.longOrNull
}

internal fun JsonElement.jsonInt(): Int? {
    return (this as? JsonPrimitive)?.intOrNull
}

internal fun strictJson(value: ByteArray): String {
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value))
        .toString()
        .also { StrictJsonScanner(it).validate() }
}

private class StrictJsonScanner(private val source: String) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        require(index == source.length) { "Body is not strict JSON" }
    }

    private fun parseValue() {
        when (current()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> consumeLiteral("true")
            'f' -> consumeLiteral("false")
            'n' -> consumeLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> invalid()
        }
    }

    private fun parseObject() {
        consume('{')
        skipWhitespace()
        if (consumeIfPresent('}')) return
        val keys = mutableSetOf<String>()
        while (true) {
            require(current() == '"') { "Body is not strict JSON" }
            val key = Normalizer.normalize(parseString(), Normalizer.Form.NFC)
            require(keys.add(key)) { "JSON contains a duplicate object key" }
            skipWhitespace()
            consume(':')
            skipWhitespace()
            parseValue()
            skipWhitespace()
            if (consumeIfPresent('}')) return
            consume(',')
            skipWhitespace()
        }
    }

    private fun parseArray() {
        consume('[')
        skipWhitespace()
        if (consumeIfPresent(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (consumeIfPresent(']')) return
            consume(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        consume('"')
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character.code in 0x00..0x1f -> invalid()
                character == '\\' -> parseEscape(result)
                Character.isHighSurrogate(character) -> {
                    require(index < source.length && Character.isLowSurrogate(source[index])) {
                        "Body is not strict JSON"
                    }
                    result.append(character).append(source[index++])
                }
                Character.isLowSurrogate(character) -> invalid()
                else -> result.append(character)
            }
        }
        invalid()
    }

    private fun parseEscape(result: StringBuilder) {
        val escaped = current() ?: invalid()
        index += 1
        when (escaped) {
            '"', '/', '\\' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000c')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val first = parseHexQuad()
                when (first) {
                    in 0xd800..0xdbff -> {
                        consume('\\')
                        consume('u')
                        val second = parseHexQuad()
                        require(second in 0xdc00..0xdfff) { "Body is not strict JSON" }
                        result.appendCodePoint(
                            0x10000 + ((first - 0xd800) shl 10) + second - 0xdc00,
                        )
                    }
                    in 0xdc00..0xdfff -> invalid()
                    else -> result.append(first.toChar())
                }
            }
            else -> invalid()
        }
    }

    private fun parseHexQuad(): Int {
        require(index <= source.length - 4) { "Body is not strict JSON" }
        var value = 0
        repeat(4) {
            val digit = source[index++].digitToIntOrNull(16) ?: invalid()
            value = (value shl 4) or digit
        }
        return value
    }

    private fun parseNumber() {
        consumeIfPresent('-')
        when (current()) {
            '0' -> {
                index += 1
                require(current() !in '0'..'9') { "Body is not strict JSON" }
            }
            in '1'..'9' -> consumeDigits()
            else -> invalid()
        }
        if (consumeIfPresent('.')) {
            require(current() in '0'..'9') { "Body is not strict JSON" }
            consumeDigits()
        }
        if (current() == 'e' || current() == 'E') {
            index += 1
            if (current() == '+' || current() == '-') index += 1
            require(current() in '0'..'9') { "Body is not strict JSON" }
            consumeDigits()
        }
    }

    private fun consumeDigits() {
        while (current() in '0'..'9') index += 1
    }

    private fun consumeLiteral(value: String) = value.forEach(::consume)

    private fun consume(expected: Char) {
        require(current() == expected) { "Body is not strict JSON" }
        index += 1
    }

    private fun consumeIfPresent(expected: Char): Boolean {
        if (current() != expected) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (current() in setOf(' ', '\t', '\n', '\r')) index += 1
    }

    private fun current(): Char? = source.getOrNull(index)

    private fun invalid(): Nothing = throw IllegalArgumentException("Body is not strict JSON")
}

internal fun JsonObject.withRequiredNull(key: String, value: Any?): JsonObject =
    if (value != null || key in this) this else JsonObject(this + (key to JsonNull))
