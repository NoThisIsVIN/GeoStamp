package com.geostamp.camera.model

import android.net.Uri

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val latitude: Double? = null,
    val longitude: Double? = null
)
