package com.geostamp.camera.ui.camera

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geostamp.camera.MainActivity
import com.geostamp.camera.model.AspectRatioChoice
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.model.TimerOption
import com.geostamp.camera.ui.components.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFFFFD166)
private val ControlSurface = Color(0xFF14171E).copy(alpha = 0.6f)
private val ControlBorder = Color.White.copy(alpha = 0.08f)
private val InactiveTint = Color.White.copy(alpha = 0.65f)

// Space reserved for the overlaid controls when placing the viewfinder (see viewfinderTop).
private val TopBarHeight = 64.dp
private val TopBarWithChipHeight = 108.dp
private val BottomControlsHeight = 176.dp

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    hasCameraPermission: Boolean = true,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

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
    val pendingSaves by viewModel.pendingSaves.collectAsStateWithLifecycle()
    val captureThumbnail by viewModel.captureThumbnail.collectAsStateWithLifecycle()

    var focusOffset by remember { mutableStateOf(Offset.Zero) }
    var showFocusRing by remember { mutableStateOf(false) }
    var focusTapId by remember { mutableIntStateOf(0) }
    var showNoteDialog by remember { mutableStateOf(false) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomState by remember { mutableStateOf<ZoomState?>(null) }

    // Toast listener
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // Hide focus ring after 1.5 seconds (restarts on every tap)
    LaunchedEffect(showFocusRing, focusTapId) {
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

    // 1:1 is cropped from the 4:3 stream by ImageStamper, so it shares the 4:3 sensor mode.
    val aspectRatioStrategy = when (appSettings.aspectRatio) {
        AspectRatioChoice.RATIO_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        AspectRatioChoice.RATIO_4_3, AspectRatioChoice.RATIO_1_1 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    }

    val imageCapture = remember(captureMode, aspectRatioStrategy) {
        ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setFlashMode(flashMode)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectRatioStrategy)
                    .build()
            )
            .build()
    }

    LaunchedEffect(flashMode, imageCapture) {
        imageCapture.flashMode = flashMode
    }

    // --- Device orientation ---------------------------------------------------------------------
    // The activity handles orientation config changes itself, so ImageCapture is never rebuilt on
    // rotation: track the physical orientation and push it into targetRotation so the JPEG's
    // orientation matches how the phone was held.
    val initialRotation = remember { view.display?.rotation ?: Surface.ROTATION_0 }
    var deviceRotation by remember { mutableIntStateOf(initialRotation) }
    var displayRotation by remember { mutableIntStateOf(initialRotation) }

    DisposableEffect(context, view) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val candidate = orientationToSurfaceRotation(orientation)
                // Hysteresis: require 10° past the bucket edge so holding the phone near 45° doesn't flicker.
                if (candidate != deviceRotation &&
                    angularDistance(orientation, surfaceRotationCenter(deviceRotation)) > 55
                ) {
                    deviceRotation = candidate
                }
                displayRotation = view.display?.rotation ?: Surface.ROTATION_0
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(imageCapture, deviceRotation) {
        imageCapture.targetRotation = deviceRotation
    }

    // Icons counter-rotate to stay upright. Subtracting the display rotation keeps them still when
    // the whole UI has already rotated with the device (auto-rotate on).
    val iconRotation by rememberIconRotation(
        (surfaceRotationDegrees(deviceRotation) - surfaceRotationDegrees(displayRotation)).toFloat()
    )

    // --- Zoom -----------------------------------------------------------------------------------
    // compose-runtime-livedata isn't a dependency, so observe zoomState manually.
    DisposableEffect(camera, lifecycleOwner) {
        val liveZoom = camera?.cameraInfo?.zoomState
        val observer = Observer<ZoomState> { zoomState = it }
        liveZoom?.observe(lifecycleOwner, observer)
        onDispose {
            liveZoom?.removeObserver(observer)
        }
    }

    // Which lens zoom was last reset for; switching cameras starts the new lens back at 1×.
    var zoomResetForBack by remember { mutableStateOf<Boolean?>(null) }

    // --- Shutter --------------------------------------------------------------------------------
    val currentImageCapture by rememberUpdatedState(imageCapture)
    val currentCountdown by rememberUpdatedState(countdownSeconds)
    val onShutter: () -> Unit = {
        // Pressing the shutter again during the self-timer cancels it, like stock camera apps.
        if (currentCountdown > 0) viewModel.cancelCountdown() else viewModel.triggerShutter(currentImageCapture)
    }

    // Hardware volume-key shutter: requests are only collected while this screen is composed.
    if (hasCameraPermission) {
        LaunchedEffect(Unit) {
            viewModel.shutterRequests.collect { onShutter() }
        }
    }

    DisposableEffect(hasCameraPermission) {
        val activity = context.findActivity() as? MainActivity
        activity?.volumeShutterEnabled = hasCameraPermission
        onDispose { activity?.volumeShutterEnabled = false }
    }

    // --- Layout ---------------------------------------------------------------------------------
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Width / height of the viewfinder as it appears on screen (portrait holds the sensor upright).
    val viewfinderRatio = when (appSettings.aspectRatio) {
        AspectRatioChoice.RATIO_4_3 -> if (isLandscape) 4f / 3f else 3f / 4f
        AspectRatioChoice.RATIO_16_9 -> if (isLandscape) 16f / 9f else 9f / 16f
        AspectRatioChoice.RATIO_1_1 -> 1f
    }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Viewfinder: sized exactly to the capture ratio so what you frame is what gets saved.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val vfWidth: Dp
            val vfHeight: Dp
            if (maxWidth / maxHeight < viewfinderRatio) {
                vfWidth = maxWidth
                vfHeight = maxWidth / viewfinderRatio
            } else {
                vfHeight = maxHeight
                vfWidth = maxHeight * viewfinderRatio
            }
            val top = if (!isLandscape && appSettings.aspectRatio == AspectRatioChoice.RATIO_4_3) {
                viewfinderTop(maxHeight, vfHeight, statusBarTop, navBarBottom)
            } else {
                (maxHeight - vfHeight) / 2
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = top)
                    .size(vfWidth, vfHeight)
                    .clip(RoundedCornerShape(if (vfWidth < maxWidth || vfHeight < maxHeight) 4.dp else 0.dp))
            ) {
                if (hasCameraPermission) {
                    CameraPreview(
                        modifier = Modifier.fillMaxSize(),
                        cameraSelector = cameraSelector,
                        imageCapture = imageCapture,
                        aspectRatioStrategy = aspectRatioStrategy,
                        onCameraReady = { boundCam ->
                            camera = boundCam
                            if (zoomResetForBack != null && zoomResetForBack != isBackCamera) {
                                boundCam.cameraControl.setZoomRatio(1f)
                            }
                            zoomResetForBack = isBackCamera
                        },
                        onTapToFocus = { offset ->
                            focusOffset = offset
                            showFocusRing = true
                            focusTapId++
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

                // Live WYSIWYG Stamp Overlay, inside the frame so it lands where it will on the photo
                StampOverlayView(
                    stampData = stampData,
                    stampSettings = stampSettings,
                    appSettings = appSettings
                )

                // Tap-to-focus ring
                FocusIndicator(
                    focusPosition = focusOffset,
                    visible = showFocusRing,
                    tapId = focusTapId
                )

                // Exposure blink
                ShutterFlashOverlay(trigger = viewModel.captureEvents)

                // Countdown Timer overlay
                CountdownOverlay(secondsRemaining = countdownSeconds, rotation = iconRotation)
            }
        }

        // Top Control Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(ControlSurface)
                    .border(1.dp, ControlBorder, RoundedCornerShape(28.dp))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flash
                val (flashIcon, flashTint, flashLabel) = when (flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Triple(Icons.Default.FlashOn, Accent, "on")
                    ImageCapture.FLASH_MODE_AUTO -> Triple(Icons.Default.FlashAuto, Color.White, "auto")
                    else -> Triple(Icons.Default.FlashOff, InactiveTint, "off")
                }
                ControlButton(
                    contentDescription = "Flash: $flashLabel",
                    onClick = { viewModel.cycleFlashMode() },
                    rotation = iconRotation
                ) {
                    Icon(imageVector = flashIcon, contentDescription = null, tint = flashTint)
                }

                // Aspect ratio
                ControlButton(
                    contentDescription = "Aspect ratio: ${appSettings.aspectRatio.displayName}",
                    onClick = { viewModel.cycleAspectRatio() },
                    rotation = iconRotation
                ) {
                    Box(
                        modifier = Modifier
                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = appSettings.aspectRatio.displayName,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Timer
                val (timerIcon, timerTint) = when (appSettings.timerOption) {
                    TimerOption.SEC_3 -> Icons.Default.Timer3 to Accent
                    TimerOption.SEC_10 -> Icons.Default.Timer10 to Accent
                    TimerOption.OFF -> Icons.Default.TimerOff to InactiveTint
                }
                ControlButton(
                    contentDescription = "Self-timer: ${appSettings.timerOption.displayName}",
                    onClick = { viewModel.cycleTimer() },
                    rotation = iconRotation
                ) {
                    Icon(imageVector = timerIcon, contentDescription = null, tint = timerTint)
                }

                // Grid
                ControlButton(
                    contentDescription = if (appSettings.showGrid) "Grid: on" else "Grid: off",
                    onClick = { viewModel.toggleGrid() },
                    rotation = iconRotation
                ) {
                    Icon(
                        imageVector = if (appSettings.showGrid) Icons.Default.GridOn else Icons.Default.GridOff,
                        contentDescription = null,
                        tint = if (appSettings.showGrid) Accent else InactiveTint
                    )
                }

                // Custom note
                ControlButton(
                    contentDescription = "Stamp note",
                    onClick = { showNoteDialog = true },
                    rotation = iconRotation
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = null,
                        tint = if (stampSettings.customNote.isNotBlank()) Accent else InactiveTint
                    )
                }

                // Settings
                ControlButton(
                    contentDescription = "Settings",
                    onClick = onNavigateToSettings,
                    rotation = iconRotation
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            GpsStatusChip(
                status = locationState.status,
                accuracyMeters = locationState.accuracyMeters,
                onClick = { viewModel.onGpsChipClicked() }
            )
        }

        // Bottom controls: zoom selector above the capture row
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val zoom = zoomState
            if (hasCameraPermission && zoom != null) {
                ZoomSelector(
                    zoomState = zoom,
                    rotation = iconRotation,
                    onZoomSelected = { ratio -> camera?.cameraControl?.setZoomRatio(ratio) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryThumbnailButton(
                    latestPhoto = latestPhoto,
                    captureThumbnail = captureThumbnail,
                    pendingSaves = pendingSaves,
                    rotation = iconRotation,
                    onClick = onNavigateToGallery
                )

                ShutterButton(
                    enabled = !isCapturing,
                    countdownActive = countdownSeconds > 0,
                    onClick = onShutter
                )

                // Camera switch: a half-turn spin on each flip, on top of the orientation rotation
                val flipSpin by animateFloatAsState(
                    targetValue = if (isBackCamera) 0f else 180f,
                    animationSpec = tween(350),
                    label = "flipSpin"
                )
                Box(
                    modifier = Modifier.size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(ControlSurface)
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Switch camera",
                                onClick = { viewModel.toggleCamera() }
                            )
                            .semantics {
                                contentDescription = if (isBackCamera) "Switch to front camera" else "Switch to back camera"
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(26.dp)
                                .graphicsLayer { rotationZ = iconRotation + flipSpin }
                        )
                    }
                }
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

/** 48dp round touch target for the top bar; only the content rotates, so the hit area stays put. */
@Composable
private fun ControlButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

@Composable
private fun ShutterButton(
    enabled: Boolean,
    countdownActive: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // The inner disc sinks on touch-down (not on release) so the button reacts the instant it's touched.
    val discScale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "shutterDisc"
    )
    val ringScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "shutterRing"
    )
    val discAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.55f,
        animationSpec = tween(120),
        label = "shutterAlpha"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = ringScale
                scaleY = ringScale
            }
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (countdownActive) "Cancel timer" else "Take photo",
                onClick = onClick
            )
            .semantics { contentDescription = if (countdownActive) "Cancel timer" else "Shutter" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = discScale
                    scaleY = discScale
                    alpha = discAlpha
                }
                .clip(CircleShape)
                .background(Color.White)
        )
        AnimatedVisibility(
            visible = countdownActive,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFFE71D36))
            )
        }
    }
}

@Composable
private fun GalleryThumbnailButton(
    latestPhoto: PhotoItem?,
    captureThumbnail: Bitmap?,
    pendingSaves: Int,
    rotation: Float,
    onClick: () -> Unit
) {
    val thumbPainter = remember(captureThumbnail) {
        captureThumbnail?.let { BitmapPainter(it.asImageBitmap()) }
    }

    // Pop when a new capture lands; skip the thumbnail already present when the screen (re)appears.
    val pop = remember { Animatable(1f) }
    val initialThumb = remember { captureThumbnail }
    LaunchedEffect(captureThumbnail) {
        if (captureThumbnail != null && captureThumbnail !== initialThumb) {
            pop.snapTo(0.7f)
            pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    Box(
        modifier = Modifier.size(60.dp),
        contentAlignment = Alignment.Center
    ) {
        // Saving continues in the background; a thin ring shows that work is still queued.
        androidx.compose.animation.AnimatedVisibility(
            visible = pendingSaves > 0,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp),
                color = Accent,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
        }

        Box(
            modifier = Modifier
                .size(50.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    rotationZ = rotation
                }
                .clip(CircleShape)
                .background(Color(0xFF20252E))
                .border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Open gallery", onClick = onClick)
                .semantics { contentDescription = "Gallery" },
            contentAlignment = Alignment.Center
        ) {
            val photo = latestPhoto
            when {
                // While the stamped file is still being written, the in-memory preview is the newest image.
                thumbPainter != null && (pendingSaves > 0 || photo == null) -> Image(
                    painter = thumbPainter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                photo != null -> Image(
                    painter = rememberPhotoPainter(photo, placeholder = thumbPainter),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                else -> Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/** Quick-zoom chips (0.6× / 1× / 2×). The active chip shows the live ratio while pinching. */
@Composable
private fun ZoomSelector(
    zoomState: ZoomState,
    rotation: Float,
    onZoomSelected: (Float) -> Unit
) {
    val current = zoomState.zoomRatio
    val presets = buildList {
        if (zoomState.minZoomRatio < 0.95f) add(zoomState.minZoomRatio)
        add(1f)
        if (zoomState.maxZoomRatio >= 2f) add(2f)
    }
    if (presets.size < 2) return

    // The active chip is the highest preset at or below the current ratio (small tolerance for rounding).
    val activeIndex = presets.indexOfLast { current >= it - 0.05f }.coerceAtLeast(0)

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(ControlSurface)
            .border(1.dp, ControlBorder, CircleShape)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        presets.forEachIndexed { index, preset ->
            val active = index == activeIndex
            val label = if (active) formatZoom(current) else formatZoom(preset)
            val chipScale by animateFloatAsState(
                targetValue = if (active) 1f else 0.8f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
                label = "zoomChip"
            )
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Zoom to ${formatZoom(preset)}",
                        onClick = { onZoomSelected(preset) }
                    )
                    .semantics { contentDescription = "Zoom ${formatZoom(preset)}" + if (active) ", selected" else "" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .graphicsLayer {
                            scaleX = chipScale
                            scaleY = chipScale
                        }
                        .clip(CircleShape)
                        .background(if (active) Color.Black.copy(alpha = 0.55f) else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (active) Accent else Color.White,
                        fontSize = if (active) 12.sp else 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        modifier = Modifier.graphicsLayer { rotationZ = rotation }
                    )
                }
            }
        }
    }
}

/**
 * Animated icon rotation that always takes the shortest path (e.g. 270° -> 0° turns +90°, not -270°),
 * by accumulating deltas instead of animating to the absolute angle.
 */
@Composable
private fun rememberIconRotation(targetDegrees: Float): State<Float> {
    var accumulated by remember { mutableFloatStateOf(targetDegrees) }
    LaunchedEffect(targetDegrees) {
        val currentNormalized = ((accumulated % 360f) + 360f) % 360f
        val targetNormalized = ((targetDegrees % 360f) + 360f) % 360f
        var delta = targetNormalized - currentNormalized
        if (delta > 180f) delta -= 360f else if (delta < -180f) delta += 360f
        accumulated += delta
    }
    return animateFloatAsState(
        targetValue = accumulated,
        animationSpec = tween(durationMillis = 300),
        label = "iconRotation"
    )
}

/**
 * For 4:3 portrait, park the frame just under the top controls (like stock cameras) when it fits
 * above the bottom controls; otherwise fall back to sitting under the pill only, then to centering.
 */
private fun viewfinderTop(screenHeight: Dp, vfHeight: Dp, statusBarTop: Dp, navBarBottom: Dp): Dp {
    val bottomLimit = screenHeight - navBarBottom - BottomControlsHeight
    val belowChip = statusBarTop + TopBarWithChipHeight
    val belowPill = statusBarTop + TopBarHeight
    return when {
        belowChip + vfHeight <= bottomLimit -> belowChip
        belowPill + vfHeight <= bottomLimit -> belowPill
        else -> ((screenHeight - vfHeight) / 2).coerceAtLeast(0.dp)
    }
}

private fun formatZoom(ratio: Float): String {
    val rounded = (ratio * 10f).roundToInt() / 10f
    return if (abs(rounded - rounded.roundToInt()) < 0.001f) {
        "${rounded.roundToInt()}×"
    } else {
        String.format(Locale.US, "%.1f×", rounded)
    }
}

/** OrientationEventListener degrees -> Surface rotation, per the CameraX orientation guide. */
private fun orientationToSurfaceRotation(orientation: Int): Int = when (orientation) {
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> Surface.ROTATION_0
}

/** Sensor orientation (degrees) at the middle of each rotation bucket. */
private fun surfaceRotationCenter(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_270 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_90 -> 270
    else -> 0
}

private fun surfaceRotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

private fun angularDistance(a: Int, b: Int): Int {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
