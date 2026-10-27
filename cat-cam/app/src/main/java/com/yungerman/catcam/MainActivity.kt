package com.yungerman.catcam

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var statusText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var placeholder: View
    private lateinit var liveBadge: View
    private lateinit var liveDot: View
    private lateinit var clockText: TextView
    private lateinit var detectionChip: TextView
    private lateinit var flashOverlay: View
    private lateinit var todayCountText: TextView
    private lateinit var lastDetectionText: TextView
    private lateinit var lastPhoto: ShapeableImageView
    private lateinit var toggleButton: MaterialButton

    private var liveDotAnimator: ObjectAnimator? = null
    private val clockFormat = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.US)
    private val clockTicker = object : Runnable {
        override fun run() {
            clockText.text = clockFormat.format(Date())
            clockText.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == true) startMonitoring()
            else toast("בלי הרשאת מצלמה האפליקציה לא יכולה לעבוד")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusText = findViewById(R.id.statusText)
        previewView = findViewById(R.id.previewView)
        placeholder = findViewById(R.id.placeholder)
        liveBadge = findViewById(R.id.liveBadge)
        liveDot = findViewById(R.id.liveDot)
        clockText = findViewById(R.id.clockText)
        detectionChip = findViewById(R.id.detectionChip)
        flashOverlay = findViewById(R.id.flashOverlay)
        todayCountText = findViewById(R.id.todayCountText)
        lastDetectionText = findViewById(R.id.lastDetectionText)
        lastPhoto = findViewById(R.id.lastPhoto)
        toggleButton = findViewById(R.id.toggleButton)

        // TextureView-based preview so it clips to the card's rounded corners.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        toggleButton.setOnClickListener {
            if (CatCamState.running.value) stopMonitoring() else requestPermissionsAndStart()
        }
        findViewById<MaterialButton>(R.id.snapshotButton).setOnClickListener {
            if (!CatCamState.running.value) {
                toast("הפעל קודם את הניטור")
                return@setOnClickListener
            }
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startService(Intent(this, CatCamService::class.java).setAction(CatCamService.ACTION_SNAPSHOT))
        }
        findViewById<MaterialButton>(R.id.settingsButton).setOnClickListener { showSettings() }
        lastPhoto.setOnClickListener { openLastPhoto() }

        observeState()
    }

    override fun onStart() {
        super.onStart()
        CatCamState.previewSurface.value = previewView.surfaceProvider
        CatCamState.detectionsToday.value = prefs.detectionsToday()
        clockText.post(clockTicker)
        if (CatCamState.lastPhotoUri.value == null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { PhotoStore.latestPhoto(this@MainActivity) }
                    ?.let { CatCamState.lastPhotoUri.value = it }
            }
        }
    }

    override fun onStop() {
        CatCamState.previewSurface.value = null
        clockText.removeCallbacks(clockTicker)
        super.onStop()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CatCamState.running.collect(::renderRunning) }
                launch { CatCamState.status.collect { statusText.text = it } }
                launch {
                    CatCamState.running.combine(CatCamState.catConfidence) { running, c -> running to c }
                        .collect { (running, confidence) -> renderDetection(running, confidence) }
                }
                launch { CatCamState.detectionsToday.collect { todayCountText.text = it.toString() } }
                launch { CatCamState.lastDetectionTime.collect { lastDetectionText.text = it ?: "—" } }
                launch { CatCamState.lastPhotoUri.collect { uri -> uri?.let { loadThumbnail(it) } } }
                launch { CatCamState.photoSaved.collect { flash() } }
            }
        }
    }

    private fun renderRunning(running: Boolean) {
        placeholder.visibility = if (running) View.GONE else View.VISIBLE
        liveBadge.visibility = if (running) View.VISIBLE else View.GONE
        clockText.visibility = if (running) View.VISIBLE else View.GONE

        toggleButton.text = if (running) "עצור ניטור" else "התחל ניטור"
        toggleButton.setIconResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        toggleButton.backgroundTintList = ContextCompat.getColorStateList(this, if (running) R.color.danger else R.color.accent)
        toggleButton.setTextColor(ContextCompat.getColor(this, if (running) android.R.color.white else R.color.on_accent))
        toggleButton.iconTint = toggleButton.textColors

        liveDotAnimator?.cancel()
        if (running) {
            liveDotAnimator = ObjectAnimator.ofFloat(liveDot, View.ALPHA, 1f, 0.2f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun renderDetection(running: Boolean, confidence: Float) {
        if (!running) {
            detectionChip.visibility = View.GONE
            return
        }
        val percent = (confidence * 100).toInt()
        val detected = percent >= prefs.minConfidence
        detectionChip.visibility = View.VISIBLE
        detectionChip.text = when {
            detected -> "🐱  חתול זוהה · $percent%"
            percent > 0 -> "מחפש חתול… ($percent%)"
            else -> "מחפש חתול…"
        }
        detectionChip.setBackgroundResource(if (detected) R.drawable.bg_chip_detected else R.drawable.bg_chip)
    }

    private fun flash() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.85f
        flashOverlay.animate().alpha(0f).setDuration(350).start()
    }

    private suspend fun loadThumbnail(uri: Uri) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
        } ?: return
        lastPhoto.imageTintList = null
        lastPhoto.setPadding(0, 0, 0, 0)
        lastPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        lastPhoto.setImageBitmap(bitmap)
    }

    private fun openLastPhoto() {
        val uri = CatCamState.lastPhotoUri.value ?: run {
            toast("עוד אין תמונות. הן יישמרו בגלריה בתיקייה CatCam")
            return
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure { toast("לא נמצאה אפליקציית גלריה") }
    }

    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_settings, null)

        val confidenceLabel = view.findViewById<TextView>(R.id.confidenceLabel)
        val confidenceSlider = view.findViewById<Slider>(R.id.confidenceSlider)
        confidenceSlider.value = prefs.minConfidence.toFloat()
        confidenceLabel.text = "רף ביטחון לזיהוי: ${prefs.minConfidence}%"
        confidenceSlider.addOnChangeListener { _, value, _ ->
            prefs.minConfidence = value.toInt()
            confidenceLabel.text = "רף ביטחון לזיהוי: ${value.toInt()}%"
        }

        val cooldownLabel = view.findViewById<TextView>(R.id.cooldownLabel)
        val cooldownSlider = view.findViewById<Slider>(R.id.cooldownSlider)
        fun cooldownText(minutes: Int) =
            if (minutes == 0) "המתנה בין צילומים: ללא" else "המתנה בין צילומים: $minutes דק׳"
        cooldownSlider.value = prefs.cooldownMinutes.toFloat()
        cooldownLabel.text = cooldownText(prefs.cooldownMinutes)
        cooldownSlider.addOnChangeListener { _, value, _ ->
            prefs.cooldownMinutes = value.toInt()
            cooldownLabel.text = cooldownText(value.toInt())
        }

        view.findViewById<MaterialSwitch>(R.id.soundSwitch).apply {
            isChecked = prefs.soundEnabled
            setOnCheckedChangeListener { _, checked -> prefs.soundEnabled = checked }
        }
        view.findViewById<MaterialSwitch>(R.id.frontCameraSwitch).apply {
            isChecked = prefs.useFrontCamera
            setOnCheckedChangeListener { _, checked ->
                prefs.useFrontCamera = checked
                if (CatCamState.running.value) {
                    // Switching cameras needs a fresh camera session.
                    stopMonitoring()
                    startMonitoring()
                }
            }
        }
        view.findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun requestPermissionsAndStart() {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startMonitoring() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(this, Intent(this, CatCamService::class.java))
    }

    private fun stopMonitoring() {
        stopService(Intent(this, CatCamService::class.java))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
