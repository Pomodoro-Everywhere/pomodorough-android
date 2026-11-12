package me.egigoka.pomodorough.timer

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhaseEndHapticsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val vibrator: Vibrator = context.getSystemService(Vibrator::class.java)

    @Test
    fun playVibratesTheHeavyClick() {
        shadowOf(vibrator).setHasVibrator(true)

        val played = PhaseEndHaptics(context).play()

        // Robolectric shadow state is shared, so the VibratorManager default vibrator
        // that production resolves on API 31+ reports through this shadow.
        assertEquals(heavyClick(), played)
        assertNotEquals(PhaseEndHaptics.legacyClick(), played)
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun playIsNoOpWithoutVibrator() {
        shadowOf(vibrator).setHasVibrator(false)

        assertNull(PhaseEndHaptics(context).play())

        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun legacyClickIsOneShortPulse() {
        val legacy = PhaseEndHaptics.legacyClick()

        assertEquals(
            VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE),
            legacy,
        )
        assertNotEquals(heavyClick(), legacy)
        assertEquals(60L, PhaseEndHaptics.LegacyClickMs)
    }

    private fun heavyClick(): VibrationEffect {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw AssertionError("Robolectric SDK ${Build.VERSION.SDK_INT} predates predefined effects")
        }
        return VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    }
}
