package io.getdesmo.tracesdk.models

import kotlinx.serialization.Serializable

/**
 * Starting location for a delivery session.
 */
@Serializable
data class StartLocation(
    val lat: Double,
    val lng: Double
)
