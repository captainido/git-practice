package com.yungerman.catcam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the camera. Watches for the cat in the background (screen off is fine), and when it is
 * seen: plays a sound and saves a date/time-stamped photo to the gallery. While the app screen is
 * open, a live preview is attached as an extra use case.
 */
class CatCamService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var captureExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var toneGenerator: ToneGenerator? = null

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.3f).build())
    }

    /** Keeps photos upright whichever way the phone is propped up. */
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                analysis?.targetRotation = rotation
            }
        }
    }

    // Analysis state: touched on the analysis executor / main thread only.
    private var lastAnalyzedAt = 0L
    @Volatile private var lastCaptureAt = 0L
    private var lastSeenAt = 0L
    @Volatile private var capturing = false
    @Volatile private var captureStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        analysisExecutor = Executors.newSingleThreadExecutor()
        captureExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SNAPSHOT -> {
                if (CatCamState.running.value) takePhoto(Date(), manual = true)
                return START_NOT_STICKY
            }
        }
        if (CatCamState.running.value) return START_NOT_STICKY

        startInForeground()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CatCam:camera")
            .apply { acquire() }
        orientationListener.enable()
        CatCamState.running.value = true
        CatCamState.status.value = "מפעיל מצלמה..."
        startCamera()
        // Android doesn't allow restarting a camera service from the background, so no STICKY.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ניטור פעיל", NotificationManager.IMPORTANCE_LOW)
        )
        manager.deleteNotificationChannel("detections") // leftover from version 1.1
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CatCamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("מצלמת החתול בניטור")
            .setContentText("תמונות נשמרות בגלריה: Pictures/CatCam")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "עצור", stop)
            .build()
        // The "camera" service type only exists from Android 11; Android 10 needs 0 here.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startCamera() {
        analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyze) }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
            } catch (e: Exception) {
                CatCamState.status.value = "שגיאה בהפעלת המצלמה: ${e.message}"
                stopSelf()
                return@addListener
            }
            // Rebind whenever the screen opens or closes, so the live view comes and goes.
            lifecycleScope.launch {
                CatCamState.previewSurface.collect { surface -> bindCamera(surface) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(surface: Preview.SurfaceProvider?) {
        val provider = cameraProvider ?: return
        val useCases = mutableListOf<UseCase>(analysis!!, imageCapture!!)
        if (surface != null) {
            useCases += Preview.Builder().build().also { it.setSurfaceProvider(surface) }
        }
        val selector = if (prefs.useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
            CatCamState.status.value = "בניטור · מחפש את החתול"
        } catch (e: Exception) {
            CatCamState.status.value = "שגיאה בהפעלת המצלמה: ${e.message}"
            stopSelf()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        // Safety net: a capture that never reported back must not block all future captures.
        if (capturing && now - captureStartedAt > CAPTURE_TIMEOUT_MS) capturing = false

        val mediaImage = proxy.image
        if (mediaImage == null || capturing || now - lastAnalyzedAt < ANALYZE_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastAnalyzedAt = now

        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        labeler.process(input)
            .addOnSuccessListener { labels ->
                val confidence = labels.firstOrNull { it.text.equals("Cat", ignoreCase = true) }?.confidence ?: 0f
                // Compare whole percents (as shown on screen) so 70% really passes a 70% threshold.
                val detected = Math.round(confidence * 100) >= prefs.minConfidence
                CatCamState.catConfidence.value = confidence
                CatCamState.catDetected.value = detected
                if (detected) onCatDetected() else CatCamState.nextCaptureAt.value = 0L
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Keeps photographing every [Prefs.captureIntervalSeconds] while the cat stays in view. */
    private fun onCatDetected() {
        val now = System.currentTimeMillis()
        // Beep once per visit (cat was away a while), not on every repeat photo.
        val newVisit = now - lastSeenAt > VISIT_GAP_MS
        lastSeenAt = now

        val intervalMs = prefs.captureIntervalSeconds * 1000L
        if (now - lastCaptureAt < intervalMs) {
            CatCamState.nextCaptureAt.value = lastCaptureAt + intervalMs
            return
        }
        if (capturing) return
        lastCaptureAt = now
        CatCamState.nextCaptureAt.value = now + intervalMs
        if (newVisit && prefs.soundEnabled) playDetectionSound()
        takePhoto(Date(now), manual = false)
    }

    private fun playDetectionSound() {
        runCatching {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_ALARM, 90).also { toneGenerator = it }
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)
        }
    }

    private fun takePhoto(time: Date, manual: Boolean) {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        captureStartedAt = SystemClock.elapsedRealtime()

        capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val label = if (manual) "CatCam · Manual capture" else "CatCam · Cat detected"
                    val bitmap = PhotoStore.stamp(image.toBitmap(), image.imageInfo.rotationDegrees, time, label)
                    val uri = PhotoStore.saveToGallery(this@CatCamService, bitmap, time)
                    CatCamState.lastPhotoUri.value = uri
                    CatCamState.photoSaved.tryEmit(Unit)
                    if (!manual) {
                        CatCamState.detectionsToday.value = prefs.incrementDetectionsToday()
                        CatCamState.lastDetectionTime.value = SimpleDateFormat("HH:mm", Locale.US).format(time)
                    }
                    CatCamState.status.value = "בניטור · מחפש את החתול"
                } catch (e: Exception) {
                    CatCamState.status.value = "שגיאה בשמירת התמונה: ${e.message}"
                    if (!manual) lastCaptureAt = 0L // retry on the next frame instead of waiting
                } finally {
                    image.close()
                    capturing = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                CatCamState.status.value = "שגיאה בצילום: ${exception.message}"
                if (!manual) lastCaptureAt = 0L
                capturing = false
            }
        })
    }

    override fun onDestroy() {
        CatCamState.running.value = false
        CatCamState.catConfidence.value = 0f
        CatCamState.catDetected.value = false
        CatCamState.nextCaptureAt.value = 0L
        CatCamState.status.value = "המצלמה כבויה"
        orientationListener.disable()
        cameraProvider?.unbindAll()
        labeler.close()
        toneGenerator?.release()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.yungerman.catcam.STOP"
        const val ACTION_SNAPSHOT = "com.yungerman.catcam.SNAPSHOT"
        private const val CHANNEL_ID = "catcam"
        private const val NOTIFICATION_ID = 1
        /** Analyze ~3 frames per second: responsive, still easy on the battery. */
        private const val ANALYZE_INTERVAL_MS = 350L
        /** A capture taking longer than this is treated as lost. */
        private const val CAPTURE_TIMEOUT_MS = 10_000L
        /** The cat must be away this long before its return counts as a new visit (new beep). */
        private const val VISIT_GAP_MS = 60_000L
    }
}
