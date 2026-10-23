package com.geostamp.camera.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FocusColor = Color(0xFFFFD166)

/**
 * Tap-to-focus reticle: snaps in large, settles with a spring, then dims while AF holds.
 * [tapId] restarts the animation for repeated taps on the same spot.
 */
@Composable
fun FocusIndicator(
    focusPosition: Offset,
    visible: Boolean,
    modifier: Modifier = Modifier,
    tapId: Int = 0
) {
    val scale = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(visible, tapId) {
        if (visible) {
            alpha.snapTo(1f)
            scale.snapTo(1.5f)
            scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
            delay(600)
            alpha.animateTo(0.5f, tween(300))
        } else {
            alpha.animateTo(0f, tween(200))
        }
    }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (focusPosition.x - 36.dp.toPx()).roundToInt(),
                    (focusPosition.y - 36.dp.toPx()).roundToInt()
                )
            }
            .size(72.dp)
            // Animated values are read in the layer/draw phase only, so the reticle never recomposes per frame.
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(color = Color.Black.copy(alpha = 0.25f), radius = radius, style = Stroke(width = stroke * 3))
            drawCircle(color = FocusColor, radius = radius, style = Stroke(width = stroke))
            drawCircle(color = FocusColor, radius = 2.5.dp.toPx(), center = center)

            val tickLen = 6.dp.toPx()
            drawLine(FocusColor, Offset(center.x - radius, center.y), Offset(center.x - radius + tickLen, center.y), strokeWidth = stroke)
            drawLine(FocusColor, Offset(center.x + radius - tickLen, center.y), Offset(center.x + radius, center.y), strokeWidth = stroke)
            drawLine(FocusColor, Offset(center.x, center.y - radius), Offset(center.x, center.y - radius + tickLen), strokeWidth = stroke)
            drawLine(FocusColor, Offset(center.x, center.y + radius - tickLen), Offset(center.x, center.y + radius), strokeWidth = stroke)
        }
    }
}

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val stroke = 1.dp.toPx()
        val gridColor = Color.White.copy(alpha = 0.35f)

        // Vertical lines (1/3 and 2/3)
        drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = stroke)
        drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), strokeWidth = stroke)

        // Horizontal lines (1/3 and 2/3)
        drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = stroke)
        drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), strokeWidth = stroke)
    }
}

/** Self-timer digits: each second zooms in and the previous one shrinks away. */
@Composable
fun CountdownOverlay(
    secondsRemaining: Int,
    modifier: Modifier = Modifier,
    rotation: Float = 0f
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = secondsRemaining,
            transitionSpec = {
                (scaleIn(tween(250), initialScale = 1.6f) + fadeIn(tween(200))) togetherWith
                    (scaleOut(tween(200), targetScale = 0.6f) + fadeOut(tween(200)))
            },
            label = "countdown"
        ) { sec ->
            if (sec > 0) {
                Text(
                    text = sec.toString(),
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Black,
                        shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), blurRadius = 16f)
                    )
                )
            } else {
                Box(modifier = Modifier.size(1.dp))
            }
        }
    }
}

/** Brief dark blink over the viewfinder at the moment of exposure, like first-party camera apps. */
@Composable
fun ShutterFlashOverlay(
    trigger: kotlinx.coroutines.flow.Flow<Unit>,
    modifier: Modifier = Modifier
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        trigger.collect {
            launch {
                alpha.snapTo(0.85f)
                alpha.animateTo(0f, tween(durationMillis = 140, easing = LinearOutSlowInEasing))
            }
        }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val a = alpha.value
        if (a > 0f) drawRect(Color.Black.copy(alpha = a))
    }
}
