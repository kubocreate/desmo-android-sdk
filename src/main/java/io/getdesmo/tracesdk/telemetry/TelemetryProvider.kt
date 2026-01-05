package io.getdesmo.tracesdk.telemetry

/**
 * Abstraction for telemetry collection.
 *
 * Mirrors the Swift `TelemetryProvider` protocol.
 */
interface TelemetryProvider {
    fun start(sessionId: String)
    fun stop()
    suspend fun flush()

    /**
     * Returns which sensors are available on this device.
     */
    fun getSensorAvailability(): SensorAvailability

    /**
     * Returns the last known GPS position as an anchor point.
     */
    fun getLastKnownPosition(): PositionPayload?
}

