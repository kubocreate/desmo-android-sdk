package io.getdesmo.tracesdk.telemetry.collectors

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import io.getdesmo.tracesdk.telemetry.PositionPayload

/**
 * Collects GPS and network location updates.
 *
 * Updates [latestPosition] whenever a new location is received.
 */
internal class LocationCollector(
    private val locationManager: LocationManager?,
    private val loggingEnabled: Boolean
) {

    private companion object {
        private const val TAG = "DesmoSDK"
        private const val UPDATE_INTERVAL_MS = 2_000L
    }

    /** The most recent location reading. */
    @Volatile
    var latestPosition: PositionPayload? = null
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestPosition = location.toPositionPayload()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start() {
        val lm = locationManager ?: return

        try {
            // 1. Get immediate position from last known location
            val lastKnown = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            if (lastKnown != null) {
                latestPosition = lastKnown.toPositionPayload()
                if (loggingEnabled) {
                    Log.d(TAG, "Initial position: ${lastKnown.latitude}, ${lastKnown.longitude}")
                }
            }

            // 2. Request GPS updates (most accurate)
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                if (loggingEnabled) {
                    Log.d(TAG, "GPS location updates started")
                }
            }

            // 3. Also request Network updates as fallback
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
                if (loggingEnabled) {
                    Log.d(TAG, "Network location updates started (fallback)")
                }
            }
        } catch (se: SecurityException) {
            if (loggingEnabled) {
                Log.w(TAG, "Location permission not granted, skipping location telemetry")
            }
        }
    }

    fun stop() {
        locationManager?.removeUpdates(listener)
        if (loggingEnabled) {
            Log.d(TAG, "Location updates stopped")
        }
    }

    /** Returns the last known GPS position as an anchor point. */
    fun getLastKnownPosition(): PositionPayload? {
        val lm = locationManager ?: return null
        return try {
            val location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            location?.toPositionPayload()
        } catch (e: SecurityException) {
            if (loggingEnabled) {
                Log.w(TAG, "Location permission not granted, cannot get anchor position")
            }
            null
        }
    }

    /** Returns true if GPS is available on this device. */
    fun isGpsAvailable(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    private fun Location.toPositionPayload(): PositionPayload {
        return PositionPayload(
            lat = latitude,
            lng = longitude,
            accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
            altitudeM = if (hasAltitude()) altitude else null,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            bearingDeg = if (hasBearing()) bearing.toDouble() else null,
            source = provider
        )
    }
}
