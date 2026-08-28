package me.egigoka.pomodorough.unit.negative

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.LocalDecodingException
import me.egigoka.pomodorough.data.TimerLocalInitializer
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.User
import me.egigoka.pomodorough.data.local.BootstrapDao
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.TimerWorkspaceDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceBoundaryNegativeUnitTest {
    private val json = Json { explicitNulls = false }
    private val strictJson = Json(json) { ignoreUnknownKeys = false }

    @Test
    fun malformedSettingsIsClassifiedWithTheExactStoredState() {
        val local = localState().copy(settingsJson = "{malformed")

        val error = assertThrows(LocalDecodingException::class.java) {
            runBlocking { initializer(local) {}.load() }
        }

        assertSame(local, error.local)
        assertTrue(error.cause is kotlinx.serialization.SerializationException)
    }

    @Test
    fun persistedNullAccountIsRejectedInsteadOfBecomingSignedOutState() {
        val local = localState().copy(userJson = "null")

        val error = assertThrows(LocalDecodingException::class.java) {
            runBlocking { initializer(local) {}.load() }
        }

        assertSame(local, error.local)
        assertEquals("Persisted account JSON is null", error.cause?.message)
    }

    @Test
    fun unknownPersistedAccountFieldFailsClosedBeforeValidation() {
        val local = localState().copy(
            userJson = """{"id":"account","email":"a@example.test","name":"A","avatarUrl":"u","role":"admin"}""",
        )
        var validationCalls = 0

        assertThrows(LocalDecodingException::class.java) {
            runBlocking { initializer(local) { validationCalls++ }.load() }
        }

        assertEquals(0, validationCalls)
    }

    @Test
    fun accountIdentityValidationFailureRetainsCorruptionEvidence() {
        val user = User("bad", "a@example.test", "A", "u")
        val local = localState().copy(userJson = json.encodeToString(user))
        val rejected = IllegalArgumentException("Account identifier is invalid")

        val error = assertThrows(LocalDecodingException::class.java) {
            runBlocking { initializer(local) { throw rejected }.load() }
        }

        assertSame(local, error.local)
        assertSame(rejected, error.cause)
    }

    private fun initializer(
        local: LocalStateEntity,
        validate: (User) -> Unit,
    ): TimerLocalInitializer {
        val values = mapOf<String, Any?>(
            "localState" to local,
            "pendingCommands" to emptyList<Any>(),
            "pendingTaskOperations" to emptyList<Any>(),
            "pendingDurationOperations" to emptyList<Any>(),
            "pendingAutoStartOperations" to emptyList<Any>(),
            "pendingSelectedTaskOperations" to emptyList<Any>(),
            "pendingBootstrapResolution" to null,
        )
        return TimerLocalInitializer(
            proxy(TimerWorkspaceDao::class.java, values),
            proxy(BootstrapDao::class.java, values),
            json,
            strictJson,
            validate,
        )
    }

    private fun localState() = LocalStateEntity(
        deviceId = "device",
        settingsJson = json.encodeToString(TimerSettings()),
    )

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> proxy(type: Class<T>, values: Map<String, Any?>): T = Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, _ -> values[method.name] } as T
    }
}
