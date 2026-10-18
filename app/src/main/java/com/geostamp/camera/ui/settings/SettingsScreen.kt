package com.geostamp.camera.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geostamp.camera.model.*
import com.geostamp.camera.ui.components.StampOverlayView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onShowPrivacy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val stampSettings by viewModel.stampSettings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val sampleData by viewModel.sampleStampData.collectAsStateWithLifecycle()

    var showBatteryDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings & Templates", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Live Template Preview Card
            Text(
                text = "Live Stamp Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF19202A))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            ) {
                // Background simulated camera photo pattern
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF263238))
                )
                // Live preview using current settings
                StampOverlayView(
                    stampData = sampleData,
                    stampSettings = stampSettings,
                    appSettings = appSettings,
                    isPreviewInSettings = true
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Template Style Picker
            Text(
                text = "Stamp Template",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StampTemplate.entries.forEach { template ->
                    val isSelected = stampSettings.template == template
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateTemplate(template) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { viewModel.updateTemplate(template) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = template.displayName,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = template.description,
                                    fontSize = 12.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Stamp Position
            Text(text = "Stamp Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StampPosition.entries.forEach { pos ->
                    val isSelected = stampSettings.position == pos
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.updatePosition(pos) },
                        label = { Text(pos.displayName) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Appearance & Sliders
            Text(text = "Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // Text Size Scale
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Text Size Scale", style = MaterialTheme.typography.bodyMedium)
                Text(String.format("%.1fx", stampSettings.textSizeScale), fontWeight = FontWeight.Bold)
            }
            Slider(
                value = stampSettings.textSizeScale,
                onValueChange = { viewModel.updateTextSizeScale(it) },
                valueRange = 0.7f..1.4f,
                steps = 6
            )

            // Background Opacity
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Background Opacity", style = MaterialTheme.typography.bodyMedium)
                Text("${(stampSettings.backgroundOpacity * 100).toInt()}%", fontWeight = FontWeight.Bold)
            }
            Slider(
                value = stampSettings.backgroundOpacity,
                onValueChange = { viewModel.updateBackgroundOpacity(it) },
                valueRange = 0.0f..0.95f
            )

            // Text Color Options
            Text("Text Color", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val colors = listOf(
                    android.graphics.Color.WHITE to "White",
                    android.graphics.Color.rgb(255, 209, 102) to "Gold",
                    android.graphics.Color.rgb(0, 229, 255) to "Cyan",
                    android.graphics.Color.rgb(6, 214, 160) to "Mint"
                )
                colors.forEach { (colorInt, name) ->
                    val isSelected = stampSettings.textColor == colorInt
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(colorInt))
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape
                            )
                            .clickable { viewModel.updateTextColor(colorInt) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Field Visibility Toggles
            Text(text = "Stamp Fields", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            SettingSwitchRow("Map Thumbnail", stampSettings.showMap) { viewModel.toggleField("map", it) }
            if (stampSettings.showMap) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MapStyle.entries.forEach { style ->
                        FilterChip(
                            selected = stampSettings.mapStyle == style,
                            onClick = { viewModel.updateMapStyle(style) },
                            label = { Text(style.displayName) }
                        )
                    }
                }
            }
            SettingSwitchRow("Full Address", stampSettings.showAddress) { viewModel.toggleField("address", it) }
            SettingSwitchRow("GPS Coordinates", stampSettings.showCoordinates) { viewModel.toggleField("coordinates", it) }
            SettingSwitchRow("Altitude", stampSettings.showAltitude) { viewModel.toggleField("altitude", it) }
            SettingSwitchRow("GPS Accuracy", stampSettings.showAccuracy) { viewModel.toggleField("accuracy", it) }
            SettingSwitchRow("Compass Direction", stampSettings.showCompass) { viewModel.toggleField("compass", it) }
            SettingSwitchRow("Date & Time", stampSettings.showDateTime) { viewModel.toggleField("datetime", it) }
            SettingSwitchRow("Weather & Temperature", stampSettings.showWeather) { viewModel.toggleField("weather", it) }
            SettingSwitchRow("Custom Title / Note", stampSettings.showNote) { viewModel.toggleField("note", it) }

            Spacer(modifier = Modifier.height(24.dp))

            // Formatting & Units
            Text(text = "Formatting & Units", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // Coordinate format
            Text("Coordinates Format", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CoordinateFormat.entries.forEach { format ->
                    FilterChip(
                        selected = stampSettings.coordinateFormat == format,
                        onClick = { viewModel.updateCoordinateFormat(format) },
                        label = { Text(format.displayName) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Unit System
            Text("Measurement Units", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitSystem.entries.forEach { units ->
                    FilterChip(
                        selected = appSettings.unitSystem == units,
                        onClick = { viewModel.updateUnitSystem(units) },
                        label = { Text(units.displayName) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 24 Hour Toggle
            SettingSwitchRow("24-Hour Time Format", stampSettings.is24Hour) { viewModel.updateIs24Hour(it) }

            Spacer(modifier = Modifier.height(24.dp))

            // Camera & Save Options
            Text(text = "Camera & Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Text("JPEG Quality", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureQuality.entries.forEach { q ->
                    FilterChip(
                        selected = appSettings.captureQuality == q,
                        onClick = { viewModel.updateCaptureQuality(q) },
                        label = { Text(q.displayName) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Text("Photo Color Profile", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorProfile.entries.forEach { cp ->
                    FilterChip(
                        selected = appSettings.colorProfile == cp,
                        onClick = { viewModel.updateColorProfile(cp) },
                        label = { Text(cp.displayName) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            SettingSwitchRow("Prioritize Shutter Speed over Quality", appSettings.captureModeLatency) {
                viewModel.updateCaptureLatencyMode(it)
            }
            SettingSwitchRow("Shutter Sound", appSettings.playShutterSound) { viewModel.toggleShutterSound(it) }
            SettingSwitchRow("Save Unstamped Original Copy", appSettings.saveUnstampedOriginal) { viewModel.toggleSaveUnstamped(it) }
            SettingSwitchRow("Mirror Selfie (Front Camera)", appSettings.mirrorSelfie) { viewModel.toggleMirrorSelfie(it) }

            Spacer(modifier = Modifier.height(24.dp))

            // OEM Optimization & Privacy
            Text(text = "Device & Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showBatteryDialog = true }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("OxygenOS / Nothing OS Battery Tip", fontWeight = FontWeight.SemiBold)
                        Text("Set battery to Unrestricted for fast, reliable GPS fixes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowPrivacy() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Privacy Policy & Zero Trackers", fontWeight = FontWeight.SemiBold)
                        Text("Photos and locations remain 100% on device", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Attributions
            Text(
                text = "Attribution & Open Source",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• Map data © OpenStreetMap contributors (ODbL)\n• Weather forecasts powered by Open-Meteo (CC BY 4.0)\n• CameraX & Jetpack Compose by Google",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Battery Tip Dialog
        if (showBatteryDialog) {
            AlertDialog(
                onDismissRequest = { showBatteryDialog = false },
                title = { Text("OxygenOS / Nothing OS Battery Tip", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "On OxygenOS (OnePlus) and Nothing OS, aggressive background management can suspend GPS updates when switching between apps.\n\n" +
                        "For the fastest GPS locking:\n" +
                        "1. Open App Info for GeoStamp Camera\n" +
                        "2. Tap 'Battery usage' or 'App battery usage'\n" +
                        "3. Change from 'Optimized' to 'Unrestricted'"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showBatteryDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }) {
                        Text("Open App Info")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatteryDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
