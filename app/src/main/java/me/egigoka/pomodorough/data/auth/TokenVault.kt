package me.egigoka.pomodorough.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
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
    fun retireForLogout(tokens: TokenPair): LogoutRevocation {
        markLogoutPending(tokens)
        return LogoutRevocation(tokens = tokens)
    }
    fun pendingLogoutRevocations(): List<LogoutRevocation> = when (val stored = state()) {
        is TokenStoreState.LogoutPending -> stored.revocations
        else -> emptyList()
    }
    fun replaceLogoutRevocation(previous: LogoutRevocation, replacement: LogoutRevocation) {
        throw UnsupportedOperationException("Pending logout replacement is not implemented")
    }
    fun completeLogoutRevocation(revocation: LogoutRevocation) = clear()
    fun clear() {
        throw UnsupportedOperationException("Token clearing is not implemented")
    }
}

@Serializable
data class LogoutRevocation(
    val id: String = UUID.randomUUID().toString(),
    val tokens: TokenPair,
    val refreshOutcomeUnknown: Boolean = false,
)

sealed interface TokenStoreState {
    data object Empty : TokenStoreState
    data class Active(val tokens: TokenPair) : TokenStoreState
    data class LogoutPending(val revocations: List<LogoutRevocation>) : TokenStoreState
    data class Unreadable(val cause: Throwable) : TokenStoreState
}

@Serializable
private data class StoredAuthPayload(
    val format: Int,
    val active: TokenPair? = null,
    val logoutRevocations: List<LogoutRevocation> = emptyList(),
)

class TokenVault(
    context: Context,
    private val json: Json,
) : TokenStore {
    private val preferences = context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    override fun read(): TokenPair? = loadPayload().getOrElse {
        throw IllegalStateException("Persisted authentication state is unreadable", it)
    }.active

    @Synchronized
    override fun state(): TokenStoreState = loadPayload().fold(
        onSuccess = { payload ->
            when {
                payload.active != null -> TokenStoreState.Active(payload.active)
                payload.logoutRevocations.isNotEmpty() -> {
                    TokenStoreState.LogoutPending(payload.logoutRevocations)
                }
                else -> TokenStoreState.Empty
            }
        },
        onFailure = TokenStoreState::Unreadable,
    )

    @Synchronized
    override fun write(tokens: TokenPair) {
        val current = loadPayload().getOrThrow()
        persist(current.copy(active = tokens), "Could not persist authentication tokens")
    }

    @Synchronized
    override fun markLogoutPending(tokens: TokenPair) {
        retireForLogout(tokens)
    }

    @Synchronized
    override fun retireForLogout(tokens: TokenPair): LogoutRevocation {
        val current = loadPayload().getOrThrow()
        check(current.active == tokens) { "Could not retire a non-current authentication session" }
        val revocation = LogoutRevocation(tokens = tokens)
        val pending = current.logoutRevocations + revocation
        persist(
            current.copy(active = null, logoutRevocations = pending),
            "Could not persist pending logout",
        )
        return revocation
    }

    @Synchronized
    override fun pendingLogoutRevocations(): List<LogoutRevocation> =
        loadPayload().getOrThrow().logoutRevocations

    @Synchronized
    override fun replaceLogoutRevocation(
        previous: LogoutRevocation,
        replacement: LogoutRevocation,
    ) {
        val current = loadPayload().getOrThrow()
        val index = current.logoutRevocations.indexOfFirst { it.id == previous.id }
        check(index >= 0) { "Could not replace missing pending logout" }
        val updated = current.logoutRevocations.toMutableList().apply { set(index, replacement) }
        persist(current.copy(logoutRevocations = updated), "Could not update pending logout")
    }

    @Synchronized
    override fun completeLogoutRevocation(revocation: LogoutRevocation) {
        val current = loadPayload().getOrThrow()
        persist(
            current.copy(logoutRevocations = current.logoutRevocations.filterNot { it.id == revocation.id }),
            "Could not complete pending logout",
        )
    }

    @Synchronized
    override fun clear() {
        val current = loadPayload().getOrNull()
        if (current == null) {
            deletePayload("Could not repair authentication storage")
            return
        }
        persist(current.copy(active = null), "Could not clear authentication tokens")
    }

    private fun loadPayload(): Result<StoredAuthPayload> = runCatching {
        val encoded = preferences.getString(PayloadKey, null)
        if (encoded != null) return@runCatching decodePayload(encoded)
        check(!preferences.contains(LogoutPendingKey)) {
            "Pending logout marker has no encrypted payload"
        }
        StoredAuthPayload(format = CurrentFormat)
    }

    private fun decodePayload(encoded: String): StoredAuthPayload {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IvSize)
        val cipher = Cipher.getInstance(Transformation).apply {
            init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TagSizeBits, bytes.copyOfRange(0, IvSize)),
            )
        }
        val plaintext = cipher.doFinal(bytes.copyOfRange(IvSize, bytes.size)).decodeToString()
        return runCatching { json.decodeFromString<StoredAuthPayload>(plaintext) }
            .getOrElse { migrateLegacy(json.decodeFromString(plaintext)) }
    }

    private fun migrateLegacy(tokens: TokenPair): StoredAuthPayload {
        if (!preferences.getBoolean(LogoutPendingKey, false)) {
            return StoredAuthPayload(format = CurrentFormat, active = tokens)
        }
        return StoredAuthPayload(
            format = CurrentFormat,
            logoutRevocations = listOf(LogoutRevocation(tokens = tokens)),
        )
    }

    @SuppressLint("ApplySharedPref")
    private fun persist(payload: StoredAuthPayload, failureMessage: String) {
        val editor = preferences.edit().remove(LogoutPendingKey)
        if (payload.active == null && payload.logoutRevocations.isEmpty()) {
            check(editor.remove(PayloadKey).commit()) { failureMessage }
            return
        }
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(payload).encodeToByteArray())
        check(editor.putString(PayloadKey, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            failureMessage
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun deletePayload(failureMessage: String) {
        check(preferences.edit().remove(PayloadKey).remove(LogoutPendingKey).commit()) {
            failureMessage
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
        const val CurrentFormat = 2
    }
}
