package me.egigoka.pomodorough.tile

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import me.egigoka.pomodorough.data.TimerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-33: the tile names the phase, a paused tile shows the time left, and the
 * tile icon is the in-app Timer glyph rather than a square that reads as "stop".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class TileTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun runningTileNamesThePhaseAndTheTimeLeft() {
        assertEquals("Short break", TileText.label(context, TileLabel.Phase(TimerPhase.ShortBreak)))
        assertEquals("04:55 left", TileText.subtitle(context, TileSubtitle.TimeLeft(295_000)))
    }

    @Test
    fun pausedTileShowsTheTimeLeft() {
        assertEquals("Focus", TileText.label(context, TileLabel.Phase(TimerPhase.Focus)))
        assertEquals("Paused · 04:55 left", TileText.subtitle(context, TileSubtitle.Paused(295_000)))
    }

    @Test
    fun idleTileShowsTheAppNameAndReady() {
        assertEquals("Pomodorough", TileText.label(context, TileLabel.AppName))
        assertEquals("Ready", TileText.subtitle(context, TileSubtitle.Ready))
    }

    @Test
    fun tileIconIsTheMaterialTimerGlyph() {
        val root = sequenceOf(File("src/main/res"), File("app/src/main/res")).first(File::isDirectory)
        val icon = File(root, "drawable/ic_tile_timer.xml").readText()

        assertTrue(icon, icon.contains("android:viewportWidth=\"24\""))
        assertTrue(icon, icon.contains("android:pathData=\"$MaterialOutlinedTimerPath\""))
    }

    private companion object {
        // Icons.Outlined.Timer, the Timer tab icon, as SVG path data.
        const val MaterialOutlinedTimerPath = "M15,1H9v2h6V1zM11,14h2V8h-2V14z" +
            "M19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42" +
            "C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9" +
            "C21,10.88 20.26,8.93 19.03,7.39zM12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7S15.87,20 12,20z"
    }
}
