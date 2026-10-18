package com.geostamp.camera.ui.gallery

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.geostamp.camera.model.PhotoItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    photo: PhotoItem,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Scoped Storage Delete Result Launcher (API 30+)
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "Photo deleted", Toast.LENGTH_SHORT).show()
            viewModel.refreshPhotos()
            onBack()
        }
    }

    // Zoom & Pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val formattedDate = remember(photo.dateTaken) {
        val sdf = SimpleDateFormat("MMM d, yyyy • HH:mm:ss", Locale.getDefault())
        sdf.format(Date(photo.dateTaken))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = photo.displayName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = formattedDate,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                )
            )
        },
        bottomBar = {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Share Button
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            val shareIntent = viewModel.getShareIntent(photo)
                            context.startActivity(Intent.createChooser(shareIntent, "Share Stamped Photo"))
                        }) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                        Text("Share", color = Color.White, fontSize = 11.sp)
                    }

                    // Open in Maps Button (if geo coordinates exist)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            val mapsIntent = viewModel.getMapsIntent(photo)
                            if (mapsIntent != null) {
                                try {
                                    context.startActivity(mapsIntent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No maps application found", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Location coordinates not embedded in this photo", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Map, contentDescription = "Open in Maps", tint = Color.White)
                        }
                        Text("Maps", color = Color.White, fontSize = 11.sp)
                    }

                    // Delete Button
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                        }
                        Text("Delete", color = Color(0xFFFF5252), fontSize = 11.sp)
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val maxOffsetX = (size.width * (scale - 1f)) / 2f
                        val maxOffsetY = (size.height * (scale - 1f)) / 2f
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                            y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = rememberAsyncImagePainter(photo.uri),
                contentDescription = photo.displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        }

        // Delete Confirmation Dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Photo?") },
                text = { Text("Are you sure you want to delete \"${photo.displayName}\"? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirmDialog = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val pendingIntent = viewModel.getDeleteIntentSender(photo)
                                if (pendingIntent != null) {
                                    val senderRequest = IntentSenderRequest.Builder(pendingIntent).build()
                                    deleteLauncher.launch(senderRequest)
                                } else {
                                    viewModel.deletePhotoLegacy(photo) {
                                        Toast.makeText(context, "Photo deleted", Toast.LENGTH_SHORT).show()
                                        onBack()
                                    }
                                }
                            } else {
                                viewModel.deletePhotoLegacy(photo) {
                                    Toast.makeText(context, "Photo deleted", Toast.LENGTH_SHORT).show()
                                    onBack()
                                }
                            }
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
