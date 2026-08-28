package me.egigoka.pomodorough.integration.positive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.auth.TokenVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandedTokenVaultPositiveIntegrationTest {
    @Test
    fun replacingTokensPersistsOnlyTheLatestEncryptedPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val vault = TokenVault(context, Json)
        val first = tokens("first")
        val second = tokens("second")

        vault.write(first)
        val firstPayload = requireNotNull(preferences.getString(PayloadKey, null))
        vault.write(second)
        val secondPayload = requireNotNull(preferences.getString(PayloadKey, null))

        assertEquals(second, vault.read())
        assertFalse(firstPayload == secondPayload)
        assertFalse(secondPayload.contains(second.accessToken))
        assertFalse(secondPayload.contains(second.refreshToken))
        vault.clear()
    }

    private fun tokens(prefix: String) = TokenPair(
        accessToken = "$prefix-access-secret",
        accessTokenExpiresAt = "2999-01-01T00:00:00Z",
        refreshToken = "$prefix-refresh-secret",
        refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
    )

    private companion object {
        const val PreferencesName = "pomodorough_tokens"
        const val PayloadKey = "token-pair"
    }
}
