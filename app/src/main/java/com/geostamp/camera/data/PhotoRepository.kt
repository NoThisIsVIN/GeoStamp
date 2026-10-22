package com.geostamp.camera.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.model.StampData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class PhotoRepository(
    private val context: Context,
    val editStore: EditStore
) {

    private val contentResolver: ContentResolver = context.contentResolver
    private val coordsPrefs = context.getSharedPreferences("geostamp_photo_coords", Context.MODE_PRIVATE)

    private val _photosFlow = MutableStateFlow<List<PhotoItem>>(emptyList())
    val photosFlow: StateFlow<List<PhotoItem>> = _photosFlow.asStateFlow()

    private val _latestPhotoFlow = MutableStateFlow<PhotoItem?>(null)
    val latestPhotoFlow: StateFlow<PhotoItem?> = _latestPhotoFlow.asStateFlow()

    suspend fun loadPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        // Filter for our app photos: GeoStamp_* in DCIM/Camera or legacy Pictures/GeoStampCamera
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'GeoStamp_%') OR (${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
        } else {
            "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE 'GeoStamp_%') OR (${MediaStore.Images.Media.DATA} LIKE ?)"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%Pictures/GeoStampCamera%")
        } else {
            arrayOf("%/Pictures/GeoStampCamera/%")
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        var querySucceeded = false
        try {
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val modifiedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Photo" else "Photo"
                    val dateTaken = if (dateCol >= 0) cursor.getLong(dateCol) else System.currentTimeMillis()
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                    val modified = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L

                    val coords = getCoordinatesForPhoto(name, uri)

                    photos.add(
                        PhotoItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            dateTaken = dateTaken,
                            sizeBytes = size,
                            width = width,
                            height = height,
                            latitude = coords?.first,
                            longitude = coords?.second,
                            dateModified = modified,
                            isEditable = editStore.has(name)
                        )
                    )
                }
            }
            querySucceeded = true
        } catch (_: Exception) {}

        // Drop editable copies of photos the user deleted (from here or another gallery app).
        if (querySucceeded && photos.isNotEmpty()) {
            editStore.prune(photos.map { it.displayName }.toSet())
        }

        _photosFlow.value = photos
        _latestPhotoFlow.value = photos.firstOrNull()
        photos
    }

    /**
     * Writes a stamped JPEG to DCIM/Camera and returns the new [PhotoItem].
     * The in-memory photo list is updated by prepending, instead of re-querying the whole library.
     *
     * @param sourceJpeg the original camera JPEG; its exposure/lens EXIF tags are carried over.
     */
    suspend fun saveImage(
        jpegBytes: ByteArray,
        displayName: String,
        stampData: StampData?,
        isOriginal: Boolean = false,
        sourceJpeg: ByteArray? = null
    ): PhotoItem? = withContext(Dispatchers.IO) {
        val fileName = if (isOriginal) {
            "${displayName.removeSuffix(".jpg")}_original.jpg"
        } else {
            if (displayName.endsWith(".jpg")) displayName else "$displayName.jpg"
        }
        val takenAt = stampData?.timestamp ?: System.currentTimeMillis()

        // Write EXIF to a temp file first so ExifInterface operates on a real file without MediaStore stream limitations
        // Raw camera JPEGs (originals) rely on their EXIF orientation tag; stamped output is already upright.
        val tempFile = writeTempWithExif(jpegBytes, stampData, sourceJpeg, keepOrientation = isOriginal)

        rememberCoordinates(fileName, stampData)

        // Save directly to DCIM/Camera so photos immediately show up in the main camera roll of Google Photos & native Gallery
        val relativePath = "${Environment.DIRECTORY_DCIM}/Camera"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, takenAt)
            putLocationColumns(this, stampData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null
        try {
            uri = contentResolver.insert(collection, values) ?: return@withContext null

            contentResolver.openOutputStream(uri)?.use { os ->
                copyPayload(tempFile, jpegBytes, os)
            }
            tempFile?.delete()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }

            scanForThirdPartyGalleries(uri)

            val size = tempFile?.length()?.takeIf { it > 0 } ?: jpegBytes.size.toLong()
            val item = PhotoItem(
                id = ContentUris.parseId(uri),
                uri = uri,
                displayName = fileName,
                dateTaken = takenAt,
                sizeBytes = size,
                width = 0,
                height = 0,
                latitude = stampData?.latitude,
                longitude = stampData?.longitude,
                dateModified = System.currentTimeMillis() / 1000L,
                isEditable = false
            )
            if (!isOriginal) {
                val updated = listOf(item) + _photosFlow.value.filterNot { it.id == item.id }
                _photosFlow.value = updated.sortedByDescending { it.dateTaken }
                _latestPhotoFlow.value = _photosFlow.value.firstOrNull()
            }
            item
        } catch (e: Exception) {
            tempFile?.delete()
            uri?.let { contentResolver.delete(it, null, null) }
            null
        }
    }

    /** Marks an already-saved photo as editable once its [EditStore] entry has been written. */
    fun markEditable(photoId: Long) {
        _photosFlow.value = _photosFlow.value.map { if (it.id == photoId) it.copy(isEditable = true) else it }
        _latestPhotoFlow.value = _photosFlow.value.firstOrNull()
    }

    /**
     * Overwrites an existing photo (created by this app) with a re-stamped JPEG, updating its EXIF,
     * DATE_TAKEN and location columns. Reloads the library afterwards since the sort order may change.
     */
    suspend fun replaceImage(
        photo: PhotoItem,
        jpegBytes: ByteArray,
        stampData: StampData,
        sourceJpeg: ByteArray? = null
    ): PhotoItem? = withContext(Dispatchers.IO) {
        val tempFile = writeTempWithExif(jpegBytes, stampData, sourceJpeg)
        try {
            contentResolver.openOutputStream(photo.uri, "wt")?.use { os ->
                copyPayload(tempFile, jpegBytes, os)
            } ?: return@withContext null

            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATE_TAKEN, stampData.timestamp)
                    putLocationColumns(this, stampData)
                }
                contentResolver.update(photo.uri, values, null, null)
            } catch (_: Exception) {}

            rememberCoordinates(photo.displayName, stampData)
            scanForThirdPartyGalleries(photo.uri)

            loadPhotos().firstOrNull { it.id == photo.id }
        } catch (e: Exception) {
            null
        } finally {
            tempFile?.delete()
        }
    }

    private fun writeTempWithExif(
        jpegBytes: ByteArray,
        stampData: StampData?,
        sourceJpeg: ByteArray?,
        keepOrientation: Boolean = false
    ): File? {
        val tempFile = File(context.cacheDir, "geostamp_temp_${System.nanoTime()}.jpg")
        return try {
            FileOutputStream(tempFile).use { it.write(jpegBytes) }
            val exif = ExifInterface(tempFile.absolutePath)
            sourceJpeg?.let { copyCameraExif(it, exif) }
            writeExifTags(exif, stampData, keepOrientation)
            exif.saveAttributes()
            tempFile
        } catch (_: Exception) {
            tempFile.delete()
            null
        }
    }

    private fun copyPayload(tempFile: File?, jpegBytes: ByteArray, os: OutputStream) {
        if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
            tempFile.inputStream().use { it.copyTo(os, bufferSize = 256 * 1024) }
        } else {
            os.write(jpegBytes)
        }
        os.flush()
    }

    private fun rememberCoordinates(fileName: String, stampData: StampData?) {
        val lat = stampData?.latitude
        val lng = stampData?.longitude
        if (lat != null && lng != null) {
            coordsPrefs.edit().putString(fileName, "$lat,$lng").apply()
        } else {
            coordsPrefs.edit().remove(fileName).apply()
        }
    }

    private fun putLocationColumns(values: ContentValues, stampData: StampData?) {
        val lat = stampData?.latitude
        val lng = stampData?.longitude
        if (lat != null && lng != null) {
            @Suppress("DEPRECATION")
            values.put(MediaStore.Images.Media.LATITUDE, lat)
            @Suppress("DEPRECATION")
            values.put(MediaStore.Images.Media.LONGITUDE, lng)
            values.put(MediaStore.Images.Media.DESCRIPTION, "GeoStamp: $lat, $lng")
        }
    }

    /** Trigger a media scan so third-party gallery apps index the file without delay. */
    private fun scanForThirdPartyGalleries(uri: Uri) {
        try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (dataIdx >= 0) {
                        val path = cursor.getString(dataIdx)
                        if (!path.isNullOrBlank()) {
                            android.media.MediaScannerConnection.scanFile(
                                context,
                                arrayOf(path),
                                arrayOf("image/jpeg"),
                                null
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /** Re-encoding drops the camera's EXIF; carry over the exposure/lens tags photographers care about. */
    private fun copyCameraExif(sourceJpeg: ByteArray, target: ExifInterface) {
        try {
            val src = ExifInterface(ByteArrayInputStream(sourceJpeg))
            for (tag in CAMERA_EXIF_TAGS) {
                src.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            }
        } catch (_: Exception) {}
    }

    private fun writeExifTags(exif: ExifInterface, stampData: StampData?, keepOrientation: Boolean = false) {
        if (!keepOrientation) {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        }
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "GeoStamp Camera")

        val tz = stampData?.timeZone ?: TimeZone.getDefault()
        val now = Date(stampData?.timestamp ?: System.currentTimeMillis())
        val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = tz }
        val formattedDate = exifDateFormat.format(now)
        exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)

        // UTC offset lets MediaStore / Google Photos place the photo at the right instant.
        val offset = SimpleDateFormat("XXX", Locale.US).apply { timeZone = tz }.format(now)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, offset)

        val lat = stampData?.latitude
        val lng = stampData?.longitude
        if (lat != null && lng != null) {
            exif.setLatLong(lat, lng)

            stampData.altitude?.let { alt ->
                exif.setAltitude(alt)
            } ?: run {
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, null)
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, null)
            }

            // GPS Timestamp
            val timeSdf = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val dateSdf = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, timeSdf.format(now))
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, dateSdf.format(now))
        }
    }

    fun createDeleteIntentSender(uris: List<Uri>): PendingIntent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(contentResolver, uris)
        } else {
            null
        }
    }

    suspend fun deleteLegacy(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val count = contentResolver.delete(uri, null, null)
            loadPhotos()
            count > 0
        } catch (e: Exception) {
            false
        }
    }

    fun createShareIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createMapsIntent(lat: Double, lng: Double, label: String = "Photo Location"): Intent {
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createWebMapsIntent(lat: Double, lng: Double): Intent {
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun getCoordinatesForPhoto(displayName: String, uri: Uri): Pair<Double, Double>? {
        val key = if (displayName.endsWith(".jpg")) displayName else "$displayName.jpg"

        // 1. SharedPreferences fast cache (both with and without .jpg suffix)
        var cached = coordsPrefs.getString(key, null)
        if (cached == null) {
            cached = coordsPrefs.getString(key.removeSuffix(".jpg"), null)
        }
        if (cached != null) {
            val parts = cached.split(",")
            if (parts.size == 2) {
                val lat = parts[0].toDoubleOrNull()
                val lng = parts[1].toDoubleOrNull()
                if (lat != null && lng != null) return Pair(lat, lng)
            }
        }

        // 2. Query MediaStore for DESCRIPTION (written by GeoStamp) and DATA path
        try {
            val projection = arrayOf(
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DESCRIPTION
            )
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Check DESCRIPTION column (format: "GeoStamp: lat, lng")
                    val descIdx = cursor.getColumnIndex(MediaStore.Images.Media.DESCRIPTION)
                    if (descIdx >= 0) {
                        val desc = cursor.getString(descIdx)
                        if (!desc.isNullOrBlank()) {
                            val match = Regex("""GeoStamp:\s*([-\d.]+),\s*([-\d.]+)""").find(desc)
                            if (match != null) {
                                val lat = match.groupValues[1].toDoubleOrNull()
                                val lng = match.groupValues[2].toDoubleOrNull()
                                if (lat != null && lng != null) {
                                    val result = Pair(lat, lng)
                                    coordsPrefs.edit().putString(key, "${result.first},${result.second}").apply()
                                    return result
                                }
                            }
                        }
                    }

                    // Direct file path if available (bypasses MediaStore redaction)
                    val dataIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                    if (dataIdx >= 0) {
                        val path = cursor.getString(dataIdx)
                        if (!path.isNullOrBlank() && File(path).exists()) {
                            val exif = ExifInterface(path)
                            exif.latLong?.let { coords ->
                                if (coords.size >= 2) {
                                    val result = Pair(coords[0], coords[1])
                                    coordsPrefs.edit().putString(key, "${result.first},${result.second}").apply()
                                    return result
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Check known device storage locations directly with ExifInterface
        val candidateFiles = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera/$key"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GeoStampCamera/$key")
        )
        for (file in candidateFiles) {
            try {
                if (file.exists()) {
                    val exif = ExifInterface(file.absolutePath)
                    exif.latLong?.let { coords ->
                        if (coords.size >= 2) {
                            val result = Pair(coords[0], coords[1])
                            coordsPrefs.edit().putString(key, "${result.first},${result.second}").apply()
                            return result
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Content stream with MediaStore.setRequireOriginal
        try {
            val readUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
            } else {
                uri
            }
            contentResolver.openInputStream(readUri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.latLong?.let { coords ->
                    if (coords.size >= 2) {
                        val result = Pair(coords[0], coords[1])
                        coordsPrefs.edit().putString(key, "${result.first},${result.second}").apply()
                        return result
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    companion object {
        private val CAMERA_EXIF_TAGS = listOf(
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE
        )
    }
}
