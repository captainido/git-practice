package com.oliver.pet

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Vibration feel for Oliver:
 *  - purring: a ~25 Hz pulse train with an inhale/exhale envelope (like a real purr on your hand)
 *  - fur texture: tiny ticks while stroking
 *  - annoyance / hiss: sharp pulses
 */
class HapticsController(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val hasVibrator = vibrator?.hasVibrator() == true
    private val amp = vibrator?.hasAmplitudeControl() == true
    private val tickPrimitive = Build.VERSION.SDK_INT >= 31 &&
        vibrator?.areAllPrimitivesSupported(android.os.VibrationEffect.Composition.PRIMITIVE_LOW_TICK) == true

    var enabled = true
        set(v) { field = v; if (!v) stopAll() }

    private val handler = Handler(Looper.getMainLooper())
    private var purrLevel = 0f            // level currently playing
    private var oneShotUntil = 0L
    private var lastTick = 0L
    private val resumePurr = Runnable { val l = purrLevel; purrLevel = 0f; setPurr(l) }

    /** Set purr intensity 0..1. Re-issues the waveform only on meaningful change. */
    fun setPurr(level: Float) {
        if (!hasVibrator || !enabled) return
        val q = (level * 5).roundToInt() / 5f         // quantize to avoid restarting every frame
        if (q == purrLevel) return
        purrLevel = q
        if (SystemClock.uptimeMillis() < oneShotUntil) {       // resume right after the one-shot
            handler.removeCallbacks(resumePurr)
            handler.postAtTime(resumePurr, oneShotUntil + 10)
            return
        }
        if (q <= 0f) { vibrator!!.cancel(); return }
        vibrator!!.vibrate(purrEffect(q))
    }

    private fun purrEffect(level: Float): VibrationEffect {
        // One breath cycle: inhale 1.1 s @ 26 Hz, exhale 1.3 s @ 23 Hz; repeated forever.
        val timings = ArrayList<Long>()
        val amps = ArrayList<Int>()
        for ((dur, hz, gain) in listOf(Triple(1100, 26, 0.7f), Triple(1300, 23, 1.0f))) {
            val period = 1000 / hz
            val n = dur / period
            for (i in 0 until n) {
                val env = sin(PI * (i + 0.5) / n).toFloat()
                val a = (255 * level * gain * (0.35f + 0.65f * env)).roundToInt().coerceIn(1, 255)
                if (amp) {
                    timings += 14L; amps += a
                    timings += (period - 14).toLong(); amps += 0
                } else {
                    // On/off only devices: pulse width carries the intensity instead.
                    val on = (4 + 8 * level * env).roundToInt().toLong()
                    timings += on; amps += 255
                    timings += (period - on); amps += 0
                }
            }
        }
        return VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), 0)
    }

    /** Tiny tick while the finger passes over fur. [strength] 0..1. */
    fun furTick(strength: Float) {
        if (!hasVibrator || !enabled || purrLevel > 0f) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 28 || now < oneShotUntil) return
        lastTick = now
        val s = strength.coerceIn(0.15f, 1f)
        if (tickPrimitive && Build.VERSION.SDK_INT >= 31) {
            vibrator!!.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, s * 0.8f)
                    .compose()
            )
        } else {
            vibrator!!.vibrate(VibrationEffect.createOneShot(7, if (amp) (40 + 90 * s).roundToInt() else 255))
        }
    }

    fun annoyed() = oneShot(longArrayOf(0, 45, 70, 45), intArrayOf(0, 255, 0, 200))
    fun hiss() = oneShot(longArrayOf(0, 60, 30, 320), intArrayOf(0, 255, 0, 230))
    fun tailSwish() = oneShot(longArrayOf(0, 30, 40, 30, 40, 30), intArrayOf(0, 120, 0, 160, 0, 120))
    fun mrrp() = oneShot(longArrayOf(0, 25, 35, 40), intArrayOf(0, 90, 0, 150))
    fun leave() = oneShot(longArrayOf(0, 80, 60, 60, 60, 40), intArrayOf(0, 200, 0, 140, 0, 80))

    private fun oneShot(t: LongArray, a: IntArray) {
        if (!hasVibrator || !enabled) return
        val effect = if (amp) VibrationEffect.createWaveform(t, a, -1)
        else VibrationEffect.createWaveform(t, -1)
        vibrator!!.vibrate(effect)
        val total = t.sum()
        oneShotUntil = SystemClock.uptimeMillis() + total
        handler.removeCallbacks(resumePurr)
        if (purrLevel > 0f) handler.postDelayed(resumePurr, total + 10)
    }

    fun stopAll() {
        handler.removeCallbacks(resumePurr)
        purrLevel = 0f
        vibrator?.cancel()
    }
}
