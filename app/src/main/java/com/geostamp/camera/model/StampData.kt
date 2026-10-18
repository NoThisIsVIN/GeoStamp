package com.geostamp.camera.model

import android.graphics.Bitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

data class StampData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val accuracyMeters: Float? = null,
    val headingDegrees: Float? = null,
    val headingCardinal: String? = null,
    val streetAddress: String? = null,
    val locality: String? = null,
    val adminArea: String? = null,
    val postalCode: String? = null,
    val countryName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val timeZone: TimeZone = TimeZone.getDefault(),
    val temperatureCelsius: Float? = null,
    val weatherDescription: String? = null,
    val mapThumbnail: Bitmap? = null,
    val isLastKnown: Boolean = false,
    val hasFix: Boolean = false
) {
    fun formattedCoordinates(format: CoordinateFormat): String {
        val lat = latitude ?: return "No GPS"
        val lng = longitude ?: return "No GPS"

        return when (format) {
            CoordinateFormat.DECIMAL_DEGREES -> {
                val latDir = if (lat >= 0) "N" else "S"
                val lngDir = if (lng >= 0) "E" else "W"
                String.format(
                    Locale.US,
                    "%.5f° %s, %.5f° %s",
                    abs(lat), latDir,
                    abs(lng), lngDir
                )
            }
            CoordinateFormat.DMS -> {
                "${toDms(lat, true)}, ${toDms(lng, false)}"
            }
        }
    }

    private fun toDms(coordinate: Double, isLat: Boolean): String {
        val dir = if (isLat) {
            if (coordinate >= 0) "N" else "S"
        } else {
            if (coordinate >= 0) "E" else "W"
        }
        val absolute = abs(coordinate)
        val degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", degrees, minutes, seconds, dir)
    }

    fun formattedAltitude(unitSystem: UnitSystem): String {
        val alt = altitude ?: return ""
        return when (unitSystem) {
            UnitSystem.METRIC -> String.format(Locale.US, "Alt: %.1f m", alt)
            UnitSystem.IMPERIAL -> {
                val feet = alt * 3.28084
                String.format(Locale.US, "Alt: %.1f ft", feet)
            }
        }
    }

    fun formattedAccuracy(unitSystem: UnitSystem): String {
        val acc = accuracyMeters ?: return ""
        return when (unitSystem) {
            UnitSystem.METRIC -> String.format(Locale.US, "Acc: ±%.0f m", acc)
            UnitSystem.IMPERIAL -> {
                val feet = acc * 3.28084f
                String.format(Locale.US, "Acc: ±%.0f ft", feet)
            }
        }
    }

    fun formattedCompass(): String {
        val deg = headingDegrees ?: return ""
        val card = headingCardinal ?: ""
        return String.format(Locale.US, "%.0f° %s", deg, card).trim()
    }

    fun formattedDateTime(is24Hour: Boolean, dateFormatPattern: String): String {
        val timePattern = if (is24Hour) "HH:mm:ss" else "hh:mm:ss a"
        val pattern = "$dateFormatPattern $timePattern z"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        sdf.timeZone = timeZone
        return sdf.format(Date(timestamp))
    }

    fun formattedAddress(): String {
        val parts = mutableListOf<String>()
        streetAddress?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        locality?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        adminArea?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        postalCode?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        countryName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return if (parts.isEmpty()) "Location details unavailable" else parts.joinToString(", ")
    }

    fun formattedWeather(unitSystem: UnitSystem): String {
        val tempC = temperatureCelsius ?: return ""
        val desc = weatherDescription ?: ""
        val tempStr = when (unitSystem) {
            UnitSystem.METRIC -> String.format(Locale.US, "%.1f°C", tempC)
            UnitSystem.IMPERIAL -> {
                val tempF = tempC * 9f / 5f + 32f
                String.format(Locale.US, "%.1f°F", tempF)
            }
        }
        return if (desc.isNotBlank()) "$tempStr $desc" else tempStr
    }
}
