package com.captainido.catalarm

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log

/**
 * Rings one alarm: plays Oliver's recording on the alarm stream, vibrates, shows the full-screen
 * alarm, and — when the alarm asks for it — phones the chosen contact in the background.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_SNOOZE = "snooze"
        private const val EXTRA_CALL = "call"
        private const val EXTRA_PREVIEW = "preview"
        private const val NOTIFICATION_ID = 42
        private const val RAMP_MS = 30_000L
        private const val CALL_DUCK_MS = 90_000L
        const val PREVIEW_ID = -1

        @Volatile
        var isRinging = false
            private set

        /** The alarm currently ringing, or [PREVIEW_ID] for the preview. */
        @Volatile
        var ringingId = 0
            private set

        @Volatile
        var callPlaced = false
            private set

        /** Set when the background call could not be placed; the alarm screen then dials itself. */
        @Volatile
        var callFailed = false
            private set

        /** Called on the main thread when ringing stops for any reason. */
        var onStopped: (() -> Unit)? = null

        /** Called on the main thread when the call state changes. */
        var onCallChanged: (() -> Unit)? = null

        fun start(ctx: Context, alarmId: Int, withCall: Boolean) {
            ctx.startForegroundService(
                Intent(ctx, AlarmService::class.java).setAction(ACTION_START)
                    .putExtra(AlarmScheduler.EXTRA_ID, alarmId)
                    .putExtra(EXTRA_CALL, withCall)
            )
        }

        fun preview(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, AlarmService::class.java).setAction(ACTION_START)
                    .putExtra(AlarmScheduler.EXTRA_ID, PREVIEW_ID)
                    .putExtra(EXTRA_PREVIEW, true)
            )
        }

        fun stop(ctx: Context) {
            if (isRinging) ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_STOP))
        }

        fun snooze(ctx: Context) {
            if (isRinging) ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_SNOOZE))
        }

        fun markCallPlaced() {
            callPlaced = true
            callFailed = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var volume = 1f
    private var ducked = false
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRinging(missed = false)
            ACTION_SNOOZE -> snoozeRinging()
            else -> startRinging(
                id = intent?.getIntExtra(AlarmScheduler.EXTRA_ID, PREVIEW_ID) ?: PREVIEW_ID,
                withCall = intent?.getBooleanExtra(EXTRA_CALL, false) ?: false,
            )
        }
        return START_NOT_STICKY
    }

    private fun alarm(): Alarm? = if (ringingId == PREVIEW_ID) null else AlarmRepo.get(this, ringingId)

    private fun startRinging(id: Int, withCall: Boolean) {
        if (isRinging) {
            // Already ringing: keep the current alarm, just stay in the foreground.
            startInForeground()
            return
        }
        ringingId = id
        isRinging = true
        callPlaced = false
        callFailed = false
        ducked = false
        startedAt = System.currentTimeMillis()
        startInForeground()

        val gradual = AlarmRepo.gradualVolume(this)
        volume = if (gradual) 0.12f else 1f
        startPlayer()
        if (alarm()?.vibrate != false) startVibration()
        if (gradual) handler.postDelayed(volumeRamp, 1000)
        handler.postDelayed({ stopRinging(missed = true) }, AlarmRepo.silenceAfterMinutes(this) * 60_000L)

        if (withCall && ContactFinder.resolveIfMissing(this)) handler.post(placeCall)
        bringScreenToFront()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
            isLooping = true
            setVolume(volume, volume)
            prepare()
            start()
        }
    }

    /** Raises the volume smoothly to full over [RAMP_MS]. */
    private val volumeRamp = object : Runnable {
        override fun run() {
            volume = (volume + 1f / (RAMP_MS / 1000f)).coerceAtMost(1f)
            applyVolume()
            if (volume < 1f) handler.postDelayed(this, 1000)
        }
    }

    private fun applyVolume() {
        val v = if (ducked) minOf(volume, 0.15f) else volume
        player?.setVolume(v, v)
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = getSystemService(Vibrator::class.java) ?: return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)
        v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
    }

    /** Dials the contact on speaker. The alarm stays quieter for a while so the call can be heard. */
    private val placeCall = Runnable {
        val number = AlarmRepo.callNumber(this)
        if (number.isBlank()) return@Runnable
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CALL_PHONE not granted, skipping call")
            return@Runnable
        }
        try {
            val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true) }
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), extras)
            markCallPlaced()
            ducked = true
            applyVolume()
            handler.postDelayed({ ducked = false; applyVolume() }, CALL_DUCK_MS)
            // The phone app opens the in-call screen; put the alarm back on top so the call carries on behind it.
            handler.postDelayed({ bringScreenToFront() }, 1500)
            handler.postDelayed({ bringScreenToFront() }, 4000)
        } catch (e: Exception) {
            Log.w(TAG, "Could not place call", e)
            callFailed = true
        }
        onCallChanged?.invoke()
    }

    /** Opens the alarm screen directly when Android allows it (display-over-apps granted). */
    private fun bringScreenToFront() {
        if (!Settings.canDrawOverlays(this)) return
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    private fun snoozeRinging() {
        alarm()?.let {
            it.snoozedUntil = System.currentTimeMillis() + AlarmRepo.snoozeMinutes(this) * 60_000L
            AlarmRepo.save(this, it)
            AlarmScheduler.scheduleAll(this)
            Notifier.showSnoozed(this, it)
        }
        stopRinging(missed = false)
    }

    private fun stopRinging(missed: Boolean) {
        if (missed) alarm()?.let { Notifier.showMissed(this, it, startedAt) }
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
        if (isRinging) stopRinging(missed = false)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        Notifier.ensureChannels(this)
        val label = alarm()?.label?.takeIf { it.isNotBlank() } ?: "התראה"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(title: String, act: String, code: Int) = Notification.Action.Builder(
            null, title,
            PendingIntent.getService(
                this, code, Intent(this, AlarmService::class.java).setAction(act),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()
        val builder = Notification.Builder(this, Notifier.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setColor(getColor(R.color.gold))
            .setContentTitle(label)
            .setContentText(Format.time(System.currentTimeMillis()))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(action("נודניק", ACTION_SNOOZE, 1))
            .addAction(action("עצירה", ACTION_STOP, 2))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
