package com.geostamp.camera.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.*

data class AddressResult(
    val streetAddress: String? = null,
    val locality: String? = null,
    val adminArea: String? = null,
    val postalCode: String? = null,
    val countryName: String? = null
)

class GeocoderService(private val context: Context) {

    private val geocoder: Geocoder = Geocoder(context, Locale.getDefault())

    private var cachedLat: Double? = null
    private var cachedLng: Double? = null
    private var cachedResult: AddressResult? = null

    suspend fun getAddress(lat: Double, lng: Double): AddressResult? {
        val lastLat = cachedLat
        val lastLng = cachedLng
        val lastResult = cachedResult

        if (lastLat != null && lastLng != null && lastResult != null) {
            val dist = distanceMeters(lastLat, lastLng, lat, lng)
            if (dist < 25.0) {
                return lastResult
            }
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocodeAsyncApi33(lat, lng)
        } else {
            geocodeLegacy(lat, lng)
        }

        if (result != null) {
            cachedLat = lat
            cachedLng = lng
            cachedResult = result
        }

        return result
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun geocodeAsyncApi33(lat: Double, lng: Double): AddressResult? =
        suspendCancellableCoroutine { cont ->
            try {
                if (!Geocoder.isPresent()) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        val addr = addresses.firstOrNull()
                        cont.resume(addr?.toAddressResult())
                    }

                    override fun onError(errorMessage: String?) {
                        cont.resume(null)
                    }
                })
            } catch (e: Exception) {
                cont.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun geocodeLegacy(lat: Double, lng: Double): AddressResult? =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext null
                val list = geocoder.getFromLocation(lat, lng, 1)
                list?.firstOrNull()?.toAddressResult()
            } catch (e: Exception) {
                null
            }
        }

    private fun Address.toAddressResult(): AddressResult {
        val street = thoroughfare?.let { th ->
            subThoroughfare?.let { num -> "$num $th" } ?: th
        } ?: getAddressLine(0)?.split(",")?.firstOrNull()?.trim()

        return AddressResult(
            streetAddress = street,
            locality = locality ?: subAdminArea,
            adminArea = adminArea,
            postalCode = postalCode,
            countryName = countryName
        )
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
