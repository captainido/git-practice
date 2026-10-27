package com.yungerman.catcam

import android.content.Context
import androidx.core.content.edit

/** User settings, persisted in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("catcam", Context.MODE_PRIVATE)

    var cooldownMinutes: Int
        get() = sp.getInt("cooldownMinutes", 5)
        set(v) = sp.edit { putInt("cooldownMinutes", v.coerceIn(0, 1440)) }

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
}
