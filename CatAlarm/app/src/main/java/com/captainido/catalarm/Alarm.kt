package com.captainido.catalarm

import org.json.JSONObject

/**
 * One alarm. [days] is a bitmask of repeat days (bit 0 = Sunday … bit 6 = Saturday); 0 means
 * one-time. A one-time alarm rings on [date] (local midnight) when set, else at the next time
 * the clock shows hour:minute.
 */
data class Alarm(
    val id: Int,
    var hour: Int = 7,
    var minute: Int = 0,
    var days: Int = 0,
    var date: Long = 0L,
    var label: String = "",
    var enabled: Boolean = true,
    var vibrate: Boolean = true,
    var call: Boolean = true,
    /** Occurrences before this time are skipped (dismissed early from the upcoming notification). */
    var skipUntil: Long = 0L,
    /** When snoozed, the time the alarm rings again; 0 otherwise. */
    var snoozedUntil: Long = 0L,
) {
    val repeats get() = days != 0

    fun repeatsOn(calendarDayOfWeek: Int) = days and (1 shl (calendarDayOfWeek - 1)) != 0

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("hour", hour).put("minute", minute).put("days", days).put("date", date)
        .put("label", label).put("enabled", enabled).put("vibrate", vibrate).put("call", call)
        .put("skipUntil", skipUntil).put("snoozedUntil", snoozedUntil)

    companion object {
        const val EVERY_DAY = 0b1111111
        const val WORK_WEEK = 0b0011111 // Sunday to Thursday

        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute", 0),
            days = o.optInt("days", 0),
            date = o.optLong("date", 0L),
            label = o.optString("label", ""),
            enabled = o.optBoolean("enabled", true),
            vibrate = o.optBoolean("vibrate", true),
            call = o.optBoolean("call", true),
            skipUntil = o.optLong("skipUntil", 0L),
            snoozedUntil = o.optLong("snoozedUntil", 0L),
        )
    }
}
