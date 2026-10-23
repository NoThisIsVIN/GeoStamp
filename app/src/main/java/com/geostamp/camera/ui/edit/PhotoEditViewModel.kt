package com.geostamp.camera.ui.edit

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geostamp.camera.GeoStampApplication
import com.geostamp.camera.data.AddressResult
import com.geostamp.camera.data.EditRecord
import com.geostamp.camera.data.GeocoderService
import com.geostamp.camera.data.MapTileDownloader
import com.geostamp.camera.data.SearchResult
import com.geostamp.camera.data.WeatherService
import com.geostamp.camera.model.PhotoItem
import com.geostamp.camera.model.StampData
import com.geostamp.camera.model.StampSettings
import com.geostamp.camera.model.StampTemplate
import com.geostamp.camera.stamping.ImageStamper
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Mutable address parts of the draft; kept separate so a typed override can replace them wholesale. */
data class DraftAddress(
    val streetAddress: String? = null,
    val locality: String? = null,
    val adminArea: String? = null,
    val postalCode: String? = null,
    val countryName: String? = null
) {
    /** Same order as [StampData.formattedAddress], but empty instead of a placeholder. */
    fun joined(): String = listOf(streetAddress, locality, adminArea, postalCode, countryName)
        .filter { !it.isNullOrBlank() }
        .joinToString(", ")
}

data class PhotoEditUiState(
    val loading: Boolean = true,
    val unavailable: Boolean = false,
    val record: EditRecord? = null,
    val draftData: StampData? = null,
    val draftSettings: StampSettings? = null,
    /** What the Address field shows; mirrors the draft unless the user has typed over it. */
    val addressText: String = "",
    val geocoding: Boolean = false,
    val addressLookupFailed: Boolean = false,
    val weatherLoading: Boolean = false,
    val mapLoading: Boolean = false,
    val preview: Bitmap? = null,
    val rendering: Boolean = false,
    val saving: Boolean = false
) {
    val ready: Boolean get() = record != null && draftData != null && draftSettings != null

    /** True when the draft would produce a different stamp than the one currently saved. */
    val dirty: Boolean
        get() {
            val r = record ?: return false
            val d = draftData ?: return false
            return d.copy(mapThumbnail = null) != r.stampData.copy(mapThumbnail = null) ||
                draftSettings != r.stampSettings
        }

    val canResetToOriginal: Boolean
        get() {
            val r = record ?: return false
            val d = draftData ?: return false
            return d.timestamp != r.originalTimestamp ||
                d.latitude != r.originalLatitude || d.longitude != r.originalLongitude
        }
}

sealed interface PhotoEditEvent {
    data class Error(val message: String) : PhotoEditEvent
    data class Saved(val photo: PhotoItem) : PhotoEditEvent
}

/**
 * Holds the draft (stamp data + per-photo stamp settings) for one editable photo, keeps a debounced
 * WYSIWYG preview in sync with it, and re-renders the full-resolution stamp from the unstamped source on save.
 */
@OptIn(FlowPreview::class)
class PhotoEditViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as GeoStampApplication
    private val editStore = app.editStore
    private val photoRepository = app.photoRepository
    private val imageStamper = ImageStamper()
    private val geocoderService = GeocoderService(application)
    private val weatherService = WeatherService()
    private val mapTileDownloader = MapTileDownloader(application)

    private val _state = MutableStateFlow(PhotoEditUiState())
    val state: StateFlow<PhotoEditUiState> = _state.asStateFlow()

    private val _events = Channel<PhotoEditEvent>(Channel.BUFFERED)
    val events: Flow<PhotoEditEvent> = _events.receiveAsFlow()

    private var source: ByteArray? = null
    private var loadStarted = false

    /** True once the user has typed in the Address field since the last location change. */
    private var addressOverridden = false

    private var addressJob: Job? = null
    private var weatherJob: Job? = null
    private var mapJob: Job? = null

    init {
        viewModelScope.launch {
            _state
                .map { s -> if (s.draftData != null && s.draftSettings != null) s.draftData to s.draftSettings else null }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(PREVIEW_DEBOUNCE_MS)
                // collectLatest drops a render that is overtaken by a newer draft.
                .collectLatest { (data, settings) -> renderPreview(data, settings) }
        }
    }

    fun load(displayName: String) {
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch {
            val record = editStore.loadRecord(displayName)
            val bytes = editStore.loadSource(displayName)
            if (record == null || bytes == null) {
                _state.update { it.copy(loading = false, unavailable = true) }
                return@launch
            }
            source = bytes

            // Wait briefly for the map so the first preview already has it (tiles are usually disk-cached
            // from capture time); offline we carry on and let it arrive later.
            val lat = record.stampData.latitude
            val lng = record.stampData.longitude
            val thumb = if (lat != null && lng != null) {
                withTimeoutOrNull(INITIAL_MAP_WAIT_MS) {
                    mapTileDownloader.renderMapThumbnail(lat, lng, mapStyle = record.stampSettings.mapStyle)
                }
            } else null

            val data = record.stampData
            _state.update {
                it.copy(
                    loading = false,
                    record = record,
                    draftData = data.copy(mapThumbnail = thumb),
                    draftSettings = record.stampSettings,
                    addressText = data.toDraftAddress().joined()
                )
            }
            if (thumb == null && lat != null && lng != null) refreshMap(lat, lng)
        }
    }

    // region Edits

    /** Replaces the instant (millis since epoch); weather is refetched for the new time. */
    fun setTimestamp(timestamp: Long) {
        val d = _state.value.draftData ?: return
        if (d.timestamp == timestamp) return
        _state.update { s -> s.copy(draftData = s.draftData?.copy(timestamp = timestamp)) }
        refreshWeather()
    }

    /** Moves the photo to a new point: clears GPS-quality fields, then re-geocodes and refreshes map + weather. */
    fun setLocation(lat: Double, lng: Double) {
        val d = _state.value.draftData ?: return
        if (d.latitude == lat && d.longitude == lng) return
        applyLocation(lat, lng)
        refreshWeather()
    }

    fun setAddressText(text: String) {
        addressOverridden = true
        // A typed address replaces the structured parts entirely so formattedAddress() renders exactly it.
        val trimmed = text.trim().takeIf { it.isNotEmpty() }
        _state.update { s ->
            s.copy(
                addressText = text,
                addressLookupFailed = false,
                draftData = s.draftData?.copy(
                    streetAddress = trimmed,
                    locality = null,
                    adminArea = null,
                    postalCode = null,
                    countryName = null
                )
            )
        }
    }

    /** Discards a typed address and looks the current point up again. */
    fun redetectAddress() {
        val d = _state.value.draftData ?: return
        val lat = d.latitude ?: return
        val lng = d.longitude ?: return
        addressOverridden = false
        refreshAddress(lat, lng)
    }

    fun setNote(note: String) {
        _state.update { s ->
            val settings = s.draftSettings ?: return@update s
            // Typing a note in the editor is a clear signal it should appear, even if notes were off at capture.
            s.copy(draftSettings = settings.copy(customNote = note, showNote = settings.showNote || note.isNotBlank()))
        }
    }

    fun setIncludeWeather(include: Boolean) {
        _state.update { s -> s.copy(draftSettings = s.draftSettings?.copy(showWeather = include)) }
    }

    /** Restores the capture-time instant and location (address, map and weather are looked up afresh). */
    fun resetToOriginal() {
        val r = _state.value.record ?: return
        _state.update { s -> s.copy(draftData = s.draftData?.copy(timestamp = r.originalTimestamp)) }
        val lat = r.originalLatitude
        val lng = r.originalLongitude
        if (lat != null && lng != null) {
            applyLocation(lat, lng)
        } else {
            addressJob?.cancel(); mapJob?.cancel()
            _state.update { s ->
                s.copy(
                    draftData = s.draftData?.copy(
                        latitude = null, longitude = null, altitude = null, accuracyMeters = null,
                        streetAddress = null, locality = null, adminArea = null, postalCode = null,
                        countryName = null, mapThumbnail = null, isLastKnown = false, hasFix = false
                    ),
                    addressText = "",
                    geocoding = false,
                    mapLoading = false
                )
            }
        }
        refreshWeather()
    }

    suspend fun search(query: String): List<SearchResult> = geocoderService.search(query)

    private fun applyLocation(lat: Double, lng: Double) {
        addressOverridden = false
        _state.update { s ->
            s.copy(
                draftData = s.draftData?.copy(
                    latitude = lat,
                    longitude = lng,
                    // Altitude/accuracy described the original GPS fix; they're meaningless for a picked point.
                    altitude = null,
                    accuracyMeters = null,
                    isLastKnown = false,
                    hasFix = true
                )
            )
        }
        refreshAddress(lat, lng)
        refreshMap(lat, lng)
    }

    // endregion

    // region Lookups

    private fun refreshAddress(lat: Double, lng: Double) {
        addressJob?.cancel()
        _state.update { it.copy(geocoding = true, addressLookupFailed = false) }
        addressJob = viewModelScope.launch {
            val result = try { geocoderService.getAddressFresh(lat, lng) } catch (_: Exception) { null }
            _state.update { s ->
                if (addressOverridden) return@update s.copy(geocoding = false)
                // On failure clear the parts: the previous address belongs to a different place now.
                val parts = result?.toDraftAddress() ?: DraftAddress()
                s.copy(
                    geocoding = false,
                    addressLookupFailed = result == null,
                    addressText = parts.joined(),
                    draftData = s.draftData?.copy(
                        streetAddress = parts.streetAddress,
                        locality = parts.locality,
                        adminArea = parts.adminArea,
                        postalCode = parts.postalCode,
                        countryName = parts.countryName
                    )
                )
            }
        }
    }

    private fun refreshMap(lat: Double, lng: Double) {
        mapJob?.cancel()
        val style = _state.value.draftSettings?.mapStyle ?: return
        _state.update { it.copy(mapLoading = true) }
        mapJob = viewModelScope.launch {
            val thumb = try {
                mapTileDownloader.renderMapThumbnail(lat, lng, mapStyle = style)
            } catch (_: Exception) {
                mapTileDownloader.createPlaceholderMap(lat, lng)
            }
            _state.update { s ->
                // Ignore a result for a point the user has already moved away from.
                if (s.draftData?.latitude != lat || s.draftData.longitude != lng) s.copy(mapLoading = false)
                else s.copy(mapLoading = false, draftData = s.draftData.copy(mapThumbnail = thumb))
            }
        }
    }

    private fun refreshWeather() {
        weatherJob?.cancel()
        val d = _state.value.draftData ?: return
        val lat = d.latitude
        val lng = d.longitude
        if (lat == null || lng == null) {
            _state.update { s ->
                s.copy(weatherLoading = false, draftData = s.draftData?.copy(temperatureCelsius = null, weatherDescription = null))
            }
            return
        }
        _state.update { it.copy(weatherLoading = true) }
        weatherJob = viewModelScope.launch {
            // Coalesce rapid date/time/location changes into one request.
            kotlinx.coroutines.delay(WEATHER_DEBOUNCE_MS)
            val cur = _state.value.draftData ?: return@launch
            val result = try {
                weatherService.fetchWeatherAt(lat, lng, cur.timestamp)
            } catch (_: Exception) {
                null
            }
            _state.update { s ->
                s.copy(
                    weatherLoading = false,
                    // Null on failure on purpose: weather from another place/time would be a false stamp.
                    draftData = s.draftData?.copy(
                        temperatureCelsius = result?.temperatureCelsius,
                        weatherDescription = result?.weatherDescription
                    )
                )
            }
        }
    }

    // endregion

    private suspend fun renderPreview(data: StampData, settings: StampSettings) {
        val bytes = source ?: return
        val record = _state.value.record ?: return
        _state.update { it.copy(rendering = true) }
        try {
            val bmp = imageStamper.renderPreview(
                jpegBytes = bytes,
                stampData = data,
                stampSettings = settings,
                appSettings = record.toAppSettings(),
                isFrontCamera = record.isFrontCamera,
                maxDimension = PREVIEW_MAX_DIMENSION
            )
            _state.update { it.copy(preview = bmp, rendering = false) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Keep the previous preview; the save path reports real failures.
            _state.update { it.copy(rendering = false) }
        }
    }

    fun save(photo: PhotoItem) {
        val initial = _state.value
        if (initial.saving || !initial.ready) return
        val bytes = source ?: return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            // Let in-flight lookups land so the saved stamp matches what the form is about to show.
            withTimeoutOrNull(PENDING_LOOKUP_WAIT_MS) {
                addressJob?.join()
                weatherJob?.join()
                mapJob?.join()
            }

            val snapshot = _state.value
            val record = snapshot.record
            var data = snapshot.draftData
            val settings = snapshot.draftSettings
            if (record == null || data == null || settings == null) {
                _state.update { it.copy(saving = false) }
                return@launch
            }

            val lat = data.latitude
            val lng = data.longitude
            if (settings.showsMap() && data.mapThumbnail == null && lat != null && lng != null) {
                val thumb = try {
                    mapTileDownloader.renderMapThumbnail(lat, lng, mapStyle = settings.mapStyle)
                } catch (_: Exception) {
                    mapTileDownloader.createPlaceholderMap(lat, lng)
                }
                data = data.copy(mapThumbnail = thumb)
            }

            val stamped = imageStamper.stampImage(
                jpegBytes = bytes,
                stampData = data,
                stampSettings = settings,
                appSettings = record.toAppSettings(),
                isFrontCamera = record.isFrontCamera
            ).getOrNull()
            if (stamped == null) {
                _state.update { it.copy(saving = false) }
                _events.send(PhotoEditEvent.Error("Couldn't render the photo. Please try again."))
                return@launch
            }

            // Once we start overwriting the MediaStore file, finish the write and the sidecar update together.
            val updated = withContext(NonCancellable) {
                val result = photoRepository.replaceImage(photo, stamped, data, sourceJpeg = bytes)
                if (result != null) {
                    val newRecord = record.copy(
                        stampData = data.copy(mapThumbnail = null),
                        stampSettings = settings,
                        edited = true
                    )
                    editStore.updateRecord(newRecord)
                    _state.update { s -> s.copy(record = newRecord) }
                }
                result
            }

            _state.update { it.copy(saving = false) }
            if (updated == null) {
                _events.send(PhotoEditEvent.Error("Couldn't save changes to the photo."))
            } else {
                _events.send(PhotoEditEvent.Saved(updated))
            }
        }
    }

    private fun StampData.toDraftAddress() = DraftAddress(streetAddress, locality, adminArea, postalCode, countryName)

    private fun AddressResult.toDraftAddress() = DraftAddress(streetAddress, locality, adminArea, postalCode, countryName)

    companion object {
        private const val PREVIEW_DEBOUNCE_MS = 150L
        private const val WEATHER_DEBOUNCE_MS = 400L
        private const val PREVIEW_MAX_DIMENSION = 1400
        private const val INITIAL_MAP_WAIT_MS = 2_500L
        private const val PENDING_LOOKUP_WAIT_MS = 12_000L
    }
}

/** Whether this stamp layout draws a map thumbnail at all. */
internal fun StampSettings.showsMap(): Boolean = showMap && template != StampTemplate.MINIMAL_TEXT_ONLY
