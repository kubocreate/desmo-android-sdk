package io.getdesmo.tracesdk.api

import io.getdesmo.tracesdk.telemetry.SensorAvailability
import kotlinx.serialization.Serializable

/**
 * Request body for starting a new Desmo session.
 */
@Serializable
internal data class StartSessionRequest(
    val deliveryId: String,
    val address: String?,
    val platform: String?,
    val sdkVersion: String?,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val sensorAvailability: SensorAvailability? = null
)

/**
 * Request body for stopping an active session.
 */
@Serializable
internal data class StopSessionRequest(
    val sessionId: String
)
