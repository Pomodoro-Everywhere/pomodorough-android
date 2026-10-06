package me.egigoka.pomodorough.data

import android.content.SharedPreferences
import me.egigoka.pomodorough.timer.shouldStopCompletionAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43-A09: failed alarm preference writes must not look successful.
 * Each test injects commit=false, then recreates the coordinator from the
 * same store to prove in-memory state matches what actually persisted.
 */
class R43A09AlarmPersistenceTest {
    @Test
    fun failedSaveKeepsPriorAlertReportsSaveAndRecreatesConsistent() {
        val store = CommitResultStore(persisted = "timer-1", commitSucceeds = false)
        val events = RecordingEvents()
        val coordinator = coordinator(store, events)

        val transition = coordinator.markCompletionAlert("timer-2")

        assertFalse(transition.changed)
        assertEquals("timer-1", transition.timerId)
        assertEquals("timer-1", coordinator.completionAlertTimerId)
        assertEquals(listOf(CompletionAlertPersistenceFailure.Save), events.failures)
        assertTrue(events.changed.isEmpty())
        val recreated = coordinator(store, RecordingEvents())
        assertEquals(store.load(), recreated.completionAlertTimerId)
        assertEquals("timer-1", recreated.completionAlertTimerId)
    }

    @Test
    fun failedClearKeepsAlertReportsClearSkipsNotificationRecreatesConsistent() {
        val store = CommitResultStore(persisted = "timer-1", commitSucceeds = false)
        val events = RecordingEvents()
        var notifications = 0
        val coordinator = coordinator(store, events) { notifications += 1 }

        val transition = coordinator.stopCompletionAlert("timer-1")

        assertFalse(transition.changed)
        assertEquals("timer-1", coordinator.completionAlertTimerId)
        assertEquals(listOf(CompletionAlertPersistenceFailure.Clear), events.failures)
        assertTrue(events.changed.isEmpty())
        assertEquals(0, notifications)
        val recreated = coordinator(store, RecordingEvents())
        assertEquals(store.load(), recreated.completionAlertTimerId)
        assertEquals("timer-1", recreated.completionAlertTimerId)
    }

    @Test
    fun sharedPreferencesCommitFalsePropagatesAsSaveFailure() {
        val store = SharedPreferencesCompletionAlertStore(FakePreferences(commitSucceeds = false), Key)
        assertFalse(store.save("timer-2"))
    }

    @Test
    fun sharedPreferencesCommitTruePersistsAcrossRecreation() {
        val preferences = FakePreferences(commitSucceeds = true)
        val store = SharedPreferencesCompletionAlertStore(preferences, Key)
        assertTrue(store.save("timer-2"))
        assertEquals("timer-2", store.load())
        val reloaded = SharedPreferencesCompletionAlertStore(preferences, Key)
        assertEquals("timer-2", reloaded.load())
    }

    private fun coordinator(
        store: CompletionAlertStore,
        events: RecordingEvents,
        onNotificationCancel: () -> Unit = {},
    ) = AlarmCoordinator(
        scheduler = NoopScheduler,
        alertStore = store,
        notificationCanceller = CompletionNotificationCanceller { onNotificationCancel() },
        completionAlertPolicy = CompletionAlertPolicy(::shouldStopCompletionAlert),
        eventSink = AlarmCoordinatorEventSink(events::record),
    )

    private object NoopScheduler : AlarmSchedulerPort {
        override fun update(timer: CanonicalTimer?) = Unit
        override fun cancel() = Unit
    }

    private class CommitResultStore(
        private var persisted: String?,
        private val commitSucceeds: Boolean,
    ) : CompletionAlertStore {
        override fun load(): String? = persisted

        override fun save(timerId: String?): Boolean {
            if (!commitSucceeds) return false
            persisted = timerId
            return true
        }
    }

    private class RecordingEvents {
        val changed = mutableListOf<String?>()
        val failures = mutableListOf<CompletionAlertPersistenceFailure>()

        fun record(event: AlarmCoordinatorEvent) {
            when (event) {
                is AlarmCoordinatorEvent.CompletionAlertChanged -> changed += event.timerId
                is AlarmCoordinatorEvent.CompletionAlertPersistenceFailed -> failures += event.failure
            }
        }
    }

    private class FakePreferences(
        private val commitSucceeds: Boolean,
        private val values: MutableMap<String, String?> = mutableMapOf(),
    ) : SharedPreferences {
        override fun edit(): SharedPreferences.Editor = FakeEditor(this, commitSucceeds)
        override fun getString(key: String, defValue: String?): String? =
            if (values.containsKey(key)) values[key] else defValue

        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        fun apply(pending: Map<String, String?>, removals: Set<String>) {
            removals.forEach { values.remove(it) }
            pending.forEach { (key, value) -> values[key] = value }
        }
    }

    private class FakeEditor(
        private val preferences: FakePreferences,
        private val commitSucceeds: Boolean,
        private val pending: MutableMap<String, String?> = mutableMapOf(),
        private val removals: MutableSet<String> = mutableSetOf(),
    ) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            removals.remove(key)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            pending.remove(key)
            return this
        }

        override fun commit(): Boolean {
            if (!commitSucceeds) return false
            preferences.apply(pending, removals)
            return true
        }

        override fun apply() = Unit
        override fun clear(): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
    }

    private companion object {
        const val Key = "completion_alert_timer_id"
    }
}
