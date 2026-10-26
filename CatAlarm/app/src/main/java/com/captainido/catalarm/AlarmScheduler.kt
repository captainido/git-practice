package com.captainido.catalarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    const val EXTRA_ID = "alarm_id"
    const val ACTION_RING = "com.captainido.catalarm.RING"
    const val ACTION_UPCOMING = "com.captainido.catalarm.UPCOMING"
    const val ACTION_SKIP_NEXT = "com.captainido.catalarm.SKIP_NEXT"
    const val ACTION_CANCEL_SNOOZE = "com.captainido.catalarm.CANCEL_SNOOZE"

    /** Like the system clock, an "upcoming alarm" notification appears two hours ahead. */
    const val UPCOMING_LEAD_MS = 2 * 3600_000L

    /** The next time the clock shows hour:minute after [now]: later today if still ahead, otherwise tomorrow. */
    fun nextOccurrence(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** When [alarm] rings next, or null if it will not ring. */
    fun nextTrigger(alarm: Alarm, now: Long = System.currentTimeMillis()): Long? {
        if (alarm.snoozedUntil > now) return alarm.snoozedUntil
        if (!alarm.enabled) return null
        val from = maxOf(now, alarm.skipUntil)
        if (alarm.repeats) {
            val cal = Calendar.getInstance().apply {
                timeInMillis = from
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            repeat(8) {
                if (cal.timeInMillis > from && alarm.repeatsOn(cal.get(Calendar.DAY_OF_WEEK))) return cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return null
        }
        if (alarm.date != 0L) {
            val t = atDate(alarm.date, alarm.hour, alarm.minute)
            return if (t > from) t else null
        }
        return nextOccurrence(alarm.hour, alarm.minute, from)
    }

    fun atDate(date: Long, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        timeInMillis = date
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The soonest ringing alarm and when it rings. */
    fun nextOverall(ctx: Context): Pair<Alarm, Long>? =
        AlarmRepo.all(ctx).mapNotNull { a -> nextTrigger(a)?.let { a to it } }.minByOrNull { it.second }

    /** Re-arms every alarm from the stored list. Call after any change. */
    fun scheduleAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        for (alarm in AlarmRepo.all(ctx)) {
            am.cancel(pending(ctx, alarm.id, ACTION_RING))
            am.cancel(pending(ctx, alarm.id, ACTION_UPCOMING))
            val t = nextTrigger(alarm, now)
            if (t == null) {
                nm.cancel(Notifier.upcomingId(alarm.id))
                continue
            }
            setExact(ctx, am, t, pending(ctx, alarm.id, ACTION_RING))
            if (alarm.snoozedUntil == 0L) {
                val upcomingAt = t - UPCOMING_LEAD_MS
                if (upcomingAt > now) {
                    nm.cancel(Notifier.upcomingId(alarm.id))
                    am.setAndAllowWhileIdle(AlarmManager.RTC, upcomingAt, pending(ctx, alarm.id, ACTION_UPCOMING))
                } else if (t - now > 60_000) {
                    Notifier.showUpcoming(ctx, alarm, t)
                }
            }
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, id, ACTION_RING))
        am.cancel(pending(ctx, id, ACTION_UPCOMING))
        Notifier.clear(ctx, id)
    }

    private fun setExact(ctx: Context, am: AlarmManager, time: Long, op: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, op)
            return
        }
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), op)
    }

    fun pending(ctx: Context, id: Int, action: String): PendingIntent {
        val code = id * 10 + when (action) {
            ACTION_RING -> 0
            ACTION_UPCOMING -> 1
            ACTION_SKIP_NEXT -> 2
            else -> 3
        }
        return PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
