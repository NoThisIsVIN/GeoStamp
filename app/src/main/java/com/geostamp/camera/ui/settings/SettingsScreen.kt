package com.geostamp.camera.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geostamp.camera.model.*
import com.geostamp.camera.ui.components.StampOverlayView
import java.util.Locale

private val StampTextColors = listOf(
    android.graphics.Color.WHITE to "White",
    android.graphics.Color.rgb(255, 209, 102) to "Gold",
    android.graphics.Color.rgb(0, 229, 255) to "Cyan",
    android.graphics.Color.rgb(6, 214, 160) to "Mint"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val editableBytes by viewModel.editableStorageBytes.collectAsStateWithLifecycle()
    val isClearing by viewModel.isClearingEditable.collectAsStateWithLifecycle()

    var showBatteryDialog by remember { mutableStateOf(false) }
    var showClearEditableDialog by remember { mutableStateOf(false) }

    // New captures add editable copies while we're away; re-measure whenever the screen returns.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshEditableStorage()
        onPauseOrDispose { }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Live preview
            SectionHeader("Preview")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF263238))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            ) {
                StampOverlayView(
                    stampData = sampleData,
                    stampSettings = stampSettings,
                    appSettings = appSettings,
                    isPreviewInSettings = true
                )
            }

            // Template
            SectionHeader("Stamp template")
            SettingsCard {
                StampTemplate.entries.forEachIndexed { index, template ->
                    if (index > 0) RowDivider()
                    TemplateRow(
                        template = template,
                        selected = stampSettings.template == template,
                        onSelect = { viewModel.updateTemplate(template) }
                    )
                }
            }

            // Appearance
            SectionHeader("Appearance")
            SettingsCard {
                ChipGroup(title = "Position") {
                    StampPosition.entries.forEach { pos ->
                        SettingChip(
                            label = pos.displayName,
                            selected = stampSettings.position == pos,
                            onClick = { viewModel.updatePosition(pos) }
                        )
                    }
                }
                RowDivider()
                SliderRow(
                    title = "Text size",
                    valueLabel = String.format(Locale.getDefault(), "%.1f×", stampSettings.textSizeScale),
                    value = stampSettings.textSizeScale,
                    onValueChange = { viewModel.updateTextSizeScale(it) },
                    valueRange = 0.7f..1.4f,
                    steps = 6
                )
                RowDivider()
                SliderRow(
                    title = "Background opacity",
                    valueLabel = "${(stampSettings.backgroundOpacity * 100).toInt()}%",
                    value = stampSettings.backgroundOpacity,
                    onValueChange = { viewModel.updateBackgroundOpacity(it) },
                    valueRange = 0.0f..0.95f
                )
                RowDivider()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Text color", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StampTextColors.forEach { (colorInt, name) ->
                            ColorSwatch(
                                color = Color(colorInt),
                                name = name,
                                selected = stampSettings.textColor == colorInt,
                                onClick = { viewModel.updateTextColor(colorInt) }
                            )
                        }
                    }
                }
            }

            // Fields
            SectionHeader("Stamp fields")
            SettingsCard {
                SwitchRow("Map thumbnail", stampSettings.showMap) { viewModel.toggleField("map", it) }
                if (stampSettings.showMap) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MapStyle.entries.forEach { style ->
                            SettingChip(
                                label = style.displayName,
                                selected = stampSettings.mapStyle == style,
                                onClick = { viewModel.updateMapStyle(style) }
                            )
                        }
                    }
                }
                RowDivider()
                SwitchRow("Full address", stampSettings.showAddress) { viewModel.toggleField("address", it) }
                RowDivider()
                SwitchRow("GPS coordinates", stampSettings.showCoordinates) { viewModel.toggleField("coordinates", it) }
                RowDivider()
                SwitchRow("Altitude", stampSettings.showAltitude) { viewModel.toggleField("altitude", it) }
                RowDivider()
                SwitchRow("GPS accuracy", stampSettings.showAccuracy) { viewModel.toggleField("accuracy", it) }
                RowDivider()
                SwitchRow("Compass direction", stampSettings.showCompass) { viewModel.toggleField("compass", it) }
                RowDivider()
                SwitchRow("Date & time", stampSettings.showDateTime) { viewModel.toggleField("datetime", it) }
                RowDivider()
                SwitchRow("Weather & temperature", stampSettings.showWeather) { viewModel.toggleField("weather", it) }
                RowDivider()
                SwitchRow("Custom title / note", stampSettings.showNote) { viewModel.toggleField("note", it) }
            }

            // Formatting
            SectionHeader("Formatting & units")
            SettingsCard {
                ChipGroup(title = "Coordinate format") {
                    CoordinateFormat.entries.forEach { format ->
                        SettingChip(
                            label = format.displayName,
                            selected = stampSettings.coordinateFormat == format,
                            onClick = { viewModel.updateCoordinateFormat(format) }
                        )
                    }
                }
                RowDivider()
                ChipGroup(title = "Measurement units") {
                    UnitSystem.entries.forEach { units ->
                        SettingChip(
                            label = units.displayName,
                            selected = appSettings.unitSystem == units,
                            onClick = { viewModel.updateUnitSystem(units) }
                        )
                    }
                }
                RowDivider()
                SwitchRow("24-hour time", stampSettings.is24Hour) { viewModel.updateIs24Hour(it) }
            }

            // Camera
            SectionHeader("Camera")
            SettingsCard {
                ChipGroup(title = "JPEG quality") {
                    CaptureQuality.entries.forEach { q ->
                        SettingChip(
                            label = q.displayName,
                            selected = appSettings.captureQuality == q,
                            onClick = { viewModel.updateCaptureQuality(q) }
                        )
                    }
                }
                RowDivider()
                ChipGroup(title = "Color profile") {
                    ColorProfile.entries.forEach { cp ->
                        SettingChip(
                            label = cp.displayName,
                            selected = appSettings.colorProfile == cp,
                            onClick = { viewModel.updateColorProfile(cp) }
                        )
                    }
                }
                RowDivider()
                SwitchRow(
                    title = "Prioritize shutter speed",
                    subtitle = "Faster capture at slightly lower image quality",
                    checked = appSettings.captureModeLatency
                ) { viewModel.updateCaptureLatencyMode(it) }
                RowDivider()
                SwitchRow("Shutter sound", appSettings.playShutterSound) { viewModel.toggleShutterSound(it) }
                RowDivider()
                SwitchRow("Mirror front camera", appSettings.mirrorSelfie) { viewModel.toggleMirrorSelfie(it) }
            }

            // Storage & editing
            SectionHeader("Storage & editing")
            SettingsCard {
                SwitchRow(
                    title = "Keep editable copies",
                    subtitle = "Lets you change location, date & time later. Uses extra storage.",
                    checked = appSettings.keepEditableCopy
                ) { viewModel.toggleKeepEditableCopy(it) }
                RowDivider()
                val bytes = editableBytes
                ActionRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = "Clear editable copies",
                    subtitle = when {
                        isClearing -> "Clearing…"
                        bytes == null -> "Calculating…"
                        bytes == 0L -> "Not using any storage"
                        else -> "Using ${Formatter.formatShortFileSize(context, bytes)}"
                    },
                    enabled = !isClearing && (bytes ?: 0L) > 0L,
                    onClick = { showClearEditableDialog = true }
                )
                RowDivider()
                SwitchRow(
                    title = "Save unstamped original",
                    subtitle = "Also saves a copy without the stamp to your gallery",
                    checked = appSettings.saveUnstampedOriginal
                ) { viewModel.toggleSaveUnstamped(it) }
            }

            // Device & privacy
            SectionHeader("Device & privacy")
            SettingsCard {
                ActionRow(
                    icon = Icons.Outlined.BatteryAlert,
                    title = "Battery tip for OxygenOS / Nothing OS",
                    subtitle = "Set battery to Unrestricted for fast, reliable GPS fixes",
                    onClick = { showBatteryDialog = true },
                    showChevron = true
                )
                RowDivider()
                ActionRow(
                    icon = Icons.Outlined.Security,
                    title = "Privacy policy",
                    subtitle = "Zero trackers. Photos and locations stay on your device.",
                    onClick = onShowPrivacy,
                    showChevron = true
                )
            }

            // Attributions
            SectionHeader("About")
            Text(
                text = "Map data © OpenStreetMap contributors (ODbL)\n" +
                    "Weather by Open-Meteo (CC BY 4.0)\n" +
                    "Built with CameraX & Jetpack Compose",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        if (showClearEditableDialog) {
            AlertDialog(
                onDismissRequest = { showClearEditableDialog = false },
                icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                title = { Text("Clear editable copies?") },
                text = {
                    Text(
                        "Your photos stay in your gallery, but you won't be able to change the location, " +
                            "date or time of photos taken so far."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showClearEditableDialog = false
                        viewModel.clearEditableCopies()
                    }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearEditableDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (showBatteryDialog) {
            AlertDialog(
                onDismissRequest = { showBatteryDialog = false },
                icon = { Icon(Icons.Outlined.BatteryAlert, contentDescription = null) },
                title = { Text("OxygenOS / Nothing OS battery tip") },
                text = {
                    Text(
                        "On OxygenOS (OnePlus) and Nothing OS, aggressive background management can suspend GPS updates when switching between apps.\n\n" +
                            "For the fastest GPS locking:\n" +
                            "1. Open App info for GeoStamp Camera\n" +
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
                        Text("Open App info")
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
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        // The whole row handles the toggle, so the switch itself is display-only.
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    showChevron: Boolean = false
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(title: String, chips: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        // FlowRow so long localized labels wrap instead of running off narrow screens.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
    }
}

@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null
    )
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun TemplateRow(template: StampTemplate, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = template.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = template.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            // Swatches are all light colors, so a dark check reads on every one.
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF1A202C), modifier = Modifier.size(18.dp))
        }
    }
}
