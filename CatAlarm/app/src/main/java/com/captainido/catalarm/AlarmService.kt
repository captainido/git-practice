package com.captainido.catalarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.util.Log

/**
 * Plays Oliver's meow in a loop (in a different "mood" each round) until dismissed,
 * and optionally phones someone on speaker to help wake Ido up.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val ACTION_START = "start"
        private const val ACTION_DISMISS = "dismiss"
        private const val ACTION_SNOOZE = "snooze"
        private const val EXTRA_CALL = "call"
        private const val CHANNEL_ID = "oliver_alarm_v2"
        private const val NOTIFICATION_ID = 42
        private const val MAX_RING_MS = 10 * 60_000L
        private const val CALL_DUCK_MS = 90_000L

        // Normal Oliver, chipmunk Oliver, big angry Oliver, squeaky kitten, slow-motion Oliver...
        private val MOODS = floatArrayOf(1.0f, 1.45f, 0.75f, 1.9f, 1.0f, 0.6f, 1.25f, 2.2f)

        @Volatile
        var isRinging = false
            private set

        @Volatile
        var callPlaced = false
            private set

        /** Set when the background call could not be placed; the alarm screen then dials itself. */
        @Volatile
        var callFailed = false

        /** Whether this ring is about to phone someone. */
        @Volatile
        var callPending = false
            private set

        /** Called on the main thread when the alarm stops for any reason. */
        var onStopped: (() -> Unit)? = null

        /** Called on the main thread right after the call is placed. */
        var onCallPlaced: (() -> Unit)? = null

        fun start(ctx: Context, withCall: Boolean = true) {
            ctx.startForegroundService(
                Intent(ctx, AlarmService::class.java).setAction(ACTION_START).putExtra(EXTRA_CALL, withCall)
            )
        }

        /** Marks the call as handled by the alarm screen's fallback dialing. */
        fun markCallPlaced() {
            callPlaced = true
            callFailed = false
            callPending = false
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
    private var ducked = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> stopAlarm()
            ACTION_SNOOZE -> {
                AlarmScheduler.snooze(this)
                stopAlarm()
            }
            else -> startRinging(intent?.getBooleanExtra(EXTRA_CALL, true) ?: true)
        }
        return START_NOT_STICKY
    }

    private fun startRinging(withCall: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (isRinging) return
        isRinging = true
        callPlaced = false
        ducked = false
        round = 0
        volume = 0.35f
        startPlayer()
        startVibration()
        handler.postDelayed(volumeRamp, 1000)
        handler.postDelayed({ stopAlarm() }, MAX_RING_MS)

        // The call goes out together with the alarm (never in the demo unless asked for).
        callFailed = false
        callPending = withCall && AlarmStore.isCallEnabled(this) && ContactFinder.resolveIfMissing(this)
        if (callPending) handler.post(placeCall)
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
            val v = if (ducked) 0.15f else volume
            player?.setVolume(v, v)
            handler.postDelayed(this, 1000)
        }
    }

    /** Dials the contact on speaker; Oliver gets quieter for a while so the call can be heard. */
    private val placeCall = Runnable {
        callPending = false
        val number = AlarmStore.callNumber(this)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CALL_PHONE not granted, skipping call")
            return@Runnable
        }
        if (number.isBlank()) return@Runnable
        try {
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
            }
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), extras)
            callPlaced = true
            ducked = true
            player?.setVolume(0.15f, 0.15f)
            handler.postDelayed({ ducked = false }, CALL_DUCK_MS)
            onCallPlaced?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Could not place call", e)
            callFailed = true
            onCallPlaced?.invoke()
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        // "Paw, paw... PAAAW" pattern.
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 150, 120, 150, 500, 700, 900), 0)
        v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
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
        callPending = false
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
            NotificationChannel(CHANNEL_ID, "אוליבר המעורר", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ההתראה שמופיעה כשאוליבר מעיר את ${Names.HUMAN}"
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
            .setContentTitle("😾 ${Names.CAT}: ${Names.HUMAN}, קום!")
            .setContentText("רק ליטופים יכבו אותי.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(null, "😾 נודניק ${AlarmScheduler.SNOOZE_MINUTES} דק׳", snooze).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
