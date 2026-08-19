package uk.co.maybeitssoftware.londondockcompass.presentation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import uk.co.maybeitssoftware.londondockcompass.domain.ProximityBand

/**
 * The channel that actually works on a bike.
 *
 * You cannot read a watch at fifteen miles an hour in traffic, so every important event has a
 * pattern you can tell apart through a sleeve without looking: two taps for "nearly there", a long
 * settle for "you have arrived", and an insistent triple for "your destination just filled up".
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun forBand(band: ProximityBand) = when (band) {
        ProximityBand.APPROACHING -> play(APPROACHING)
        ProximityBand.ARRIVED -> play(ARRIVED)
    }

    /** The destination is filling up and diverting is about to get expensive. */
    fun divert() = play(DIVERT)

    /** Confirms a deliberate tap, so the mode chip is usable without watching it. */
    fun confirm() = play(CONFIRM)

    private fun play(pattern: LongArray) {
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        device.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private companion object {
        // Waveforms are wait/vibrate pairs, in milliseconds.
        val APPROACHING = longArrayOf(0, 60, 90, 60)
        val ARRIVED = longArrayOf(0, 60, 80, 60, 80, 240)
        val DIVERT = longArrayOf(0, 120, 70, 120, 70, 120)
        val CONFIRM = longArrayOf(0, 25)
    }
}
