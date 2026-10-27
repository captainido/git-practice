package com.yungerman.catcam

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User settings and the daily detection counter, persisted in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("catcam", Context.MODE_PRIVATE)

    var cooldownMinutes: Int
        get() = sp.getInt("cooldownMinutes", 5).coerceIn(0, 30)
        set(v) = sp.edit { putInt("cooldownMinutes", v.coerceIn(0, 30)) }

    /** Minimum ML Kit confidence (percent) for the "Cat" label. */
    var minConfidence: Int
        get() = sp.getInt("minConfidence", 70)
        set(v) = sp.edit { putInt("minConfidence", v.coerceIn(50, 95)) }

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
}
