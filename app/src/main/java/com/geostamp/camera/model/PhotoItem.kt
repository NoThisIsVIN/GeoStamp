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
    val longitude: Double? = null,
    /** MediaStore DATE_MODIFIED (seconds). Changes when the file is re-stamped, so it doubles as an image cache key. */
    val dateModified: Long = 0L,
    /** True when an unstamped source + metadata sidecar exists in [com.geostamp.camera.data.EditStore]. */
    val isEditable: Boolean = false
) {
    /** Stable key for image caches that changes whenever the file content is rewritten. */
    val cacheKey: String get() = "$uri#$dateModified#$sizeBytes"
}
