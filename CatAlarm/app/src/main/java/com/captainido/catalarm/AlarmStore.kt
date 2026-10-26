package com.captainido.catalarm

import android.content.Context
import java.util.Calendar

/** The single alarm's settings, kept in SharedPreferences. */
object AlarmStore {
    private const val PREFS = "cat_alarm"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Exact date and time of the alarm (for a daily alarm: the first day). Defaults to tomorrow 07:00. */
    fun alarmAt(ctx: Context): Long {
        val stored = prefs(ctx).getLong("alarm_at", 0L)
        if (stored != 0L) return stored
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun setAlarmAt(ctx: Context, time: Long) = prefs(ctx).edit().putLong("alarm_at", time).apply()

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("enabled", enabled).apply()

    fun isRepeating(ctx: Context) = prefs(ctx).getBoolean("repeat", false)
    fun setRepeating(ctx: Context, repeat: Boolean) = prefs(ctx).edit().putBoolean("repeat", repeat).apply()

    fun isCallEnabled(ctx: Context) = prefs(ctx).getBoolean("call_enabled", false)
    fun setCallEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("call_enabled", enabled).apply()

    fun callNumber(ctx: Context) = prefs(ctx).getString("call_number", "") ?: ""
    fun setCallNumber(ctx: Context, number: String) = prefs(ctx).edit().putString("call_number", number).apply()

    fun callName(ctx: Context) = prefs(ctx).getString("call_name", Names.DEFAULT_CALLEE) ?: Names.DEFAULT_CALLEE
    fun setCallName(ctx: Context, name: String) = prefs(ctx).edit().putString("call_name", name).apply()

    fun callDelayMinutes(ctx: Context) = prefs(ctx).getInt("call_delay", 0)
    fun setCallDelayMinutes(ctx: Context, minutes: Int) = prefs(ctx).edit().putInt("call_delay", minutes).apply()
}
