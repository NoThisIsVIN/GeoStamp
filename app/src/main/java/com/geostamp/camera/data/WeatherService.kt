package com.geostamp.camera.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class WeatherResult(
    val temperatureCelsius: Float,
    val weatherDescription: String,
    val weatherCode: Int
)

class WeatherService {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private var cachedLat: Double? = null
    private var cachedLng: Double? = null
    private var cachedTimestamp: Long = 0L
    private var cachedWeather: WeatherResult? = null

    suspend fun fetchWeather(lat: Double, lng: Double): WeatherResult? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastLat = cachedLat
        val lastLng = cachedLng
        val lastWeather = cachedWeather

        // Cache for 20 minutes if within 1km
        if (lastLat != null && lastLng != null && lastWeather != null) {
            val isRecent = (now - cachedTimestamp) < 20 * 60 * 1000L
            val isClose = distanceMeters(lastLat, lastLng, lat, lng) < 1000.0
            if (isRecent && isClose) {
                return@withContext lastWeather
            }
        }

        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,weather_code"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "GeoStampCamera/1.0 (Android; Open-Meteo Client)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext lastWeather
                val bodyStr = response.body?.string() ?: return@withContext lastWeather
                val json = JSONObject(bodyStr)
                val current = json.optJSONObject("current") ?: return@withContext lastWeather

                val temp = current.getDouble("temperature_2m").toFloat()
                val code = current.getInt("weather_code")
                val desc = wmoCodeToDescription(code)

                val result = WeatherResult(
                    temperatureCelsius = temp,
                    weatherDescription = desc,
                    weatherCode = code
                )

                cachedLat = lat
                cachedLng = lng
                cachedTimestamp = now
                cachedWeather = result

                result
            }
        } catch (_: Exception) {
            // Offline or network error: return cached or null gracefully
            lastWeather
        }
    }

    private fun wmoCodeToDescription(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45 -> "Fog"
            48 -> "Depositing rime fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63 -> "Rain"
            65 -> "Heavy rain"
            66, 67 -> "Freezing rain"
            71, 73 -> "Snow fall"
            75 -> "Heavy snow fall"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Fair"
        }
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
