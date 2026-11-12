package me.egigoka.pomodorough.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.egigoka.pomodorough.PomodoroughApplication
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerRepositoryContract
import me.egigoka.pomodorough.timer.TimerSurfaceTiming

/** Quick Settings tile: active while a timer runs; tapping starts, pauses, or resumes. */
class PomodoroughTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            val repository = timerRepository()
            initializeQuietly(repository)
            repository.state.collectLatest { state -> renderWhileRunning(state) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val repository = timerRepository()
            toggleTimerFromTile(repository)
            render(repository.state.value)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Running timers count down once per second; other states render once per publication. */
    private suspend fun renderWhileRunning(state: AppState) {
        while (true) {
            val timing = render(state)
            if (timing !is TimerSurfaceTiming.Running) return
            delay(TickMs)
        }
    }

    private fun render(state: AppState): TimerSurfaceTiming {
        val nowMs = System.currentTimeMillis()
        val timing = TimerSurfaceTiming.from(state.timer, nowMs)
        val tile = qsTile ?: return timing
        val snapshot = TileSnapshot.from(timing, nowMs)
        tile.state = when (snapshot.activation) {
            TileActivation.Active -> Tile.STATE_ACTIVE
            TileActivation.Inactive -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitleText(snapshot.subtitle)
        }
        tile.updateTile()
        return timing
    }

    private fun subtitleText(subtitle: TileSubtitle): String = when (subtitle) {
        is TileSubtitle.TimeLeft ->
            getString(R.string.tile_subtitle_left, TimerSurfaceTiming.formatClock(subtitle.remainingMs))
        TileSubtitle.Paused -> getString(R.string.paused)
        TileSubtitle.Ready -> getString(R.string.widget_status_ready)
    }

    private suspend fun initializeQuietly(repository: TimerRepository) {
        try {
            repository.initialize()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(Tag, "Tile renders the last published state; initialize failed", error)
            CrashReporter.report(error)
        }
    }

    private fun timerRepository(): TimerRepository =
        (applicationContext as PomodoroughApplication).timerRepository

    private companion object {
        const val TickMs = 1_000L
    }
}

private const val Tag = "PomodoroughTile"

// UX round 2: the tile starts the timer through the repository entry point,
// which first completes a task delete that still waits for Undo.
internal suspend fun toggleTimerFromTile(repository: TimerRepositoryContract) {
    try {
        repository.toggleTimer()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(Tag, "Tile toggle failed", error)
        CrashReporter.report(error)
    }
}
