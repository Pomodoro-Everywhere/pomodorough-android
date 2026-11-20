package me.egigoka.pomodorough.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * R55-A03: one field of the canonical HLC pair in a sync response. Only a
 * missing key means "absent" (an older server); an explicit JSON null fails
 * decoding, exactly like a null serverHlc* field, so a null pair can never
 * fall back to the server clock. Core and Desktop reject a null pair too.
 *
 * The descriptor is nullable so the decoder hands a literal null to
 * [deserialize] instead of mapping it to Kotlin null on its own.
 */
internal object CanonicalHlcFieldSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = Long.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): Long? {
        if (!decoder.decodeNotNullMark()) {
            throw SerializationException("Canonical HLC head field must be an integer, not null")
        }
        return decoder.decodeLong()
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}
