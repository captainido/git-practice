package com.yungerman.catcam

import android.content.Context
import androidx.core.content.edit

/** User settings, persisted in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("catcam", Context.MODE_PRIVATE)

    var phone: String
        get() = sp.getString("phone", "")!!
        set(v) = sp.edit { putString("phone", v.trim()) }

    var idInstance: String
        get() = sp.getString("idInstance", "")!!
        set(v) = sp.edit { putString("idInstance", v.trim()) }

    var apiToken: String
        get() = sp.getString("apiToken", "")!!
        set(v) = sp.edit { putString("apiToken", v.trim()) }

    var apiUrl: String
        get() = sp.getString("apiUrl", DEFAULT_API_URL)!!
        set(v) = sp.edit { putString("apiUrl", v.trim().trimEnd('/')) }

    var mediaUrl: String
        get() = sp.getString("mediaUrl", DEFAULT_MEDIA_URL)!!
        set(v) = sp.edit { putString("mediaUrl", v.trim().trimEnd('/')) }

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

    /** Returns a Hebrew error message, or null when everything needed for sending is set. */
    fun validate(): String? = when {
        GreenApi.chatId(phone) == null -> "מספר הטלפון של לילך לא תקין"
        idInstance.isBlank() -> "חסר idInstance"
        apiToken.isBlank() -> "חסר apiTokenInstance"
        !apiUrl.startsWith("https://") -> "apiUrl צריך להתחיל ב-https://"
        !mediaUrl.startsWith("https://") -> "mediaUrl צריך להתחיל ב-https://"
        else -> null
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.green-api.com"
        const val DEFAULT_MEDIA_URL = "https://media.green-api.com"
    }
}
