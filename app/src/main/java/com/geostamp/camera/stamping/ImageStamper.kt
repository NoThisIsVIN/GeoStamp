package com.geostamp.camera.stamping

import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import com.geostamp.camera.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

class ImageStamper {

    suspend fun stampImage(
        jpegBytes: ByteArray,
        stampData: StampData,
        stampSettings: StampSettings,
        appSettings: AppSettings,
        isFrontCamera: Boolean
    ): Result<ByteArray> = withContext(Dispatchers.Default) {
        try {
            // First attempt with full resolution
            val stampedBytes = processBitmap(jpegBytes, stampData, stampSettings, appSettings, isFrontCamera, sampleSize = 1)
            Result.success(stampedBytes)
        } catch (oom: OutOfMemoryError) {
            System.gc()
            try {
                // Retry with downsampled bitmap (1/2 size)
                val stampedBytes = processBitmap(jpegBytes, stampData, stampSettings, appSettings, isFrontCamera, sampleSize = 2)
                Result.success(stampedBytes)
            } catch (oom2: OutOfMemoryError) {
                System.gc()
                Result.failure(Exception("Image too large to process in memory (Out of Memory)."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun processBitmap(
        jpegBytes: ByteArray,
        stampData: StampData,
        stampSettings: StampSettings,
        appSettings: AppSettings,
        isFrontCamera: Boolean,
        sampleSize: Int
    ): ByteArray {
        // Read EXIF orientation before decoding
        val exif = ExifInterface(ByteArrayInputStream(jpegBytes))
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: throw IllegalStateException("Could not decode image bytes")

        // Apply rotation to make upright
        bitmap = applyExifRotation(bitmap, orientation, isFrontCamera, appSettings.mirrorSelfie)

        // Enhance colors if Vibrant profile is active
        if (appSettings.colorProfile == ColorProfile.VIBRANT) {
            bitmap = applyVibrantEnhancement(bitmap)
        }

        // Draw stamp overlay onto bitmap
        drawStamp(bitmap, stampData, stampSettings, appSettings)

        // Compress to JPEG at chosen quality
        val baos = ByteArrayOutputStream()
        val quality = appSettings.captureQuality.jpegQuality
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bitmap.recycle()

        return baos.toByteArray()
    }

    private fun applyExifRotation(
        bitmap: Bitmap,
        orientation: Int,
        isFrontCamera: Boolean,
        mirrorSelfie: Boolean
    ): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        // Handle front camera mirroring: by default we un-mirror unless mirrorSelfie is true
        if (isFrontCamera && !mirrorSelfie) {
            matrix.postScale(-1f, 1f)
        }

        return if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } else {
            bitmap
        }
    }

    private fun applyVibrantEnhancement(bitmap: Bitmap): Bitmap {
        val enhanced = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)

        // Boost saturation (+25%) for punchy, lifelike colors
        val cm = ColorMatrix().apply {
            setSaturation(1.25f)
        }
        // Subtle contrast curve (+10%) to lift shadows and deepen rich tones
        val scale = 1.10f
        val translate = (-0.5f * scale + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        contrastMatrix.preConcat(cm)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(contrastMatrix)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        if (enhanced != bitmap) bitmap.recycle()
        return enhanced
    }

    private fun drawStamp(
        bitmap: Bitmap,
        stampData: StampData,
        settings: StampSettings,
        appSettings: AppSettings
    ) {
        val canvas = Canvas(bitmap)
        val imgWidth = bitmap.width.toFloat()
        val imgHeight = bitmap.height.toFloat()

        when (settings.template) {
            StampTemplate.CLASSIC_BOTTOM_BAR -> drawClassicBar(canvas, imgWidth, imgHeight, stampData, settings, appSettings)
            StampTemplate.COMPACT_CORNER_BOX -> drawCompactBox(canvas, imgWidth, imgHeight, stampData, settings, appSettings)
            StampTemplate.FULL_WIDTH_LARGE_MAP -> drawFullWidthLargeMap(canvas, imgWidth, imgHeight, stampData, settings, appSettings)
            StampTemplate.MINIMAL_TEXT_ONLY -> drawMinimalTextOnly(canvas, imgWidth, imgHeight, stampData, settings, appSettings)
        }
    }

    private fun drawClassicBar(
        canvas: Canvas,
        width: Float,
        height: Float,
        data: StampData,
        settings: StampSettings,
        appSettings: AppSettings
    ) {
        val scale = width / 1000f * settings.textSizeScale
        val margin = 20f * scale
        val padding = 16f * scale
        val cornerRadius = 16f * scale

        // Build metadata text lines
        val lines = buildInfoLines(data, settings, appSettings)
        if (lines.isEmpty() && (!settings.showMap || data.mapThumbnail == null)) return

        val titleSize = 22f * scale
        val bodySize = 17f * scale
        val smallSize = 14f * scale

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = settings.textColor
            textSize = bodySize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = settings.textColor
            textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val mapSize = if (settings.showMap && data.mapThumbnail != null) (lines.size * 28f * scale).coerceIn(120f * scale, 220f * scale) else 0f
        val textWidth = (width - margin * 2 - padding * 2 - (if (mapSize > 0) mapSize + padding else 0f)).coerceAtLeast(100f)

        // Calculate height needed
        var contentHeight = 0f
        val layouts = mutableListOf<StaticLayout>()
        for ((idx, line) in lines.withIndex()) {
            val paint = if (idx == 0 && settings.customNote.isNotBlank()) titlePaint else textPaint
            val layout = makeStaticLayout(line, paint, textWidth.toInt())
            layouts.add(layout)
            contentHeight += layout.height + 4f * scale
        }

        val totalBoxHeight = max(contentHeight, mapSize) + padding * 2
        val top = if (settings.position == StampPosition.BOTTOM) {
            height - totalBoxHeight - margin
        } else {
            margin
        }

        val bgRect = RectF(margin, top, width - margin, top + totalBoxHeight)
        drawBackground(canvas, bgRect, cornerRadius, settings.backgroundOpacity)

        // Draw Map Thumbnail
        var textLeft = margin + padding
        if (mapSize > 0 && data.mapThumbnail != null) {
            val mapRect = RectF(
                textLeft,
                top + padding,
                textLeft + mapSize,
                top + padding + mapSize
            )
            drawThumbnail(canvas, data.mapThumbnail, mapRect, cornerRadius / 2f)
            textLeft += mapSize + padding
        }

        // Draw Text Lines
        var textY = top + padding
        for (layout in layouts) {
            canvas.save()
            canvas.translate(textLeft, textY)
            layout.draw(canvas)
            canvas.restore()
            textY += layout.height + 4f * scale
        }
    }

    private fun drawCompactBox(
        canvas: Canvas,
        width: Float,
        height: Float,
        data: StampData,
        settings: StampSettings,
        appSettings: AppSettings
    ) {
        val scale = width / 1000f * settings.textSizeScale
        val margin = 20f * scale
        val padding = 14f * scale
        val cornerRadius = 14f * scale

        val lines = buildInfoLines(data, settings, appSettings)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = settings.textColor
            textSize = 15f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val mapSize = if (settings.showMap && data.mapThumbnail != null) 90f * scale else 0f
        val maxBoxWidth = (width * 0.75f).coerceAtMost(width - margin * 2)
        val textWidth = (maxBoxWidth - padding * 2 - (if (mapSize > 0) mapSize + padding else 0f)).coerceAtLeast(100f)

        var contentHeight = 0f
        val layouts = mutableListOf<StaticLayout>()
        for (line in lines) {
            val layout = makeStaticLayout(line, textPaint, textWidth.toInt())
            layouts.add(layout)
            contentHeight += layout.height + 3f * scale
        }

        val boxHeight = max(contentHeight, mapSize) + padding * 2
        val top = if (settings.position == StampPosition.BOTTOM) height - boxHeight - margin else margin
        val left = margin
        val right = left + (if (mapSize > 0) mapSize + padding else 0f) + textWidth + padding * 2

        val bgRect = RectF(left, top, min(right, width - margin), top + boxHeight)
        drawBackground(canvas, bgRect, cornerRadius, settings.backgroundOpacity)

        var curX = left + padding
        if (mapSize > 0 && data.mapThumbnail != null) {
            val mapRect = RectF(curX, top + padding, curX + mapSize, top + padding + mapSize)
            drawThumbnail(canvas, data.mapThumbnail, mapRect, cornerRadius / 2f)
            curX += mapSize + padding
        }

        var curY = top + padding
        for (layout in layouts) {
            canvas.save()
            canvas.translate(curX, curY)
            layout.draw(canvas)
            canvas.restore()
            curY += layout.height + 3f * scale
        }
    }

    private fun drawFullWidthLargeMap(
        canvas: Canvas,
        width: Float,
        height: Float,
        data: StampData,
        settings: StampSettings,
        appSettings: AppSettings
    ) {
        val scale = width / 1000f * settings.textSizeScale
        val padding = 18f * scale

        val lines = buildInfoLines(data, settings, appSettings)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = settings.textColor
            textSize = 17f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val boldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = settings.textColor
            textSize = 21f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val mapSize = if (settings.showMap && data.mapThumbnail != null) 180f * scale else 0f
        val textWidth = (width - padding * 2 - (if (mapSize > 0) mapSize + padding else 0f)).coerceAtLeast(100f)

        var textBlockHeight = 0f
        val layouts = mutableListOf<StaticLayout>()
        for ((idx, line) in lines.withIndex()) {
            val paint = if (idx == 0 && (settings.customNote.isNotBlank() || settings.showAddress)) boldPaint else textPaint
            val layout = makeStaticLayout(line, paint, textWidth.toInt())
            layouts.add(layout)
            textBlockHeight += layout.height + 4f * scale
        }

        val boxHeight = max(textBlockHeight, mapSize) + padding * 2
        val top = if (settings.position == StampPosition.BOTTOM) height - boxHeight else 0f
        val bgRect = RectF(0f, top, width, top + boxHeight)

        drawBackground(canvas, bgRect, cornerRadius = 0f, settings.backgroundOpacity)

        var curX = padding
        if (mapSize > 0 && data.mapThumbnail != null) {
            val mapRect = RectF(curX, top + padding, curX + mapSize, top + padding + mapSize)
            drawThumbnail(canvas, data.mapThumbnail, mapRect, cornerRadius = 8f * scale)
            curX += mapSize + padding
        }

        var curY = top + padding
        for (layout in layouts) {
            canvas.save()
            canvas.translate(curX, curY)
            layout.draw(canvas)
            canvas.restore()
            curY += layout.height + 4f * scale
        }
    }

    private fun drawMinimalTextOnly(
        canvas: Canvas,
        width: Float,
        height: Float,
        data: StampData,
        settings: StampSettings,
        appSettings: AppSettings
    ) {
        val scale = width / 1000f * settings.textSizeScale
        val margin = 24f * scale
        val padding = 12f * scale
        val cornerRadius = 10f * scale

        val lines = buildInfoLines(data, settings, appSettings)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = settings.textColor
            textSize = 18f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f * scale, 1f * scale, 2f * scale, Color.argb(200, 0, 0, 0))
        }

        val textWidth = (width - margin * 2 - padding * 2).coerceAtLeast(100f)
        var totalTextHeight = 0f
        val layouts = mutableListOf<StaticLayout>()
        for (line in lines) {
            val layout = makeStaticLayout(line, textPaint, textWidth.toInt())
            layouts.add(layout)
            totalTextHeight += layout.height + 4f * scale
        }

        val boxHeight = totalTextHeight + padding * 2
        val top = if (settings.position == StampPosition.BOTTOM) height - boxHeight - margin else margin
        val bgRect = RectF(margin, top, width - margin, top + boxHeight)

        // Subtle dark pill background
        drawBackground(canvas, bgRect, cornerRadius, settings.backgroundOpacity.coerceAtMost(0.5f))

        var curY = top + padding
        for (layout in layouts) {
            canvas.save()
            canvas.translate(margin + padding, curY)
            layout.draw(canvas)
            canvas.restore()
            curY += layout.height + 4f * scale
        }
    }

    private fun buildInfoLines(
        data: StampData,
        settings: StampSettings,
        appSettings: AppSettings
    ): List<String> {
        val lines = mutableListOf<String>()

        // 1. Custom Note (if enabled and present)
        if (settings.showNote && settings.customNote.isNotBlank()) {
            lines.add(settings.customNote)
        }

        // 2. Full Address
        if (settings.showAddress) {
            val addr = data.formattedAddress()
            if (addr.isNotBlank()) lines.add(addr)
        }

        // 3. Coordinates & Status
        if (settings.showCoordinates) {
            var coordStr = data.formattedCoordinates(settings.coordinateFormat)
            if (data.isLastKnown) {
                coordStr += " (Last known)"
            }
            lines.add(coordStr)
        }

        // 4. Altitude, Accuracy, Compass
        val metaParts = mutableListOf<String>()
        if (settings.showAltitude) {
            val alt = data.formattedAltitude(appSettings.unitSystem)
            if (alt.isNotBlank()) metaParts.add(alt)
        }
        if (settings.showAccuracy) {
            val acc = data.formattedAccuracy(appSettings.unitSystem)
            if (acc.isNotBlank()) metaParts.add(acc)
        }
        if (settings.showCompass) {
            val comp = data.formattedCompass()
            if (comp.isNotBlank()) metaParts.add(comp)
        }
        if (metaParts.isNotEmpty()) {
            lines.add(metaParts.joinToString(" • "))
        }

        // 5. Date & Time + Weather
        val timeWeatherParts = mutableListOf<String>()
        if (settings.showDateTime) {
            timeWeatherParts.add(data.formattedDateTime(settings.is24Hour, settings.dateFormatPattern))
        }
        if (settings.showWeather) {
            val weather = data.formattedWeather(appSettings.unitSystem)
            if (weather.isNotBlank()) timeWeatherParts.add(weather)
        }
        if (timeWeatherParts.isNotEmpty()) {
            lines.add(timeWeatherParts.joinToString(" • "))
        }

        return lines
    }

    private fun makeStaticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, max(10, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, max(10, width), Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, true)
        }
    }

    private fun drawBackground(canvas: Canvas, rect: RectF, cornerRadius: Float, opacity: Float) {
        val alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 18, 22, 28)
            style = Paint.Style.FILL
        }
        if (cornerRadius > 0f) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        } else {
            canvas.drawRect(rect, bgPaint)
        }
    }

    private fun drawThumbnail(canvas: Canvas, thumb: Bitmap, destRect: RectF, cornerRadius: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val srcRect = Rect(0, 0, thumb.width, thumb.height)

        if (cornerRadius > 0f) {
            val clipPath = Path().apply {
                addRoundRect(destRect, cornerRadius, cornerRadius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(thumb, srcRect, destRect, paint)
            canvas.restore()

            // Border
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(120, 255, 255, 255)
                strokeWidth = 2f
                style = Paint.Style.STROKE
            }
            canvas.drawRoundRect(destRect, cornerRadius, cornerRadius, borderPaint)
        } else {
            canvas.drawBitmap(thumb, srcRect, destRect, paint)
        }
    }
}
