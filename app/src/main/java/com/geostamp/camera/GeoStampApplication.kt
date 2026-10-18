package com.geostamp.camera

import android.app.Application
import com.geostamp.camera.data.SettingsDataStore
import com.geostamp.camera.data.PhotoRepository

class GeoStampApplication : Application() {

    lateinit var settingsDataStore: SettingsDataStore
        private set

    lateinit var photoRepository: PhotoRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsDataStore = SettingsDataStore(this)
        photoRepository = PhotoRepository(this)
    }

    companion object {
        lateinit var instance: GeoStampApplication
            private set
    }
}
