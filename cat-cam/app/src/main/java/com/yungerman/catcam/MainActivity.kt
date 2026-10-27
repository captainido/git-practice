package com.yungerman.catcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var lastPhoto: ImageView
    private lateinit var lastDetectionText: TextView
    private lateinit var cooldownInput: TextInputEditText
    private lateinit var confidenceLabel: TextView
    private lateinit var confidenceSlider: Slider
    private lateinit var frontCameraSwitch: MaterialSwitch
    private lateinit var soundSwitch: MaterialSwitch

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == true) {
                startCatCam()
            } else {
                toast("בלי הרשאת מצלמה האפליקציה לא יכולה לעבוד")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        lastPhoto = findViewById(R.id.lastPhoto)
        lastDetectionText = findViewById(R.id.lastDetectionText)
        cooldownInput = findViewById(R.id.cooldownInput)
        confidenceLabel = findViewById(R.id.confidenceLabel)
        confidenceSlider = findViewById(R.id.confidenceSlider)
        frontCameraSwitch = findViewById(R.id.frontCameraSwitch)
        soundSwitch = findViewById(R.id.soundSwitch)

        loadSettings()

        confidenceSlider.addOnChangeListener { _, value, _ ->
            confidenceLabel.text = "רף ביטחון לזיהוי: ${value.toInt()}%"
        }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            saveSettings()
            toast("ההגדרות נשמרו ✓")
        }
        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        toggleButton.setOnClickListener {
            if (CatCamState.running.value) stopCatCam() else requestPermissionsAndStart()
        }

        observeState()
    }

    private fun loadSettings() {
        cooldownInput.setText(prefs.cooldownMinutes.toString())
        confidenceSlider.value = prefs.minConfidence.toFloat()
        confidenceLabel.text = "רף ביטחון לזיהוי: ${prefs.minConfidence}%"
        frontCameraSwitch.isChecked = prefs.useFrontCamera
        soundSwitch.isChecked = prefs.soundEnabled
    }

    private fun saveSettings() {
        prefs.cooldownMinutes = cooldownInput.text.toString().toIntOrNull() ?: 5
        prefs.minConfidence = confidenceSlider.value.toInt()
        prefs.useFrontCamera = frontCameraSwitch.isChecked
        prefs.soundEnabled = soundSwitch.isChecked
    }

    private fun requestPermissionsAndStart() {
        saveSettings()
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) startCatCam() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startCatCam() {
        ContextCompat.startForegroundService(this, Intent(this, CatCamService::class.java))
    }

    private fun stopCatCam() {
        stopService(Intent(this, CatCamService::class.java))
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    CatCamState.running.collect { running ->
                        toggleButton.text = if (running) "עצור מצלמה" else "הפעל מצלמה"
                    }
                }
                launch { CatCamState.status.collect { statusText.text = it } }
                launch { CatCamState.lastDetection.collect { lastDetectionText.text = it } }
                launch {
                    CatCamState.lastPhotoPath.collect { path ->
                        val file = path?.let(::File)?.takeIf { it.exists() } ?: return@collect
                        val bitmap = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                        }
                        lastPhoto.setImageBitmap(bitmap)
                        lastPhoto.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
