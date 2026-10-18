package com.geostamp.camera.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun OnboardingDialog(
    onGrantPermissionsClick: () -> Unit,
    isPermanentlyDenied: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Welcome to GeoStamp",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "GeoStamp Camera stamps accurate GPS coordinates, full address, compass heading, and map thumbnails directly onto your photos with zero ads or tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                PermissionRationaleItem(
                    icon = Icons.Default.CameraAlt,
                    title = "Camera",
                    description = "Required to preview scenes and capture high-resolution photos."
                )

                Spacer(modifier = Modifier.height(12.dp))

                PermissionRationaleItem(
                    icon = Icons.Default.LocationOn,
                    title = "Precise Location",
                    description = "Required to detect exact coordinates, altitude, and lookup street addresses."
                )

                Spacer(modifier = Modifier.height(12.dp))

                PermissionRationaleItem(
                    icon = Icons.Default.Shield,
                    title = "100% Private",
                    description = "Zero trackers, zero analytics. Photos and GPS never leave your device."
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (isPermanentlyDenied) {
                    Text(
                        text = "Permissions were previously denied. Please open Settings to allow Camera and Location.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Open Settings")
                    }
                } else {
                    Button(
                        onClick = onGrantPermissionsClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Continue & Grant Permissions", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRationaleItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
