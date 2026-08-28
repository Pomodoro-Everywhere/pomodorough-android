package me.egigoka.pomodorough.integration.negative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.auth.TokenVault
import me.egigoka.pomodorough.data.auth.TokenStoreState
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenVaultNegativeTest {
    @Test
    fun corruptEncryptedPayloadIsPreservedAsUnreadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        preferences.edit().putString(PayloadKey, "not-valid-base64").commit()
        val vault = TokenVault(context, Json)

        assertTrue(vault.state() is TokenStoreState.Unreadable)
        assertThrows(IllegalStateException::class.java, vault::read)
        assertTrue(preferences.contains(PayloadKey))
    }

    private companion object {
        const val PreferencesName = "pomodorough_tokens"
        const val PayloadKey = "token-pair"
    }
}
