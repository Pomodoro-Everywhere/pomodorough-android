package me.egigoka.pomodorough.data.auth

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TokenPair

interface TokenStore {
    fun read(): TokenPair?
    fun state(): TokenStoreState = read()?.let(TokenStoreState::Active) ?: TokenStoreState.Empty
    fun write(tokens: TokenPair)
    fun markLogoutPending(tokens: TokenPair) {
        throw UnsupportedOperationException("Pending logout persistence is not implemented")
    }
    fun clear() {
        throw UnsupportedOperationException("Token clearing is not implemented")
    }
}

sealed interface TokenStoreState {
    data object Empty : TokenStoreState
    data class Active(val tokens: TokenPair) : TokenStoreState
    data class LogoutPending(val tokens: TokenPair) : TokenStoreState
    data class Unreadable(val cause: Throwable) : TokenStoreState
}

class TokenVault(
    context: Context,
    private val json: Json,
) : TokenStore {
    private val preferences = context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    override fun read(): TokenPair? = when (val state = state()) {
        TokenStoreState.Empty -> null
        is TokenStoreState.Active -> state.tokens
        is TokenStoreState.LogoutPending -> state.tokens
        is TokenStoreState.Unreadable -> throw IllegalStateException(
            "Persisted authentication state is unreadable",
            state.cause,
        )
    }

    @Synchronized
    override fun state(): TokenStoreState {
        val encoded = preferences.getString(PayloadKey, null)
        if (encoded == null) {
            return if (preferences.contains(LogoutPendingKey)) {
                TokenStoreState.Unreadable(
                    IllegalStateException("Pending logout marker has no credential payload"),
                )
            } else {
                TokenStoreState.Empty
            }
        }
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IvSize)
            val iv = bytes.copyOfRange(0, IvSize)
            val ciphertext = bytes.copyOfRange(IvSize, bytes.size)
            val cipher = Cipher.getInstance(Transformation).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TagSizeBits, iv))
            }
            val tokens = json.decodeFromString<TokenPair>(cipher.doFinal(ciphertext).decodeToString())
            if (preferences.getBoolean(LogoutPendingKey, false)) {
                TokenStoreState.LogoutPending(tokens)
            } else {
                TokenStoreState.Active(tokens)
            }
        }.getOrElse(TokenStoreState::Unreadable)
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun write(tokens: TokenPair) {
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val encrypted = cipher.doFinal(json.encodeToString(tokens).encodeToByteArray())
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(PayloadKey, Base64.encodeToString(payload, Base64.NO_WRAP))
                .remove(LogoutPendingKey)
                .commit(),
        ) { "Could not persist authentication tokens" }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun markLogoutPending(tokens: TokenPair) {
        val current = state()
        check(current == TokenStoreState.Active(tokens) || current == TokenStoreState.LogoutPending(tokens)) {
            "Could not mark a non-current authentication session for logout"
        }
        check(preferences.edit().putBoolean(LogoutPendingKey, true).commit()) {
            "Could not persist pending logout"
        }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun clear() {
        check(preferences.edit().remove(PayloadKey).remove(LogoutPendingKey).commit()) {
            "Could not clear authentication tokens"
        }
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

    private companion object {
        const val KeyAlias = "pomodorough-token-key-v1"
        const val PayloadKey = "token-pair"
        const val LogoutPendingKey = "logout-pending"
        const val Transformation = "AES/GCM/NoPadding"
        const val IvSize = 12
        const val TagSizeBits = 128
    }
}
