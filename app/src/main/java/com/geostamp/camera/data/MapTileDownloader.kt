package com.geostamp.camera.data

import android.content.Context
import android.graphics.*
import com.geostamp.camera.model.MapStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.*

class MapTileDownloader(private val context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val cacheDir = File(context.cacheDir, "osm_tiles").apply { mkdirs() }
    private val maxCacheSizeBytes = 50 * 1024 * 1024L // 50 MB

    private var cachedLat: Double? = null
    private var cachedLng: Double? = null
    private var cachedMapStyle: MapStyle? = null
    private var cachedThumbnail: Bitmap? = null

    suspend fun getMapThumbnail(
        lat: Double,
        lng: Double,
        zoom: Int = 16,
        mapStyle: MapStyle = MapStyle.SATELLITE
    ): Bitmap = withContext(Dispatchers.IO) {
        val lastLat = cachedLat
        val lastLng = cachedLng
        val lastBitmap = cachedThumbnail
        val lastStyle = cachedMapStyle

        if (lastLat != null && lastLng != null && lastBitmap != null && !lastBitmap.isRecycled && lastStyle == mapStyle) {
            val dist = distanceMeters(lastLat, lastLng, lat, lng)
            if (dist < 30.0) {
                return@withContext lastBitmap
            }
        }

        val bitmap = try {
            fetchAndStitchTiles(lat, lng, zoom, mapStyle)
        } catch (e: Exception) {
            createPlaceholderMap(lat, lng)
        }

        cachedLat = lat
        cachedLng = lng
        cachedThumbnail = bitmap
        cachedMapStyle = mapStyle
        pruneCacheIfNeeded()

        bitmap
    }

    /**
     * Renders a fresh thumbnail, bypassing the 30 m reuse cache (tiles still come from the disk cache).
     * The photo editor uses this so a small pin adjustment yields a correctly centred map.
     */
    suspend fun renderMapThumbnail(
        lat: Double,
        lng: Double,
        zoom: Int = 16,
        mapStyle: MapStyle = MapStyle.SATELLITE
    ): Bitmap = withContext(Dispatchers.IO) {
        val bitmap = try {
            fetchAndStitchTiles(lat, lng, zoom, mapStyle)
        } catch (e: Exception) {
            createPlaceholderMap(lat, lng)
        }
        pruneCacheIfNeeded()
        bitmap
    }

    /** One raw 256 px tile (disk-cached), or null when it can't be loaded. Used by the map picker. */
    suspend fun loadTileBitmap(zoom: Int, x: Int, y: Int, style: MapStyle): Bitmap? =
        withContext(Dispatchers.IO) {
            val n = 1 shl zoom
            if (zoom < 0 || y < 0 || y >= n) return@withContext null
            val bmp = loadTile(zoom, ((x % n) + n) % n, y, style)
            // Panning around can pull in hundreds of tiles, so prune periodically rather than never.
            if (tileLoadsSincePrune.incrementAndGet() >= 100) {
                tileLoadsSincePrune.set(0)
                pruneCacheIfNeeded()
            }
            bmp
        }

    private val tileLoadsSincePrune = java.util.concurrent.atomic.AtomicInteger(0)

    private fun fetchAndStitchTiles(lat: Double, lng: Double, zoom: Int, mapStyle: MapStyle): Bitmap {
        val n = 1 shl zoom
        val exactTileX = (lng + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val exactTileY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n

        val centerTileX = exactTileX.toInt()
        val centerTileY = exactTileY.toInt()

        val stitchedSize = 256 * 3
        val stitched = Bitmap.createBitmap(stitchedSize, stitchedSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitched)

        var loadedAny = false
        for (dx in -1..1) {
            for (dy in -1..1) {
                val tx = (centerTileX + dx + n) % n
                val ty = (centerTileY + dy + n) % n
                val tileBitmap = loadTile(zoom, tx, ty, mapStyle)
                if (tileBitmap != null) {
                    loadedAny = true
                    val posX = (dx + 1) * 256f
                    val posY = (dy + 1) * 256f
                    canvas.drawBitmap(tileBitmap, posX, posY, null)
                }
            }
        }

        if (!loadedAny) {
            stitched.recycle()
            return createPlaceholderMap(lat, lng)
        }

        // Exact pixel of user coordinate in stitched bitmap
        val userX = ((exactTileX - (centerTileX - 1)) * 256.0).toFloat()
        val userY = ((exactTileY - (centerTileY - 1)) * 256.0).toFloat()

        // Crop a 256x256 square around user coordinate
        val cropSize = 256
        val cropLeft = (userX - cropSize / 2f).coerceIn(0f, (stitchedSize - cropSize).toFloat())
        val cropTop = (userY - cropSize / 2f).coerceIn(0f, (stitchedSize - cropSize).toFloat())

        val resultBitmap = Bitmap.createBitmap(stitched, cropLeft.toInt(), cropTop.toInt(), cropSize, cropSize)
        stitched.recycle()

        // Draw pin and attribution
        val resultCanvas = Canvas(resultBitmap)
        val pinX = userX - cropLeft
        val pinY = userY - cropTop
        drawLocationPin(resultCanvas, pinX, pinY)
        drawAttribution(resultCanvas, cropSize, mapStyle.attribution)

        return resultBitmap
    }

    private fun loadTile(zoom: Int, x: Int, y: Int, mapStyle: MapStyle): Bitmap? {
        val ext = if (mapStyle == MapStyle.SATELLITE) "jpg" else "png"
        val tileFile = File(cacheDir, "${mapStyle.name.lowercase()}_${zoom}_${x}_${y}.$ext")
        if (tileFile.exists() && tileFile.length() > 0) {
            val bmp = BitmapFactory.decodeFile(tileFile.absolutePath)
            if (bmp != null) return bmp
        }

        return try {
            val url = when (mapStyle) {
                MapStyle.SATELLITE -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$zoom/$y/$x"
                MapStyle.STREET -> "https://tile.openstreetmap.org/$zoom/$x/$y.png"
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GeoStampCamera/1.0 (Android; contact: app@geostamp.camera)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                // Write-then-rename: the picker and thumbnail renderer may read the same tile concurrently.
                try {
                    val tmp = File(cacheDir, "${tileFile.name}.${System.nanoTime()}.tmp")
                    FileOutputStream(tmp).use { it.write(bytes) }
                    if (!tmp.renameTo(tileFile)) tmp.delete()
                } catch (_: Exception) {}
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun createPlaceholderMap(lat: Double, lng: Double, size: Int = 256): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Subtle gradient background resembling map grid
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                Color.rgb(230, 235, 238),
                Color.rgb(215, 225, 230),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Grid lines
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 120, 140, 160)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val step = size / 4f
        for (i in 1..3) {
            canvas.drawLine(i * step, 0f, i * step, size.toFloat(), gridPaint)
            canvas.drawLine(0f, i * step, size.toFloat(), i * step, gridPaint)
        }

        // Concentric radar circles
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 0, 120, 215)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val cx = size / 2f
        val cy = size / 2f
        canvas.drawCircle(cx, cy, step * 0.8f, circlePaint)
        canvas.drawCircle(cx, cy, step * 1.5f, circlePaint)

        // Pin in center
        drawLocationPin(canvas, cx, cy)

        // Offline status text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 90, 100)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Offline Map", cx, size - 28f, textPaint)

        val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 110, 120)
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        val coordText = String.format("%.3f, %.3f", lat, lng)
        canvas.drawText(coordText, cx, size - 10f, coordPaint)

        return bitmap
    }

    private fun drawLocationPin(canvas: Canvas, x: Float, y: Float) {
        val pinHeight = 32f
        val pinRadius = 11f
        val headY = y - pinHeight + pinRadius

        // Pin drop shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawOval(RectF(x - 8f, y - 2f, x + 8f, y + 4f), shadowPaint)

        // Pin Body Path
        val path = Path().apply {
            moveTo(x, y) // Pin tip
            lineTo(x - pinRadius * 0.9f, headY + pinRadius * 0.5f)
            arcTo(
                RectF(x - pinRadius, headY - pinRadius, x + pinRadius, headY + pinRadius),
                150f, 240f, false
            )
            close()
        }

        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 60, 60)
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, pinPaint)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
        }
        canvas.drawPath(path, strokePaint)

        // White dot inside pin
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, headY, 4f, dotPaint)
    }

    private fun drawAttribution(canvas: Canvas, size: Int, attributionText: String) {
        val bgPaint = Paint().apply {
            color = Color.argb(170, 0, 0, 0)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10f
            typeface = Typeface.DEFAULT
        }
        val textWidth = textPaint.measureText(attributionText)
        canvas.drawRect(size - textWidth - 8f, size - 15f, size.toFloat(), size.toFloat(), bgPaint)
        canvas.drawText(attributionText, size - textWidth - 4f, size - 4f, textPaint)
    }

    private fun pruneCacheIfNeeded() {
        try {
            val files = cacheDir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize > maxCacheSizeBytes) {
                // Delete oldest files first
                val sorted = files.sortedBy { it.lastModified() }
                for (file in sorted) {
                    val len = file.length()
                    if (file.delete()) {
                        totalSize -= len
                        if (totalSize < maxCacheSizeBytes * 0.8) break
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
