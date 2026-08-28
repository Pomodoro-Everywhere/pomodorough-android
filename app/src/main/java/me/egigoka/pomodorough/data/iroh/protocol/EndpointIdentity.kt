package me.egigoka.pomodorough.data.iroh.protocol

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import me.egigoka.pomodorough.data.SyncWireBounds
import me.egigoka.pomodorough.data.UuidV7

object Base64Url {
    private val pattern = Regex("^[A-Za-z0-9_-]+$")

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String): ByteArray {
        require(value.matches(pattern) && value.length % 4 != 1) { "Malformed base64url" }
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("Malformed base64url", it) }
        require(encode(decoded) == value) { "Malformed base64url" }
        return decoded
    }
}

internal object EndpointIdentity {
    private val roomPrefix = "pomodorough-room-v1\u0000".encodeToByteArray()

    fun roomId(roomSecret: ByteArray): String {
        require(roomSecret.size == 32) { "Room secret must contain 32 bytes" }
        return Base64Url.encode(
            MessageDigest.getInstance("SHA-256").digest(roomPrefix + roomSecret),
        )
    }

    fun isIdentifier(value: String): Boolean {
        return SyncWireBounds.isIdentifier(value)
    }

    fun isRoomId(value: String): Boolean = runCatching {
        val decoded = Base64Url.decode(value)
        decoded.size == 32 && Base64Url.encode(decoded) == value
    }.getOrDefault(false)

    fun isDisplayName(value: String?): Boolean = value == null ||
        value.hasWellFormedUtf16() && value.codePointCount() in 1..64

    fun isRequestId(value: String): Boolean = runCatching {
        UuidV7.parts(UUID.fromString(value))
        true
    }.getOrDefault(false)

    fun requestId(nowMs: Long = System.currentTimeMillis()): String =
        UuidV7.reserve(nowMs, previous = null).single().toString()

    fun utf8Compare(left: String, right: String): Int {
        return SyncWireBounds.compareUtf8(left, right)
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private fun String.hasWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val current = this[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index += 1
            }
        }
        return true
    }
}
