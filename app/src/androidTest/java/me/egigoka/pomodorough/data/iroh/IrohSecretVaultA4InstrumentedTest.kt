package me.egigoka.pomodorough.data.iroh

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IrohSecretVaultA4InstrumentedTest {
    private lateinit var context: Context
    private lateinit var storageId: String
    private lateinit var preferencesName: String
    private lateinit var keyAlias: String
    private lateinit var vault: IrohSecretVault

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageId = UUID.randomUUID().toString()
        preferencesName = "pomodorough_iroh-$storageId"
        keyAlias = "pomodorough-iroh-secret-key-v1-$storageId"
        vault = IrohSecretVault(context, storageId)
    }

    @After
    fun tearDown() {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        assertTrue(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test
    fun malformedBase64IsTypedCorruptionAndCiphertextIsRetained() {
        assertTrue(endpointPreferences().edit().putString(EndpointPayloadKey, "%%%not-base64%%%").commit())

        assertRecovery(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED) { vault.endpointSecret() }

        assertEquals("%%%not-base64%%%", endpointPreferences().getString(EndpointPayloadKey, null))
    }

    @Test
    fun corruptedCiphertextIsTypedCorruptionAndCiphertextIsRetained() {
        vault.writeEndpointSecret(ByteArray(32) { it.toByte() })
        val payload = Base64.decode(savedEndpointPayload(), Base64.NO_WRAP).also {
            it[it.lastIndex] = (it.last() + 1).toByte()
        }
        val corrupted = Base64.encodeToString(payload, Base64.NO_WRAP)
        assertTrue(endpointPreferences().edit().putString(EndpointPayloadKey, corrupted).commit())

        assertRecovery(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED) { vault.endpointSecret() }

        assertEquals(corrupted, savedEndpointPayload())
    }

    @Test
    fun wrongLengthPlaintextIsTypedCorruption() {
        vault.writeEndpointSecret(ByteArray(32) { it.toByte() })
        val key = androidKeyStore().getKey(keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(EndpointAad)
        }
        val payload = cipher.iv + cipher.doFinal(ByteArray(31) { it.toByte() })
        assertTrue(endpointPreferences().edit().putString(
            EndpointPayloadKey,
            Base64.encodeToString(payload, Base64.NO_WRAP),
        ).commit())

        assertRecovery(IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED) { vault.endpointSecret() }
    }

    @Test
    fun missingKeyIsTypedAndNeverSilentlyRegenerated() {
        vault.writeEndpointSecret(ByteArray(32) { it.toByte() })
        val savedPayload = savedEndpointPayload()
        androidKeyStore().deleteEntry(keyAlias)

        assertRecovery(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING) { vault.endpointSecret() }
        assertRecovery(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING) {
            vault.writeEndpointSecret(ByteArray(32) { (it + 1).toByte() })
        }

        assertEquals(savedPayload, savedEndpointPayload())
        assertFalse(androidKeyStore().containsAlias(keyAlias))
    }

    @Test
    fun resetMarkerQuarantinesColdRestartUntilExplicitCompletion() {
        vault.writeEndpointSecret(ByteArray(32) { it.toByte() })
        vault.beginIdentityReset()
        val restarted = IrohSecretVault(context, storageId)

        assertEquals(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING, restarted.pendingIdentityRecovery())
        assertRecovery(IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING) { restarted.endpointSecret() }

        restarted.completeIdentityReset()
        assertNull(restarted.pendingIdentityRecovery())
        assertNull(restarted.endpointSecret())
        assertFalse(androidKeyStore().containsAlias(keyAlias))
    }

    @Test
    fun endpointReplacementPreservesDecryptableRoomSecret() {
        vault.writeEndpointSecret(ByteArray(32) { it.toByte() })
        val roomSecret = ByteArray(32) { (it + 9).toByte() }
        val roomId = IrohProtocolV1.roomId(roomSecret)
        val encryptedRoomSecret = vault.encryptRoomSecret(roomId, roomSecret)

        vault.replaceEndpointSecret(ByteArray(32) { (it + 31).toByte() })

        assertArrayEquals(roomSecret, vault.decryptRoomSecret(roomId, encryptedRoomSecret))
    }

    private fun endpointPreferences() =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun savedEndpointPayload() =
        requireNotNull(endpointPreferences().getString(EndpointPayloadKey, null))

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun assertRecovery(kind: IrohIdentityRecoveryKind, action: () -> Unit) {
        val error = assertThrows(IrohSecretVaultException::class.java) { action() }
        assertEquals(kind, error.recoveryKind)
    }

    private companion object {
        const val EndpointPayloadKey = "endpoint-secret-v1"
        val EndpointAad = "pomodorough-iroh-endpoint-v1".encodeToByteArray()
    }
}
