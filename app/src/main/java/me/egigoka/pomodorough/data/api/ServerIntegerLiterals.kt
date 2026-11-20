package me.egigoka.pomodorough.data.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * R55-A04: decodes a server response body and fails closed when an integer
 * field is not a plain JSON integer literal.
 *
 * kotlinx reads a quoted number ("5"), an exponent (1e0) and -0 into a Long
 * field without complaint. Core and Desktop reject those forms, and the Go
 * server writes only plain integer literals. The body is decoded first, so
 * every body that fails today fails with the same error; the literal check
 * then rejects what kotlinx coerced. It throws [SerializationException], the
 * class a malformed serverHlc* raises, so sync and bootstrap classify both
 * failures the same way.
 */
internal inline fun <reified T> Json.decodeServerResponse(body: String): T =
    decodeServerResponse(serializersModule.serializer<T>(), body)

internal fun <T> Json.decodeServerResponse(deserializer: DeserializationStrategy<T>, body: String): T {
    val decoded = decodeFromString(deserializer, body)
    ServerIntegerLiterals.requirePlainIntegers(deserializer.descriptor, parseToJsonElement(body), "response")
    return decoded
}

/**
 * Walks a decoded body along the response type's own serial descriptor, so
 * nested fields (timer, history, durations) are checked too. The shared model
 * serializers stay unchanged: Room, Core output and Iroh peers keep decoding
 * these types exactly as before.
 */
internal object ServerIntegerLiterals {
    // JSON integer grammar without fraction or exponent; -0 is not an integer.
    private val plainInteger = Regex("0|-?[1-9][0-9]*")
    private val integerKinds = setOf(PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG)

    fun requirePlainIntegers(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        // An explicit null is the decoder's decision (CanonicalHlcFieldSerializer).
        if (element is JsonNull) return
        when (descriptor.kind) {
            in integerKinds -> requirePlainInteger(element, path)
            StructureKind.CLASS -> requireFields(descriptor, element, path)
            StructureKind.LIST -> requireItems(descriptor, element, path)
            else -> Unit
        }
    }

    private fun requirePlainInteger(element: JsonElement, path: String) {
        val literal = element as? JsonPrimitive
        if (literal == null || literal.isString || !plainInteger.matches(literal.content)) {
            throw SerializationException("Server field $path must be a plain JSON integer")
        }
    }

    private fun requireFields(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        val fields = element as? JsonObject ?: throw SerializationException("Server field $path must be an object")
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val value = fields[name] ?: continue
            requirePlainIntegers(descriptor.getElementDescriptor(index), value, "$path.$name")
        }
    }

    private fun requireItems(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        val items = element as? JsonArray ?: throw SerializationException("Server field $path must be an array")
        val itemDescriptor = descriptor.getElementDescriptor(0)
        items.forEachIndexed { index, item -> requirePlainIntegers(itemDescriptor, item, "$path[$index]") }
    }
}
