package com.captainido.catalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)
        if (AlarmStore.isEnabled(ctx) && AlarmStore.isRepeating(ctx)) {
            AlarmScheduler.schedule(ctx)
        } else if (!snooze) {
            AlarmStore.setEnabled(ctx, false)
        }
        AlarmService.start(ctx)
    }
}
