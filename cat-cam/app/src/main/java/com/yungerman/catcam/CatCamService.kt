package com.yungerman.catcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that watches the camera, and when it sees the cat:
 * plays a sound, takes a timestamped photo, saves it and shows it in a notification.
 */
class CatCamService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var captureExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var toneGenerator: ToneGenerator? = null

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.4f).build())
    }

    // Touched only on the analysis executor / main thread respectively.
    private var lastAnalyzedAt = 0L
    private var consecutiveHits = 0
    private var lastAlertAt = 0L
    @Volatile private var capturing = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        analysisExecutor = Executors.newSingleThreadExecutor()
        captureExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (CatCamState.running.value) return START_NOT_STICKY

        startInForeground()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CatCam:camera")
            .apply { acquire() }
        CatCamState.running.value = true
        startCamera()
        // Android doesn't allow restarting a camera service from the background, so no STICKY.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "מצלמת חתול פעילה", NotificationManager.IMPORTANCE_LOW)
        )
        // Silent channel: the detection tone is played by the service itself (and can be turned off).
        manager.createNotificationChannel(
            NotificationChannel(DETECTION_CHANNEL_ID, "זיהוי החתול", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) }
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CatCamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("מצלמת החתול פעילה")
            .setContentText("מחפש את החתול...")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "עצור", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val selector = if (prefs.useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis, capture)
                CatCamState.status.value = "🟢 המצלמה פעילה — מחפש את החתול..."
            } catch (e: Exception) {
                CatCamState.status.value = "שגיאה בהפעלת המצלמה: ${e.message}"
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val mediaImage = proxy.image
        if (mediaImage == null || capturing || now - lastAnalyzedAt < ANALYZE_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastAnalyzedAt = now

        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        labeler.process(input)
            .addOnSuccessListener { labels ->
                val cat = labels.firstOrNull { it.text.equals("Cat", ignoreCase = true) }
                if (cat != null && cat.confidence * 100 >= prefs.minConfidence) {
                    consecutiveHits++
                    if (consecutiveHits >= REQUIRED_HITS) {
                        consecutiveHits = 0
                        onCatDetected()
                    }
                } else {
                    consecutiveHits = 0
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onCatDetected() {
        val now = System.currentTimeMillis()
        val cooldownMs = prefs.cooldownMinutes * 60_000L
        if (now - lastAlertAt < cooldownMs) return
        lastAlertAt = now

        if (prefs.soundEnabled) playDetectionSound()
        takePhoto(Date(now))
    }

    private fun playDetectionSound() {
        runCatching {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, 90).also { toneGenerator = it }
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
        }
    }

    private fun takePhoto(time: Date) {
        val capture = imageCapture ?: return
        capturing = true
        val timeText = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(time)
        CatCamState.status.value = "🐱 זוהה החתול! ($timeText) מצלם..."

        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = PhotoStore.prepare(image.toBitmap(), image.imageInfo.rotationDegrees, timeText)
                    val file = PhotoStore.save(this@CatCamService, bitmap, time)
                    CatCamState.lastPhotoPath.value = file.absolutePath
                    CatCamState.lastDetection.value = "🐱 זוהה החתול ב-$timeText (נשמר בגלריה: Pictures/CatCam)"
                    CatCamState.status.value = "🟢 פעילה — זיהוי אחרון: $timeText"
                    showDetectionNotification(bitmap, timeText)
                } catch (e: Exception) {
                    CatCamState.status.value = "שגיאה בשמירת התמונה: ${e.message}"
                } finally {
                    image.close()
                    capturing = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                CatCamState.status.value = "שגיאה בצילום: ${exception.message}"
                capturing = false
            }
        })
    }

    private fun showDetectionNotification(photo: Bitmap, timeText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // Full-size photos are too large to pass to the notification system.
        val scale = 720f / maxOf(photo.width, photo.height)
        val small = Bitmap.createScaledBitmap(photo, (photo.width * scale).toInt(), (photo.height * scale).toInt(), true)
        val notification = NotificationCompat.Builder(this, DETECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("🐱 זוהה החתול!")
            .setContentText(timeText)
            .setLargeIcon(small)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(small).bigLargeIcon(null as Bitmap?))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // A new id each time keeps earlier detections in the notification shade.
        manager.notify((System.currentTimeMillis() / 1000).toInt(), notification)
    }

    override fun onDestroy() {
        CatCamState.running.value = false
        CatCamState.status.value = "המצלמה כבויה"
        labeler.close()
        toneGenerator?.release()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.yungerman.catcam.STOP"
        private const val CHANNEL_ID = "catcam"
        private const val DETECTION_CHANNEL_ID = "detections"
        private const val NOTIFICATION_ID = 1
        /** Analyze ~2 frames per second: plenty for a cat, easy on the battery. */
        private const val ANALYZE_INTERVAL_MS = 500L
        /** Require the cat in 2 consecutive frames to avoid one-off false alarms. */
        private const val REQUIRED_HITS = 2
    }
}
