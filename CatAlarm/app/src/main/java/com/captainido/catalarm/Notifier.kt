package com.captainido.catalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** The quiet notifications around an alarm: upcoming, snoozed and missed. */
object Notifier {
    const val CHANNEL_RINGING = "ringing"
    private const val CHANNEL_UPCOMING = "upcoming"
    private const val CHANNEL_SNOOZED = "snoozed"
    private const val CHANNEL_MISSED = "missed"

    fun upcomingId(id: Int) = 1000 + id
    private fun snoozedId(id: Int) = 2000 + id
    private fun missedId(id: Int) = 3000 + id

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_RINGING, "התראות מצלצלות", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(CHANNEL_UPCOMING, "התראות קרובות", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_SNOOZED, "נודניק", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_MISSED, "התראות שהוחמצו", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun showUpcoming(ctx: Context, alarm: Alarm, at: Long) {
        ensureChannels(ctx)
        val n = base(ctx, CHANNEL_UPCOMING)
            .setContentTitle("התראה קרובה")
            .setContentText(line(alarm, at))
            .setShowWhen(false)
            .addAction(action(ctx, "ביטול ההתראה", AlarmScheduler.pending(ctx, alarm.id, AlarmScheduler.ACTION_SKIP_NEXT)))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(upcomingId(alarm.id), n)
    }

    fun showSnoozed(ctx: Context, alarm: Alarm) {
        ensureChannels(ctx)
        val n = base(ctx, CHANNEL_SNOOZED)
            .setContentTitle("נודניק עד ${Format.time(alarm.snoozedUntil)}")
            .setContentText(alarm.label.ifBlank { "התראה" })
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(action(ctx, "ביטול", AlarmScheduler.pending(ctx, alarm.id, AlarmScheduler.ACTION_CANCEL_SNOOZE)))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(snoozedId(alarm.id), n)
    }

    fun showMissed(ctx: Context, alarm: Alarm, rangAt: Long) {
        ensureChannels(ctx)
        val n = base(ctx, CHANNEL_MISSED)
            .setContentTitle("התראה שהוחמצה")
            .setContentText(line(alarm, rangAt))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(missedId(alarm.id), n)
    }

    fun clear(ctx: Context, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(upcomingId(id))
        nm.cancel(snoozedId(id))
    }

    private fun line(alarm: Alarm, at: Long) =
        listOf(Format.relativeDay(at) + " " + Format.time(at), alarm.label).filter { it.isNotBlank() }.joinToString(" · ")

    private fun base(ctx: Context, channel: String) = Notification.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_alarm)
        .setColor(ctx.getColor(R.color.gold))
        .setCategory(Notification.CATEGORY_REMINDER)
        .setContentIntent(
            PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )

    private fun action(ctx: Context, title: String, pi: PendingIntent) =
        Notification.Action.Builder(null, title, pi).build()
}
