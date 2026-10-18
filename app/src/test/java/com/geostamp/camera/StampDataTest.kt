package com.geostamp.camera

import com.geostamp.camera.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class StampDataTest {

    @Test
    fun testCoordinatesFormattingDecimal() {
        val data = StampData(
            latitude = 37.77493,
            longitude = -122.41942
        )
        val formatted = data.formattedCoordinates(CoordinateFormat.DECIMAL_DEGREES)
        assertTrue(formatted.contains("37.77493° N"))
        assertTrue(formatted.contains("122.41942° W"))
    }

    @Test
    fun testCoordinatesFormattingDms() {
        val data = StampData(
            latitude = 37.77493,
            longitude = -122.41942
        )
        val formatted = data.formattedCoordinates(CoordinateFormat.DMS)
        assertTrue(formatted.contains("37°"))
        assertTrue(formatted.contains("N"))
        assertTrue(formatted.contains("122°"))
        assertTrue(formatted.contains("W"))
    }

    @Test
    fun testAltitudeFormatting() {
        val data = StampData(altitude = 100.0)
        val metric = data.formattedAltitude(UnitSystem.METRIC)
        assertEquals("Alt: 100.0 m", metric)

        val imperial = data.formattedAltitude(UnitSystem.IMPERIAL)
        assertTrue(imperial.startsWith("Alt: 328."))
        assertTrue(imperial.endsWith("ft"))
    }

    @Test
    fun testAccuracyFormatting() {
        val data = StampData(accuracyMeters = 5f)
        val metric = data.formattedAccuracy(UnitSystem.METRIC)
        assertEquals("Acc: ±5 m", metric)

        val imperial = data.formattedAccuracy(UnitSystem.IMPERIAL)
        assertTrue(imperial.startsWith("Acc: ±16"))
        assertTrue(imperial.endsWith("ft"))
    }

    @Test
    fun testAddressFormatting() {
        val data = StampData(
            streetAddress = "100 Market St",
            locality = "San Francisco",
            adminArea = "CA",
            postalCode = "94105",
            countryName = "USA"
        )
        val addr = data.formattedAddress()
        assertEquals("100 Market St, San Francisco, CA, 94105, USA", addr)
    }

    @Test
    fun testWeatherFormatting() {
        val data = StampData(
            temperatureCelsius = 20.0f,
            weatherDescription = "Clear sky"
        )
        val metric = data.formattedWeather(UnitSystem.METRIC)
        assertEquals("20.0°C Clear sky", metric)

        val imperial = data.formattedWeather(UnitSystem.IMPERIAL)
        assertEquals("68.0°F Clear sky", imperial)
    }

    @Test
    fun testDateTimeFormatting() {
        val data = StampData(
            timestamp = 1700000000000L, // Fixed timestamp
            timeZone = TimeZone.getTimeZone("UTC")
        )
        val formatted24 = data.formattedDateTime(is24Hour = true, dateFormatPattern = "yyyy-MM-dd")
        assertTrue(formatted24.startsWith("2023-11-14"))
        assertTrue(formatted24.contains("22:13:20"))
    }
}
