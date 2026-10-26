package com.captainido.catalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    const val EXTRA_SNOOZE = "snooze"
    const val SNOOZE_MINUTES = 5
    private const val REQ_ALARM = 1
    private const val REQ_SNOOZE = 2

    /** Next wall-clock time for hour:minute, at least a minute from now. */
    fun nextTrigger(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis - now < 60_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun schedule(ctx: Context) {
        if (!AlarmStore.isEnabled(ctx)) {
            cancel(ctx)
            return
        }
        setAt(ctx, nextTrigger(AlarmStore.hour(ctx), AlarmStore.minute(ctx)), snooze = false)
    }

    fun snooze(ctx: Context) =
        setAt(ctx, System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L, snooze = true)

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(ctx, false))
        am.cancel(alarmIntent(ctx, true))
    }

    private fun setAt(ctx: Context, time: Long, snooze: Boolean) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val op = alarmIntent(ctx, snooze)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, op)
            return
        }
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), op)
    }

    // Snooze gets its own PendingIntent so it never replaces the pending daily alarm.
    private fun alarmIntent(ctx: Context, snooze: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            if (snooze) REQ_SNOOZE else REQ_ALARM,
            Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_SNOOZE, snooze),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
