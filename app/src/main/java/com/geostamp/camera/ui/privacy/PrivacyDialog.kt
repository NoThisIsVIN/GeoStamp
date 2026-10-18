package com.geostamp.camera.ui.privacy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrivacyDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Privacy & Data Policy", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "Zero Ads. Zero Analytics. Zero Trackers.",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "GeoStamp Camera is designed with a strict offline-first, local-only architecture:\n\n" +
                    "• Your Photos: Saved strictly to your device's MediaStore (Pictures/GeoStampCamera). We never transmit, upload, or process your photos on any cloud server.\n\n" +
                    "• Your Location: GPS coordinates and altitude are queried solely on-device via Android Location APIs and Google Play Services to stamp your photos and write standard EXIF tags.\n\n" +
                    "• Geocoding: Addresses are resolved using the native Android Geocoder service.\n\n" +
                    "• Anonymous Network Calls: Only two network endpoints are ever contacted:\n" +
                    "  1. OpenStreetMap (tile.openstreetmap.org) to download standard map raster tiles for thumbnails.\n" +
                    "  2. Open-Meteo (api.open-meteo.com) for real-time temperature and weather forecasts (free, requires no account, no personal identifiers sent).\n\n" +
                    "• No In-App Purchases, No Third-Party SDKs, No Tracking SDKs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got It")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
