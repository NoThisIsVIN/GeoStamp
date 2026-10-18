package com.geostamp.camera.ui.camera

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geostamp.camera.GeoStampApplication
import com.geostamp.camera.data.*
import com.geostamp.camera.model.*
import com.geostamp.camera.stamping.ImageStamper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as GeoStampApplication
    val settingsDataStore: SettingsDataStore = app.settingsDataStore
    val photoRepository: PhotoRepository = app.photoRepository

    private val locationService = LocationService(application)
    private val geocoderService = GeocoderService(application)
    private val compassSensor = CompassSensor(application)
    private val weatherService = WeatherService()
    private val mapTileDownloader = MapTileDownloader(application)
    private val imageStamper = ImageStamper()

    private val soundPlayer: MediaActionSound? = try {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
        }
    } catch (_: Exception) {
        null
    }

    // Settings flows
    val stampSettings: StateFlow<StampSettings> = settingsDataStore.stampSettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, StampSettings())

    val appSettings: StateFlow<AppSettings> = settingsDataStore.appSettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // Location & Sensors
    val locationState: StateFlow<LocationState> = locationService.locationState
    val compassState: StateFlow<CompassState> = compassSensor.compassFlow

    // Combined live StampData
    private val _stampData = MutableStateFlow(StampData())
    val stampData: StateFlow<StampData> = _stampData.asStateFlow()

    // Camera controls state
    private val _isBackCamera = MutableStateFlow(true)
    val isBackCamera: StateFlow<Boolean> = _isBackCamera.asStateFlow()

    private val _flashMode = MutableStateFlow(ImageCapture.FLASH_MODE_AUTO)
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(0)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _showLocationDisabledDialog = MutableStateFlow(false)
    val showLocationDisabledDialog: StateFlow<Boolean> = _showLocationDisabledDialog.asStateFlow()

    val latestPhoto: StateFlow<PhotoItem?> = photoRepository.latestPhotoFlow

    private var countdownJob: Job? = null
    private var weatherJob: Job? = null
    private var mapJob: Job? = null
    private var addressJob: Job? = null

    init {
        viewModelScope.launch {
            photoRepository.loadPhotos()
        }

        // Observe location updates
        viewModelScope.launch {
            locationService.locationState.collect { locState ->
                val loc = locState.location
                if (loc != null) {
                    _stampData.update { current ->
                        current.copy(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitude = if (loc.hasAltitude()) loc.altitude else null,
                            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
                            timestamp = System.currentTimeMillis(),
                            isLastKnown = locState.isLastKnown,
                            hasFix = true
                        )
                    }

                    // Async Address lookup
                    addressJob?.cancel()
                    addressJob = launch {
                        val addr = geocoderService.getAddress(loc.latitude, loc.longitude)
                        if (addr != null) {
                            _stampData.update {
                                it.copy(
                                    streetAddress = addr.streetAddress,
                                    locality = addr.locality,
                                    adminArea = addr.adminArea,
                                    postalCode = addr.postalCode,
                                    countryName = addr.countryName
                                )
                            }
                        }
                    }

                    // Async Map thumbnail
                    mapJob?.cancel()
                    mapJob = launch {
                        val currentStyle = stampSettings.value.mapStyle
                        val thumb = mapTileDownloader.getMapThumbnail(loc.latitude, loc.longitude, mapStyle = currentStyle)
                        _stampData.update { it.copy(mapThumbnail = thumb) }
                    }

                    // Async Weather fetch
                    weatherJob?.cancel()
                    weatherJob = launch {
                        val weather = weatherService.fetchWeather(loc.latitude, loc.longitude)
                        if (weather != null) {
                            _stampData.update {
                                it.copy(
                                    temperatureCelsius = weather.temperatureCelsius,
                                    weatherDescription = weather.weatherDescription
                                )
                            }
                        }
                    }
                } else {
                    _stampData.update {
                        it.copy(
                            latitude = null,
                            longitude = null,
                            hasFix = false,
                            isLastKnown = false
                        )
                    }
                }
            }
        }

        // Observe Compass updates
        viewModelScope.launch {
            compassSensor.compassFlow.collect { comp ->
                _stampData.update {
                    it.copy(
                        headingDegrees = comp.azimuthDegrees,
                        headingCardinal = comp.cardinalDirection
                    )
                }
            }
        }

        // Observe StampSettings updates (e.g. MapStyle switch)
        viewModelScope.launch {
            var previousStyle = stampSettings.value.mapStyle
            stampSettings.collect { settings ->
                if (settings.mapStyle != previousStyle) {
                    previousStyle = settings.mapStyle
                    val loc = locationService.locationState.value.location
                    if (loc != null) {
                        mapJob?.cancel()
                        mapJob = launch {
                            val thumb = mapTileDownloader.getMapThumbnail(loc.latitude, loc.longitude, mapStyle = settings.mapStyle)
                            _stampData.update { it.copy(mapThumbnail = thumb) }
                        }
                    }
                }
            }
        }
    }

    fun onResume() {
        locationService.startTracking()
        compassSensor.startListening()
    }

    fun onPause() {
        locationService.stopTracking()
        compassSensor.stopListening()
    }

    fun toggleCamera() {
        _isBackCamera.value = !_isBackCamera.value
    }

    fun cycleFlashMode() {
        _flashMode.value = when (_flashMode.value) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    fun cycleAspectRatio() {
        viewModelScope.launch {
            val current = appSettings.value.aspectRatio
            val next = when (current) {
                AspectRatioChoice.RATIO_4_3 -> AspectRatioChoice.RATIO_16_9
                AspectRatioChoice.RATIO_16_9 -> AspectRatioChoice.RATIO_1_1
                AspectRatioChoice.RATIO_1_1 -> AspectRatioChoice.RATIO_4_3
            }
            settingsDataStore.updateAppSettings { it.copy(aspectRatio = next) }
        }
    }

    fun cycleTimer() {
        viewModelScope.launch {
            val current = appSettings.value.timerOption
            val next = when (current) {
                TimerOption.OFF -> TimerOption.SEC_3
                TimerOption.SEC_3 -> TimerOption.SEC_10
                TimerOption.SEC_10 -> TimerOption.OFF
            }
            settingsDataStore.updateAppSettings { it.copy(timerOption = next) }
        }
    }

    fun toggleGrid() {
        viewModelScope.launch {
            val current = appSettings.value.showGrid
            settingsDataStore.updateAppSettings { it.copy(showGrid = !current) }
        }
    }

    fun setCustomNote(note: String) {
        viewModelScope.launch {
            settingsDataStore.setCustomNote(note)
        }
    }

    fun onGpsChipClicked() {
        val state = locationState.value
        if (!state.isLocationServiceEnabled || state.status == GpsStatus.DISABLED) {
            _showLocationDisabledDialog.value = true
        }
    }

    fun dismissLocationDialog() {
        _showLocationDisabledDialog.value = false
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun triggerShutter(imageCapture: ImageCapture) {
        if (_isCapturing.value) return

        val timerSeconds = appSettings.value.timerOption.seconds
        if (timerSeconds > 0) {
            countdownJob?.cancel()
            countdownJob = viewModelScope.launch {
                for (sec in timerSeconds downTo 1) {
                    _countdownSeconds.value = sec
                    delay(1000)
                }
                _countdownSeconds.value = 0
                executeCapture(imageCapture)
            }
        } else {
            executeCapture(imageCapture)
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = 0
    }

    private fun executeCapture(imageCapture: ImageCapture) {
        _isCapturing.value = true

        val executor = ContextCompat.getMainExecutor(getApplication())
        val currentStampData = _stampData.value.copy(timestamp = System.currentTimeMillis())
        val currentStampSettings = stampSettings.value
        val currentAppSettings = appSettings.value
        val isFront = !_isBackCamera.value

        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // Feedback: Haptic & Sound
                    playCaptureFeedback(currentAppSettings.playShutterSound)

                    val buffer: ByteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    viewModelScope.launch {
                        processAndSaveCapturedPhoto(
                            bytes = bytes,
                            data = currentStampData,
                            stampSettings = currentStampSettings,
                            appSettings = currentAppSettings,
                            isFront = isFront
                        )
                        _isCapturing.value = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    _isCapturing.value = false
                    _toastMessage.value = "Capture failed: ${exception.localizedMessage}"
                }
            }
        )
    }

    private suspend fun processAndSaveCapturedPhoto(
        bytes: ByteArray,
        data: StampData,
        stampSettings: StampSettings,
        appSettings: AppSettings,
        isFront: Boolean
    ) = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val fileBaseName = "GeoStamp_${sdf.format(Date(now))}"

        // Save unstamped original if setting enabled
        if (appSettings.saveUnstampedOriginal) {
            photoRepository.saveImage(
                jpegBytes = bytes,
                displayName = fileBaseName,
                stampData = data,
                isOriginal = true
            )
        }

        // Burn overlay stamp
        val stampResult = imageStamper.stampImage(
            jpegBytes = bytes,
            stampData = data,
            stampSettings = stampSettings,
            appSettings = appSettings,
            isFrontCamera = isFront
        )

        stampResult.onSuccess { stampedBytes ->
            val savedUri = photoRepository.saveImage(
                jpegBytes = stampedBytes,
                displayName = fileBaseName,
                stampData = data,
                isOriginal = false
            )
            if (savedUri != null) {
                _toastMessage.value = "Photo saved with GPS stamp"
            } else {
                _toastMessage.value = "Failed to save photo"
            }
        }.onFailure { error ->
            _toastMessage.value = "Stamping failed: ${error.message}"
        }
    }

    private fun playCaptureFeedback(playSound: Boolean) {
        if (playSound) {
            try {
                soundPlayer?.play(MediaActionSound.SHUTTER_CLICK)
            } catch (_: Exception) {}
        }

        // Haptic feedback
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getApplication<Application>().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        soundPlayer?.release()
        locationService.stopTracking()
        compassSensor.stopListening()
    }
}
