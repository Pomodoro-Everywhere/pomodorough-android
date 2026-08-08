package me.egigoka.pomodorough.data.iroh

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IrohSecretVault(context: Context) {
    private val preferences = context.getSharedPreferences("pomodorough_iroh", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun endpointSecret(): ByteArray? {
        val encoded = preferences.getString(EndpointPayloadKey, null) ?: return null
        return decrypt(Base64.decode(encoded, Base64.NO_WRAP), EndpointAad)
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    fun writeEndpointSecret(secret: ByteArray) {
        require(secret.size == 32) { "Iroh endpoint secret must contain 32 bytes" }
        val encoded = Base64.encodeToString(encrypt(secret, EndpointAad), Base64.NO_WRAP)
        check(preferences.edit().putString(EndpointPayloadKey, encoded).commit()) {
            "Could not persist Iroh endpoint identity"
        }
    }

    @Synchronized
    fun encryptRoomSecret(roomId: String, secret: ByteArray): ByteArray {
        require(IrohProtocolV1.roomId(secret) == roomId) { "Room secret does not match room ID" }
        return encrypt(secret, roomAad(roomId))
    }

    @Synchronized
    fun decryptRoomSecret(roomId: String, payload: ByteArray): ByteArray =
        decrypt(payload, roomAad(roomId)).also { secret ->
            require(IrohProtocolV1.roomId(secret) == roomId) { "Saved room secret does not match room ID" }
        }

    private fun encrypt(value: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
            updateAAD(aad)
        }
        return cipher.iv + cipher.doFinal(value)
    }

    private fun decrypt(payload: ByteArray, aad: ByteArray): ByteArray {
        require(payload.size > IvSize) { "Encrypted Iroh secret is invalid" }
        val cipher = Cipher.getInstance(Transformation).apply {
            init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TagSizeBits, payload.copyOfRange(0, IvSize)),
            )
            updateAAD(aad)
        }
        return cipher.doFinal(payload.copyOfRange(IvSize, payload.size))
    }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun roomAad(roomId: String) = "pomodorough-iroh-room-v1\u0000$roomId".encodeToByteArray()

    private companion object {
        const val KeyAlias = "pomodorough-iroh-secret-key-v1"
        const val EndpointPayloadKey = "endpoint-secret-v1"
        const val Transformation = "AES/GCM/NoPadding"
        const val IvSize = 12
        const val TagSizeBits = 128
        val EndpointAad = "pomodorough-iroh-endpoint-v1".encodeToByteArray()
    }
}
