package me.egigoka.pomodorough.integration.negative

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.auth.TokenVault
import me.egigoka.pomodorough.data.auth.TokenStoreState
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandedTokenVaultNegativeIntegrationTest {
    @Test
    fun authenticatedCiphertextTamperingIsPreservedAsUnreadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val vault = TokenVault(context, Json)
        vault.write(TokenPair(
            accessToken = "access-secret",
            accessTokenExpiresAt = "2999-01-01T00:00:00Z",
            refreshToken = "refresh-secret",
            refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
        ))
        val payload = requireNotNull(preferences.getString(PayloadKey, null))
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        preferences.edit()
            .putString(PayloadKey, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .commit()

        assertTrue(vault.state() is TokenStoreState.Unreadable)
        assertThrows(IllegalStateException::class.java, vault::read)
        assertTrue(preferences.contains(PayloadKey))
    }

    private companion object {
        const val PreferencesName = "pomodorough_tokens"
        const val PayloadKey = "token-pair"
    }
}
