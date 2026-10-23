package com.geostamp.camera.ui.edit

import androidx.activity.compose.BackHandler
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.geostamp.camera.data.MapTileDownloader
import com.geostamp.camera.data.SearchResult
import com.geostamp.camera.model.MapStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import kotlin.math.*

private const val MIN_ZOOM = 2f
private const val MAX_ZOOM = 19.5f
private const val MAX_TILE_ZOOM = 19
private const val MAX_MERCATOR_LAT = 85.05112878

/** Web-Mercator helpers: normalized world coordinates in [0, 1] on both axes. */
private fun lngToX(lng: Double) = (lng + 180.0) / 360.0
private fun latToY(lat: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
}
private fun xToLng(x: Double) = x * 360.0 - 180.0
private fun yToLat(y: Double) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

/** Parses "lat, lng" (also space/semicolon separated). Returns null when malformed or out of range. */
internal fun parseCoordinates(text: String): Pair<Double, Double>? {
    val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,;\s]\s*(-?\d+(?:\.\d+)?)\s*$""").find(text.replace('°', ' ').trim())
        ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lng = m.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

internal fun formatCoordinates(lat: Double, lng: Double): String =
    String.format(Locale.US, "%.6f, %.6f", lat, lng)

private data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Full-screen location picker: a draggable slippy map with a fixed centre pin, place search,
 * coordinate entry, and reset to the photo's original location.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerDialog(
    initialLat: Double?,
    initialLng: Double?,
    originalLat: Double?,
    originalLng: Double?,
    mapStyle: MapStyle,
    onSearch: suspend (String) -> List<SearchResult>,
    onConfirm: (lat: Double, lng: Double) -> Unit,
    onDismiss: () -> Unit
) {
    val hasStart = initialLat != null && initialLng != null
    // Normalized world centre + continuous zoom; saved so rotation doesn't lose the user's panning.
    var centerX by rememberSaveable { mutableDoubleStateOf(lngToX(initialLng ?: originalLng ?: 0.0)) }
    var centerY by rememberSaveable { mutableDoubleStateOf(latToY(initialLat ?: originalLat ?: 20.0)) }
    var zoom by rememberSaveable { mutableFloatStateOf(if (hasStart || originalLat != null) 16f else 2f) }

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var showCoordinateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun moveTo(lat: Double, lng: Double, z: Float? = null) {
        centerX = lngToX(lng)
        centerY = latToY(lat)
        if (z != null) zoom = z
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        focusManager.clearFocus()
        // Typed coordinates jump straight there; no geocoder round-trip needed.
        parseCoordinates(q)?.let { (lat, lng) ->
            moveTo(lat, lng, max(zoom, 16f))
            results = emptyList()
            searchMessage = null
            return
        }
        searching = true
        searchMessage = null
        scope.launch {
            val found = try { onSearch(q) } catch (_: Exception) { emptyList() }
            searching = false
            results = found
            if (found.isEmpty()) searchMessage = "No places found for “$q”"
        }
    }

    val centerLat = yToLat(centerY)
    val centerLng = xToLng(centerX)

    // Full-screen overlay rather than a Dialog: dialog windows get unreliable insets on edge-to-edge
    // activities, which pushed the bottom actions under the navigation bar.
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Swallow touches that miss the picker's own controls so they don't reach the screen below.
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Choose location") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    },
                    actions = {
                        TextButton(onClick = { onConfirm(centerLat, centerLng) }) { Text("Confirm") }
                    }
                )
            }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
            ) {
                SlippyMap(
                    centerX = centerX,
                    centerY = centerY,
                    zoom = zoom,
                    mapStyle = mapStyle,
                    onViewportChange = { x, y, z ->
                        centerX = x
                        centerY = y
                        zoom = z
                    },
                    modifier = Modifier.fillMaxSize()
                )

                CenterPin(Modifier.align(Alignment.Center))

                Text(
                    text = mapStyle.attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 132.dp, start = 8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                // Search field + results overlay at the top.
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 3.dp,
                        shadowElevation = 4.dp
                    ) {
                        TextField(
                            value = query,
                            onValueChange = {
                                query = it
                                if (it.isBlank()) { results = emptyList(); searchMessage = null }
                            },
                            placeholder = { Text("Search place, address or “lat, lng”") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                when {
                                    searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    query.isNotEmpty() -> IconButton(onClick = {
                                        query = ""; results = emptyList(); searchMessage = null
                                    }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (results.isNotEmpty() || searchMessage != null) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 3.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (results.isEmpty()) {
                                Text(
                                    searchMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            } else {
                                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                                    items(results) { r ->
                                        ListItem(
                                            headlineContent = {
                                                Text(r.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            },
                                            supportingContent = {
                                                Text(formatCoordinates(r.latitude, r.longitude), style = MaterialTheme.typography.bodySmall)
                                            },
                                            leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                            modifier = Modifier.clickable {
                                                moveTo(r.latitude, r.longitude, 17f)
                                                results = emptyList()
                                                focusManager.clearFocus()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Zoom controls.
                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallFloatingActionButton(onClick = { zoom = (zoom + 1f).coerceAtMost(MAX_ZOOM) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                    }
                    SmallFloatingActionButton(onClick = { zoom = (zoom - 1f).coerceAtLeast(MIN_ZOOM) }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                    }
                }

                // Bottom card: current coordinates + actions.
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Pin location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            formatCoordinates(centerLat, centerLng),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (originalLat != null && originalLng != null) {
                                AssistChip(
                                    onClick = { moveTo(originalLat, originalLng, max(zoom, 16f)) },
                                    label = { Text("Original") },
                                    leadingIcon = { Icon(Icons.Filled.Restore, contentDescription = null, Modifier.size(18.dp)) }
                                )
                            }
                            AssistChip(
                                onClick = { showCoordinateDialog = true },
                                label = { Text("Enter coordinates") },
                                leadingIcon = { Icon(Icons.Filled.EditLocationAlt, contentDescription = null, Modifier.size(18.dp)) }
                            )
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { onConfirm(centerLat, centerLng) }) { Text("Use this") }
                        }
                    }
                }
            }
        }

        if (showCoordinateDialog) {
            CoordinateEntryDialog(
                initial = formatCoordinates(centerLat, centerLng),
                onConfirm = { lat, lng ->
                    showCoordinateDialog = false
                    moveTo(lat, lng, max(zoom, 16f))
                },
                onDismiss = { showCoordinateDialog = false }
            )
        }
    }
}

@Composable
private fun CenterPin(modifier: Modifier = Modifier) {
    val pinSize = 44.dp
    Box(modifier.size(pinSize * 2), contentAlignment = Alignment.Center) {
        // Small dot marks the exact point; the pin's tip sits on it.
        Box(
            Modifier
                .size(6.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        )
        Icon(
            Icons.Filled.Place,
            contentDescription = null,
            tint = Color(0xFFE53935),
            modifier = Modifier
                .size(pinSize)
                .offset(y = -pinSize / 2 + 3.dp)
        )
    }
}

@Composable
private fun CoordinateEntryDialog(
    initial: String,
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val parsed = parseCoordinates(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter coordinates") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Latitude, longitude") },
                placeholder = { Text("48.85837, 2.29448") },
                singleLine = true,
                isError = text.isNotBlank() && parsed == null,
                supportingText = {
                    Text(if (text.isNotBlank() && parsed == null) "Use decimal degrees, e.g. 48.85837, 2.29448" else "Paste or type decimal degrees")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { parsed?.let { onConfirm(it.first, it.second) } })
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onConfirm(it.first, it.second) } }, enabled = parsed != null) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Minimal raster tile map. Tiles are drawn at an integer tile zoom and scaled for fractional zoom;
 * while a tile loads, a cached ancestor tile is upscaled in its place so the map never flashes blank.
 */
@Composable
private fun SlippyMap(
    centerX: Double,
    centerY: Double,
    zoom: Float,
    mapStyle: MapStyle,
    onViewportChange: (x: Double, y: Double, zoom: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val downloader = remember { MapTileDownloader(context.applicationContext) }
    // 256 px tiles look tiny on dense screens; upscale moderately rather than to full dp size.
    val tileSizePx = remember(density) { (256f * density * 0.5f).coerceAtLeast(256f) }
    val cache = remember {
        object : LruCache<TileKey, ImageBitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: TileKey, value: ImageBitmap): Int = value.width * value.height * 4
        }
    }
    var tileTick by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Gesture handlers outlive recompositions, so read the latest viewport through State.
    val latestX by rememberUpdatedState(centerX)
    val latestY by rememberUpdatedState(centerY)
    val latestZoom by rememberUpdatedState(zoom)
    val latestOnChange by rememberUpdatedState(onViewportChange)

    fun applyZoomAround(focus: Offset, newZoomRaw: Float, panX: Float = 0f, panY: Float = 0f) {
        val w = size.width / 2f
        val h = size.height / 2f
        val oldWorld = tileSizePx * 2.0.pow(latestZoom.toDouble())
        val newZoom = newZoomRaw.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newWorld = tileSizePx * 2.0.pow(newZoom.toDouble())
        // Keep the world point under the focus fixed while zooming, then apply the pan.
        val px = latestX + (focus.x - w) / oldWorld
        val py = latestY + (focus.y - h) / oldWorld
        var nx = px - (focus.x - w) / newWorld - panX / newWorld
        val ny = (py - (focus.y - h) / newWorld - panY / newWorld).coerceIn(latToY(MAX_MERCATOR_LAT), latToY(-MAX_MERCATOR_LAT))
        nx -= floor(nx) // wrap around the antimeridian
        latestOnChange(nx, ny, newZoom)
    }

    // Load whichever tiles the viewport needs; cancel queued loads that scrolled out of view.
    LaunchedEffect(mapStyle, tileSizePx) {
        val inflight = HashMap<TileKey, Job>()
        val permits = Semaphore(6)
        snapshotFlow { visibleTiles(latestX, latestY, latestZoom, size, tileSizePx) }
            .distinctUntilChanged()
            .collect { needed ->
                val neededSet = needed.toHashSet()
                inflight.entries.removeAll { (k, job) ->
                    val drop = k !in neededSet || !job.isActive
                    if (drop) job.cancel()
                    drop
                }
                for (key in needed) {
                    if (cache.get(key) != null || inflight.containsKey(key)) continue
                    inflight[key] = launch {
                        val bmp = permits.withPermit { downloader.loadTileBitmap(key.z, key.x, key.y, mapStyle) }
                        if (bmp != null) {
                            cache.put(key, bmp.asImageBitmap())
                            tileTick++
                        }
                    }
                }
            }
    }

    val placeholder = MaterialTheme.colorScheme.surfaceContainerHighest
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier
            .background(placeholder)
            .onSizeChanged { size = it }
            // Tap detector outermost: the inner transform detector consumes drags first, cancelling taps.
            .pointerInput(tileSizePx) {
                detectTapGestures(onDoubleTap = { pos -> applyZoomAround(pos, latestZoom + 1f) })
            }
            .pointerInput(tileSizePx) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val newZoom = latestZoom + log2(gestureZoom.coerceAtLeast(0.01f))
                    applyZoomAround(centroid, newZoom, pan.x, pan.y)
                }
            }
    ) {
        if (tileTick < 0) return@Canvas // reading tileTick redraws when a tile arrives
        drawTiles(centerX, centerY, zoom, tileSizePx, cache, gridColor)
    }
}

/** Integer tile zoom used for a fractional view zoom. */
private fun tileZoomFor(zoom: Float): Int = zoom.roundToInt().coerceIn(0, MAX_TILE_ZOOM)

private fun visibleTiles(cx: Double, cy: Double, zoom: Float, size: IntSize, tileSizePx: Float): List<TileKey> {
    if (size.width == 0 || size.height == 0) return emptyList()
    val tz = tileZoomFor(zoom)
    val n = 1 shl tz
    val world = tileSizePx * 2.0.pow(zoom.toDouble())
    val s = world / n
    val left = cx * world - size.width / 2.0
    val top = cy * world - size.height / 2.0
    val x0 = floor(left / s).toInt()
    val x1 = floor((left + size.width) / s).toInt()
    val y0 = floor(top / s).toInt().coerceAtLeast(0)
    val y1 = floor((top + size.height) / s).toInt().coerceAtMost(n - 1)
    val out = ArrayList<TileKey>()
    for (ty in y0..y1) for (tx in x0..x1) out.add(TileKey(tz, ((tx % n) + n) % n, ty))
    return out
}

private fun DrawScope.drawTiles(
    cx: Double,
    cy: Double,
    zoom: Float,
    tileSizePx: Float,
    cache: LruCache<TileKey, ImageBitmap>,
    gridColor: Color
) {
    val tz = tileZoomFor(zoom)
    val n = 1 shl tz
    val world = tileSizePx * 2.0.pow(zoom.toDouble())
    val s = world / n
    val left = cx * world - size.width / 2.0
    val top = cy * world - size.height / 2.0
    val x0 = floor(left / s).toInt()
    val x1 = floor((left + size.width) / s).toInt()
    val y0 = floor(top / s).toInt().coerceAtLeast(0)
    val y1 = floor((top + size.height) / s).toInt().coerceAtMost(n - 1)
    // Round edges to whole pixels so neighbouring tiles butt together without hairline seams.
    for (ty in y0..y1) for (tx in x0..x1) {
        val wx = ((tx % n) + n) % n
        val dl = (tx * s - left).roundToInt()
        val dt = (ty * s - top).roundToInt()
        val dr = ((tx + 1) * s - left).roundToInt()
        val db = ((ty + 1) * s - top).roundToInt()
        val dstOffset = IntOffset(dl, dt)
        val dstSize = IntSize(dr - dl, db - dt)

        val exact = cache.get(TileKey(tz, wx, ty))
        if (exact != null) {
            drawImage(exact, srcOffset = IntOffset.Zero, srcSize = IntSize(exact.width, exact.height), dstOffset = dstOffset, dstSize = dstSize)
            continue
        }
        var drawn = false
        for (k in 1..4) {
            if (tz - k < 0) break
            val parent = cache.get(TileKey(tz - k, wx shr k, ty shr k)) ?: continue
            val sub = parent.width shr k
            if (sub <= 0) break
            val sx = (wx and ((1 shl k) - 1)) * sub
            val sy = (ty and ((1 shl k) - 1)) * sub
            drawImage(parent, srcOffset = IntOffset(sx, sy), srcSize = IntSize(sub, sub), dstOffset = dstOffset, dstSize = dstSize)
            drawn = true
            break
        }
        if (!drawn) {
            drawRect(
                color = gridColor,
                topLeft = Offset(dl.toFloat(), dt.toFloat()),
                size = androidx.compose.ui.geometry.Size(dstSize.width.toFloat(), dstSize.height.toFloat()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
            )
        }
    }
}
