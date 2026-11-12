package me.egigoka.pomodorough.timer

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.RequiresApi
import me.egigoka.pomodorough.crash.CrashReporter

/** Plays one heavy click when a timer phase ends. No-op on devices without a vibrator. */
internal class PhaseEndHaptics(context: Context) {
    private val appContext = context.applicationContext

    /**
     * Returns the effect handed to the vibrator, or null when no vibration was requested.
     * Haptic failures never block the alert.
     */
    fun play(): VibrationEffect? = try {
        val vibrator = resolveVibrator()
        if (vibrator == null || !vibrator.hasVibrator()) {
            null
        } else {
            val effect = phaseEndEffect()
            vibrator.vibrate(effect)
            effect
        }
    } catch (error: RuntimeException) {
        Log.w(Tag, "Phase-end haptic failed", error)
        CrashReporter.report(error)
        null
    }

    private fun resolveVibrator(): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            if (manager != null) return manager.defaultVibrator
        }
        return appContext.getSystemService(Vibrator::class.java)
    }

    companion object {
        private const val Tag = "PomodoroughHaptics"
        internal const val LegacyClickMs = 60L

        /** The predefined heavy click needs API 29; older devices get one short pulse. */
        internal fun phaseEndEffect(): VibrationEffect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) heavyClick() else legacyClick()

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun heavyClick(): VibrationEffect =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

        internal fun legacyClick(): VibrationEffect =
            VibrationEffect.createOneShot(LegacyClickMs, VibrationEffect.DEFAULT_AMPLITUDE)
    }
}
