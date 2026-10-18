package com.geostamp.camera.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GpsStatus {
    ACQUIRING,
    LOCKED,
    LAST_KNOWN,
    DISABLED,
    NO_PERMISSION
}

data class LocationState(
    val location: Location? = null,
    val status: GpsStatus = GpsStatus.ACQUIRING,
    val isLastKnown: Boolean = false,
    val accuracyMeters: Float? = null,
    val isLocationServiceEnabled: Boolean = true
)

class LocationService(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _locationState = MutableStateFlow(LocationState())
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var isTracking = false
    private var lastValidLocation: Location? = null

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onNewLocation(loc, isLastKnown = false)
        }

        override fun onLocationAvailability(avail: LocationAvailability) {
            if (!avail.isLocationAvailable && lastValidLocation == null) {
                val isGpsOn = isLocationEnabled()
                _locationState.value = _locationState.value.copy(
                    status = if (isGpsOn) GpsStatus.ACQUIRING else GpsStatus.DISABLED,
                    isLocationServiceEnabled = isGpsOn
                )
            }
        }
    }

    private val lmListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNewLocation(location, isLastKnown = false)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { checkLocationAvailability() }
        override fun onProviderDisabled(provider: String) { checkLocationAvailability() }
    }

    fun isFinePermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isPermissionGranted(): Boolean {
        val fine = isFinePermissionGranted()
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            val gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val network = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            gps || network
        }
    }

    fun checkLocationAvailability() {
        val enabled = isLocationEnabled()
        if (!enabled) {
            _locationState.value = _locationState.value.copy(
                status = GpsStatus.DISABLED,
                isLocationServiceEnabled = false
            )
        } else if (_locationState.value.location == null) {
            _locationState.value = _locationState.value.copy(
                status = GpsStatus.ACQUIRING,
                isLocationServiceEnabled = true
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (!isPermissionGranted()) {
            _locationState.value = _locationState.value.copy(
                status = GpsStatus.NO_PERMISSION
            )
            return
        }

        if (isTracking) return
        isTracking = true

        val isGpsOn = isLocationEnabled()
        if (!isGpsOn) {
            _locationState.value = _locationState.value.copy(
                status = GpsStatus.DISABLED,
                isLocationServiceEnabled = false
            )
        } else {
            _locationState.value = _locationState.value.copy(
                status = GpsStatus.ACQUIRING,
                isLocationServiceEnabled = true
            )
        }

        // Try getting last known location first for instant responsiveness
        fetchLastKnownLocation()

        val playServicesAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

        if (playServicesAvailable) {
            startFusedUpdates()
        } else {
            startLegacyLocationManagerUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLastKnownLocation() {
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _locationState.value.location == null) {
                    onNewLocation(loc, isLastKnown = true)
                }
            }.addOnFailureListener {
                try {
                    val gpsLoc = if (isFinePermissionGranted()) {
                        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    } else null
                    val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    val best = when {
                        gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                        gpsLoc != null -> gpsLoc
                        else -> netLoc
                    }
                    if (best != null && _locationState.value.location == null) {
                        onNewLocation(best, isLastKnown = true)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: SecurityException) {}
    }

    @SuppressLint("MissingPermission")
    private fun startFusedUpdates() {
        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1500L // 1.5 seconds interval
            ).apply {
                setMinUpdateIntervalMillis(1000L)
                setMinUpdateDistanceMeters(1.0f)
                setWaitForAccurateLocation(false)
            }.build()

            fusedClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
        } catch (e: Exception) {
            // Fallback to standard LocationManager
            startLegacyLocationManagerUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyLocationManagerUpdates() {
        try {
            if (isFinePermissionGranted() && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1500L,
                    1f,
                    lmListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    2f,
                    lmListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {}
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        try {
            fusedClient.removeLocationUpdates(fusedCallback)
            locationManager.removeUpdates(lmListener)
        } catch (_: Exception) {}
    }

    private fun onNewLocation(loc: Location, isLastKnown: Boolean) {
        lastValidLocation = loc
        val status = if (isLastKnown) GpsStatus.LAST_KNOWN else GpsStatus.LOCKED
        _locationState.value = LocationState(
            location = loc,
            status = status,
            isLastKnown = isLastKnown,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
            isLocationServiceEnabled = true
        )
    }
}
