package com.geostamp.camera.ui.gallery

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geostamp.camera.GeoStampApplication
import com.geostamp.camera.data.PhotoRepository
import com.geostamp.camera.model.PhotoItem
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val photoRepository: PhotoRepository =
        (application as GeoStampApplication).photoRepository

    val photos: StateFlow<List<PhotoItem>> = photoRepository.photosFlow

    fun refreshPhotos() {
        viewModelScope.launch {
            photoRepository.loadPhotos()
        }
    }

    fun getDeleteIntentSender(photo: PhotoItem): PendingIntent? {
        return photoRepository.createDeleteIntentSender(listOf(photo.uri))
    }

    fun deletePhotoLegacy(photo: PhotoItem, onComplete: () -> Unit) {
        viewModelScope.launch {
            photoRepository.deleteLegacy(photo.uri)
            onComplete()
        }
    }

    fun getShareIntent(photo: PhotoItem): Intent {
        return photoRepository.createShareIntent(photo.uri)
    }

    fun getMapsIntent(photo: PhotoItem): Intent? {
        val coords = getPhotoCoordinates(photo)
        return if (coords != null) {
            photoRepository.createMapsIntent(coords.first, coords.second, photo.displayName)
        } else {
            null
        }
    }

    fun getWebMapsIntent(photo: PhotoItem): Intent? {
        val coords = getPhotoCoordinates(photo)
        return if (coords != null) {
            photoRepository.createWebMapsIntent(coords.first, coords.second)
        } else {
            null
        }
    }

    fun getPhotoCoordinates(photo: PhotoItem): Pair<Double, Double>? {
        val lat = photo.latitude
        val lng = photo.longitude
        if (lat != null && lng != null) return Pair(lat, lng)
        return photoRepository.getCoordinatesForPhoto(photo.displayName, photo.uri)
    }
}
