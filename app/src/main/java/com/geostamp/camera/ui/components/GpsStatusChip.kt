package com.geostamp.camera.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geostamp.camera.data.GpsStatus

@Composable
fun GpsStatusChip(
    status: GpsStatus,
    accuracyMeters: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (label, icon, chipColor) = when (status) {
        GpsStatus.LOCKED -> {
            val accText = if (accuracyMeters != null) " (±${accuracyMeters.toInt()}m)" else ""
            Triple("GPS locked$accText", Icons.Default.GpsFixed, Color(0xFF2EC4B6))
        }
        GpsStatus.LAST_KNOWN -> {
            Triple("Last known location", Icons.Default.GpsFixed, Color(0xFFFF9F1C))
        }
        GpsStatus.ACQUIRING -> {
            Triple("Acquiring GPS…", Icons.Default.GpsNotFixed, Color(0xFFE71D36))
        }
        GpsStatus.DISABLED -> {
            Triple("No GPS - enable location", Icons.Default.GpsOff, Color(0xFFE71D36))
        }
        GpsStatus.NO_PERMISSION -> {
            Triple("Location permission needed", Icons.Default.GpsOff, Color(0xFFE71D36))
        }
    }

    val animatedColor by animateColorAsState(targetValue = chipColor, label = "chipColor")

    // A pulsing dot signals "working on it" while a fix is being acquired.
    val pulse = rememberInfiniteTransition(label = "gpsPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "gpsPulseAlpha"
    )
    val acquiring = status == GpsStatus.ACQUIRING

    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF14171E).copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Location status") { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if (acquiring) pulseAlpha else 1f }
                .clip(CircleShape)
                .background(animatedColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = animatedColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
