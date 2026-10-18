package com.geostamp.camera.ui.camera

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import com.geostamp.camera.model.AspectRatioChoice
import com.geostamp.camera.model.TimerOption
import com.geostamp.camera.ui.components.*
import kotlinx.coroutines.delay

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    hasCameraPermission: Boolean = true,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val stampData by viewModel.stampData.collectAsStateWithLifecycle()
    val stampSettings by viewModel.stampSettings.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val locationState by viewModel.locationState.collectAsStateWithLifecycle()
    val isBackCamera by viewModel.isBackCamera.collectAsStateWithLifecycle()
    val flashMode by viewModel.flashMode.collectAsStateWithLifecycle()
    val isCapturing by viewModel.isCapturing.collectAsStateWithLifecycle()
    val countdownSeconds by viewModel.countdownSeconds.collectAsStateWithLifecycle()
    val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()
    val showLocationDialog by viewModel.showLocationDisabledDialog.collectAsStateWithLifecycle()
    val latestPhoto by viewModel.latestPhoto.collectAsStateWithLifecycle()

    var focusOffset by remember { mutableStateOf(Offset.Zero) }
    var showFocusRing by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }

    // Toast listener
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // Hide focus ring after 1.5 seconds
    LaunchedEffect(showFocusRing) {
        if (showFocusRing) {
            delay(1500)
            showFocusRing = false
        }
    }

    val cameraSelector = if (isBackCamera) {
        CameraSelector.DEFAULT_BACK_CAMERA
    } else {
        CameraSelector.DEFAULT_FRONT_CAMERA
    }

    val captureMode = if (appSettings.captureModeLatency) {
        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
    } else {
        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
    }

    val imageCapture = remember(captureMode) {
        ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setFlashMode(flashMode)
            .build()
    }

    LaunchedEffect(flashMode) {
        imageCapture.flashMode = flashMode
    }

    // Aspect ratio container box
    val ratioModifier = when (appSettings.aspectRatio) {
        AspectRatioChoice.RATIO_4_3 -> Modifier.fillMaxWidth().aspectRatio(3f / 4f)
        AspectRatioChoice.RATIO_16_9 -> Modifier.fillMaxSize()
        AspectRatioChoice.RATIO_1_1 -> Modifier.fillMaxWidth().aspectRatio(1f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Viewfinder Area centered
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(modifier = ratioModifier) {
                if (hasCameraPermission) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        cameraSelector = cameraSelector,
                        imageCapture = imageCapture,
                        onTapToFocus = { offset ->
                            focusOffset = offset
                            showFocusRing = true
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }

                // Rule-of-thirds Grid Overlay
                if (appSettings.showGrid) {
                    GridOverlay()
                }

                // Live WYSIWYG Stamp Overlay
                StampOverlayView(
                    stampData = stampData,
                    stampSettings = stampSettings,
                    appSettings = appSettings
                )

                // Tap-to-focus ring
                FocusIndicator(
                    focusPosition = focusOffset,
                    visible = showFocusRing
                )

                // Countdown Timer overlay
                CountdownOverlay(secondsRemaining = countdownSeconds)
            }
        }

        // Top Control Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Action Row: Flash, Ratio, Timer, Grid, Note, Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF14171E).copy(alpha = 0.75f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flash Toggle
                IconButton(onClick = { viewModel.cycleFlashMode() }) {
                    val (flashIcon, flashColor) = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn to Color(0xFFFFD166)
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto to Color.White
                        else -> Icons.Default.FlashOff to Color.White.copy(alpha = 0.6f)
                    }
                    Icon(imageVector = flashIcon, contentDescription = "Flash", tint = flashColor)
                }

                // Aspect Ratio Toggle
                IconButton(onClick = { viewModel.cycleAspectRatio() }) {
                    Text(
                        text = appSettings.aspectRatio.displayName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Timer Toggle
                IconButton(onClick = { viewModel.cycleTimer() }) {
                    val (timerText, timerColor) = when (appSettings.timerOption) {
                        TimerOption.SEC_3 -> "3s" to Color(0xFFFFD166)
                        TimerOption.SEC_10 -> "10s" to Color(0xFFFFD166)
                        else -> "Off" to Color.White.copy(alpha = 0.6f)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Timer, contentDescription = "Timer", tint = timerColor, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(text = timerText, color = timerColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Grid Toggle
                IconButton(onClick = { viewModel.toggleGrid() }) {
                    Icon(
                        imageVector = Icons.Default.GridOn,
                        contentDescription = "Grid",
                        tint = if (appSettings.showGrid) Color(0xFFFFD166) else Color.White.copy(alpha = 0.6f)
                    )
                }

                // Custom Note quick editor
                IconButton(onClick = { showNoteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = "Custom Note",
                        tint = if (stampSettings.customNote.isNotBlank()) Color(0xFFFFD166) else Color.White.copy(alpha = 0.7f)
                    )
                }

                // Settings Navigation
                IconButton(onClick = onNavigateToSettings) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // GPS Status Chip
            GpsStatusChip(
                status = locationState.status,
                accuracyMeters = locationState.accuracyMeters,
                onClick = { viewModel.onGpsChipClicked() }
            )
        }

        // Bottom Capture Bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery Thumbnail Button
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF20252E))
                    .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    .clickable { onNavigateToGallery() },
                contentAlignment = Alignment.Center
            ) {
                if (latestPhoto != null) {
                    Image(
                        painter = rememberAsyncImagePainter(latestPhoto!!.uri),
                        contentDescription = "Latest Photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Gallery",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            // Big Circular Shutter Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(enabled = !isCapturing) {
                        viewModel.triggerShutter(imageCapture)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Color(0xFF1E232B),
                        strokeWidth = 3.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.5.dp, Color(0xFF1E232B), CircleShape)
                    )
                }
            }

            // Camera Switch Button
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF20252E))
                    .border(1.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    .clickable { viewModel.toggleCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Location Services Disabled Dialog
        if (showLocationDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissLocationDialog() },
                title = { Text(text = "Enable Location Services", fontWeight = FontWeight.Bold) },
                text = { Text("GeoStamp Camera needs GPS location services enabled to stamp accurate coordinates and addresses onto your photos.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissLocationDialog()
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissLocationDialog() }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Custom Note Input Dialog
        if (showNoteDialog) {
            var noteInput by remember { mutableStateOf(stampSettings.customNote) }
            AlertDialog(
                onDismissRequest = { showNoteDialog = false },
                title = { Text(text = "Stamp Title / Note", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            text = "Add a title or note that appears on your photos (e.g. \"Site visit - Plot 14\"):",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            placeholder = { Text("Site visit - Plot 14") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setCustomNote(noteInput.trim())
                        showNoteDialog = false
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        viewModel.setCustomNote("")
                        showNoteDialog = false
                    }) {
                        Text("Clear")
                    }
                }
            )
        }
    }
}
