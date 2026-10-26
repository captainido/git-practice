package com.captainido.catalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val alarm = AlarmRepo.get(ctx, id) ?: return
        val now = System.currentTimeMillis()
        when (intent.action) {
            AlarmScheduler.ACTION_RING -> {
                val fromSnooze = alarm.snoozedUntil != 0L
                alarm.snoozedUntil = 0L
                // A one-time alarm switches itself off once it has rung, like the system clock.
                if (alarm.repeats) alarm.skipUntil = now + 60_000 else alarm.enabled = false
                AlarmRepo.save(ctx, alarm)
                AlarmScheduler.scheduleAll(ctx)
                Notifier.clear(ctx, id)
                AlarmService.start(ctx, id, withCall = alarm.call && !fromSnooze)
            }
            AlarmScheduler.ACTION_UPCOMING -> {
                AlarmScheduler.nextTrigger(alarm, now)?.let { Notifier.showUpcoming(ctx, alarm, it) }
            }
            AlarmScheduler.ACTION_SKIP_NEXT -> {
                val next = AlarmScheduler.nextTrigger(alarm, now)
                if (alarm.repeats && next != null) alarm.skipUntil = next + 1000 else alarm.enabled = false
                alarm.snoozedUntil = 0L
                AlarmRepo.save(ctx, alarm)
                Notifier.clear(ctx, id)
                AlarmScheduler.scheduleAll(ctx)
            }
            AlarmScheduler.ACTION_CANCEL_SNOOZE -> {
                alarm.snoozedUntil = 0L
                AlarmRepo.save(ctx, alarm)
                Notifier.clear(ctx, id)
                AlarmScheduler.scheduleAll(ctx)
            }
        }
    }
}
