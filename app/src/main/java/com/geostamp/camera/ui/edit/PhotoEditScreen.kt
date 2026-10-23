package com.geostamp.camera.ui.edit

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.model.StampData
import com.geostamp.camera.model.StampSettings
import com.geostamp.camera.data.EditRecord
import com.geostamp.camera.ui.components.rememberPhotoPainter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Edit a captured photo's stamp (location, date & time, note) and re-render it from the
 * unstamped source kept in [com.geostamp.camera.data.EditStore].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditScreen(
    photo: PhotoItem,
    onBack: () -> Unit,
    onSaved: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: PhotoEditViewModel = viewModel(key = photo.displayName)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val latestOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(photo.displayName) { viewModel.load(photo.displayName) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PhotoEditEvent.Error -> snackbarHostState.showSnackbar(event.message)
                is PhotoEditEvent.Saved -> latestOnSaved(event.photo)
            }
        }
    }

    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showMapPicker by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val requestBack = {
        when {
            state.saving -> Unit
            state.dirty -> showDiscardDialog = true
            else -> onBack()
        }
    }
    BackHandler(onBack = requestBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.unavailable) "Edit photo" else "Edit stamp") },
                navigationIcon = {
                    IconButton(onClick = requestBack, enabled = !state.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.ready) {
                        TextButton(
                            onClick = { viewModel.save(photo) },
                            enabled = state.dirty && !state.saving
                        ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                        Box {
                            IconButton(onClick = { menuOpen = true }, enabled = !state.saving) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Reset to original") },
                                    leadingIcon = { Icon(Icons.Filled.Restore, contentDescription = null) },
                                    enabled = state.canResetToOriginal,
                                    onClick = {
                                        menuOpen = false
                                        viewModel.resetToOriginal()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.unavailable || !state.ready -> UnavailableContent(onBack = onBack, modifier = Modifier.padding(padding))
            else -> EditForm(
                photo = photo,
                state = state,
                record = state.record!!,
                data = state.draftData!!,
                settings = state.draftSettings!!,
                onDateClick = { showDatePicker = true },
                onTimeClick = { showTimePicker = true },
                onLocationClick = { showMapPicker = true },
                onAddressChange = viewModel::setAddressText,
                onRedetectAddress = viewModel::redetectAddress,
                onNoteChange = viewModel::setNote,
                onIncludeWeatherChange = viewModel::setIncludeWeather,
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
            )
        }
    }

    val data = state.draftData
    val settings = state.draftSettings
    val record = state.record

    if (showDatePicker && data != null) {
        StampDatePickerDialog(
            timestamp = data.timestamp,
            timeZone = data.timeZone,
            onConfirm = { viewModel.setTimestamp(it); showDatePicker = false },
            onDismiss = { showDatePicker = false }
        )
    }
    if (showTimePicker && data != null && settings != null) {
        StampTimePickerDialog(
            timestamp = data.timestamp,
            timeZone = data.timeZone,
            is24Hour = settings.is24Hour,
            onConfirm = { viewModel.setTimestamp(it); showTimePicker = false },
            onDismiss = { showTimePicker = false }
        )
    }
    if (showMapPicker && data != null && settings != null && record != null) {
        MapPickerDialog(
            initialLat = data.latitude,
            initialLng = data.longitude,
            originalLat = record.originalLatitude,
            originalLng = record.originalLongitude,
            mapStyle = settings.mapStyle,
            onSearch = viewModel::search,
            onConfirm = { lat, lng ->
                showMapPicker = false
                viewModel.setLocation(lat, lng)
            },
            onDismiss = { showMapPicker = false }
        )
    }
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this photo's stamp haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Keep editing") }
            }
        )
    }
    if (state.saving) {
        // Blocking: overwriting the photo must not race with further edits or navigation.
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
                Row(
                    Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(20.dp))
                    Text("Saving…", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun UnavailableContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("This photo can't be edited", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Its unstamped original isn't available on this device, so the stamp can't be redrawn cleanly.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onBack) { Text("Go back") }
    }
}

@Composable
private fun EditForm(
    photo: PhotoItem,
    state: PhotoEditUiState,
    record: EditRecord,
    data: StampData,
    settings: StampSettings,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onLocationClick: () -> Unit,
    onAddressChange: (String) -> Unit,
    onRedetectAddress: () -> Unit,
    onNoteChange: (String) -> Unit,
    onIncludeWeatherChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = Locale.getDefault()
    val dateText = remember(data.timestamp, data.timeZone) {
        SimpleDateFormat("EEE, d MMM yyyy", locale).apply { timeZone = data.timeZone }.format(Date(data.timestamp))
    }
    val timeText = remember(data.timestamp, data.timeZone, settings.is24Hour) {
        val pattern = if (settings.is24Hour) "HH:mm:ss" else "h:mm:ss a"
        SimpleDateFormat(pattern, locale).apply { timeZone = data.timeZone }.format(Date(data.timestamp))
    }
    val zoneLabel = remember(data.timestamp, data.timeZone) {
        SimpleDateFormat("zzzz", locale).apply { timeZone = data.timeZone }.format(Date(data.timestamp))
    }
    val zoneDiffersFromDevice = remember(data.timeZone) {
        data.timeZone.rawOffset != TimeZone.getDefault().rawOffset || data.timeZone.id != TimeZone.getDefault().id
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        PreviewCard(photo = photo, preview = state.preview, rendering = state.rendering)

        SectionLabel("When")
        FormGroup {
            FormRow(
                icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                label = "Date",
                value = dateText,
                onClick = onDateClick
            )
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
            FormRow(
                icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                label = "Time",
                value = timeText,
                onClick = onTimeClick
            )
        }
        Text(
            if (zoneDiffersFromDevice) "Shown in the photo's time zone: $zoneLabel" else zoneLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp)
        )

        SectionLabel("Where")
        FormGroup {
            LocationRow(data = data, mapLoading = state.mapLoading, onClick = onLocationClick)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.addressText,
                    onValueChange = onAddressChange,
                    label = { Text("Address") },
                    placeholder = { Text(if (state.geocoding) "Looking up address…" else "Street, city, country") },
                    enabled = data.latitude != null || state.addressText.isNotEmpty(),
                    trailingIcon = {
                        when {
                            state.geocoding -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            data.latitude != null -> IconButton(onClick = onRedetectAddress) {
                                Icon(Icons.Filled.MyLocation, contentDescription = "Detect address from location")
                            }
                        }
                    },
                    supportingText = {
                        when {
                            state.addressLookupFailed -> Text("Couldn't find an address here — type one if you like")
                            !settings.showAddress -> Text("Address is hidden in this photo's stamp layout")
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SectionLabel("Note")
        FormGroup {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = settings.customNote,
                    onValueChange = onNoteChange,
                    placeholder = { Text("Add a note to the stamp") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SectionLabel("Weather")
        FormGroup {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Thermostat, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val weatherText = data.formattedWeather(record.unitSystem)
                    Text(
                        when {
                            state.weatherLoading -> "Fetching weather…"
                            weatherText.isNotEmpty() -> weatherText
                            data.latitude == null -> "Needs a location"
                            else -> "Unavailable for this time & place"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Include weather",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.weatherLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Switch(checked = settings.showWeather, onCheckedChange = onIncludeWeatherChange)
            }
        }
    }
}

@Composable
private fun PreviewCard(photo: PhotoItem, preview: Bitmap?, rendering: Boolean) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    val ratio = when {
        preview != null -> preview.width.toFloat() / preview.height
        photo.width > 0 && photo.height > 0 -> photo.width.toFloat() / photo.height
        else -> 3f / 4f
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val height = (maxWidth / ratio).coerceAtMost(maxHeight)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(20.dp))
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                    }
                    .pointerInput(Unit) {
                        // Hand-rolled so single-finger drags still scroll the form unless the preview is zoomed.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2 || scale > 1f) {
                                    val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                    val maxX = boxSize.width * (newScale - 1f) / 2f
                                    val maxY = boxSize.height * (newScale - 1f) / 2f
                                    val pan = event.calculatePan()
                                    scale = newScale
                                    offset = if (newScale <= 1f) Offset.Zero else Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val layer = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                if (preview != null) {
                    // remember: asImageBitmap wraps cheaply, but avoid re-wrapping on every recomposition.
                    val image = remember(preview) { preview.asImageBitmap() }
                    Image(image, contentDescription = "Stamp preview", contentScale = ContentScale.Fit, modifier = layer)
                } else {
                    // Until the first render lands, show the currently saved (stamped) photo.
                    Image(
                        painter = rememberPhotoPainter(photo),
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = layer
                    )
                }
                if (rendering || preview == null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(6.dp).size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationRow(data: StampData, mapLoading: Boolean, onClick: () -> Unit) {
    val hasLocation = data.latitude != null && data.longitude != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            val thumb = data.mapThumbnail
            if (thumb != null && !thumb.isRecycled) {
                val image = remember(thumb) { thumb.asImageBitmap() }
                Image(image, contentDescription = "Map", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (!hasLocation) {
                Icon(Icons.Filled.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (mapLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (hasLocation) formatCoordinates(data.latitude!!, data.longitude!!) else "No location",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                if (hasLocation) "Tap to move the pin" else "Tap to pick a location",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun FormGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun FormRow(icon: @Composable () -> Unit, label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// region Date / time pickers

/**
 * Material's DatePicker works in UTC-midnight millis. We translate the photo's local calendar date
 * (in its own time zone) to that representation and back, preserving the time of day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StampDatePickerDialog(
    timestamp: Long,
    timeZone: TimeZone,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialUtcMidnight = remember(timestamp, timeZone) {
        val local = Calendar.getInstance(timeZone).apply { timeInMillis = timestamp }
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMidnight)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = pickerState.selectedDateMillis ?: return@TextButton onDismiss()
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = selected }
                    val local = Calendar.getInstance(timeZone).apply {
                        timeInMillis = timestamp
                        set(Calendar.YEAR, utc.get(Calendar.YEAR))
                        set(Calendar.MONTH, utc.get(Calendar.MONTH))
                        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                    }
                    onConfirm(local.timeInMillis)
                },
                enabled = pickerState.selectedDateMillis != null
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StampTimePickerDialog(
    timestamp: Long,
    timeZone: TimeZone,
    is24Hour: Boolean,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initial = remember(timestamp, timeZone) { Calendar.getInstance(timeZone).apply { timeInMillis = timestamp } }
    val pickerState = rememberTimePickerState(
        initialHour = initial.get(Calendar.HOUR_OF_DAY),
        initialMinute = initial.get(Calendar.MINUTE),
        is24Hour = is24Hour
    )
    var useKeyboard by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 380.dp)
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Select time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                )
                if (useKeyboard) TimeInput(state = pickerState) else TimePicker(state = pickerState)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { useKeyboard = !useKeyboard }) {
                        Icon(
                            if (useKeyboard) Icons.Filled.Schedule else Icons.Filled.Keyboard,
                            contentDescription = if (useKeyboard) "Use dial" else "Type time"
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        // Keep the original seconds so a pure hour/minute change doesn't look rounded.
                        val local = Calendar.getInstance(timeZone).apply {
                            timeInMillis = timestamp
                            set(Calendar.HOUR_OF_DAY, pickerState.hour)
                            set(Calendar.MINUTE, pickerState.minute)
                        }
                        onConfirm(local.timeInMillis)
                    }) { Text("OK") }
                }
            }
        }
    }
}

// endregion
