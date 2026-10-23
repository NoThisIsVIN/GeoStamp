package com.geostamp.camera.ui.gallery

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * A photo that supports pinch-zoom, double-tap zoom (1x <-> 2.5x around the tap point) and panning
 * while zoomed. At 1x single-finger drags are left unconsumed so a parent pager can swipe; when zoomed
 * and already panned to the left/right edge, a horizontal drag is also handed to the pager.
 *
 * @param isActive false when this page is not the settled pager page; zoom resets then.
 */
@Composable
fun ZoomableImage(
    painter: Painter,
    contentDescription: String?,
    isActive: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val currentOnTap by rememberUpdatedState(onTap)

    LaunchedEffect(isActive) {
        if (!isActive) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    // Size of the image as drawn at 1x with ContentScale.Fit, so panning stops at the photo's edges
    // instead of the letterbox bars.
    fun fittedSize(): Size {
        val container = Size(containerSize.width.toFloat(), containerSize.height.toFloat())
        val intrinsic = painter.intrinsicSize
        if (intrinsic == Size.Unspecified || intrinsic.width <= 0f || intrinsic.height <= 0f ||
            container.width <= 0f || container.height <= 0f
        ) return container
        val fit = min(container.width / intrinsic.width, container.height / intrinsic.height)
        return Size(intrinsic.width * fit, intrinsic.height * fit)
    }

    fun clampOffset(raw: Offset, forScale: Float): Offset {
        val content = fittedSize()
        val maxX = max(0f, (content.width * forScale - containerSize.width) / 2f)
        val maxY = max(0f, (content.height * forScale - containerSize.height) / 2f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Image(
        painter = painter,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { tap ->
                        val startScale = scale
                        val startOffset = offset
                        val targetScale = if (scale > 1.05f) 1f else DOUBLE_TAP_SCALE
                        val center = Offset(size.width / 2f, size.height / 2f)
                        // Keep the tapped point under the finger: graphicsLayer scales around the center.
                        val targetOffset = if (targetScale == 1f) Offset.Zero
                        else clampOffset((tap - center) * (1f - targetScale), targetScale)
                        scope.launch {
                            animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                                scale = lerp(startScale, targetScale, t)
                                offset = Offset(
                                    lerp(startOffset.x, targetOffset.x, t),
                                    lerp(startOffset.y, targetOffset.y, t)
                                )
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Decided once per gesture: once we consume a move the pager cancels its drag for
                    // the rest of the gesture, and vice versa, so mixing would make swipes stutter.
                    var ownsGesture = false
                    var passThrough = false
                    var accumulatedPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (pointers > 1) {
                            ownsGesture = true // a pinch always belongs to us
                            passThrough = false
                        } else if (!ownsGesture && !passThrough && scale > 1.01f) {
                            accumulatedPan += panChange
                            if (accumulatedPan.getDistance() > touchSlop) {
                                val content = fittedSize()
                                val maxX = max(0f, (content.width * scale - containerSize.width) / 2f)
                                val horizontal = abs(accumulatedPan.x) > abs(accumulatedPan.y)
                                // Dragging right at the left edge (or left at the right edge): go to pager.
                                val atEdge = (accumulatedPan.x > 0 && offset.x >= maxX - 0.5f) ||
                                    (accumulatedPan.x < 0 && offset.x <= -maxX + 0.5f)
                                if (horizontal && atEdge) passThrough = true else ownsGesture = true
                            }
                        }

                        if (ownsGesture) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            var newOffset = offset
                            if (centroid.isSpecified && newScale != scale) {
                                // Zoom around the fingers' centroid rather than the view center.
                                val focus = centroid - center
                                newOffset = focus - (focus - offset) * (newScale / scale)
                            }
                            newOffset += panChange
                            scale = newScale
                            offset = if (newScale <= 1f) Offset.Zero else clampOffset(newOffset, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    // Snap back cleanly from a pinch that ended just above 1x.
                    if (scale < 1.02f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    )
}
