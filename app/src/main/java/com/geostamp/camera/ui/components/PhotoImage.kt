package com.geostamp.camera.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.geostamp.camera.model.PhotoItem

/**
 * Painter for a gallery photo whose memory-cache key follows [PhotoItem.cacheKey], so a photo that was
 * re-stamped in place (same URI, new content) is not served stale from Coil's cache.
 */
@Composable
fun rememberPhotoPainter(
    photo: PhotoItem,
    placeholder: Painter? = null,
    crossfade: Boolean = true
): Painter {
    val context = LocalContext.current
    val request = remember(photo.cacheKey, crossfade) {
        ImageRequest.Builder(context)
            .data(photo.uri)
            .memoryCacheKey(photo.cacheKey)
            .crossfade(crossfade)
            .build()
    }
    return rememberAsyncImagePainter(model = request, placeholder = placeholder)
}
