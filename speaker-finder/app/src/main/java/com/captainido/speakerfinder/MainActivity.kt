package com.captainido.speakerfinder

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel

class SpeakerViewModel(app: Application) : AndroidViewModel(app) {
    val controller = BluetoothController(app)

    override fun onCleared() = controller.release()
}

class MainActivity : ComponentActivity() {

    private val viewModel: SpeakerViewModel by viewModels()
    private var permissionsGranted by mutableStateOf(false)
    private var locationEnabled by mutableStateOf(true)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshState() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        if (!permissionsGranted && savedInstanceState == null) askForPermissions()

        setContent {
            SpeakerFinderTheme {
                SpeakerFinderScreen(
                    controller = viewModel.controller,
                    permissionsGranted = permissionsGranted,
                    locationEnabled = locationEnabled,
                    onRequestPermissions = ::askForPermissions,
                    onEnableBluetooth = {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    onOpenBluetoothSettings = {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun askForPermissions() = permissionLauncher.launch(requiredPermissions)

    private fun refreshState() {
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        // Before Android 12, Bluetooth discovery returns nothing while location services are off.
        locationEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
        if (permissionsGranted) viewModel.controller.start()
    }

    private companion object {
        val requiredPermissions: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
