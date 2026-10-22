package com.geostamp.camera

import android.app.Application
import com.geostamp.camera.data.EditStore
import com.geostamp.camera.data.SettingsDataStore
import com.geostamp.camera.data.PhotoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GeoStampApplication : Application() {

    lateinit var settingsDataStore: SettingsDataStore
        private set

    lateinit var editStore: EditStore
        private set

    lateinit var photoRepository: PhotoRepository
        private set

    /** Process-wide scope for work that must outlive a screen, e.g. finishing queued photo saves. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsDataStore = SettingsDataStore(this)
        editStore = EditStore(this)
        photoRepository = PhotoRepository(this, editStore)
    }

    companion object {
        lateinit var instance: GeoStampApplication
            private set
    }
}
