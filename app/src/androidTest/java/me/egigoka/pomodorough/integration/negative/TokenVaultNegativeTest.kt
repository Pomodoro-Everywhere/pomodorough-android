package me.egigoka.pomodorough.integration.negative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.auth.TokenVault
import me.egigoka.pomodorough.data.auth.TokenStoreState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenVaultNegativeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(UnrelatedPreferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        TokenVault(context, Json).clear()
        context.getSharedPreferences(UnrelatedPreferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun corruptEncryptedPayloadIsPreservedAsUnreadable() {
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        preferences.edit().putString(PayloadKey, "not-valid-base64").commit()
        val vault = TokenVault(context, Json)

        assertTrue(vault.state() is TokenStoreState.Unreadable)
        assertThrows(IllegalStateException::class.java, vault::read)
        assertTrue(preferences.contains(PayloadKey))
    }

    @Test
    fun explicitClearRepairsCorruptPayloadWithoutClearingUnrelatedPreferences() {
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val unrelated = context.getSharedPreferences(UnrelatedPreferencesName, Context.MODE_PRIVATE)
        preferences.edit().putString(PayloadKey, "not-valid-base64").commit()
        unrelated.edit().putString("sentinel", "keep").commit()
        val vault = TokenVault(context, Json)

        vault.clear()

        assertEquals(TokenStoreState.Empty, vault.state())
        assertFalse(preferences.contains(PayloadKey))
        assertEquals("keep", unrelated.getString("sentinel", null))
    }

    private companion object {
        const val PreferencesName = "pomodorough_tokens"
        const val UnrelatedPreferencesName = "token-vault-negative-unrelated"
        const val PayloadKey = "token-pair"
    }
}
