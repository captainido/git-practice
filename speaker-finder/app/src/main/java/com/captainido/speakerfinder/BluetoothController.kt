package com.captainido.speakerfinder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FoundDevice(
    val address: String,
    val name: String?,
    /** Last received signal strength in dBm, or null for a paired device not seen in this scan. */
    val rssi: Int?,
    val seenClassic: Boolean,
    val seenBle: Boolean,
    /** Advertised over BLE Coded PHY (Bluetooth 5 long range). */
    val longRange: Boolean,
    val isAudio: Boolean,
    val bonded: Boolean,
    val connected: Boolean,
)

/**
 * Finds nearby speakers using both Classic discovery (how JBL and most speakers stream audio)
 * and BLE scanning (including Coded PHY / long range when the phone supports it), and connects
 * to them by pairing and then opening the A2DP audio profile.
 *
 * All public methods must be called on the main thread, after Bluetooth permissions are granted.
 */
@SuppressLint("MissingPermission") // MainActivity only starts the controller once permissions are granted.
class BluetoothController(private val context: Context) {

    sealed interface Status {
        data class Pairing(val name: String) : Status
        data class Connecting(val name: String) : Status
        data class Connected(val name: String) : Status
        data class PairFailed(val name: String) : Status
        data class PairedOnly(val name: String) : Status
        data class Disconnected(val name: String) : Status
    }

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<Map<String, FoundDevice>>(emptyMap())
    val devices: StateFlow<Map<String, FoundDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private val _enabled = MutableStateFlow(adapter?.isEnabled == true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isSupported: Boolean get() = adapter != null
    val supportsLongRange: Boolean get() = adapter?.isLeCodedPhySupported == true
    val supportsExtendedAdvertising: Boolean get() = adapter?.isLeExtendedAdvertisingSupported == true

    private var a2dp: BluetoothA2dp? = null
    private var pendingConnect: String? = null
    private var started = false

    private val stopScanRunnable = Runnable { stopScan() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> onClassicDeviceFound(intent)
                // Classic discovery stops by itself after ~12 seconds; keep it going while scanning.
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (_scanning.value) adapter?.startDiscovery()
                BluetoothAdapter.ACTION_STATE_CHANGED -> onAdapterStateChanged(intent)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondStateChanged(intent)
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> onA2dpStateChanged(intent)
                BluetoothDevice.ACTION_NAME_CHANGED -> intent.device()?.let { refresh(it) }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = proxy as BluetoothA2dp
            proxy.connectedDevices.forEach { setConnected(it, true) }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null
        }
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onLeResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onLeResult)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    fun start() {
        if (started || adapter == null) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        _enabled.value = adapter.isEnabled
        adapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
        addBondedSpeakers()
    }

    fun release() {
        if (!started) return
        stopScan()
        handler.removeCallbacksAndMessages(null)
        context.unregisterReceiver(receiver)
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
        started = false
    }

    fun startScan() {
        val adapter = adapter ?: return
        if (!adapter.isEnabled || _scanning.value) return
        // Start fresh, but keep what we know about connected devices.
        _devices.update { map -> map.filterValues { it.connected }.mapValues { it.value.copy(rssi = null) } }
        addBondedSpeakers()
        _scanning.value = true
        adapter.startDiscovery()
        adapter.bluetoothLeScanner?.startScan(null, leScanSettings(adapter), leCallback)
        handler.postDelayed(stopScanRunnable, SCAN_DURATION_MS)
    }

    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (!_scanning.value) return
        _scanning.value = false
        adapter?.cancelDiscovery()
        try {
            adapter?.bluetoothLeScanner?.stopScan(leCallback)
        } catch (e: IllegalStateException) {
            // Bluetooth was turned off while scanning; the scan is already gone.
        }
    }

    fun connect(address: String) {
        val adapter = adapter ?: return
        // Discovery slows down pairing and connection considerably.
        stopScan()
        val device = adapter.getRemoteDevice(address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connectAudio(device)
            return
        }
        pendingConnect = address
        _status.value = Status.Pairing(displayName(device))
        if (!device.createBond()) {
            pendingConnect = null
            _status.value = Status.PairFailed(displayName(device))
        }
    }

    /** Returns false when the system doesn't allow apps to disconnect, so the UI can fall back to settings. */
    fun disconnect(address: String): Boolean {
        val proxy = a2dp ?: return false
        val device = adapter?.getRemoteDevice(address) ?: return false
        return invokeHiddenA2dp(proxy, "disconnect", device)
    }

    private fun connectAudio(device: BluetoothDevice) {
        val name = displayName(device)
        val proxy = a2dp
        if (proxy == null) {
            _status.value = Status.PairedOnly(name)
            return
        }
        if (proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            setConnected(device, true)
            _status.value = Status.Connected(name)
            return
        }
        _status.value = Status.Connecting(name)
        if (!invokeHiddenA2dp(proxy, "connect", device)) {
            // Android usually connects audio on its own right after pairing; if it doesn't,
            // the user has to tap the speaker in the system Bluetooth settings.
            handler.postDelayed({ fallBackIfStillConnecting(name) }, AUTO_CONNECT_GRACE_MS)
            return
        }
        handler.postDelayed({ fallBackIfStillConnecting(name) }, CONNECT_TIMEOUT_MS)
    }

    private fun fallBackIfStillConnecting(name: String) {
        if (_status.value == Status.Connecting(name)) _status.value = Status.PairedOnly(name)
    }

    /**
     * BluetoothA2dp.connect()/disconnect() are hidden APIs. They work on many phones, but newer
     * Android versions restrict them to system apps, so failure is expected and handled.
     */
    private fun invokeHiddenA2dp(proxy: BluetoothA2dp, method: String, device: BluetoothDevice): Boolean =
        try {
            BluetoothA2dp::class.java.getMethod(method, BluetoothDevice::class.java)
                .invoke(proxy, device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.i(TAG, "A2DP $method not available: $e")
            false
        }

    private fun onClassicDeviceFound(intent: Intent) {
        val device = intent.device() ?: return
        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
        val btClass = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
            ?: device.bluetoothClass
        upsert(
            device = device,
            name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name,
            rssi = if (rssi == Short.MIN_VALUE) null else rssi.toInt(),
            classic = true,
            audioClass = btClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO,
        )
    }

    private fun onLeResult(result: ScanResult) {
        upsert(
            device = result.device,
            name = result.scanRecord?.deviceName ?: result.device.name,
            rssi = result.rssi,
            ble = true,
            longRange = result.primaryPhy == BluetoothDevice.PHY_LE_CODED,
        )
    }

    private fun onAdapterStateChanged(intent: Intent) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        _enabled.value = state == BluetoothAdapter.STATE_ON
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stopScan()
            BluetoothAdapter.STATE_ON -> {
                if (a2dp == null) adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
                addBondedSpeakers()
            }
        }
    }

    private fun onBondStateChanged(intent: Intent) {
        val device = intent.device() ?: return
        refresh(device)
        if (device.address != pendingConnect) return
        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
            BluetoothDevice.BOND_BONDED -> {
                pendingConnect = null
                connectAudio(device)
            }
            BluetoothDevice.BOND_NONE -> {
                pendingConnect = null
                _status.value = Status.PairFailed(displayName(device))
            }
        }
    }

    private fun onA2dpStateChanged(intent: Intent) {
        val device = intent.device() ?: return
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        val wasConnected = _devices.value[device.address]?.connected == true
        setConnected(device, state == BluetoothProfile.STATE_CONNECTED)
        when {
            state == BluetoothProfile.STATE_CONNECTED -> _status.value = Status.Connected(displayName(device))
            state == BluetoothProfile.STATE_DISCONNECTED && wasConnected ->
                _status.value = Status.Disconnected(displayName(device))
        }
    }

    /** Paired speakers that aren't in pairing mode won't show up in discovery, but can still be connected. */
    private fun addBondedSpeakers() {
        adapter?.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
            .forEach { upsert(it, it.name, rssi = null, audioClass = true) }
    }

    private fun setConnected(device: BluetoothDevice, connected: Boolean) {
        upsert(device, device.name, rssi = null, audioClass = connected)
        _devices.update { map ->
            map[device.address]?.let { map + (device.address to it.copy(connected = connected)) } ?: map
        }
    }

    private fun refresh(device: BluetoothDevice) {
        if (device.address in _devices.value) upsert(device, device.name, rssi = null)
    }

    private fun upsert(
        device: BluetoothDevice,
        name: String?,
        rssi: Int?,
        classic: Boolean = false,
        ble: Boolean = false,
        longRange: Boolean = false,
        audioClass: Boolean = false,
    ) {
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        _devices.update { map ->
            val old = map[device.address]
            val resolvedName = name?.takeIf { it.isNotBlank() } ?: old?.name
            map + (device.address to FoundDevice(
                address = device.address,
                name = resolvedName,
                rssi = rssi ?: old?.rssi,
                seenClassic = classic || old?.seenClassic == true,
                seenBle = ble || old?.seenBle == true,
                longRange = longRange || old?.longRange == true,
                isAudio = audioClass || old?.isAudio == true || looksLikeSpeaker(resolvedName),
                bonded = bonded,
                connected = old?.connected == true,
            ))
        }
    }

    private fun displayName(device: BluetoothDevice): String =
        _devices.value[device.address]?.name ?: device.name ?: device.address

    private fun leScanSettings(adapter: BluetoothAdapter): ScanSettings =
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                // Extended scanning also picks up Bluetooth 5 long-range (Coded PHY) advertisements.
                if (adapter.isLeExtendedAdvertisingSupported) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()

    private fun Intent.device(): BluetoothDevice? =
        IntentCompat.getParcelableExtra(this, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    companion object {
        private const val TAG = "BluetoothController"
        private const val SCAN_DURATION_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val AUTO_CONNECT_GRACE_MS = 6_000L

        // Name hints for speakers whose Bluetooth class isn't known (e.g. found only over BLE).
        // Deliberately excludes brands that also make phones, to keep phones out of the list.
        private val SPEAKER_BRANDS = listOf(
            "jbl", "harman", "bose", "srs-", "ult field", "ult wear", "ultimate ears", "boom", "marshall",
            "soundcore", "anker", "beats", "tribit", "beosound", "beoplay", "sonos", "mi speaker",
            "xboom", "speaker", "soundlink", "partybox",
        )

        fun looksLikeSpeaker(name: String?): Boolean {
            val lower = name?.lowercase() ?: return false
            return SPEAKER_BRANDS.any { lower.contains(it) }
        }
    }
}
