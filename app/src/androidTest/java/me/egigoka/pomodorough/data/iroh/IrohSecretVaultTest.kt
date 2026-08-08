package me.egigoka.pomodorough.data.iroh

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohSecretVaultTest {
    @Test
    fun endpointIdentityAndRoomSecretRoundTripThroughAndroidKeystore() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vault = IrohSecretVault(context)
        val endpointSecret = ByteArray(32) { (it * 3).toByte() }
        val roomSecret = ByteArray(32) { it.toByte() }
        val roomId = IrohProtocolV1.roomId(roomSecret)

        vault.writeEndpointSecret(endpointSecret)
        assertArrayEquals(endpointSecret, vault.endpointSecret())

        val encrypted = vault.encryptRoomSecret(roomId, roomSecret)
        assertFalse(encrypted.contentEquals(roomSecret))
        assertArrayEquals(roomSecret, vault.decryptRoomSecret(roomId, encrypted))
    }

    @Test
    fun corruptedEndpointPayloadFailsClosedWithoutDeletingIdentity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("pomodorough_iroh", Context.MODE_PRIVATE)
        val vault = IrohSecretVault(context)
        val endpointSecret = ByteArray(32) { (it * 5).toByte() }
        vault.writeEndpointSecret(endpointSecret)
        val encrypted = requireNotNull(preferences.getString("endpoint-secret-v1", null))
        preferences.edit().putString("endpoint-secret-v1", "AQID").commit()

        assertThrows(Exception::class.java) { vault.endpointSecret() }
        assertEquals("AQID", preferences.getString("endpoint-secret-v1", null))

        preferences.edit().putString("endpoint-secret-v1", encrypted).commit()
        assertArrayEquals(endpointSecret, vault.endpointSecret())
    }
}
