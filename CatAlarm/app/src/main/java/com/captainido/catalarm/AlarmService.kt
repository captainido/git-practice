package com.captainido.catalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/** Plays the cat recording in a loop (with a different "mood" each round) until dismissed. */
class AlarmService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_DISMISS = "dismiss"
        private const val ACTION_SNOOZE = "snooze"
        private const val CHANNEL_ID = "cat_alarm_v1"
        private const val NOTIFICATION_ID = 42
        private const val MAX_RING_MS = 10 * 60_000L

        // Normal cat, chipmunk cat, angry big cat, squeaky kitten, slow-motion cat...
        private val MOODS = floatArrayOf(1.0f, 1.45f, 0.75f, 1.9f, 1.0f, 0.6f, 1.25f, 2.2f)

        @Volatile
        var isRinging = false
            private set

        /** Called on the main thread when the alarm stops for any reason. */
        var onStopped: (() -> Unit)? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AlarmService::class.java).setAction(ACTION_START))
        }

        fun dismiss(ctx: Context) {
            if (isRinging) ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_DISMISS))
        }

        fun snooze(ctx: Context) {
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_SNOOZE))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var round = 0
    private var volume = 0.35f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> stopAlarm()
            ACTION_SNOOZE -> {
                AlarmScheduler.snooze(this)
                stopAlarm()
            }
            else -> startRinging()
        }
        return START_NOT_STICKY
    }

    private fun startRinging() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (isRinging) return
        isRinging = true
        round = 0
        volume = 0.35f
        startPlayer()
        startVibration()
        handler.postDelayed(volumeRamp, 1000)
        handler.postDelayed({ stopAlarm() }, MAX_RING_MS)
    }

    private fun startPlayer() {
        val afd = resources.openRawResourceFd(R.raw.cat_alarm)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
            setVolume(volume, volume)
            setOnCompletionListener { handler.postDelayed(nextRound, 500) }
            prepare()
            start()
        }
    }

    private val nextRound = Runnable {
        val p = player ?: return@Runnable
        round++
        val mood = MOODS[round % MOODS.size]
        try {
            p.seekTo(0)
            p.playbackParams = PlaybackParams().setSpeed(mood).setPitch(mood)
            if (!p.isPlaying) p.start()
        } catch (e: Exception) {
            // Some devices refuse odd playback params; fall back to the plain recording.
            runCatching { p.seekTo(0); p.start() }
        }
    }

    private val volumeRamp = object : Runnable {
        override fun run() {
            volume = (volume + 0.03f).coerceAtMost(1f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        // "Paw, paw... PAAAW" pattern.
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 150, 120, 150, 500, 700, 900), 0)
        v.vibrate(
            effect,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
    }

    private fun stopAlarm() {
        handler.removeCallbacksAndMessages(null)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        isRinging = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        onStopped?.invoke()
    }

    override fun onDestroy() {
        if (isRinging) stopAlarm()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "החתול המעורר", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ההתראה שמופיעה כשהחתול מעיר אותך"
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snooze = PendingIntent.getService(
            this, 1,
            Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_paw)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.cat_face))
            .setContentTitle("😾 מיאו!! זמן לקום!")
            .setContentText("החתול מחכה. ליטופים בלבד יכבו אותו.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(null, "😾 עוד 5 דקות", snooze).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
