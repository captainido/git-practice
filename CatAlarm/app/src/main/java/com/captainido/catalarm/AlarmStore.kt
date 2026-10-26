package com.captainido.catalarm

import android.content.Context

/** The single alarm's settings, kept in SharedPreferences. */
object AlarmStore {
    private const val PREFS = "cat_alarm"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Exact date and time of the alarm (for a daily alarm: the first day). Defaults to the next 07:00. */
    fun alarmAt(ctx: Context): Long {
        val stored = prefs(ctx).getLong("alarm_at", 0L)
        return if (stored != 0L) stored else AlarmScheduler.nextOccurrence(7, 0)
    }

    fun setAlarmAt(ctx: Context, time: Long) = prefs(ctx).edit().putLong("alarm_at", time).apply()

    /** True when the user picked a specific date, so changing the time keeps that date. */
    fun isDateLocked(ctx: Context) = prefs(ctx).getBoolean("date_locked", false)
    fun setDateLocked(ctx: Context, locked: Boolean) = prefs(ctx).edit().putBoolean("date_locked", locked).apply()

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("enabled", enabled).apply()

    fun isRepeating(ctx: Context) = prefs(ctx).getBoolean("repeat", false)
    fun setRepeating(ctx: Context, repeat: Boolean) = prefs(ctx).edit().putBoolean("repeat", repeat).apply()

    /** Phoning the contact is on unless turned off: it happens together with the alarm. */
    fun isCallEnabled(ctx: Context) = prefs(ctx).getBoolean("call_enabled_v3", true)
    fun setCallEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("call_enabled_v3", enabled).apply()

    fun callNumber(ctx: Context) = prefs(ctx).getString("call_number", "") ?: ""
    fun callName(ctx: Context) = prefs(ctx).getString("call_name", Names.DEFAULT_CALLEE) ?: Names.DEFAULT_CALLEE

    fun setCallContact(ctx: Context, name: String, number: String) =
        prefs(ctx).edit().putString("call_name", name).putString("call_number", number).apply()
}
