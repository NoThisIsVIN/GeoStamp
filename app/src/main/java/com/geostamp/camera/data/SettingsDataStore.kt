package com.geostamp.camera.data

import android.content.Context
import android.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.geostamp.camera.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "geostamp_settings")

class SettingsDataStore(private val context: Context) {

    private object PreferencesKeys {
        // Stamp Settings
        val STAMP_TEMPLATE = stringPreferencesKey("stamp_template")
        val STAMP_POSITION = stringPreferencesKey("stamp_position")
        val MAP_STYLE = stringPreferencesKey("map_style")
        val TEXT_SIZE_SCALE = floatPreferencesKey("text_size_scale")
        val BG_OPACITY = floatPreferencesKey("bg_opacity")
        val TEXT_COLOR = intPreferencesKey("text_color")
        val SHOW_MAP = booleanPreferencesKey("show_map")
        val SHOW_ADDRESS = booleanPreferencesKey("show_address")
        val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        val SHOW_ALTITUDE = booleanPreferencesKey("show_altitude")
        val SHOW_ACCURACY = booleanPreferencesKey("show_accuracy")
        val SHOW_COMPASS = booleanPreferencesKey("show_compass")
        val SHOW_DATE_TIME = booleanPreferencesKey("show_date_time")
        val SHOW_WEATHER = booleanPreferencesKey("show_weather")
        val SHOW_NOTE = booleanPreferencesKey("show_note")
        val COORDINATE_FORMAT = stringPreferencesKey("coordinate_format")
        val IS_24_HOUR = booleanPreferencesKey("is_24_hour")
        val DATE_FORMAT_PATTERN = stringPreferencesKey("date_format_pattern")
        val CUSTOM_NOTE = stringPreferencesKey("custom_note")

        // App Settings
        val CAPTURE_QUALITY = stringPreferencesKey("capture_quality")
        val COLOR_PROFILE = stringPreferencesKey("color_profile")
        val CAPTURE_LATENCY_MODE = booleanPreferencesKey("capture_latency_mode")
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        val TIMER_OPTION = intPreferencesKey("timer_option")
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val PLAY_SHUTTER_SOUND = booleanPreferencesKey("play_shutter_sound")
        val SAVE_UNSTAMPED = booleanPreferencesKey("save_unstamped")
        val MIRROR_SELFIE = booleanPreferencesKey("mirror_selfie")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val ALLOW_CAPTURE_WITHOUT_GPS = booleanPreferencesKey("allow_capture_without_gps")
        val FILE_NAME_PATTERN = stringPreferencesKey("file_name_pattern")
        val BATTERY_TIP_DISMISSED = booleanPreferencesKey("battery_tip_dismissed")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val stampSettingsFlow: Flow<StampSettings> = context.dataStore.data.map { prefs ->
        StampSettings(
            template = try {
                StampTemplate.valueOf(prefs[PreferencesKeys.STAMP_TEMPLATE] ?: StampTemplate.CLASSIC_BOTTOM_BAR.name)
            } catch (e: Exception) { StampTemplate.CLASSIC_BOTTOM_BAR },
            position = try {
                StampPosition.valueOf(prefs[PreferencesKeys.STAMP_POSITION] ?: StampPosition.BOTTOM.name)
            } catch (e: Exception) { StampPosition.BOTTOM },
            mapStyle = try {
                MapStyle.valueOf(prefs[PreferencesKeys.MAP_STYLE] ?: MapStyle.SATELLITE.name)
            } catch (e: Exception) { MapStyle.SATELLITE },
            textSizeScale = prefs[PreferencesKeys.TEXT_SIZE_SCALE] ?: 1.0f,
            backgroundOpacity = prefs[PreferencesKeys.BG_OPACITY] ?: 0.65f,
            textColor = prefs[PreferencesKeys.TEXT_COLOR] ?: Color.WHITE,
            showMap = prefs[PreferencesKeys.SHOW_MAP] ?: true,
            showAddress = prefs[PreferencesKeys.SHOW_ADDRESS] ?: true,
            showCoordinates = prefs[PreferencesKeys.SHOW_COORDINATES] ?: true,
            showAltitude = prefs[PreferencesKeys.SHOW_ALTITUDE] ?: true,
            showAccuracy = prefs[PreferencesKeys.SHOW_ACCURACY] ?: true,
            showCompass = prefs[PreferencesKeys.SHOW_COMPASS] ?: true,
            showDateTime = prefs[PreferencesKeys.SHOW_DATE_TIME] ?: true,
            showWeather = prefs[PreferencesKeys.SHOW_WEATHER] ?: true,
            showNote = prefs[PreferencesKeys.SHOW_NOTE] ?: true,
            coordinateFormat = try {
                CoordinateFormat.valueOf(prefs[PreferencesKeys.COORDINATE_FORMAT] ?: CoordinateFormat.DECIMAL_DEGREES.name)
            } catch (e: Exception) { CoordinateFormat.DECIMAL_DEGREES },
            is24Hour = prefs[PreferencesKeys.IS_24_HOUR] ?: true,
            dateFormatPattern = prefs[PreferencesKeys.DATE_FORMAT_PATTERN] ?: "yyyy-MM-dd",
            customNote = prefs[PreferencesKeys.CUSTOM_NOTE] ?: ""
        )
    }

    val appSettingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            captureQuality = try {
                CaptureQuality.valueOf(prefs[PreferencesKeys.CAPTURE_QUALITY] ?: CaptureQuality.HIGH.name)
            } catch (e: Exception) { CaptureQuality.HIGH },
            colorProfile = try {
                ColorProfile.valueOf(prefs[PreferencesKeys.COLOR_PROFILE] ?: ColorProfile.VIBRANT.name)
            } catch (e: Exception) { ColorProfile.VIBRANT },
            captureModeLatency = prefs[PreferencesKeys.CAPTURE_LATENCY_MODE] ?: false,
            aspectRatio = try {
                AspectRatioChoice.valueOf(prefs[PreferencesKeys.ASPECT_RATIO] ?: AspectRatioChoice.RATIO_4_3.name)
            } catch (e: Exception) { AspectRatioChoice.RATIO_4_3 },
            timerOption = when (prefs[PreferencesKeys.TIMER_OPTION] ?: 0) {
                3 -> TimerOption.SEC_3
                10 -> TimerOption.SEC_10
                else -> TimerOption.OFF
            },
            unitSystem = try {
                UnitSystem.valueOf(prefs[PreferencesKeys.UNIT_SYSTEM] ?: UnitSystem.METRIC.name)
            } catch (e: Exception) { UnitSystem.METRIC },
            playShutterSound = prefs[PreferencesKeys.PLAY_SHUTTER_SOUND] ?: true,
            saveUnstampedOriginal = prefs[PreferencesKeys.SAVE_UNSTAMPED] ?: false,
            mirrorSelfie = prefs[PreferencesKeys.MIRROR_SELFIE] ?: false,
            showGrid = prefs[PreferencesKeys.SHOW_GRID] ?: false,
            allowCaptureWithoutGps = prefs[PreferencesKeys.ALLOW_CAPTURE_WITHOUT_GPS] ?: true,
            fileNamePattern = prefs[PreferencesKeys.FILE_NAME_PATTERN] ?: "GeoStamp_yyyyMMdd_HHmmss",
            batteryTipDismissed = prefs[PreferencesKeys.BATTERY_TIP_DISMISSED] ?: false,
            onboardingCompleted = prefs[PreferencesKeys.ONBOARDING_COMPLETED] ?: false
        )
    }

    suspend fun updateStampSettings(transform: (StampSettings) -> StampSettings) {
        context.dataStore.edit { prefs ->
            val current = StampSettings(
                template = try { StampTemplate.valueOf(prefs[PreferencesKeys.STAMP_TEMPLATE] ?: StampTemplate.CLASSIC_BOTTOM_BAR.name) } catch (e: Exception) { StampTemplate.CLASSIC_BOTTOM_BAR },
                position = try { StampPosition.valueOf(prefs[PreferencesKeys.STAMP_POSITION] ?: StampPosition.BOTTOM.name) } catch (e: Exception) { StampPosition.BOTTOM },
                mapStyle = try { MapStyle.valueOf(prefs[PreferencesKeys.MAP_STYLE] ?: MapStyle.SATELLITE.name) } catch (e: Exception) { MapStyle.SATELLITE },
                textSizeScale = prefs[PreferencesKeys.TEXT_SIZE_SCALE] ?: 1.0f,
                backgroundOpacity = prefs[PreferencesKeys.BG_OPACITY] ?: 0.65f,
                textColor = prefs[PreferencesKeys.TEXT_COLOR] ?: Color.WHITE,
                showMap = prefs[PreferencesKeys.SHOW_MAP] ?: true,
                showAddress = prefs[PreferencesKeys.SHOW_ADDRESS] ?: true,
                showCoordinates = prefs[PreferencesKeys.SHOW_COORDINATES] ?: true,
                showAltitude = prefs[PreferencesKeys.SHOW_ALTITUDE] ?: true,
                showAccuracy = prefs[PreferencesKeys.SHOW_ACCURACY] ?: true,
                showCompass = prefs[PreferencesKeys.SHOW_COMPASS] ?: true,
                showDateTime = prefs[PreferencesKeys.SHOW_DATE_TIME] ?: true,
                showWeather = prefs[PreferencesKeys.SHOW_WEATHER] ?: true,
                showNote = prefs[PreferencesKeys.SHOW_NOTE] ?: true,
                coordinateFormat = try { CoordinateFormat.valueOf(prefs[PreferencesKeys.COORDINATE_FORMAT] ?: CoordinateFormat.DECIMAL_DEGREES.name) } catch (e: Exception) { CoordinateFormat.DECIMAL_DEGREES },
                is24Hour = prefs[PreferencesKeys.IS_24_HOUR] ?: true,
                dateFormatPattern = prefs[PreferencesKeys.DATE_FORMAT_PATTERN] ?: "yyyy-MM-dd",
                customNote = prefs[PreferencesKeys.CUSTOM_NOTE] ?: ""
            )
            val updated = transform(current)
            prefs[PreferencesKeys.STAMP_TEMPLATE] = updated.template.name
            prefs[PreferencesKeys.STAMP_POSITION] = updated.position.name
            prefs[PreferencesKeys.MAP_STYLE] = updated.mapStyle.name
            prefs[PreferencesKeys.TEXT_SIZE_SCALE] = updated.textSizeScale
            prefs[PreferencesKeys.BG_OPACITY] = updated.backgroundOpacity
            prefs[PreferencesKeys.TEXT_COLOR] = updated.textColor
            prefs[PreferencesKeys.SHOW_MAP] = updated.showMap
            prefs[PreferencesKeys.SHOW_ADDRESS] = updated.showAddress
            prefs[PreferencesKeys.SHOW_COORDINATES] = updated.showCoordinates
            prefs[PreferencesKeys.SHOW_ALTITUDE] = updated.showAltitude
            prefs[PreferencesKeys.SHOW_ACCURACY] = updated.showAccuracy
            prefs[PreferencesKeys.SHOW_COMPASS] = updated.showCompass
            prefs[PreferencesKeys.SHOW_DATE_TIME] = updated.showDateTime
            prefs[PreferencesKeys.SHOW_WEATHER] = updated.showWeather
            prefs[PreferencesKeys.SHOW_NOTE] = updated.showNote
            prefs[PreferencesKeys.COORDINATE_FORMAT] = updated.coordinateFormat.name
            prefs[PreferencesKeys.IS_24_HOUR] = updated.is24Hour
            prefs[PreferencesKeys.DATE_FORMAT_PATTERN] = updated.dateFormatPattern
            prefs[PreferencesKeys.CUSTOM_NOTE] = updated.customNote
        }
    }

    suspend fun updateAppSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = AppSettings(
                captureQuality = try { CaptureQuality.valueOf(prefs[PreferencesKeys.CAPTURE_QUALITY] ?: CaptureQuality.HIGH.name) } catch (e: Exception) { CaptureQuality.HIGH },
                colorProfile = try { ColorProfile.valueOf(prefs[PreferencesKeys.COLOR_PROFILE] ?: ColorProfile.VIBRANT.name) } catch (e: Exception) { ColorProfile.VIBRANT },
                captureModeLatency = prefs[PreferencesKeys.CAPTURE_LATENCY_MODE] ?: false,
                aspectRatio = try { AspectRatioChoice.valueOf(prefs[PreferencesKeys.ASPECT_RATIO] ?: AspectRatioChoice.RATIO_4_3.name) } catch (e: Exception) { AspectRatioChoice.RATIO_4_3 },
                timerOption = when (prefs[PreferencesKeys.TIMER_OPTION] ?: 0) {
                    3 -> TimerOption.SEC_3
                    10 -> TimerOption.SEC_10
                    else -> TimerOption.OFF
                },
                unitSystem = try { UnitSystem.valueOf(prefs[PreferencesKeys.UNIT_SYSTEM] ?: UnitSystem.METRIC.name) } catch (e: Exception) { UnitSystem.METRIC },
                playShutterSound = prefs[PreferencesKeys.PLAY_SHUTTER_SOUND] ?: true,
                saveUnstampedOriginal = prefs[PreferencesKeys.SAVE_UNSTAMPED] ?: false,
                mirrorSelfie = prefs[PreferencesKeys.MIRROR_SELFIE] ?: false,
                showGrid = prefs[PreferencesKeys.SHOW_GRID] ?: false,
                allowCaptureWithoutGps = prefs[PreferencesKeys.ALLOW_CAPTURE_WITHOUT_GPS] ?: true,
                fileNamePattern = prefs[PreferencesKeys.FILE_NAME_PATTERN] ?: "GeoStamp_yyyyMMdd_HHmmss",
                batteryTipDismissed = prefs[PreferencesKeys.BATTERY_TIP_DISMISSED] ?: false,
                onboardingCompleted = prefs[PreferencesKeys.ONBOARDING_COMPLETED] ?: false
            )
            val updated = transform(current)
            prefs[PreferencesKeys.CAPTURE_QUALITY] = updated.captureQuality.name
            prefs[PreferencesKeys.COLOR_PROFILE] = updated.colorProfile.name
            prefs[PreferencesKeys.CAPTURE_LATENCY_MODE] = updated.captureModeLatency
            prefs[PreferencesKeys.ASPECT_RATIO] = updated.aspectRatio.name
            prefs[PreferencesKeys.TIMER_OPTION] = updated.timerOption.seconds
            prefs[PreferencesKeys.UNIT_SYSTEM] = updated.unitSystem.name
            prefs[PreferencesKeys.PLAY_SHUTTER_SOUND] = updated.playShutterSound
            prefs[PreferencesKeys.SAVE_UNSTAMPED] = updated.saveUnstampedOriginal
            prefs[PreferencesKeys.MIRROR_SELFIE] = updated.mirrorSelfie
            prefs[PreferencesKeys.SHOW_GRID] = updated.showGrid
            prefs[PreferencesKeys.ALLOW_CAPTURE_WITHOUT_GPS] = updated.allowCaptureWithoutGps
            prefs[PreferencesKeys.FILE_NAME_PATTERN] = updated.fileNamePattern
            prefs[PreferencesKeys.BATTERY_TIP_DISMISSED] = updated.batteryTipDismissed
            prefs[PreferencesKeys.ONBOARDING_COMPLETED] = updated.onboardingCompleted
        }
    }

    suspend fun setCustomNote(note: String) {
        updateStampSettings { it.copy(customNote = note) }
    }

    suspend fun setOnboardingCompleted() {
        updateAppSettings { it.copy(onboardingCompleted = true) }
    }

    suspend fun setBatteryTipDismissed() {
        updateAppSettings { it.copy(batteryTipDismissed = true) }
    }
}
