package com.geostamp.camera.ui.camera

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    /** Number of captures still being stamped/saved in the background. */
    private val _pendingSaves = MutableStateFlow(0)
    val pendingSaves: StateFlow<Int> = _pendingSaves.asStateFlow()

    /** Small upright preview of the most recent capture, available long before the stamped file is saved. */
    private val _captureThumbnail = MutableStateFlow<Bitmap?>(null)
    val captureThumbnail: StateFlow<Bitmap?> = _captureThumbnail.asStateFlow()

    /** Emits at the moment of exposure (for the shutter flash animation). */
    private val _captureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val captureEvents: SharedFlow<Unit> = _captureEvents.asSharedFlow()

    /** Emits when a hardware key asks for a capture. */
    private val _shutterRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutterRequests: SharedFlow<Unit> = _shutterRequests.asSharedFlow()

    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val saveMutex = Mutex()

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
                    // The geoid lookup may read model data from disk.
                    val altitude = withContext(Dispatchers.IO) { mslAltitude(loc) }
                    _stampData.update { current ->
                        current.copy(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitude = altitude,
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

    private val altitudeConverter: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) AltitudeConverter() else null

    /**
     * Height above sea level. [Location.getAltitude] is height above the WGS84 ellipsoid, which differs
     * from sea level by up to ~100 m (about -80 m across India) and shows up as a negative "altitude".
     */
    private fun mslAltitude(loc: Location): Double? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (loc.hasMslAltitude()) return loc.mslAltitudeMeters
            if (loc.hasAltitude()) {
                val converted = Location(loc)
                try {
                    (altitudeConverter as AltitudeConverter).addMslAltitudeToLocation(getApplication(), converted)
                    if (converted.hasMslAltitude()) return converted.mslAltitudeMeters
                } catch (_: Exception) {}
            }
        }
        // Pre-Android 14 has no geoid model; better to omit altitude than stamp a misleading one.
        return null
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
        if (_isCapturing.value || _countdownSeconds.value > 0) return

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

    /** Hardware shutter (volume keys). The camera screen owns the ImageCapture and fulfils the request. */
    fun requestShutter() {
        _shutterRequests.tryEmit(Unit)
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _countdownSeconds.value = 0
    }

    private fun executeCapture(imageCapture: ImageCapture) {
        if (_isCapturing.value) return
        if (_pendingSaves.value >= MAX_PENDING_SAVES) {
            _toastMessage.value = "Still saving previous photos…"
            return
        }
        _isCapturing.value = true

        val currentStampData = _stampData.value.copy(timestamp = System.currentTimeMillis())
        val currentStampSettings = stampSettings.value
        val currentAppSettings = appSettings.value
        val isFront = !_isBackCamera.value
        var feedbackPlayed = false

        imageCapture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureStarted() {
                    // Fires at exposure time, well before the JPEG is ready: give feedback here.
                    feedbackPlayed = true
                    playCaptureFeedback(currentAppSettings.playShutterSound)
                    _captureEvents.tryEmit(Unit)
                }

                override fun onCaptureSuccess(image: ImageProxy) {
                    if (!feedbackPlayed) {
                        playCaptureFeedback(currentAppSettings.playShutterSound)
                        _captureEvents.tryEmit(Unit)
                    }

                    val bytes = try {
                        val buffer: ByteBuffer = image.planes[0].buffer
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    } finally {
                        image.close()
                    }

                    // The frame is in memory: the shutter is free again while we stamp & save in the background.
                    _pendingSaves.update { it + 1 }
                    _isCapturing.value = false

                    app.appScope.launch {
                        processAndSaveCapturedPhoto(
                            bytes = bytes,
                            data = currentStampData,
                            stampSettings = currentStampSettings,
                            appSettings = currentAppSettings,
                            isFront = isFront
                        )
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
    ) {
        try {
            // Instant feedback for the gallery button (~tens of ms), independent of the save queue.
            imageStamper.decodeThumbnail(bytes, appSettings, isFront)?.let { _captureThumbnail.value = it }

            // One full-resolution job at a time keeps peak memory bounded during burst shooting.
            saveMutex.withLock {
                // Millisecond suffix keeps names unique when shooting several photos per second.
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                val fileBaseName = "GeoStamp_${sdf.format(Date(data.timestamp))}"

                if (appSettings.saveUnstampedOriginal) {
                    photoRepository.saveImage(
                        jpegBytes = bytes,
                        displayName = fileBaseName,
                        stampData = data,
                        isOriginal = true
                    )
                }

                val stampResult = imageStamper.stampImage(
                    jpegBytes = bytes,
                    stampData = data,
                    stampSettings = stampSettings,
                    appSettings = appSettings,
                    isFrontCamera = isFront
                )

                stampResult.onSuccess { stampedBytes ->
                    val saved = photoRepository.saveImage(
                        jpegBytes = stampedBytes,
                        displayName = fileBaseName,
                        stampData = data,
                        sourceJpeg = bytes
                    )
                    if (saved == null) {
                        _toastMessage.value = "Failed to save photo"
                    } else if (appSettings.keepEditableCopy) {
                        app.editStore.save(
                            rawJpeg = bytes,
                            record = EditRecord(
                                displayName = saved.displayName,
                                stampData = data.copy(mapThumbnail = null),
                                stampSettings = stampSettings,
                                isFrontCamera = isFront,
                                mirrorSelfie = appSettings.mirrorSelfie,
                                colorProfile = appSettings.colorProfile,
                                aspectRatio = appSettings.aspectRatio,
                                unitSystem = appSettings.unitSystem,
                                captureQuality = appSettings.captureQuality
                            )
                        )
                        if (app.editStore.has(saved.displayName)) {
                            photoRepository.markEditable(saved.id)
                        }
                    }
                }.onFailure { error ->
                    _toastMessage.value = "Stamping failed: ${error.message}"
                }
            }
        } finally {
            _pendingSaves.update { it - 1 }
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
        captureExecutor.shutdown()
        locationService.stopTracking()
        compassSensor.stopListening()
    }

    companion object {
        /** Back-pressure for burst shooting: each queued capture holds a full JPEG in memory. */
        private const val MAX_PENDING_SAVES = 4
    }
}
