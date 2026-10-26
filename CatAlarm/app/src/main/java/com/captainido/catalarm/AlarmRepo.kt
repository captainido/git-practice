package com.captainido.catalarm

import android.content.Context
import org.json.JSONArray
import java.util.Calendar

/** All alarms and app settings, kept in SharedPreferences. */
object AlarmRepo {
    private const val PREFS = "cat_alarm"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): MutableList<Alarm> {
        val raw = prefs(ctx).getString("alarms", null) ?: return migrateFromSingleAlarm(ctx)
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { Alarm.fromJson(arr.getJSONObject(it)) }
            .sortedWith(compareBy({ it.hour }, { it.minute }))
            .toMutableList()
    }

    fun get(ctx: Context, id: Int) = all(ctx).firstOrNull { it.id == id }

    fun saveAll(ctx: Context, alarms: List<Alarm>) {
        val arr = JSONArray()
        alarms.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString("alarms", arr.toString()).apply()
    }

    fun save(ctx: Context, alarm: Alarm) {
        val list = all(ctx)
        val i = list.indexOfFirst { it.id == alarm.id }
        if (i >= 0) list[i] = alarm else list += alarm
        saveAll(ctx, list)
    }

    fun delete(ctx: Context, id: Int) = saveAll(ctx, all(ctx).filterNot { it.id == id })

    fun newAlarm(ctx: Context, hour: Int, minute: Int): Alarm {
        val id = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1
        return Alarm(id = id, hour = hour, minute = minute)
    }

    /** Earlier versions kept a single alarm in loose preferences; turn it into the first list entry. */
    private fun migrateFromSingleAlarm(ctx: Context): MutableList<Alarm> {
        val p = prefs(ctx)
        val at = p.getLong("alarm_at", 0L)
        if (at == 0L) return mutableListOf()
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val alarm = Alarm(
            id = 1,
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            days = if (p.getBoolean("repeat", false)) Alarm.EVERY_DAY else 0,
            enabled = p.getBoolean("enabled", false),
        )
        saveAll(ctx, listOf(alarm))
        return mutableListOf(alarm)
    }

    // ---- Settings (defaults follow the Android Clock app) ----

    fun snoozeMinutes(ctx: Context) = prefs(ctx).getInt("snooze_minutes", 10)
    fun setSnoozeMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("snooze_minutes", v).apply()

    fun silenceAfterMinutes(ctx: Context) = prefs(ctx).getInt("silence_minutes", 10)
    fun setSilenceAfterMinutes(ctx: Context, v: Int) = prefs(ctx).edit().putInt("silence_minutes", v).apply()

    fun gradualVolume(ctx: Context) = prefs(ctx).getBoolean("gradual_volume", true)
    fun setGradualVolume(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("gradual_volume", v).apply()

    const val VOLUME_KEYS_SNOOZE = 0
    const val VOLUME_KEYS_STOP = 1
    const val VOLUME_KEYS_VOLUME = 2
    fun volumeKeys(ctx: Context) = prefs(ctx).getInt("volume_keys", VOLUME_KEYS_SNOOZE)
    fun setVolumeKeys(ctx: Context, v: Int) = prefs(ctx).edit().putInt("volume_keys", v).apply()

    fun callNumber(ctx: Context) = prefs(ctx).getString("call_number", "") ?: ""
    fun callName(ctx: Context) = prefs(ctx).getString("call_name", Names.DEFAULT_CALLEE) ?: Names.DEFAULT_CALLEE
    fun setCallContact(ctx: Context, name: String, number: String) =
        prefs(ctx).edit().putString("call_name", name).putString("call_number", number).apply()
}
