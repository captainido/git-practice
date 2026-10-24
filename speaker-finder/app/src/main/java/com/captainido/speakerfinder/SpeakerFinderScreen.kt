package com.captainido.speakerfinder

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.pow

private val JblOrange = Color(0xFFFF6200)

@Composable
fun SpeakerFinderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = JblOrange)
        else -> lightColorScheme(primary = JblOrange)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeakerFinderScreen(
    controller: BluetoothController,
    permissionsGranted: Boolean,
    locationEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val devices by controller.devices.collectAsStateWithLifecycle()
    val scanning by controller.scanning.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val enabled by controller.enabled.collectAsStateWithLifecycle()
    var speakersOnly by rememberSaveable { mutableStateOf(true) }

    val shown = remember(devices, speakersOnly) {
        devices.values
            .filter { !speakersOnly || it.isAudio }
            .sortedWith(
                compareByDescending<FoundDevice> { it.connected }
                    .thenByDescending { it.rssi ?: Int.MIN_VALUE },
            )
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !controller.isSupported -> Message(stringResource(R.string.no_bt))
                !permissionsGranted -> Message(
                    text = stringResource(R.string.permissions_needed),
                    action = stringResource(R.string.grant_permissions),
                    onAction = onRequestPermissions,
                )
                !enabled -> Message(
                    text = stringResource(R.string.bt_off),
                    action = stringResource(R.string.enable_bt),
                    onAction = onEnableBluetooth,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { CapabilitiesCard(controller) }
                    if (!locationEnabled) {
                        item { Text(stringResource(R.string.location_off), color = MaterialTheme.colorScheme.error) }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { if (scanning) controller.stopScan() else controller.startScan() }) {
                                Text(stringResource(if (scanning) R.string.scan_stop else R.string.scan_start))
                            }
                            Spacer(Modifier.width(12.dp))
                            FilterChip(
                                selected = speakersOnly,
                                onClick = { speakersOnly = !speakersOnly },
                                label = { Text(stringResource(R.string.speakers_only)) },
                            )
                            Spacer(Modifier.weight(1f))
                            if (scanning) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        }
                    }
                    status?.let { s ->
                        item { StatusCard(s, onOpenBluetoothSettings) }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                stringResource(if (scanning) R.string.no_devices else R.string.idle),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(shown, key = { it.address }) { device ->
                        DeviceCard(
                            device = device,
                            onConnect = { controller.connect(device.address) },
                            onDisconnect = {
                                if (!controller.disconnect(device.address)) onOpenBluetoothSettings()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun CapabilitiesCard(controller: BluetoothController) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.caps_title), fontWeight = FontWeight.Bold)
            CapabilityRow(stringResource(R.string.cap_coded), controller.supportsLongRange)
            CapabilityRow(stringResource(R.string.cap_ext_adv), controller.supportsExtendedAdvertising)
            Text(stringResource(R.string.range_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CapabilityRow(label: String, supported: Boolean) {
    Row {
        Text(label, Modifier.weight(1f))
        Text(stringResource(if (supported) R.string.yes else R.string.no), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusCard(status: BluetoothController.Status, onOpenBluetoothSettings: () -> Unit) {
    val (text, showSettings) = when (status) {
        is BluetoothController.Status.Pairing -> stringResource(R.string.status_pairing, status.name) to false
        is BluetoothController.Status.Connecting -> stringResource(R.string.status_connecting, status.name) to false
        is BluetoothController.Status.Connected -> stringResource(R.string.status_connected, status.name) to false
        is BluetoothController.Status.PairFailed -> stringResource(R.string.status_pair_failed, status.name) to true
        is BluetoothController.Status.PairedOnly -> stringResource(R.string.status_paired_only, status.name) to true
        is BluetoothController.Status.Disconnected -> stringResource(R.string.status_disconnected, status.name) to false
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(text)
            if (showSettings) {
                TextButton(onClick = onOpenBluetoothSettings) { Text(stringResource(R.string.open_bt_settings)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(device: FoundDevice, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name ?: stringResource(R.string.unknown_device),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
                device.rssi?.let { SignalBars(it) }
            }
            device.rssi?.let { rssi ->
                Text(
                    "$rssi dBm · " + stringResource(R.string.distance_estimate, formatMeters(estimateDistanceMeters(rssi))),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (device.isAudio) Tag(stringResource(R.string.tag_audio))
                if (device.seenClassic) Tag(stringResource(R.string.tag_classic))
                if (device.seenBle) Tag(stringResource(R.string.tag_ble))
                if (device.longRange) Tag(stringResource(R.string.tag_long_range))
                if (device.bonded) Tag(stringResource(R.string.tag_paired))
            }
            if (device.connected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.connected),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                }
            } else {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    AssistChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) })
}

@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (bar in 1..4) {
            Box(
                Modifier
                    .width(5.dp)
                    .height((6 * bar).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (bar <= level) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

/**
 * Log-distance path-loss model. Real distance varies a lot with walls, bodies and the speaker's
 * transmit power, so the UI always labels this as an estimate.
 */
internal fun estimateDistanceMeters(rssi: Int, rssiAtOneMeter: Int = -59, pathLossExponent: Double = 2.5): Double =
    10.0.pow((rssiAtOneMeter - rssi) / (10 * pathLossExponent))

private fun formatMeters(meters: Double): String =
    if (meters < 10) String.format(Locale.US, "%.1f", meters) else meters.toInt().toString()
