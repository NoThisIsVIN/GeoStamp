package com.geostamp.camera.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun FocusIndicator(
    focusPosition: Offset,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val infiniteTransition = rememberInfiniteTransition(label = "focusAnim")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.25f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "focusScale"
    )

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (focusPosition.x - 36.dp.toPx()).roundToInt(),
                    (focusPosition.y - 36.dp.toPx()).roundToInt()
                )
            }
            .size(72.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = (size.minDimension / 2f) * scale
            drawCircle(
                color = Color(0xFFFFD166),
                radius = radius,
                style = Stroke(width = 2.dp.toPx())
            )
            // Center crosshair tick marks
            val tickLen = 6.dp.toPx()
            val center = Offset(size.width / 2, size.height / 2)
            drawLine(Color(0xFFFFD166), Offset(center.x - radius, center.y), Offset(center.x - radius + tickLen, center.y), strokeWidth = 2.dp.toPx())
            drawLine(Color(0xFFFFD166), Offset(center.x + radius - tickLen, center.y), Offset(center.x + radius, center.y), strokeWidth = 2.dp.toPx())
            drawLine(Color(0xFFFFD166), Offset(center.x, center.y - radius), Offset(center.x, center.y - radius + tickLen), strokeWidth = 2.dp.toPx())
            drawLine(Color(0xFFFFD166), Offset(center.x, center.y + radius - tickLen), Offset(center.x, center.y + radius), strokeWidth = 2.dp.toPx())
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

@Composable
fun CountdownOverlay(
    secondsRemaining: Int,
    modifier: Modifier = Modifier
) {
    if (secondsRemaining <= 0) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = secondsRemaining.toString(),
            color = Color.White,
            fontSize = 84.sp,
            fontWeight = FontWeight.Black
        )
    }
}
