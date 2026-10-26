package com.captainido.catalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are wiped on reboot, app update and clock changes; put ours back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(ctx)
    }
}
