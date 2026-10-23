package com.geostamp.camera.ui.gallery

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geostamp.camera.GeoStampApplication
import com.geostamp.camera.data.PhotoRepository
import com.geostamp.camera.model.PhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val photoRepository: PhotoRepository =
        (application as GeoStampApplication).photoRepository

    val photos: StateFlow<List<PhotoItem>> = photoRepository.photosFlow

    /**
     * True once this ViewModel has completed at least one library query. Lets routes tell an empty
     * list that simply hasn't loaded yet (e.g. after process death) apart from a photo that is gone.
     */
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    fun refreshPhotos() {
        viewModelScope.launch {
            photoRepository.loadPhotos()
            _hasLoaded.value = true
        }
    }

    fun getDeleteIntentSender(photo: PhotoItem): PendingIntent? {
        return photoRepository.createDeleteIntentSender(listOf(photo.uri))
    }

    fun deletePhotoLegacy(photo: PhotoItem, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val deleted = photoRepository.deleteLegacy(photo.uri)
            onComplete(deleted)
        }
    }

    fun getShareIntent(photo: PhotoItem): Intent {
        return photoRepository.createShareIntent(photo.uri)
    }

    fun getMapsIntent(lat: Double, lng: Double, label: String): Intent =
        photoRepository.createMapsIntent(lat, lng, label)

    fun getWebMapsIntent(lat: Double, lng: Double): Intent =
        photoRepository.createWebMapsIntent(lat, lng)

    /**
     * Coordinates are normally already on the [PhotoItem]; the fallback reads EXIF from storage,
     * so it must stay off the main thread.
     */
    suspend fun resolveCoordinates(photo: PhotoItem): Pair<Double, Double>? {
        val lat = photo.latitude
        val lng = photo.longitude
        if (lat != null && lng != null) return Pair(lat, lng)
        return withContext(Dispatchers.IO) {
            photoRepository.getCoordinatesForPhoto(photo.displayName, photo.uri)
        }
    }
}
