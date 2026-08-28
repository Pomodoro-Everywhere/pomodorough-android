package me.egigoka.pomodorough.data.auth

import android.annotation.SuppressLint
import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService

class AuthenticationRequired(message: String = "Sign in required") : Exception(message)

enum class AuthCredentialState { Empty, Active, LogoutPending, Unreadable }

interface AuthSession {
    suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String): TokenPair
    fun hasTokens(): Boolean
    fun credentialState(): AuthCredentialState =
        if (hasTokens()) AuthCredentialState.Active else AuthCredentialState.Empty
    suspend fun <T> authorized(block: suspend (String) -> T): T
    suspend fun logout()
    suspend fun deleteAccount(confirmation: String) {
        throw UnsupportedOperationException("Account deletion is not implemented")
    }
    fun clear()
}

interface GoogleCredentialProvider {
    suspend fun identityToken(serverClientId: String, nonce: String): String
}

class SystemGoogleCredentialProvider(
    private val activity: Activity,
) : GoogleCredentialProvider {
    @SuppressLint("CredentialManagerSignInWithGoogle")
    override suspend fun identityToken(serverClientId: String, nonce: String): String {
        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setNonce(nonce)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val result = CredentialManager.create(activity).getCredential(
            context = activity,
            request = GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build(),
        )
        val credential = result.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw AuthenticationRequired("Google did not return an ID token")
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}

class AuthRepository(
    private val api: PomodoroughService,
    private val tokenVault: TokenStore,
    private val googleServerClientId: String,
) : AuthSession {
    private val refreshMutex = Mutex()
    private val logoutMutex = Mutex()
    private val sessionLock = Any()
    private var sessionGeneration = 0L
    private var logoutInFlight: SessionSnapshot? = null

    override suspend fun signIn(
        credentialProvider: GoogleCredentialProvider,
        deviceId: String,
    ): TokenPair {
        retryPendingLogout()
        val challenge = api.createChallenge()
        val idToken = credentialProvider.identityToken(googleServerClientId, challenge.nonce)
            .takeIf(String::isNotBlank)
            ?: throw AuthenticationRequired("Google did not return an ID token")
        val tokens = api.exchange(
            NativeExchangeRequest(
                idToken = idToken,
                challenge = challenge.challenge,
                deviceId = deviceId,
                platform = "android",
            ),
        )
        replaceSession(tokens)
        return tokens
    }

    override fun hasTokens(): Boolean = tokenVault.state() is TokenStoreState.Active

    override fun credentialState(): AuthCredentialState = when (tokenVault.state()) {
        TokenStoreState.Empty -> AuthCredentialState.Empty
        is TokenStoreState.Active -> AuthCredentialState.Active
        is TokenStoreState.LogoutPending -> AuthCredentialState.LogoutPending
        is TokenStoreState.Unreadable -> AuthCredentialState.Unreadable
    }

    override suspend fun <T> authorized(block: suspend (String) -> T): T =
        authorizedWithSession(block).first

    private suspend fun <T> authorizedWithSession(
        block: suspend (String) -> T,
    ): Pair<T, SessionSnapshot> {
        val initial = validAccessToken()
        return try {
            block(initial.tokens.accessToken) to initial
        } catch (error: ApiException) {
            if (error.statusCode != 401) throw error
            val refreshed = refresh(initial.tokens.accessToken, force = true)
            try {
                block(refreshed.tokens.accessToken) to refreshed
            } catch (retryError: ApiException) {
                if (retryError.statusCode != 401) throw retryError
                clearSessionIfCurrent(refreshed)
                throw AuthenticationRequired("Session expired")
            }
        }
    }

    override suspend fun logout() = logoutMutex.withLock {
        val session = prepareLogout() ?: return@withLock
        try {
            try {
                api.logout(session.tokens.accessToken)
            } catch (error: ApiException) {
                if (error.statusCode != 401) throw error
            }
            clearSessionIfCurrent(session)
        } finally {
            synchronized(sessionLock) {
                if (logoutInFlight == session) logoutInFlight = null
            }
        }
    }

    private suspend fun retryPendingLogout() {
        when (val state = tokenVault.state()) {
            TokenStoreState.Empty, is TokenStoreState.Active -> return
            is TokenStoreState.LogoutPending -> {
                val alreadyRunning = synchronized(sessionLock) {
                    logoutInFlight?.tokens == state.tokens
                }
                if (!alreadyRunning) logout()
            }
            is TokenStoreState.Unreadable -> throw AuthenticationRequired(
                "Stored session is unreadable; clear local account data before signing in",
            )
        }
    }

    private fun prepareLogout(): SessionSnapshot? = synchronized(sessionLock) {
        when (val state = tokenVault.state()) {
            TokenStoreState.Empty -> {
                sessionGeneration += 1
                tokenVault.clear()
                null
            }
            is TokenStoreState.Active -> {
                tokenVault.markLogoutPending(state.tokens)
                sessionGeneration += 1
                SessionSnapshot(state.tokens, sessionGeneration).also { logoutInFlight = it }
            }
            is TokenStoreState.LogoutPending ->
                SessionSnapshot(state.tokens, sessionGeneration).also { logoutInFlight = it }
            is TokenStoreState.Unreadable -> throw AuthenticationRequired(
                "Stored session is unreadable; clear local account data to recover",
            )
        }
    }

    override suspend fun deleteAccount(confirmation: String) {
        require(confirmation == "DELETE") { "Type DELETE exactly" }
        val (_, deletedSession) = authorizedWithSession { api.deleteAccount(it, confirmation) }
        clearSessionIfCurrent(deletedSession)
    }

    override fun clear() {
        clearSession()
    }

    private suspend fun validAccessToken(): SessionSnapshot {
        val current = currentSession() ?: throw AuthenticationRequired()
        return if (isAccessFresh(current.tokens)) {
            current
        } else {
            refresh(current.tokens.accessToken, force = false)
        }
    }

    private suspend fun refresh(previousAccessToken: String, force: Boolean): SessionSnapshot =
        refreshMutex.withLock {
            val current = currentSession() ?: throw AuthenticationRequired()
            if (current.tokens.accessToken != previousAccessToken ||
                (!force && isAccessFresh(current.tokens))
            ) {
                return@withLock current
            }
            try {
                val refreshed = api.refresh(current.tokens.refreshToken)
                replaceSessionIfCurrent(current, refreshed) ?: throw AuthenticationRequired()
            } catch (error: ApiException) {
                if (error.statusCode == 401) {
                    clearSessionIfCurrent(current)
                    throw AuthenticationRequired("Session expired")
                }
                throw error
            }
        }

    private fun currentSession(): SessionSnapshot? = synchronized(sessionLock) {
        (tokenVault.state() as? TokenStoreState.Active)?.tokens
            ?.let { SessionSnapshot(it, sessionGeneration) }
    }

    private fun replaceSession(tokens: TokenPair): SessionSnapshot = synchronized(sessionLock) {
        sessionGeneration += 1
        tokenVault.write(tokens)
        SessionSnapshot(tokens, sessionGeneration)
    }

    private fun replaceSessionIfCurrent(
        current: SessionSnapshot,
        replacement: TokenPair,
    ): SessionSnapshot? = synchronized(sessionLock) {
        if (sessionGeneration != current.generation || tokenVault.read() != current.tokens) {
            return@synchronized null
        }
        replaceSession(replacement)
    }

    private fun clearSession(): SessionSnapshot? = synchronized(sessionLock) {
        val current = tokenVault.read()?.let { SessionSnapshot(it, sessionGeneration) }
        sessionGeneration += 1
        tokenVault.clear()
        current
    }

    private fun clearSessionIfCurrent(current: SessionSnapshot): Boolean = synchronized(sessionLock) {
        val storedTokens = when (val state = tokenVault.state()) {
            is TokenStoreState.Active -> state.tokens
            is TokenStoreState.LogoutPending -> state.tokens
            else -> null
        }
        if (sessionGeneration != current.generation || storedTokens != current.tokens) {
            return@synchronized false
        }
        clearSession()
        true
    }

    private fun isAccessFresh(tokens: TokenPair): Boolean {
        val expiresAt = runCatching { Instant.parse(tokens.accessTokenExpiresAt) }.getOrNull() ?: return false
        return expiresAt.isAfter(Instant.now().plusSeconds(60))
    }

    private data class SessionSnapshot(
        val tokens: TokenPair,
        val generation: Long,
    )
}
