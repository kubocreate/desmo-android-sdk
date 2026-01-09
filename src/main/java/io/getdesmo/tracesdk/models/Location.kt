package io.getdesmo.tracesdk.models

import kotlinx.serialization.Serializable

/**
 * Geographic location with latitude and longitude.
 */
@Serializable
data class Location(
    val lat: Double,
    val lng: Double
)
