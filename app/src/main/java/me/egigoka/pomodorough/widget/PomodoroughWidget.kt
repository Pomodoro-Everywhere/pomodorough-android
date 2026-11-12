package me.egigoka.pomodorough.widget

import android.content.Context
import android.util.Log
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CancellationException
import me.egigoka.pomodorough.MainActivity
import me.egigoka.pomodorough.PomodoroughApplication
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.timer.TimerSurfaceTiming
import me.egigoka.pomodorough.ui.Butter
import me.egigoka.pomodorough.ui.Cloud
import me.egigoka.pomodorough.ui.Ink
import me.egigoka.pomodorough.ui.Lavender
import me.egigoka.pomodorough.ui.Violet

/** Home-screen widget: phase, task, timer status, and a Start/Pause button. */
class PomodoroughWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = timerRepository(context)
        val snapshot = WidgetSnapshot.from(loadState(repository), System.currentTimeMillis())
        provideContent {
            GlanceTheme(colors = WidgetColors) {
                WidgetContent(snapshot)
            }
        }
    }

    private suspend fun loadState(repository: TimerRepository): AppState {
        try {
            repository.initialize()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(Tag, "Widget renders the last published state; initialize failed", error)
            CrashReporter.report(error)
        }
        return repository.state.value
    }

    companion object {
        private const val Tag = "PomodoroughWidget"

        internal fun timerRepository(context: Context): TimerRepository =
            (context.applicationContext as PomodoroughApplication).timerRepository
    }
}

/** Start, pause, or resume from the widget button; the core decides which command applies. */
class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        PomodoroughWidget.timerRepository(context).toggleTimer()
        PomodoroughWidget().update(context, glanceId)
    }
}

private val WidgetColors = ColorProviders(
    light = lightColorScheme(
        primary = Violet,
        onPrimary = Cloud,
        secondary = Butter,
        background = Cloud,
        onBackground = Ink,
        surface = Cloud,
        onSurface = Ink,
        onSurfaceVariant = Violet,
    ),
    dark = darkColorScheme(
        primary = Lavender,
        onPrimary = Ink,
        secondary = Butter,
        background = Ink,
        onBackground = Cloud,
        surface = Ink,
        onSurface = Cloud,
        onSurfaceVariant = Lavender,
    ),
)

@Composable
private fun WidgetContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    // Glance has no measuring layout: derive spacing from the host-reported widget size.
    val gutter = LocalSize.current.width * 0.05f
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .clickable(actionStartActivity<MainActivity>())
            .padding(gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = WidgetText.phaseLabel(context, snapshot.phase),
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            ),
            maxLines = 1,
        )
        Text(
            text = WidgetText.taskTitle(context, snapshot),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            maxLines = 1,
        )
        WidgetStatusRow(snapshot.timing)
    }
}

@Composable
private fun WidgetStatusRow(timing: TimerSurfaceTiming) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = WidgetText.status(context, timing),
            style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Button(
            text = WidgetText.toggleLabel(context, timing),
            onClick = actionRunCallback<ToggleTimerAction>(),
        )
    }
}
