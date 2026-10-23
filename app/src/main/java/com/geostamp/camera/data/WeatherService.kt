package com.geostamp.camera.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    // The archive endpoint is noticeably slower than the live one.
    private val historyClient: OkHttpClient by lazy {
        httpClient.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
    }

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

    /**
     * Weather at a specific place and instant (past or near future), for re-stamping edited photos.
     * Uses Open-Meteo's forecast API (which also serves roughly the last 3 months) when it can,
     * and the historical archive otherwise. Picks the hourly sample nearest [timestampMillis].
     * Returns null on any failure — callers should drop stale weather rather than keep it.
     */
    suspend fun fetchWeatherAt(lat: Double, lng: Double, timestampMillis: Long): WeatherResult? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val day = 24 * 60 * 60 * 1000L
            if (timestampMillis > now + 14 * day) return@withContext null

            // The archive lags real time by several days, so recent dates must come from the forecast API.
            val useForecast = timestampMillis >= now - 85 * day
            val host = if (useForecast) "https://api.open-meteo.com/v1/forecast" else "https://archive-api.open-meteo.com/v1/archive"
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
            // One hour of slack on each side so the nearest sample may fall on an adjacent UTC day.
            val startDate = dateFormat.format(Date(timestampMillis - 60 * 60 * 1000L))
            val endDate = dateFormat.format(Date(timestampMillis + 60 * 60 * 1000L))
            val url = String.format(
                Locale.US,
                "%s?latitude=%.5f&longitude=%.5f&hourly=temperature_2m,weather_code&start_date=%s&end_date=%s&timezone=GMT",
                host, lat, lng, startDate, endDate
            )

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "GeoStampCamera/1.0 (Android; Open-Meteo Client)")
                    .build()
                historyClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val hourly = JSONObject(body).optJSONObject("hourly") ?: return@withContext null
                    val times = hourly.optJSONArray("time") ?: return@withContext null
                    val temps = hourly.optJSONArray("temperature_2m") ?: return@withContext null
                    val codes = hourly.optJSONArray("weather_code") ?: return@withContext null
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }

                    var bestIndex = -1
                    var bestDelta = Long.MAX_VALUE
                    for (i in 0 until times.length()) {
                        // The archive reports null for hours it has no data for yet.
                        if (temps.isNull(i) || codes.isNull(i)) continue
                        val t = try { timeFormat.parse(times.getString(i))?.time } catch (_: Exception) { null } ?: continue
                        val delta = abs(t - timestampMillis)
                        if (delta < bestDelta) {
                            bestDelta = delta
                            bestIndex = i
                        }
                    }
                    // Nearest sample more than 90 minutes away means the requested hour itself is missing.
                    if (bestIndex < 0 || bestDelta > 90 * 60 * 1000L) return@withContext null

                    val code = codes.getInt(bestIndex)
                    WeatherResult(
                        temperatureCelsius = temps.getDouble(bestIndex).toFloat(),
                        weatherDescription = wmoCodeToDescription(code),
                        weatherCode = code
                    )
                }
            } catch (_: Exception) {
                null
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
