package me.egigoka.pomodorough.data.iroh.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object IrohFrameCodec {
    private val macPrefix = "pomodorough-iroh-frame-v1\u0000".encodeToByteArray()

    fun encode(body: ByteArray, roomSecret: ByteArray): ByteArray {
        require(roomSecret.size == 32 && body.size <= IrohProtocolV1.MaxFrameBodyBytes) {
            "Invalid Iroh frame"
        }
        val mac = hmac(roomSecret, macPrefix + body)
        return ByteBuffer.allocate(4 + mac.size + body.size)
            .putInt(body.size)
            .put(mac)
            .put(body)
            .array()
    }

    fun decode(frame: ByteArray, roomSecret: ByteArray): ByteArray {
        require(roomSecret.size == 32 && frame.size >= 36) { "Invalid Iroh frame" }
        val bodyLength = ByteBuffer.wrap(frame, 0, 4).int
        require(bodyLength in 0..IrohProtocolV1.MaxFrameBodyBytes && frame.size == 36 + bodyLength) {
            "Invalid Iroh frame"
        }
        val supplied = frame.copyOfRange(4, 36)
        val body = frame.copyOfRange(36, frame.size)
        require(MessageDigest.isEqual(supplied, hmac(roomSecret, macPrefix + body))) {
            "Iroh frame authentication failed"
        }
        return body
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(value)
        }
}
