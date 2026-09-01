package me.egigoka.pomodorough.data.iroh

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class IrohIdentityRecoveryKind {
    ENDPOINT_CORRUPTED,
    KEY_INVALIDATED_OR_MISSING,
}

class IrohSecretVaultException(
    val recoveryKind: IrohIdentityRecoveryKind,
    cause: Throwable? = null,
) : Exception(recoveryKind.name, cause)

class IrohSecretVault private constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    constructor(context: Context) : this(context, PreferencesName, KeyAlias)

    internal constructor(context: Context, storageId: String) : this(
        context,
        "$PreferencesName-$storageId",
        "$KeyAlias-$storageId",
    )

    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun pendingIdentityRecovery(): IrohIdentityRecoveryKind? =
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING.takeIf {
            preferences.getBoolean(ResetPendingKey, false)
        }

    @Synchronized
    fun endpointSecret(): ByteArray? {
        if (preferences.getBoolean(ResetPendingKey, false)) throw keyUnavailable()
        val encoded = preferences.getString(EndpointPayloadKey, null) ?: return null
        val payload = decodeEndpointPayload(encoded)
        return decrypt(payload, EndpointAad, IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED).also {
            if (it.size != EndpointSecretSize) {
                it.fill(0)
                throw endpointCorrupted()
            }
        }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    fun writeEndpointSecret(secret: ByteArray) {
        require(secret.size == EndpointSecretSize) { "Iroh endpoint secret must contain 32 bytes" }
        val key = existingKey() ?: createInitialKey()
        persistEndpointSecret(secret, key)
    }

    @Synchronized
    fun replaceEndpointSecret(secret: ByteArray) {
        require(secret.size == EndpointSecretSize) { "Iroh endpoint secret must contain 32 bytes" }
        persistEndpointSecret(secret, requireExistingKey())
    }

    @Synchronized
    fun encryptRoomSecret(roomId: String, secret: ByteArray): ByteArray {
        require(IrohProtocolV1.roomId(secret) == roomId) { "Room secret does not match room ID" }
        return encrypt(secret, roomAad(roomId), existingKey() ?: createInitialKey())
    }

    @Synchronized
    fun decryptRoomSecret(roomId: String, payload: ByteArray): ByteArray {
        val secret = decrypt(payload, roomAad(roomId), IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING)
        if (secret.size != RoomSecretSize || IrohProtocolV1.roomId(secret) != roomId) {
            secret.fill(0)
            throw keyUnavailable()
        }
        return secret
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    fun beginIdentityReset() {
        check(preferences.edit().putBoolean(ResetPendingKey, true).commit()) {
            "Could not persist Iroh identity reset quarantine"
        }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    fun completeIdentityReset() {
        deleteKey()
        check(preferences.edit().remove(EndpointPayloadKey).remove(ResetPendingKey).commit()) {
            "Could not complete Iroh identity reset"
        }
    }

    private fun decodeEndpointPayload(encoded: String): ByteArray = try {
        Base64.decode(encoded, Base64.NO_WRAP)
    } catch (error: IllegalArgumentException) {
        throw endpointCorrupted(error)
    }

    @SuppressLint("ApplySharedPref")
    private fun persistEndpointSecret(secret: ByteArray, key: SecretKey) {
        val payload = encrypt(secret, EndpointAad, key)
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        check(preferences.edit().putString(EndpointPayloadKey, encoded).commit()) {
            "Could not persist Iroh endpoint identity"
        }
    }

    private fun encrypt(value: ByteArray, aad: ByteArray, key: SecretKey): ByteArray =
        classifyCryptoFailure(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING) {
            val cipher = Cipher.getInstance(Transformation).apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(aad)
            }
            cipher.iv + cipher.doFinal(value)
        }

    private fun decrypt(
        payload: ByteArray,
        aad: ByteArray,
        corruptionKind: IrohIdentityRecoveryKind,
    ): ByteArray {
        if (payload.size <= IvSize) throw recoveryFailure(corruptionKind)
        return classifyCryptoFailure(corruptionKind) {
            val cipher = Cipher.getInstance(Transformation).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    requireExistingKey(),
                    GCMParameterSpec(TagSizeBits, payload.copyOfRange(0, IvSize)),
                )
                updateAAD(aad)
            }
            cipher.doFinal(payload.copyOfRange(IvSize, payload.size))
        }
    }

    private fun existingKey(): SecretKey? = classifyCryptoFailure(
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING,
    ) {
        val value = keyStore.getKey(keyAlias, null) ?: return@classifyCryptoFailure null
        value as? SecretKey ?: throw keyUnavailable()
    }

    private fun requireExistingKey(): SecretKey = existingKey() ?: throw keyUnavailable()

    private fun createInitialKey(): SecretKey {
        if (preferences.contains(EndpointPayloadKey) || preferences.getBoolean(ResetPendingKey, false)) {
            throw keyUnavailable()
        }
        return classifyCryptoFailure(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(keySpecification())
                generateKey()
            }
        }
    }

    private fun keySpecification() = KeyGenParameterSpec.Builder(
        keyAlias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build()

    private fun deleteKey() = classifyCryptoFailure(
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING,
    ) {
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }

    private inline fun <T> classifyCryptoFailure(
        corruptionKind: IrohIdentityRecoveryKind,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: IrohSecretVaultException) {
        throw error
    } catch (error: GeneralSecurityException) {
        val kind = if (error.isKeyFailure()) {
            IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING
        } else {
            corruptionKind
        }
        throw recoveryFailure(kind, error)
    } catch (error: ProviderException) {
        throw keyUnavailable(error)
    }

    private fun GeneralSecurityException.isKeyFailure(): Boolean =
        this is InvalidKeyException && this !is AEADBadTagException

    private fun roomAad(roomId: String) = "pomodorough-iroh-room-v1\u0000$roomId".encodeToByteArray()

    private companion object {
        const val PreferencesName = "pomodorough_iroh"
        const val KeyAlias = "pomodorough-iroh-secret-key-v1"
        const val EndpointPayloadKey = "endpoint-secret-v1"
        const val ResetPendingKey = "identity-reset-pending-v1"
        const val Transformation = "AES/GCM/NoPadding"
        const val EndpointSecretSize = 32
        const val RoomSecretSize = 32
        const val IvSize = 12
        const val TagSizeBits = 128
        val EndpointAad = "pomodorough-iroh-endpoint-v1".encodeToByteArray()
    }
}

private fun endpointCorrupted(cause: Throwable? = null) =
    IrohSecretVaultException(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED, cause)

private fun keyUnavailable(cause: Throwable? = null) =
    IrohSecretVaultException(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING, cause)

private fun recoveryFailure(kind: IrohIdentityRecoveryKind, cause: Throwable? = null) =
    IrohSecretVaultException(kind, cause)
