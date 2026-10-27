package com.yungerman.catcam

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User settings and the daily detection counter, persisted in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("catcam", Context.MODE_PRIVATE)

    /** While the cat stays in view, take a photo every this many seconds. */
    var captureIntervalSeconds: Int
        get() = snap(sp.getInt("captureIntervalSec", 10), 5, 120)
        set(v) = sp.edit { putInt("captureIntervalSec", snap(v, 5, 120)) }

    var useFrontCamera: Boolean
        get() = sp.getBoolean("useFrontCamera", false)
        set(v) = sp.edit { putBoolean("useFrontCamera", v) }

    var soundEnabled: Boolean
        get() = sp.getBoolean("soundEnabled", true)
        set(v) = sp.edit { putBoolean("soundEnabled", v) }

    fun detectionsToday(): Int =
        if (sp.getString("countDay", "") == today()) sp.getInt("count", 0) else 0

    fun incrementDetectionsToday(): Int {
        val count = detectionsToday() + 1
        sp.edit {
            putString("countDay", today())
            putInt("count", count)
        }
        return count
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Clamps to range and rounds to the sliders' step of 5 (a Slider crashes on off-step values). */
    private fun snap(value: Int, min: Int, max: Int) = (Math.round(value / 5f) * 5).coerceIn(min, max)
}
