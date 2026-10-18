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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class PhotoRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

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
            MediaStore.Images.Media.HEIGHT
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

        try {
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Photo" else "Photo"
                    val dateTaken = if (dateCol >= 0) cursor.getLong(dateCol) else System.currentTimeMillis()
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0

                    photos.add(
                        PhotoItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            dateTaken = dateTaken,
                            sizeBytes = size,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        _photosFlow.value = photos
        _latestPhotoFlow.value = photos.firstOrNull()
        photos
    }

    suspend fun saveImage(
        jpegBytes: ByteArray,
        displayName: String,
        stampData: StampData?,
        isOriginal: Boolean = false
    ): Uri? = withContext(Dispatchers.IO) {
        val fileName = if (isOriginal) {
            "${displayName.removeSuffix(".jpg")}_original.jpg"
        } else {
            if (displayName.endsWith(".jpg")) displayName else "$displayName.jpg"
        }

        // Save directly to DCIM/Camera so photos immediately show up in the main camera roll of Google Photos & native Gallery
        val relativePath = "${Environment.DIRECTORY_DCIM}/Camera"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
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
                os.write(jpegBytes)
                os.flush()
            }

            // Write EXIF tags using ExifInterface on the Uri
            try {
                contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    writeExifTags(exif, stampData)
                    exif.saveAttributes()
                }
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }

            // Trigger system media scan so the photo is indexed immediately by Gallery and Google Photos
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

            // Refresh list
            loadPhotos()

            uri
        } catch (e: Exception) {
            uri?.let { contentResolver.delete(it, null, null) }
            null
        }
    }

    private fun writeExifTags(exif: ExifInterface, stampData: StampData?) {
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)

        val now = Date(stampData?.timestamp ?: System.currentTimeMillis())
        val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val formattedDate = exifDateFormat.format(now)
        exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)

        val lat = stampData?.latitude
        val lng = stampData?.longitude
        if (lat != null && lng != null) {
            exif.setLatLong(lat, lng)

            stampData.altitude?.let { alt ->
                exif.setAltitude(alt)
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
        return Intent(Intent.ACTION_VIEW, uri)
    }
}
