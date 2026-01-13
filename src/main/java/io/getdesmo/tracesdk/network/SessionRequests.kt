package io.getdesmo.tracesdk.network

import io.getdesmo.tracesdk.models.Address
import io.getdesmo.tracesdk.models.Device
import io.getdesmo.tracesdk.models.StartLocation
import io.getdesmo.tracesdk.models.SessionType
import io.getdesmo.tracesdk.telemetry.SensorAvailability
import kotlinx.serialization.Serializable

/**
 * Request body for starting a new Desmo session.
 * Internal DTO - not exposed to customers.
 */
@Serializable
internal data class StartSessionRequest(
    val deliveryId: String,
    val sessionType: SessionType,
    val externalRiderId: String? = null,
    val address: Address? = null,
    val device: Device? = null,
    val startLocation: StartLocation? = null,
    val sensorAvailability: SensorAvailability? = null
)

/**
 * Request body for stopping an active session.
 * Internal DTO - not exposed to customers.
 */
@Serializable
internal data class StopSessionRequest(
    val sessionId: String
)
