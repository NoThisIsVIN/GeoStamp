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

/** A forward-geocoding hit: a human-readable label and its coordinates. */
data class SearchResult(
    val label: String,
    val latitude: Double,
    val longitude: Double
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

    /**
     * Reverse-geocodes without consulting or updating the 25 m cache. The photo editor needs this:
     * a user nudging the pin by a few metres expects a fresh lookup, and its results must not leak
     * into the live camera's cache.
     */
    suspend fun getAddressFresh(lat: Double, lng: Double): AddressResult? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocodeAsyncApi33(lat, lng)
        } else {
            geocodeLegacy(lat, lng)
        }

    /** Forward-geocodes a free-text place/address query. Returns an empty list on any failure. */
    suspend fun search(query: String, maxResults: Int = 6): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            searchAsyncApi33(q, maxResults)
        } else {
            searchLegacy(q, maxResults)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun searchAsyncApi33(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                try {
                    if (!Geocoder.isPresent()) {
                        cont.resume(emptyList())
                        return@suspendCancellableCoroutine
                    }
                    geocoder.getFromLocationName(query, maxResults, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.mapNotNull { it.toSearchResult() })
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun searchLegacy(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!Geocoder.isPresent()) return@withContext emptyList()
                geocoder.getFromLocationName(query, maxResults)
                    ?.mapNotNull { it.toSearchResult() }
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    private fun Address.toSearchResult(): SearchResult? {
        if (!hasLatitude() || !hasLongitude()) return null
        val label = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }
            .joinToString(", ")
            .ifBlank {
                listOfNotNull(featureName, locality, adminArea, countryName)
                    .distinct()
                    .joinToString(", ")
            }
            .ifBlank { String.format(Locale.US, "%.5f, %.5f", latitude, longitude) }
        return SearchResult(label, latitude, longitude)
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
