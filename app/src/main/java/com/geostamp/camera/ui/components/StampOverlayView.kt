package com.geostamp.camera.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geostamp.camera.model.*

@Composable
fun StampOverlayView(
    modifier: Modifier = Modifier,
    stampData: StampData,
    stampSettings: StampSettings,
    appSettings: AppSettings,
    isPreviewInSettings: Boolean = false
) {
    val alignment = if (stampSettings.position == StampPosition.BOTTOM) {
        Alignment.BottomCenter
    } else {
        Alignment.TopCenter
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = alignment
    ) {
        when (stampSettings.template) {
            StampTemplate.CLASSIC_BOTTOM_BAR -> ClassicBottomBarView(stampData, stampSettings, appSettings, isPreviewInSettings)
            StampTemplate.COMPACT_CORNER_BOX -> CompactCornerBoxView(stampData, stampSettings, appSettings, isPreviewInSettings)
            StampTemplate.FULL_WIDTH_LARGE_MAP -> FullWidthLargeMapView(stampData, stampSettings, appSettings, isPreviewInSettings)
            StampTemplate.MINIMAL_TEXT_ONLY -> MinimalTextOnlyView(stampData, stampSettings, appSettings, isPreviewInSettings)
        }
    }
}

@Composable
private fun ClassicBottomBarView(
    data: StampData,
    settings: StampSettings,
    appSettings: AppSettings,
    isPreview: Boolean
) {
    val scale = settings.textSizeScale
    val bgAlpha = settings.backgroundOpacity.coerceIn(0f, 1f)
    val textColor = Color(settings.textColor)
    val mapSize = (72 * scale).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isPreview) 8.dp else 16.dp, vertical = if (isPreview) 8.dp else 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF12161C).copy(alpha = bgAlpha))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (settings.showMap && data.mapThumbnail != null) {
            Image(
                bitmap = data.mapThumbnail.asImageBitmap(),
                contentDescription = "Map Thumbnail",
                modifier = Modifier
                    .size(mapSize)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            // Note
            if (settings.showNote && settings.customNote.isNotBlank()) {
                Text(
                    text = settings.customNote,
                    color = textColor,
                    fontSize = (13 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Address
            if (settings.showAddress) {
                Text(
                    text = data.formattedAddress(),
                    color = textColor,
                    fontSize = (11 * scale).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Coordinates
            if (settings.showCoordinates) {
                var coord = data.formattedCoordinates(settings.coordinateFormat)
                if (data.isLastKnown) coord += " (Last known)"
                Text(
                    text = coord,
                    color = textColor.copy(alpha = 0.9f),
                    fontSize = (10 * scale).sp
                )
            }

            // Altitude • Accuracy • Compass
            val metaParts = mutableListOf<String>()
            if (settings.showAltitude) metaParts.add(data.formattedAltitude(appSettings.unitSystem))
            if (settings.showAccuracy) metaParts.add(data.formattedAccuracy(appSettings.unitSystem))
            if (settings.showCompass) metaParts.add(data.formattedCompass())
            val metaStr = metaParts.filter { it.isNotBlank() }.joinToString(" • ")
            if (metaStr.isNotBlank()) {
                Text(
                    text = metaStr,
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = (10 * scale).sp
                )
            }

            // Date Time & Weather
            val dtParts = mutableListOf<String>()
            if (settings.showDateTime) dtParts.add(data.formattedDateTime(settings.is24Hour, settings.dateFormatPattern))
            if (settings.showWeather) dtParts.add(data.formattedWeather(appSettings.unitSystem))
            val dtStr = dtParts.filter { it.isNotBlank() }.joinToString(" • ")
            if (dtStr.isNotBlank()) {
                Text(
                    text = dtStr,
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = (9.5f * scale).sp
                )
            }
        }
    }
}

@Composable
private fun CompactCornerBoxView(
    data: StampData,
    settings: StampSettings,
    appSettings: AppSettings,
    isPreview: Boolean
) {
    val scale = settings.textSizeScale
    val bgAlpha = settings.backgroundOpacity.coerceIn(0f, 1f)
    val textColor = Color(settings.textColor)
    val mapSize = (56 * scale).dp

    Row(
        modifier = Modifier
            .padding(horizontal = if (isPreview) 8.dp else 16.dp, vertical = if (isPreview) 8.dp else 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF12161C).copy(alpha = bgAlpha))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (settings.showMap && data.mapThumbnail != null) {
            Image(
                bitmap = data.mapThumbnail.asImageBitmap(),
                contentDescription = "Map Thumbnail",
                modifier = Modifier
                    .size(mapSize)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column {
            if (settings.showCoordinates) {
                Text(
                    text = data.formattedCoordinates(settings.coordinateFormat),
                    color = textColor,
                    fontSize = (10 * scale).sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (settings.showAltitude || settings.showCompass) {
                val parts = listOfNotNull(
                    if (settings.showAltitude) data.formattedAltitude(appSettings.unitSystem) else null,
                    if (settings.showCompass) data.formattedCompass() else null
                ).filter { it.isNotBlank() }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" • "),
                        color = textColor.copy(alpha = 0.85f),
                        fontSize = (9 * scale).sp
                    )
                }
            }
            if (settings.showDateTime) {
                Text(
                    text = data.formattedDateTime(settings.is24Hour, settings.dateFormatPattern),
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = (9 * scale).sp
                )
            }
        }
    }
}

@Composable
private fun FullWidthLargeMapView(
    data: StampData,
    settings: StampSettings,
    appSettings: AppSettings,
    isPreview: Boolean
) {
    val scale = settings.textSizeScale
    val bgAlpha = settings.backgroundOpacity.coerceIn(0f, 1f)
    val textColor = Color(settings.textColor)
    val mapSize = (88 * scale).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF12161C).copy(alpha = bgAlpha))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (settings.showMap && data.mapThumbnail != null) {
            Image(
                bitmap = data.mapThumbnail.asImageBitmap(),
                contentDescription = "Map Thumbnail",
                modifier = Modifier
                    .size(mapSize)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            if (settings.showNote && settings.customNote.isNotBlank()) {
                Text(
                    text = settings.customNote,
                    color = textColor,
                    fontSize = (14 * scale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (settings.showAddress) {
                Text(
                    text = data.formattedAddress(),
                    color = textColor,
                    fontSize = (12 * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (settings.showCoordinates) {
                Text(
                    text = data.formattedCoordinates(settings.coordinateFormat),
                    color = textColor.copy(alpha = 0.9f),
                    fontSize = (10.5f * scale).sp
                )
            }
            val metaParts = mutableListOf<String>()
            if (settings.showAltitude) metaParts.add(data.formattedAltitude(appSettings.unitSystem))
            if (settings.showAccuracy) metaParts.add(data.formattedAccuracy(appSettings.unitSystem))
            if (settings.showCompass) metaParts.add(data.formattedCompass())
            if (settings.showWeather) metaParts.add(data.formattedWeather(appSettings.unitSystem))
            val metaStr = metaParts.filter { it.isNotBlank() }.joinToString(" • ")
            if (metaStr.isNotBlank()) {
                Text(
                    text = metaStr,
                    color = textColor.copy(alpha = 0.85f),
                    fontSize = (10 * scale).sp
                )
            }
            if (settings.showDateTime) {
                Text(
                    text = data.formattedDateTime(settings.is24Hour, settings.dateFormatPattern),
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = (9.5f * scale).sp
                )
            }
        }
    }
}

@Composable
private fun MinimalTextOnlyView(
    data: StampData,
    settings: StampSettings,
    appSettings: AppSettings,
    isPreview: Boolean
) {
    val scale = settings.textSizeScale
    val textColor = Color(settings.textColor)

    Column(
        modifier = Modifier
            .padding(horizontal = if (isPreview) 8.dp else 16.dp, vertical = if (isPreview) 8.dp else 20.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF12161C).copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (settings.showNote && settings.customNote.isNotBlank()) {
            Text(
                text = settings.customNote,
                color = textColor,
                fontSize = (13 * scale).sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (settings.showAddress) {
            Text(
                text = data.formattedAddress(),
                color = textColor,
                fontSize = (11 * scale).sp,
                fontWeight = FontWeight.Medium
            )
        }
        if (settings.showCoordinates) {
            Text(
                text = data.formattedCoordinates(settings.coordinateFormat),
                color = textColor,
                fontSize = (10 * scale).sp
            )
        }
        val parts = mutableListOf<String>()
        if (settings.showAltitude) parts.add(data.formattedAltitude(appSettings.unitSystem))
        if (settings.showCompass) parts.add(data.formattedCompass())
        if (settings.showDateTime) parts.add(data.formattedDateTime(settings.is24Hour, settings.dateFormatPattern))
        val partsStr = parts.filter { it.isNotBlank() }.joinToString(" • ")
        if (partsStr.isNotBlank()) {
            Text(
                text = partsStr,
                color = textColor.copy(alpha = 0.9f),
                fontSize = (9.5f * scale).sp
            )
        }
    }
}
