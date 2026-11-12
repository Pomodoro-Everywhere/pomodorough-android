package me.egigoka.pomodorough.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.AppState

/** Re-renders placed widgets when the published timer, phase, or task changes. */
internal class WidgetRefresh(
    private val context: Context,
    private val update: suspend () -> Unit = { PomodoroughWidget().updateAll(context) },
    private val hasPlacedWidgets: suspend () -> Boolean = {
        GlanceAppWidgetManager(context).getGlanceIds(PomodoroughWidget::class.java).isNotEmpty()
    },
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, state: Flow<AppState>): Job = scope.launch {
        state
            .map { WidgetSnapshot.from(it, System.currentTimeMillis()) }
            .distinctUntilChanged()
            .debounce(DebounceMs)
            .collect { refresh() }
    }

    private suspend fun refresh() {
        try {
            if (hasPlacedWidgets()) update()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(Tag, "Widget refresh failed", error)
            CrashReporter.report(error)
        }
    }

    private companion object {
        const val Tag = "PomodoroughWidget"
        const val DebounceMs = 300L
    }
}
