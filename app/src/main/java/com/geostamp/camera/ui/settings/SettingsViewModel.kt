package com.geostamp.camera.ui.settings

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geostamp.camera.GeoStampApplication
import com.geostamp.camera.data.MapTileDownloader
import com.geostamp.camera.data.SettingsDataStore
import com.geostamp.camera.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore: SettingsDataStore = (application as GeoStampApplication).settingsDataStore
    private val mapTileDownloader = MapTileDownloader(application)

    val stampSettings: StateFlow<StampSettings> = dataStore.stampSettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, StampSettings())

    val appSettings: StateFlow<AppSettings> = dataStore.appSettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // Mock StampData for live template preview in settings
    private val _sampleStampData = MutableStateFlow(
        StampData(
            latitude = 37.774929,
            longitude = -122.419416,
            altitude = 52.4,
            accuracyMeters = 4f,
            headingDegrees = 42f,
            headingCardinal = "NE",
            streetAddress = "100 Market St",
            locality = "San Francisco",
            adminArea = "California",
            postalCode = "94105",
            countryName = "United States",
            timestamp = System.currentTimeMillis(),
            temperatureCelsius = 19.5f,
            weatherDescription = "Clear sky",
            isLastKnown = false,
            hasFix = true
        )
    )
    val sampleStampData: StateFlow<StampData> = _sampleStampData.asStateFlow()

    init {
        // Generate placeholder map for sample preview
        viewModelScope.launch {
            val thumb = mapTileDownloader.createPlaceholderMap(37.774929, -122.419416)
            _sampleStampData.value = _sampleStampData.value.copy(mapThumbnail = thumb)
        }
    }

    fun updateTemplate(template: StampTemplate) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(template = template) }
        }
    }

    fun updatePosition(pos: StampPosition) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(position = pos) }
        }
    }

    fun updateMapStyle(style: MapStyle) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(mapStyle = style) }
        }
    }

    fun updateTextSizeScale(scale: Float) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(textSizeScale = scale) }
        }
    }

    fun updateBackgroundOpacity(opacity: Float) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(backgroundOpacity = opacity) }
        }
    }

    fun updateTextColor(color: Int) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(textColor = color) }
        }
    }

    fun toggleField(fieldName: String, enabled: Boolean) {
        viewModelScope.launch {
            dataStore.updateStampSettings { current ->
                when (fieldName) {
                    "map" -> current.copy(showMap = enabled)
                    "address" -> current.copy(showAddress = enabled)
                    "coordinates" -> current.copy(showCoordinates = enabled)
                    "altitude" -> current.copy(showAltitude = enabled)
                    "accuracy" -> current.copy(showAccuracy = enabled)
                    "compass" -> current.copy(showCompass = enabled)
                    "datetime" -> current.copy(showDateTime = enabled)
                    "weather" -> current.copy(showWeather = enabled)
                    "note" -> current.copy(showNote = enabled)
                    else -> current
                }
            }
        }
    }

    fun updateCoordinateFormat(format: CoordinateFormat) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(coordinateFormat = format) }
        }
    }

    fun updateIs24Hour(is24: Boolean) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(is24Hour = is24) }
        }
    }

    fun updateDateFormat(pattern: String) {
        viewModelScope.launch {
            dataStore.updateStampSettings { it.copy(dateFormatPattern = pattern) }
        }
    }

    fun updateCaptureQuality(quality: CaptureQuality) {
        viewModelScope.launch {
            dataStore.updateAppSettings { it.copy(captureQuality = quality) }
        }
    }

    fun updateColorProfile(profile: ColorProfile) {
        viewModelScope.launch {
            dataStore.updateAppSettings { it.copy(colorProfile = profile) }
        }
    }

    fun updateCaptureLatencyMode(isLatency: Boolean) {
        viewModelScope.launch {
            dataStore.updateAppSettings { it.copy(captureModeLatency = isLatency) }
        }
    }

    fun updateUnitSystem(units: UnitSystem) {
        viewModelScope.launch {
            dataStore.updateAppSettings { it.copy(unitSystem = units) }
        }
    }

    fun toggleShutterSound(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.updateAppSettings { it.copy(playShutterSound = enabled) }
        }
    }

    fun toggleSaveUnstamped(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.updateAppSettings { it.copy(saveUnstampedOriginal = enabled) }
        }
    }

    fun toggleMirrorSelfie(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.updateAppSettings { it.copy(mirrorSelfie = enabled) }
        }
    }
}
