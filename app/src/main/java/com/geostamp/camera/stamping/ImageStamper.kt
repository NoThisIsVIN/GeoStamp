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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImageStamper {

    /** Full-resolution render, encoded to JPEG. Falls back to half resolution on OOM. */
    suspend fun stampImage(
        jpegBytes: ByteArray,
        stampData: StampData,
        stampSettings: StampSettings,
        appSettings: AppSettings,
        isFrontCamera: Boolean
    ): Result<ByteArray> = withContext(Dispatchers.Default) {
        try {
            Result.success(renderAndEncode(jpegBytes, stampData, stampSettings, appSettings, isFrontCamera, sampleSize = 1))
        } catch (oom: OutOfMemoryError) {
            System.gc()
            try {
                Result.success(renderAndEncode(jpegBytes, stampData, stampSettings, appSettings, isFrontCamera, sampleSize = 2))
            } catch (oom2: OutOfMemoryError) {
                System.gc()
                Result.failure(Exception("Image too large to process in memory (Out of Memory)."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downsampled render (longest side roughly <= [maxDimension]) for on-screen previews.
     * The stamp is laid out relative to image width, so the result is a faithful WYSIWYG preview.
     */
    suspend fun renderPreview(
        jpegBytes: ByteArray,
        stampData: StampData,
        stampSettings: StampSettings,
        appSettings: AppSettings,
        isFrontCamera: Boolean,
        maxDimension: Int = 1600
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = renderBase(jpegBytes, appSettings, isFrontCamera, sampleSizeFor(jpegBytes, maxDimension), applyColor = true)
        drawStamp(bitmap, stampData, stampSettings, appSettings)
        bitmap
    }

    /** Fast, small upright thumbnail of a raw capture (no stamp, no color pass) for instant UI feedback. */
    suspend fun decodeThumbnail(
        jpegBytes: ByteArray,
        appSettings: AppSettings,
        isFrontCamera: Boolean,
        maxDimension: Int = 320
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            renderBase(jpegBytes, appSettings, isFrontCamera, sampleSizeFor(jpegBytes, maxDimension), applyColor = false)
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderAndEncode(
        jpegBytes: ByteArray,
        stampData: StampData,
        stampSettings: StampSettings,
        appSettings: AppSettings,
        isFrontCamera: Boolean,
        sampleSize: Int
    ): ByteArray {
        val bitmap = renderBase(jpegBytes, appSettings, isFrontCamera, sampleSize, applyColor = true)
        drawStamp(bitmap, stampData, stampSettings, appSettings)
        val baos = ByteArrayOutputStream(bitmap.width * bitmap.height / 3)
        bitmap.compress(Bitmap.CompressFormat.JPEG, appSettings.captureQuality.jpegQuality, baos)
        bitmap.recycle()
        return baos.toByteArray()
    }

    private fun sampleSizeFor(jpegBytes: ByteArray, maxDimension: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    /**
     * Decodes the camera JPEG and produces an upright, mirrored-as-configured, aspect-cropped,
     * color-enhanced mutable bitmap in a single draw pass (one extra full-size allocation instead of
     * separate rotate + color copies).
     */
    private fun renderBase(
        jpegBytes: ByteArray,
        appSettings: AppSettings,
        isFrontCamera: Boolean,
        sampleSize: Int,
        applyColor: Boolean
    ): Bitmap {
        val orientation = ExifInterface(ByteArrayInputStream(jpegBytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: throw IllegalStateException("Could not decode image bytes")

        val matrix = orientationMatrix(orientation, isFrontCamera, appSettings.mirrorSelfie)

        // Upright bounds of the transformed source.
        val mapped = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        matrix.mapRect(mapped)
        val uprightW = mapped.width()
        val uprightH = mapped.height()

        // Center-crop to the requested aspect ratio (orientation-aware: long side / short side).
        val target = appSettings.aspectRatio.ratio
        val isPortrait = uprightH >= uprightW
        val longSide = max(uprightW, uprightH)
        val shortSide = min(uprightW, uprightH)
        var outLong = longSide
        var outShort = shortSide
        val current = longSide / shortSide
        if (abs(current - target) / target > 0.02f) {
            if (current > target) outLong = shortSide * target else outShort = longSide / target
        }
        val outW = (if (isPortrait) outShort else outLong).toInt().coerceAtLeast(1)
        val outH = (if (isPortrait) outLong else outShort).toInt().coerceAtLeast(1)

        val vibrant = applyColor && appSettings.colorProfile == ColorProfile.VIBRANT
        if (matrix.isIdentity && outW == src.width && outH == src.height && !vibrant) {
            return if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        }

        // Shift transformed content so the centered crop window starts at the origin.
        matrix.postTranslate(-mapped.left - (uprightW - outW) / 2f, -mapped.top - (uprightH - outH) / 2f)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (vibrant) colorFilter = ColorMatrixColorFilter(vibrantMatrix())
        }
        Canvas(out).drawBitmap(src, matrix, paint)
        src.recycle()
        return out
    }

    private fun orientationMatrix(orientation: Int, isFrontCamera: Boolean, mirrorSelfie: Boolean): Matrix {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        // Front camera: un-mirror by default unless the user wants selfie mirroring.
        if (isFrontCamera && !mirrorSelfie) {
            matrix.postScale(-1f, 1f)
        }
        return matrix
    }

    /** +25% saturation followed by a +10% contrast curve. */
    private fun vibrantMatrix(): ColorMatrix {
        val scale = 1.10f
        val translate = (-0.5f * scale + 0.5f) * 255f
        return ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )).apply { preConcat(ColorMatrix().apply { setSaturation(1.25f) }) }
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
