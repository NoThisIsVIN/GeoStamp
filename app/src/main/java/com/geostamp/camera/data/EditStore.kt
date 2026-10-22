package com.geostamp.camera.data

import android.content.Context
import com.geostamp.camera.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * Everything needed to re-render a photo's stamp later: the capture-time metadata,
 * the stamp layout that was used, and the camera/pipeline flags that affect the base image.
 */
data class EditRecord(
    val displayName: String,
    val stampData: StampData,
    val stampSettings: StampSettings,
    val isFrontCamera: Boolean,
    val mirrorSelfie: Boolean,
    val colorProfile: ColorProfile,
    val aspectRatio: AspectRatioChoice,
    val unitSystem: UnitSystem,
    val captureQuality: CaptureQuality,
    /** Capture-time values, kept so the editor can offer "reset to original". */
    val originalTimestamp: Long = stampData.timestamp,
    val originalLatitude: Double? = stampData.latitude,
    val originalLongitude: Double? = stampData.longitude,
    val edited: Boolean = false
) {
    /** AppSettings view of the pipeline flags, for passing to [com.geostamp.camera.stamping.ImageStamper]. */
    fun toAppSettings(): AppSettings = AppSettings(
        captureQuality = captureQuality,
        colorProfile = colorProfile,
        aspectRatio = aspectRatio,
        unitSystem = unitSystem,
        mirrorSelfie = mirrorSelfie
    )
}

/**
 * Private (app-internal, excluded from backup) store of unstamped camera JPEGs plus a JSON sidecar,
 * keyed by the stamped photo's MediaStore display name. Lets the user change location/date/time
 * after capture without stamping on top of an existing stamp.
 */
class EditStore(context: Context) {

    private val dir = File(context.filesDir, "editable").apply { mkdirs() }

    private fun key(displayName: String) = displayName.removeSuffix(".jpg")
    private fun sourceFile(displayName: String) = File(dir, "${key(displayName)}.jpg")
    private fun recordFile(displayName: String) = File(dir, "${key(displayName)}.json")

    fun has(displayName: String): Boolean =
        sourceFile(displayName).exists() && recordFile(displayName).exists()

    suspend fun save(rawJpeg: ByteArray, record: EditRecord) = withContext(Dispatchers.IO) {
        try {
            sourceFile(record.displayName).writeBytes(rawJpeg)
            writeRecord(record)
        } catch (_: Exception) {
            delete(record.displayName)
        }
    }

    suspend fun loadRecord(displayName: String): EditRecord? = withContext(Dispatchers.IO) {
        try {
            val f = recordFile(displayName)
            if (!f.exists()) null else fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun loadSource(displayName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            sourceFile(displayName).takeIf { it.exists() }?.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun updateRecord(record: EditRecord) = withContext(Dispatchers.IO) {
        try { writeRecord(record) } catch (_: Exception) {}
    }

    fun delete(displayName: String) {
        sourceFile(displayName).delete()
        recordFile(displayName).delete()
    }

    /** Removes entries whose stamped photo no longer exists in MediaStore. */
    suspend fun prune(existingDisplayNames: Set<String>) = withContext(Dispatchers.IO) {
        val keep = existingDisplayNames.map { key(it) }.toSet()
        // Grace period: a capture writes its MediaStore row before its edit entry, so a concurrent
        // library query may not include it yet.
        val cutoff = System.currentTimeMillis() - 2 * 60_000L
        dir.listFiles()?.forEach { f ->
            val k = f.name.substringBefore('.')
            if (k !in keep && f.lastModified() < cutoff) f.delete()
        }
    }

    fun totalSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeRecord(record: EditRecord) {
        val tmp = File(dir, "${key(record.displayName)}.json.tmp")
        tmp.writeText(toJson(record).toString())
        tmp.renameTo(recordFile(record.displayName))
    }

    private fun toJson(r: EditRecord): JSONObject {
        val d = r.stampData
        val s = r.stampSettings
        return JSONObject().apply {
            put("version", 1)
            put("displayName", r.displayName)
            put("isFrontCamera", r.isFrontCamera)
            put("mirrorSelfie", r.mirrorSelfie)
            put("colorProfile", r.colorProfile.name)
            put("aspectRatio", r.aspectRatio.name)
            put("unitSystem", r.unitSystem.name)
            put("captureQuality", r.captureQuality.name)
            put("originalTimestamp", r.originalTimestamp)
            r.originalLatitude?.let { put("originalLatitude", it) }
            r.originalLongitude?.let { put("originalLongitude", it) }
            put("edited", r.edited)
            put("stampData", JSONObject().apply {
                d.latitude?.let { put("latitude", it) }
                d.longitude?.let { put("longitude", it) }
                d.altitude?.let { put("altitude", it) }
                d.accuracyMeters?.let { put("accuracyMeters", it.toDouble()) }
                d.headingDegrees?.let { put("headingDegrees", it.toDouble()) }
                d.headingCardinal?.let { put("headingCardinal", it) }
                d.streetAddress?.let { put("streetAddress", it) }
                d.locality?.let { put("locality", it) }
                d.adminArea?.let { put("adminArea", it) }
                d.postalCode?.let { put("postalCode", it) }
                d.countryName?.let { put("countryName", it) }
                put("timestamp", d.timestamp)
                put("timeZone", d.timeZone.id)
                d.temperatureCelsius?.let { put("temperatureCelsius", it.toDouble()) }
                d.weatherDescription?.let { put("weatherDescription", it) }
                put("isLastKnown", d.isLastKnown)
                put("hasFix", d.hasFix)
            })
            put("stampSettings", JSONObject().apply {
                put("template", s.template.name)
                put("position", s.position.name)
                put("mapStyle", s.mapStyle.name)
                put("textSizeScale", s.textSizeScale.toDouble())
                put("backgroundOpacity", s.backgroundOpacity.toDouble())
                put("textColor", s.textColor)
                put("showMap", s.showMap)
                put("showAddress", s.showAddress)
                put("showCoordinates", s.showCoordinates)
                put("showAltitude", s.showAltitude)
                put("showAccuracy", s.showAccuracy)
                put("showCompass", s.showCompass)
                put("showDateTime", s.showDateTime)
                put("showWeather", s.showWeather)
                put("showNote", s.showNote)
                put("coordinateFormat", s.coordinateFormat.name)
                put("is24Hour", s.is24Hour)
                put("dateFormatPattern", s.dateFormatPattern)
                put("customNote", s.customNote)
            })
        }
    }

    private fun fromJson(o: JSONObject): EditRecord {
        val d = o.getJSONObject("stampData")
        val s = o.getJSONObject("stampSettings")
        val defaults = StampSettings()
        val stampData = StampData(
            latitude = d.optDoubleOrNull("latitude"),
            longitude = d.optDoubleOrNull("longitude"),
            altitude = d.optDoubleOrNull("altitude"),
            accuracyMeters = d.optDoubleOrNull("accuracyMeters")?.toFloat(),
            headingDegrees = d.optDoubleOrNull("headingDegrees")?.toFloat(),
            headingCardinal = d.optStringOrNull("headingCardinal"),
            streetAddress = d.optStringOrNull("streetAddress"),
            locality = d.optStringOrNull("locality"),
            adminArea = d.optStringOrNull("adminArea"),
            postalCode = d.optStringOrNull("postalCode"),
            countryName = d.optStringOrNull("countryName"),
            timestamp = d.getLong("timestamp"),
            timeZone = TimeZone.getTimeZone(d.optString("timeZone", TimeZone.getDefault().id)),
            temperatureCelsius = d.optDoubleOrNull("temperatureCelsius")?.toFloat(),
            weatherDescription = d.optStringOrNull("weatherDescription"),
            isLastKnown = d.optBoolean("isLastKnown", false),
            hasFix = d.optBoolean("hasFix", false)
        )
        val stampSettings = StampSettings(
            template = enumOr(s.optString("template"), defaults.template),
            position = enumOr(s.optString("position"), defaults.position),
            mapStyle = enumOr(s.optString("mapStyle"), defaults.mapStyle),
            textSizeScale = s.optDouble("textSizeScale", defaults.textSizeScale.toDouble()).toFloat(),
            backgroundOpacity = s.optDouble("backgroundOpacity", defaults.backgroundOpacity.toDouble()).toFloat(),
            textColor = s.optInt("textColor", defaults.textColor),
            showMap = s.optBoolean("showMap", defaults.showMap),
            showAddress = s.optBoolean("showAddress", defaults.showAddress),
            showCoordinates = s.optBoolean("showCoordinates", defaults.showCoordinates),
            showAltitude = s.optBoolean("showAltitude", defaults.showAltitude),
            showAccuracy = s.optBoolean("showAccuracy", defaults.showAccuracy),
            showCompass = s.optBoolean("showCompass", defaults.showCompass),
            showDateTime = s.optBoolean("showDateTime", defaults.showDateTime),
            showWeather = s.optBoolean("showWeather", defaults.showWeather),
            showNote = s.optBoolean("showNote", defaults.showNote),
            coordinateFormat = enumOr(s.optString("coordinateFormat"), defaults.coordinateFormat),
            is24Hour = s.optBoolean("is24Hour", defaults.is24Hour),
            dateFormatPattern = s.optString("dateFormatPattern", defaults.dateFormatPattern),
            customNote = s.optString("customNote", "")
        )
        return EditRecord(
            displayName = o.getString("displayName"),
            stampData = stampData,
            stampSettings = stampSettings,
            isFrontCamera = o.optBoolean("isFrontCamera", false),
            mirrorSelfie = o.optBoolean("mirrorSelfie", false),
            colorProfile = enumOr(o.optString("colorProfile"), ColorProfile.VIBRANT),
            aspectRatio = enumOr(o.optString("aspectRatio"), AspectRatioChoice.RATIO_4_3),
            unitSystem = enumOr(o.optString("unitSystem"), UnitSystem.METRIC),
            captureQuality = enumOr(o.optString("captureQuality"), CaptureQuality.HIGH),
            originalTimestamp = o.optLong("originalTimestamp", stampData.timestamp),
            originalLatitude = o.optDoubleOrNull("originalLatitude"),
            originalLongitude = o.optDoubleOrNull("originalLongitude"),
            edited = o.optBoolean("edited", false)
        )
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) optString(name) else null

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        try { if (name.isNullOrEmpty()) default else enumValueOf<T>(name) } catch (_: Exception) { default }
}
