package com.captainido.catalarm

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Hebrew wording for times, days and durations, shared by every screen. */
object Format {
    private val HEBREW: Locale = Locale.forLanguageTag("he")
    private val DAY_LETTERS = listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש׳")
    private const val WEEKEND = 0b1100000 // Friday and Saturday

    fun time(hour: Int, minute: Int) = String.format(Locale.US, "%02d:%02d", hour, minute)

    fun time(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(millis)

    fun dayLetter(index: Int) = DAY_LETTERS[index]

    fun dayName(index: Int): String = Calendar.getInstance().run {
        set(Calendar.DAY_OF_WEEK, index + 1)
        SimpleDateFormat("EEEE", HEBREW).format(timeInMillis)
    }

    fun days(mask: Int): String = when (mask) {
        Alarm.EVERY_DAY -> "כל יום"
        Alarm.WORK_WEEK -> "ימים א׳–ה׳"
        WEEKEND -> "סוף שבוע"
        else -> (0..6).filter { mask and (1 shl it) != 0 }.joinToString(", ") { DAY_LETTERS[it] }
    }

    fun longDate(millis: Long): String = SimpleDateFormat("EEEE, d בMMMM", HEBREW).format(millis)

    /** "היום", "מחר" or a short date. */
    fun relativeDay(millis: Long): String {
        val days = daysFromToday(millis)
        return when (days) {
            0 -> "היום"
            1 -> "מחר"
            else -> SimpleDateFormat("EEEE, d בMMM", HEBREW).format(millis)
        }
    }

    private fun daysFromToday(millis: Long): Int {
        val a = Calendar.getInstance().apply { startOfDay() }
        val b = Calendar.getInstance().apply { timeInMillis = millis; startOfDay() }
        return ((b.timeInMillis - a.timeInMillis + 12 * 3600_000L) / (24 * 3600_000L)).toInt()
    }

    private fun Calendar.startOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** "7 שעות ו-12 דקות", rounded up to the minute like the system clock. */
    fun duration(ms: Long): String {
        val totalMin = (ms + 59_999) / 60_000
        if (totalMin <= 0) return "פחות מדקה"
        val days = totalMin / (60 * 24)
        val hours = (totalMin / 60) % 24
        val mins = totalMin % 60
        val parts = buildList {
            if (days > 0) add(if (days == 1L) "יום" else "$days ימים")
            if (hours > 0) add(if (hours == 1L) "שעה" else "$hours שעות")
            if (mins > 0 && days == 0L) add(if (mins == 1L) "דקה" else "$mins דקות")
        }
        return parts.joinToString(" ו-")
    }

    fun minutes(n: Int) = if (n == 1) "דקה" else "$n דקות"

    /** One line under the time on an alarm card. */
    fun summary(alarm: Alarm, now: Long = System.currentTimeMillis()): String {
        if (alarm.snoozedUntil > now) return "נודניק עד ${time(alarm.snoozedUntil)}"
        if (alarm.repeats) return days(alarm.days)
        val next = AlarmScheduler.nextTrigger(alarm, now)
        return if (alarm.enabled && next != null) "חד-פעמי · ${relativeDay(next)}" else "חד-פעמי"
    }
}
