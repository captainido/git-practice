package com.captainido.catalarm

import android.content.Context

/** The single alarm's settings, kept in SharedPreferences. */
object AlarmStore {
    private const val PREFS = "cat_alarm"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hour(ctx: Context) = prefs(ctx).getInt("hour", 7)
    fun minute(ctx: Context) = prefs(ctx).getInt("minute", 0)
    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun isRepeating(ctx: Context) = prefs(ctx).getBoolean("repeat", true)

    fun setTime(ctx: Context, hour: Int, minute: Int) =
        prefs(ctx).edit().putInt("hour", hour).putInt("minute", minute).apply()

    fun setEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean("enabled", enabled).apply()

    fun setRepeating(ctx: Context, repeat: Boolean) =
        prefs(ctx).edit().putBoolean("repeat", repeat).apply()
}
