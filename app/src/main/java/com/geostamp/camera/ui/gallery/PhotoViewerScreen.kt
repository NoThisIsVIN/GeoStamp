package com.geostamp.camera.ui.gallery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.ui.components.rememberPhotoPainter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val ChromeScrim = Color.Black.copy(alpha = 0.55f)
private val DangerRed = Color(0xFFFF6B6B)

/**
 * Full-screen, swipeable photo viewer over the gallery list (same order as the grid).
 *
 * The photo being shown is tracked by id rather than index, so it survives the list being reloaded
 * and re-sorted (e.g. after an edit changes its date) and falls to a neighbour when it is deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    initialPhotoId: Long,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onEdit: (PhotoItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val hasLoaded by viewModel.hasLoaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentPhotoId by rememberSaveable { mutableLongStateOf(initialPhotoId) }
    // Last index the current photo was seen at; used to pick a neighbour once it disappears.
    var lastKnownIndex by rememberSaveable { mutableIntStateOf(0) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PhotoItem?>(null) }

    val pagerState = rememberPagerState(
        initialPage = photos.indexOfFirst { it.id == initialPhotoId }.coerceAtLeast(0)
    ) { photos.size }

    val currentPhoto = photos.firstOrNull { it.id == currentPhotoId }
    val latestOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(Unit) {
        if (!hasLoaded) viewModel.refreshPhotos()
    }

    // List changed (load, edit re-sort, delete): keep showing the same photo, or its neighbour.
    LaunchedEffect(photos, hasLoaded) {
        if (photos.isEmpty()) {
            if (hasLoaded) latestOnBack()
            return@LaunchedEffect
        }
        val index = photos.indexOfFirst { it.id == currentPhotoId }
        if (index >= 0) {
            lastKnownIndex = index
            if (pagerState.currentPage != index) pagerState.scrollToPage(index)
        } else if (hasLoaded) {
            val neighbour = lastKnownIndex.coerceIn(0, photos.lastIndex)
            currentPhotoId = photos[neighbour].id
            lastKnownIndex = neighbour
            if (pagerState.currentPage != neighbour) pagerState.scrollToPage(neighbour)
        }
    }

    // User swiped: the settled page becomes the photo that actions apply to.
    val latestPhotos by rememberUpdatedState(photos)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                latestPhotos.getOrNull(page)?.let {
                    currentPhotoId = it.id
                    lastKnownIndex = page
                }
            }
    }

    ImmersiveSystemBars(visible = chromeVisible)

    // Scoped Storage Delete Result Launcher (API 30+)
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "Photo deleted", Toast.LENGTH_SHORT).show()
            // The reload drops the photo; the effect above then moves to its neighbour.
            viewModel.refreshPhotos()
        }
    }

    fun confirmDelete(photo: PhotoItem) {
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.getDeleteIntentSender(photo)
        } else null
        if (pendingIntent != null) {
            deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        } else {
            viewModel.deletePhotoLegacy(photo) { deleted ->
                Toast.makeText(
                    context,
                    if (deleted) "Photo deleted" else "Couldn't delete photo",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            key = { page -> photos.getOrNull(page)?.id ?: page.toLong() },
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos.getOrNull(page)
            if (photo != null) {
                ZoomableImage(
                    painter = rememberPhotoPainter(photo),
                    contentDescription = photo.displayName,
                    isActive = pagerState.settledPage == page,
                    onTap = { chromeVisible = !chromeVisible }
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && currentPhoto != null,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            currentPhoto?.let { ViewerTopBar(photo = it, viewModel = viewModel, onBack = onBack) }
        }

        AnimatedVisibility(
            visible = chromeVisible && currentPhoto != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            currentPhoto?.let { photo ->
                ViewerBottomBar(
                    onShare = {
                        val shareIntent = viewModel.getShareIntent(photo)
                        context.startActivity(Intent.createChooser(shareIntent, "Share photo"))
                    },
                    onEdit = {
                        if (photo.isEditable) {
                            onEdit(photo)
                        } else {
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar(
                                    "Editing is available for photos taken with this version while " +
                                        "\"Keep editable copies\" is on in Settings."
                                )
                            }
                        }
                    },
                    editEnabled = photo.isEditable,
                    onMaps = { scope.launch { openInMaps(context, viewModel, photo) } },
                    onInfo = { showInfo = true },
                    onDelete = { pendingDelete = photo }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (chromeVisible) 104.dp else 16.dp)
        )
    }

    if (showInfo && currentPhoto != null) {
        PhotoInfoSheet(
            photo = currentPhoto,
            viewModel = viewModel,
            onDismiss = { showInfo = false }
        )
    }

    pendingDelete?.let { photo ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete photo?") },
            text = { Text("\"${photo.displayName}\" will be permanently removed from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    confirmDelete(photo)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ViewerTopBar(photo: PhotoItem, viewModel: GalleryViewModel, onBack: () -> Unit) {
    val title = remember(photo.dateTaken) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(photo.dateTaken))
    }
    val coords = rememberCoordinates(photo, viewModel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ChromeScrim, Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = coords?.let { formatCoords(it) } ?: "No location",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ViewerBottomBar(
    onShare: () -> Unit,
    onEdit: () -> Unit,
    editEnabled: Boolean,
    onMaps: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, ChromeScrim)))
            .navigationBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ViewerAction(Icons.Outlined.Share, "Share", onShare)
        // Still clickable when disabled-looking, so the user learns why editing isn't available.
        ViewerAction(Icons.Outlined.Edit, "Edit", onEdit, tint = if (editEnabled) Color.White else Color.White.copy(alpha = 0.4f))
        ViewerAction(Icons.Outlined.Map, "Maps", onMaps)
        ViewerAction(Icons.Outlined.Info, "Info", onInfo)
        ViewerAction(Icons.Outlined.Delete, "Delete", onDelete, tint = DangerRed)
    }
}

@Composable
private fun ViewerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoInfoSheet(photo: PhotoItem, viewModel: GalleryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coords = rememberCoordinates(photo, viewModel)
    val dateTime = remember(photo.dateTaken) {
        DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.MEDIUM).format(Date(photo.dateTaken))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Details", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            InfoRow("File name", photo.displayName)
            InfoRow("Date & time", dateTime)
            InfoRow(
                label = "Coordinates",
                value = coords?.let { formatCoords(it) } ?: "Not available",
                onCopy = coords?.let { c ->
                    {
                        clipboard.setText(AnnotatedString(formatCoords(c)))
                        // Android 13+ shows its own clipboard confirmation.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, "Coordinates copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            InfoRow(
                "Resolution",
                if (photo.width > 0 && photo.height > 0) {
                    val mp = photo.width.toLong() * photo.height / 1_000_000.0
                    "${photo.width} × ${photo.height}  ·  ${String.format(Locale.getDefault(), "%.1f", mp)} MP"
                } else "Unknown"
            )
            InfoRow("Size", if (photo.sizeBytes > 0) Formatter.formatFileSize(context, photo.sizeBytes) else "Unknown")
            InfoRow("Editable", if (photo.isEditable) "Yes — location, date & time can be changed" else "No")
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}

/** Coordinates resolved off the main thread (the EXIF fallback touches storage). */
@Composable
private fun rememberCoordinates(photo: PhotoItem, viewModel: GalleryViewModel): Pair<Double, Double>? {
    val immediate = photo.latitude?.let { lat -> photo.longitude?.let { lng -> lat to lng } }
    val resolved by produceState(initialValue = immediate, photo.cacheKey) {
        value = immediate ?: viewModel.resolveCoordinates(photo)
    }
    return resolved
}

private fun formatCoords(coords: Pair<Double, Double>): String =
    String.format(Locale.US, "%.6f, %.6f", coords.first, coords.second)

private suspend fun openInMaps(context: Context, viewModel: GalleryViewModel, photo: PhotoItem) {
    val coords = viewModel.resolveCoordinates(photo)
    if (coords == null) {
        Toast.makeText(context, "This photo has no location", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(viewModel.getMapsIntent(coords.first, coords.second, photo.displayName))
    } catch (_: Exception) {
        try {
            context.startActivity(viewModel.getWebMapsIntent(coords.first, coords.second))
        } catch (_: Exception) {
            Toast.makeText(context, "No maps app or browser found", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Black viewer needs light status/navigation icons, and hiding the chrome should also hide the system
 * bars. Both are restored when leaving the viewer so the rest of the app looks as before.
 */
@Composable
private fun ImmersiveSystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }

    DisposableEffect(controller) {
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
    LaunchedEffect(visible) {
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
